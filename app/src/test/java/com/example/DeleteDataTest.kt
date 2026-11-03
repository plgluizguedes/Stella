package com.example

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.attachments.AttachmentManager
import com.example.data.attachments.AttachmentStorage
import com.example.data.attachments.StageResult
import com.example.data.db.AppDatabase
import com.example.data.drive.CloudDeleteResult
import com.example.data.drive.CloudFilesCount
import com.example.data.drive.DriveAccountInfo
import com.example.data.drive.DriveAuthGateway
import com.example.data.drive.DriveConnectionStatus
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.erase.LocalDataEraser
import com.example.data.erase.LocalEraseResult
import com.example.data.model.AttachmentOwner
import com.example.data.model.GlucoseRecord
import com.example.data.model.Reminder
import com.example.data.model.StellaProfile
import com.example.data.repository.StellaRepository
import com.example.notifications.ReminderNotificationManager
import com.example.viewmodel.CloudFilesCountState
import com.example.viewmodel.DataDeletionKind
import com.example.viewmodel.DataDeletionState
import com.example.viewmodel.DriveSyncDecisionState
import com.example.viewmodel.StellaViewModel
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Etapa 3b: contar e apagar a nuvem, apagar o aparelho e apagar os dois, com as travas de segurança.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteDataTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var drive: FakeDrive

    private val email = "apagar@example.com"
    private val folderId = "folder_apagar"
    private val folderMime = "application/vnd.google-apps.folder"

    private val cloudPrefix = GoogleDriveSyncManager.SAFETY_COPY_CLOUD_PREFIX
    private val currentName = GoogleDriveSyncManager.BACKUP_FILE_NAME

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AttachmentTestSupport.wipeAttachmentsDir(context)
        AttachmentTestSupport.resetDatabase(context)
        server = MockWebServer()
        server.start()
        drive = FakeDrive(server, folderId)
        server.dispatcher = drive.dispatcher
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
        AttachmentTestSupport.resetDatabase(context)
        AttachmentTestSupport.wipeAttachmentsDir(context)
    }

    // ------------------------------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------------------------------

    private fun gateway(account: Boolean) = object : DriveAuthGateway {
        override fun getConnectedAccount() = if (account) DriveAccountInfo(email = email, hasDriveScope = true) else null
        override fun hasRequiredScope() = account
        override fun checkAccountHasScope(account: GoogleSignInAccount) = true
        override fun getToken() = "test-token"
        override fun invalidateToken(token: String) {}
        override fun signOut(onComplete: () -> Unit) = onComplete()
    }

    private fun newManager(account: Boolean = true): GoogleDriveSyncManager {
        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { "test-token" },
            driveApiBaseUrl = server.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = server.url("/").toString().removeSuffix("/"),
            authGateway = gateway(account)
        )
        if (account) {
            manager.persistFolderId(email, folderId)
            manager.setSyncApprovedForAccount(email, true)
        }
        return manager
    }

    private fun repository() = StellaRepository(AppDatabase.getDatabase(context).stellaDao())

    private fun backupBytes(profileName: String, glucoseCount: Int): ByteArray =
        JSONObject()
            .put("backupVersion", 2)
            .put("exportDate", "2026-10-14T10:00:00")
            .put("profile", JSONObject().put("id", 1).put("name", profileName))
            .put("glucoseRecords", JSONArray().apply {
                for (i in 1..glucoseCount) {
                    put(
                        JSONObject().put("id", i).put("dateString", "10/10/2026").put("timeString", "08:00")
                            .put("dateTimeMillis", 1760086800000L + i).put("value", 100.0 + i)
                            .put("unit", "mg/dL").put("mealRelation", "Em jejum")
                    )
                }
            })
            .toString().toByteArray(Charsets.UTF_8)

    private class Cloud(val current: String, val copy: String, val report: String, val att1: String, val att2: String)

    /** 5 arquivos do app na pasta: 2 backups, 1 relatório e 2 anexos. */
    private fun populateCloud(): Cloud {
        val current = drive.put(currentName, backupBytes("Da Nuvem", 2), "2026-10-10T10:00:00Z")
        val copy = drive.put("${cloudPrefix}20261005_1000.json", backupBytes("Cópia", 1), "2026-10-05T10:00:00Z")
        val att1 = drive.put("stella_anexo_${"a".repeat(64)}.pdf", AttachmentTestSupport.pdfBytes(seed = 1))
        val report = drive.put(GoogleDriveSyncManager.REPORT_FILE_NAME, "relatório".toByteArray())
        val att2 = drive.put("stella_anexo_${"b".repeat(64)}.png", AttachmentTestSupport.pngBytes(seed = 2))
        return Cloud(current, copy, report, att1, att2)
    }

    private fun deletedIdsInOrder(): List<String> =
        drive.deleteRequests().map { it.path.orEmpty().substringBefore('?').removePrefix("/files/") }

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Dados de verdade no aparelho: perfil, glicemias, consulta e exame com arquivos, e um lembrete com alarme. */
    private suspend fun createDeviceData() {
        val repository = repository()
        val attachments = AttachmentManager(repository, AttachmentStorage(context))
        repository.saveProfile(StellaProfile(name = "Do Aparelho", notes = "Só existe aqui"))
        repository.addGlucoseRecord(GlucoseRecord(dateString = "13/10/2026", timeString = "08:00", value = 105.0, mealRelation = "Em jejum"))
        repository.addGlucoseRecord(GlucoseRecord(dateString = "14/10/2026", timeString = "08:00", value = 111.0, mealRelation = "Em jejum"))
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val examId = repository.addExamDocument(AttachmentTestSupport.newExamDocument())

        suspend fun stage(name: String, bytes: ByteArray, label: String) = run {
            val source = AttachmentTestSupport.source(context, name, bytes)
            val result = attachments.stage(source.absolutePath, label)
            assertTrue("Deveria ter preparado '$name', mas foi $result", result is StageResult.Staged)
            (result as StageResult.Staged).attachment
        }
        attachments.commit(
            AttachmentOwner.APPOINTMENT, appointmentId,
            listOf(stage("receita.pdf", AttachmentTestSupport.pdfBytes(seed = 41), "Receituário"))
        )
        attachments.commit(
            AttachmentOwner.EXAM_DOCUMENT, examId,
            listOf(stage("laudo.png", AttachmentTestSupport.pngBytes(seed = 42), "Laudo"))
        )
        // Sobra de uma cópia interrompida, também precisa sumir.
        File(AttachmentTestSupport.attachmentsDir(context), "import_resto.part").writeText("resto")

        val reminder = Reminder(
            title = "Insulina", category = "Medicamento", dateString = "20/10/2026", timeString = "23:00",
            timestamp = System.currentTimeMillis() + 3_600_000L
        )
        val reminderId = repository.addReminder(reminder)
        ReminderNotificationManager(context).scheduleNotification(reminder.copy(id = reminderId))
    }

    private suspend fun assertDeviceIsEmpty(what: String) {
        val snapshot = repository().getBackupSnapshot()
        assertTrue("$what: ainda há registros no aparelho", snapshot.hasNoUserData())
        assertEquals("$what: o perfil deveria voltar ao padrão", StellaProfile(), snapshot.profile)
        assertTrue("$what: ainda há anexos no banco", snapshot.attachments.isEmpty())
        assertTrue("$what: ainda há arquivos na pasta de anexos", AttachmentTestSupport.attachmentsDir(context).list().isNullOrEmpty())
        assertEquals("$what: ainda há alarmes agendados", 0, shadowOf(alarmManager()).scheduledAlarms.size)
    }

    private suspend fun assertDeviceStillHasItsData(what: String) {
        val repository = repository()
        assertEquals("$what: o perfil mudou", "Do Aparelho", repository.getProfileSync()?.name)
        assertEquals("$what: perderam-se glicemias", 2, repository.getAllGlucoseRecordsSync().size)
        assertEquals("$what: perderam-se anexos", 2, repository.getBackupSnapshot().attachments.size)
        assertTrue("$what: perderam-se arquivos", (AttachmentTestSupport.attachmentsDir(context).list()?.size ?: 0) >= 2)
    }

    private fun mainLooper() = shadowOf(android.os.Looper.getMainLooper())

    private suspend fun awaitCondition(maxLoops: Int = 400, condition: () -> Boolean): Boolean {
        repeat(maxLoops) {
            mainLooper().idle()
            if (condition()) return true
            withContext(Dispatchers.Default) { delay(25) }
        }
        mainLooper().idle()
        return condition()
    }

    /** O ViewModel cria o perfil padrão se o banco não tem: espera isso para não competir com o teste. */
    private suspend fun settle() {
        repeat(200) {
            mainLooper().idle()
            if (repository().getProfileSync() != null) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("O ViewModel não criou o perfil inicial a tempo")
    }

    private fun viewModel(manager: GoogleDriveSyncManager, eraser: LocalDataEraser? = null) =
        StellaViewModel(context as Application, customDriveSyncManager = manager, customLocalDataEraser = eraser)

    // ------------------------------------------------------------------------------------------
    // Contar os arquivos da nuvem
    // ------------------------------------------------------------------------------------------

    @Test
    fun count_countsOnlyTheFilesOfTheFolder_withTheBreakdown() = runTest {
        populateCloud()
        drive.put("de_outra_pasta.json", backupBytes("Outra", 1), parent = "outra_pasta")
        val trashed = drive.put("na_lixeira.json", backupBytes("Lixeira", 1))
        drive.trashedIds.add(trashed)
        drive.put("subpasta", ByteArray(0), mimeType = folderMime)
        drive.listPageSize = 2 // várias páginas

        val count = newManager().countCloudFiles(email)

        assertEquals(CloudFilesCount(total = 5, backupFiles = 2, attachmentFiles = 2, otherFiles = 1), count)
    }

    @Test
    fun count_emptyFolder_isZero() = runTest {
        assertEquals(CloudFilesCount(0, 0, 0, 0), newManager().countCloudFiles(email))
    }

    // ------------------------------------------------------------------------------------------
    // Apagar a nuvem (motor)
    // ------------------------------------------------------------------------------------------

    @Test
    fun deleteAll_deletesEveryFile_keepsTheFolder_andLeavesOtherThingsAlone() = runTest {
        val cloud = populateCloud()
        val otherFolder = drive.put("de_outra_pasta.json", backupBytes("Outra", 1), parent = "outra_pasta")
        val trashed = drive.put("na_lixeira.json", backupBytes("Lixeira", 1))
        drive.trashedIds.add(trashed)
        val subfolder = drive.put("subpasta", ByteArray(0), mimeType = folderMime)
        context.getSharedPreferences("stella_drive_sync", Context.MODE_PRIVATE)
            .edit().putString("last_sync_timestamp", "01/10/2026 10:00").commit()
        val manager = newManager()
        assertEquals("01/10/2026 10:00", manager.lastSyncTimestamp.value)

        val result = manager.deleteAllCloudFiles(email)

        assertEquals(CloudDeleteResult.Deleted(5), result)
        assertEquals(
            "Sobram só o que não é do app nesta pasta: outra pasta, lixeira e subpasta",
            setOf(otherFolder, trashed, subfolder), drive.files.keys.toSet()
        )
        val deleted = deletedIdsInOrder()
        assertFalse("A pasta nunca é apagada", deleted.contains(folderId))
        assertFalse("A subpasta nunca é apagada", deleted.contains(subfolder))
        assertEquals("Os anexos vão por último", setOf(cloud.att1, cloud.att2), deleted.takeLast(2).toSet())
        assertEquals(DriveSyncState.Idle, manager.syncState.value)
        assertEquals("Nunca sincronizado", manager.lastSyncTimestamp.value)
    }

    @Test
    fun deleteAll_twice_theSecondFindsNothing() = runTest {
        populateCloud()
        val manager = newManager()

        assertEquals(CloudDeleteResult.Deleted(5), manager.deleteAllCloudFiles(email))
        assertEquals(CloudDeleteResult.Deleted(0), manager.deleteAllCloudFiles(email))
    }

    @Test
    fun deleteAll_aFileThatDriveSaysIsAlreadyGone_countsAsDeleted() = runTest {
        populateCloud()
        drive.failDeletesWhoseNameContains = GoogleDriveSyncManager.REPORT_FILE_NAME
        drive.failDeleteStatusCode = 404

        assertEquals(CloudDeleteResult.Deleted(5), newManager().deleteAllCloudFiles(email))
    }

    @Test
    fun deleteAll_aProblemWithOneFile_goesOnWithTheOthers_isNotDone_andRepeatingFinishesIt() = runTest {
        val cloud = populateCloud()
        drive.failDeletesWhoseNameContains = "a".repeat(64)
        drive.failDeleteStatusCode = 403
        val manager = newManager()

        val result = manager.deleteAllCloudFiles(email)

        assertTrue("Deveria ser Failed, mas foi $result", result is CloudDeleteResult.Failed)
        result as CloudDeleteResult.Failed
        assertEquals(4, result.filesDeleted)
        assertEquals(1, result.filesRemaining)
        assertFalse(result.offline)
        assertTrue(result.message.contains("4 de 5"))
        assertEquals("Só o arquivo com problema sobrou", setOf(cloud.att1), drive.files.keys.toSet())
        assertTrue(manager.syncState.value is DriveSyncState.Error)

        drive.failDeletesWhoseNameContains = null
        assertEquals("Repetir apaga o que sobrou", CloudDeleteResult.Deleted(1), manager.deleteAllCloudFiles(email))
        assertTrue(drive.files.isEmpty())
    }

    @Test
    fun deleteAll_aProblemThatWouldHitEveryFile_stopsAtTheFirst() = runTest {
        populateCloud()
        drive.failDeletesWhoseNameContains = ".json"
        drive.failDeleteStatusCode = 503

        val result = newManager().deleteAllCloudFiles(email)

        assertTrue(result is CloudDeleteResult.Failed)
        result as CloudDeleteResult.Failed
        assertEquals(0, result.filesDeleted)
        assertEquals(5, result.filesRemaining)
        assertEquals("Não insiste em cada arquivo", 1, drive.deleteRequests().size)
        assertEquals(5, drive.files.size)
    }

    @Test
    fun deleteAll_losingTheInternetMidway_isNotDone_andRepeatingFinishesIt() = runTest {
        populateCloud()
        drive.dropConnectionAfterDeletes = 2
        val manager = newManager()

        val result = manager.deleteAllCloudFiles(email)

        assertTrue("Deveria ser Failed, mas foi $result", result is CloudDeleteResult.Failed)
        result as CloudDeleteResult.Failed
        assertTrue(result.offline)
        assertEquals(2, result.filesDeleted)
        assertEquals(3, result.filesRemaining)
        assertTrue(result.message.contains("Sem conexão"))
        assertEquals(3, drive.files.size)
        assertTrue(manager.syncState.value is DriveSyncState.Error)

        drive.dropConnectionAfterDeletes = null
        assertEquals(CloudDeleteResult.Deleted(3), manager.deleteAllCloudFiles(email))
        assertTrue(drive.files.isEmpty())
    }

    @Test
    fun deleteAll_withoutInternetFromTheStart_isNotDone_andNothingIsDeleted() = runTest {
        populateCloud()
        val manager = newManager()
        server.shutdown()

        val result = manager.deleteAllCloudFiles(email)

        assertTrue("Deveria ser Failed, mas foi $result", result is CloudDeleteResult.Failed)
        result as CloudDeleteResult.Failed
        assertTrue(result.offline)
        assertEquals(0, result.filesDeleted)
        assertNull("Nem chegou a listar", result.filesRemaining)
        assertEquals(5, drive.files.size)
        assertTrue(manager.syncState.value is DriveSyncState.Error)
    }

    @Test
    fun deleteAll_dropsASyncCapturedBefore_soNothingIsUploadedAfterwards() = runTest {
        populateCloud()
        val manager = newManager()
        repository().saveProfile(StellaProfile(name = "Do Aparelho"))
        val captured = manager.captureBackup(repository())

        assertEquals(CloudDeleteResult.Deleted(5), manager.deleteAllCloudFiles(email))

        assertFalse("O envio preparado antes não pode subir", manager.syncToDrive(captured))
        assertEquals(0, drive.uploadRequests().size)
        assertTrue(drive.files.isEmpty())
    }

    @Test
    fun disconnect_forgetsTheAccountTheFolderAndTheApproval() = runTest {
        val manager = newManager()
        assertEquals(email, manager.connectedAccountEmail.value)

        manager.disconnect()

        assertNull(manager.connectedAccountEmail.value)
        assertEquals(DriveConnectionStatus.NOT_CONNECTED, manager.connectionStatus.value)
        assertNull(manager.getPersistedFolderId(email))
        assertFalse(manager.isSyncApprovedForAccount(email))
    }

    @Test
    fun blockSyncUntilDecision_closesTheLock_andDropsWhatWasCapturedBefore() = runTest {
        populateCloud()
        val manager = newManager()
        repository().saveProfile(StellaProfile(name = "Do Aparelho"))
        val captured = manager.captureBackup(repository())

        manager.blockSyncUntilDecision(email)

        assertFalse(manager.isSyncApprovedForAccount(email))
        assertFalse(manager.syncToDrive(captured))
        assertEquals(0, drive.uploadRequests().size)
    }

    // ------------------------------------------------------------------------------------------
    // Apagar o aparelho (motor)
    // ------------------------------------------------------------------------------------------

    private fun newEraser(
        clearDatabase: (suspend () -> Unit)? = null,
        deleteFiles: (() -> Boolean)? = null
    ): LocalDataEraser {
        val repository = repository()
        val storage = AttachmentStorage(context)
        val reminders = ReminderNotificationManager(context)
        val clear: suspend () -> Unit = clearDatabase ?: { repository.clearAllUserData() }
        val delete: () -> Boolean = deleteFiles ?: { storage.deleteAllFiles() }
        return LocalDataEraser(repository, storage, reminders, clearDatabase = clear, deleteFiles = delete)
    }

    @Test
    fun eraser_removesRecordsFilesAndAlarms() = runTest {
        createDeviceData()
        assertEquals("O lembrete tinha um alarme", 1, shadowOf(alarmManager()).scheduledAlarms.size)

        val result = newEraser().eraseAll()

        assertEquals(LocalEraseResult.Erased(emptyList()), result)
        assertDeviceIsEmpty("depois de apagar")
    }

    @Test
    fun eraser_onAnEmptyDevice_worksAndCanBeRepeated() = runTest {
        assertEquals(LocalEraseResult.Erased(emptyList()), newEraser().eraseAll())
        assertEquals(LocalEraseResult.Erased(emptyList()), newEraser().eraseAll())
        assertDeviceIsEmpty("aparelho vazio")
    }

    @Test
    fun eraser_ifTheDatabaseFails_reportsIt_andKeepsTheFiles() = runTest {
        createDeviceData()

        val result = newEraser(clearDatabase = { throw IllegalStateException("banco indisponível") }).eraseAll()

        assertTrue("Deveria ser Failed, mas foi $result", result is LocalEraseResult.Failed)
        assertTrue((result as LocalEraseResult.Failed).message.contains("nada foi apagado do banco"))
        assertDeviceStillHasItsData("banco com falha")
    }

    @Test
    fun eraser_ifSomeFilesRemain_isAFailure_andRepeatingFinishesIt() = runTest {
        createDeviceData()

        val result = newEraser(deleteFiles = { false }).eraseAll()

        assertTrue("Deveria ser Failed, mas foi $result", result is LocalEraseResult.Failed)
        assertTrue((result as LocalEraseResult.Failed).message.contains("alguns arquivos"))
        assertTrue("Os registros já tinham sido apagados", repository().getBackupSnapshot().hasNoUserData())

        assertEquals(LocalEraseResult.Erased(emptyList()), newEraser().eraseAll())
        assertDeviceIsEmpty("depois de repetir")
    }

    // ------------------------------------------------------------------------------------------
    // ViewModel: o que a tela chama
    // ------------------------------------------------------------------------------------------

    @Test
    fun viewModel_confirmationWord_ignoresSpacesAndCase_butNothingElse() {
        assertEquals("APAGAR", StellaViewModel.DELETE_CONFIRMATION_WORD)
        assertTrue(StellaViewModel.isDeleteConfirmationText("APAGAR"))
        assertTrue(StellaViewModel.isDeleteConfirmationText("  apagar "))
        assertTrue(StellaViewModel.isDeleteConfirmationText("Apagar"))
        assertFalse(StellaViewModel.isDeleteConfirmationText(""))
        assertFalse(StellaViewModel.isDeleteConfirmationText("APAG"))
        assertFalse(StellaViewModel.isDeleteConfirmationText("APAGAR TUDO"))
        assertFalse(StellaViewModel.isDeleteConfirmationText("A P A G A R"))
    }

    @Test
    fun viewModel_loadCloudFilesCount_publishesTheCount_orTheReason() = runTest {
        populateCloud()
        val viewModel = viewModel(newManager())

        viewModel.loadCloudFilesCount()

        assertTrue(awaitCondition { viewModel.cloudFilesCount.value is CloudFilesCountState.Loaded })
        assertEquals(5, (viewModel.cloudFilesCount.value as CloudFilesCountState.Loaded).count.total)

        val withoutAccount = viewModel(newManager(account = false))
        withoutAccount.loadCloudFilesCount()
        assertTrue(withoutAccount.cloudFilesCount.value is CloudFilesCountState.Error)
    }

    @Test
    fun viewModel_buttonsAreEnabledOnlyWhenTheyMakeSense() = runTest {
        val connected = viewModel(newManager())
        settle()
        assertTrue(awaitCondition { connected.canDeleteCloud.value })
        assertTrue(connected.canDeleteDevice.value)

        val disconnected = viewModel(newManager(account = false))
        awaitCondition(maxLoops = 10) { false }
        assertFalse("Sem conta, não há nuvem para apagar", disconnected.canDeleteCloud.value)
        assertTrue("Apagar o aparelho não precisa de conta", disconnected.canDeleteDevice.value)
    }

    @Test
    fun viewModel_deleteCloud_deletesTheCloud_disconnects_andKeepsTheDevice() = runTest {
        createDeviceData()
        populateCloud()
        val manager = newManager()
        val viewModel = viewModel(manager)
        settle()
        var completed: Boolean? = null

        viewModel.deleteCloudData { completed = it }

        assertTrue(awaitCondition { completed != null })
        assertEquals(true, completed)
        val state = viewModel.dataDeletion.value as DataDeletionState.Done
        assertEquals(DataDeletionKind.CLOUD, state.kind)
        assertEquals(5, state.cloudFilesDeleted)
        assertTrue(drive.files.isEmpty())
        assertNull("A conta foi desconectada", viewModel.driveAccountEmail.value)
        assertEquals(DriveConnectionStatus.NOT_CONNECTED, manager.connectionStatus.value)
        assertDeviceStillHasItsData("apagar a nuvem")

        viewModel.dismissDataDeletionResult()
        assertEquals(DataDeletionState.Idle, viewModel.dataDeletion.value)
    }

    @Test
    fun viewModel_deleteCloud_withoutInternet_isNotDone_andTheAccountStaysConnected() = runTest {
        createDeviceData()
        populateCloud()
        val viewModel = viewModel(newManager())
        settle()
        server.shutdown()
        var completed: Boolean? = null

        viewModel.deleteCloudData { completed = it }

        assertTrue(awaitCondition { completed != null })
        assertEquals(false, completed)
        val state = viewModel.dataDeletion.value as DataDeletionState.Failed
        assertEquals(DataDeletionKind.CLOUD, state.kind)
        assertTrue(state.offline)
        assertEquals(email, viewModel.driveAccountEmail.value)
        assertEquals(5, drive.files.size)
        assertDeviceStillHasItsData("apagar a nuvem sem internet")
    }

    @Test
    fun viewModel_deleteCloud_withoutAccount_reportsInsteadOfCrashing() = runTest {
        val viewModel = viewModel(newManager(account = false))
        var completed: Boolean? = null

        viewModel.deleteCloudData { completed = it }

        assertEquals(false, completed)
        assertTrue(viewModel.dataDeletion.value is DataDeletionState.Failed)
        assertEquals("Nada foi pedido ao Drive", 0, server.requestCount)
    }

    @Test
    fun viewModel_deleteDevice_emptiesTheDevice_keepsTheCloud_andLocksSyncUntilTheUserDecides() = runTest {
        createDeviceData()
        val cloud = populateCloud()
        val manager = newManager()
        val viewModel = viewModel(manager)
        settle()
        val cloudBefore = drive.files.keys.toSet()
        val currentBefore = drive.files.getValue(cloud.current).bytes.copyOf()
        var completed: Boolean? = null

        viewModel.deleteDeviceData { completed = it }

        assertTrue(awaitCondition { completed != null })
        assertEquals(true, completed)
        assertTrue(viewModel.dataDeletion.value is DataDeletionState.Done)
        assertDeviceIsEmpty("apagar o aparelho")
        assertEquals("A nuvem não foi tocada", cloudBefore, drive.files.keys.toSet())
        assertEquals(email, viewModel.driveAccountEmail.value)
        assertFalse("A sincronização ficou travada", manager.isSyncApprovedForAccount(email))
        assertFalse(manager.hasPendingChanges.value)

        // O app pergunta o que fazer com o backup da nuvem, já sabendo que o aparelho está vazio.
        assertTrue(awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending })
        assertTrue((viewModel.syncDecisionState.value as DriveSyncDecisionState.DecisionPending).isLocalEmpty)

        // E uma sincronização pedida agora não sobrescreve o backup da nuvem.
        viewModel.syncToGoogleDrive()
        assertEquals(0, drive.uploadRequests().size)
        assertEquals(currentBefore.toList(), drive.files.getValue(cloud.current).bytes.toList())
    }

    @Test
    fun viewModel_deleteDevice_withoutAccount_works_andNeverTouchesTheNetwork() = runTest {
        createDeviceData()
        val viewModel = viewModel(newManager(account = false))
        settle()
        var completed: Boolean? = null

        viewModel.deleteDeviceData { completed = it }

        assertTrue(awaitCondition { completed != null })
        assertEquals(true, completed)
        assertDeviceIsEmpty("apagar o aparelho sem conta")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun viewModel_deleteBoth_deletesTheCloudThenTheDevice_andDisconnects() = runTest {
        createDeviceData()
        populateCloud()
        val viewModel = viewModel(newManager())
        settle()
        var completed: Boolean? = null

        viewModel.deleteDeviceAndCloudData { completed = it }

        assertTrue(awaitCondition { completed != null })
        assertEquals(true, completed)
        val state = viewModel.dataDeletion.value as DataDeletionState.Done
        assertEquals(DataDeletionKind.BOTH, state.kind)
        assertEquals(5, state.cloudFilesDeleted)
        assertTrue(drive.files.isEmpty())
        assertDeviceIsEmpty("apagar ambos")
        assertNull(viewModel.driveAccountEmail.value)
    }

    @Test
    fun viewModel_deleteBoth_ifTheCloudFails_nothingIsDeletedOnTheDevice() = runTest {
        createDeviceData()
        populateCloud()
        val viewModel = viewModel(newManager())
        settle()
        drive.dropConnectionAfterDeletes = 1
        var completed: Boolean? = null

        viewModel.deleteDeviceAndCloudData { completed = it }

        assertTrue(awaitCondition { completed != null })
        assertEquals(false, completed)
        val state = viewModel.dataDeletion.value as DataDeletionState.Failed
        assertEquals(DataDeletionKind.BOTH, state.kind)
        assertTrue(state.offline)
        assertFalse(state.cloudDeleted)
        assertTrue(state.message.contains("Nada foi apagado neste aparelho"))
        assertEquals(email, viewModel.driveAccountEmail.value)
        assertEquals(4, drive.files.size)
        assertDeviceStillHasItsData("apagar ambos com a nuvem falhando")
    }

    @Test
    fun viewModel_deleteBoth_ifTheDeviceFails_keepsTheAccount_locksSync_andRepeatingFinishesIt() = runTest {
        createDeviceData()
        populateCloud()
        val manager = newManager()
        var failFiles = true
        val eraser = newEraser(deleteFiles = { if (failFiles) false else AttachmentStorage(context).deleteAllFiles() })
        val viewModel = viewModel(manager, eraser)
        settle()
        var first: Boolean? = null

        viewModel.deleteDeviceAndCloudData { first = it }

        assertTrue(awaitCondition { first != null })
        assertEquals(false, first)
        val state = viewModel.dataDeletion.value as DataDeletionState.Failed
        assertEquals(DataDeletionKind.BOTH, state.kind)
        assertTrue("A nuvem já foi apagada", state.cloudDeleted)
        assertTrue(state.message.contains("A conta continua conectada"))
        assertTrue(drive.files.isEmpty())
        assertEquals(email, viewModel.driveAccountEmail.value)
        assertFalse("Nada pode subir de novo até decidir", manager.isSyncApprovedForAccount(email))

        // Repetir termina o serviço: a nuvem já está vazia, o aparelho é esvaziado e a conta sai.
        failFiles = false
        var second: Boolean? = null
        viewModel.deleteDeviceAndCloudData { second = it }

        assertTrue(awaitCondition { second != null })
        assertEquals(true, second)
        assertTrue(viewModel.dataDeletion.value is DataDeletionState.Done)
        assertDeviceIsEmpty("apagar ambos, repetido")
        assertNull(viewModel.driveAccountEmail.value)
    }

    @Test
    fun viewModel_deleteBoth_withoutAccount_changesNothing() = runTest {
        createDeviceData()
        val viewModel = viewModel(newManager(account = false))
        settle()
        var completed: Boolean? = null

        viewModel.deleteDeviceAndCloudData { completed = it }

        assertEquals(false, completed)
        assertTrue(viewModel.dataDeletion.value is DataDeletionState.Failed)
        assertDeviceStillHasItsData("apagar ambos sem conta")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun viewModel_aSecondTapWhileDeleting_isIgnored() = runTest {
        populateCloud()
        val viewModel = viewModel(newManager())
        settle()
        val results = mutableListOf<Boolean>()

        viewModel.deleteCloudData { results.add(it) }
        viewModel.deleteCloudData { results.add(it) } // toque duplo

        assertTrue(awaitCondition { results.isNotEmpty() })
        awaitCondition(maxLoops = 20) { false } // deixa passar tempo para um segundo resultado indevido
        assertEquals("Só um resultado", listOf(true), results)
        assertTrue(viewModel.dataDeletion.value is DataDeletionState.Done)
        assertEquals("Cada arquivo foi apagado uma vez", 5, drive.deleteRequests().size)
    }
}
