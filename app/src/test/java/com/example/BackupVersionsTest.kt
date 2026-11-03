package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.drive.BackupVersionKind
import com.example.data.drive.DriveAccountInfo
import com.example.data.drive.DriveAuthGateway
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.model.GlucoseRecord
import com.example.data.model.StellaProfile
import com.example.data.repository.StellaRepository
import com.example.viewmodel.BackupPreviewResult
import com.example.viewmodel.BackupVersionsState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Etapa 3a: lista de versões do backup (o atual e as cópias datadas) e restauração de uma versão escolhida.
 */
@RunWith(RobolectricTestRunner::class)
class BackupVersionsTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var drive: FakeDrive

    private val email = "versoes@example.com"
    private val folderId = "folder_versoes"

    private val cloudPrefix = GoogleDriveSyncManager.SAFETY_COPY_CLOUD_PREFIX
    private val devicePrefix = GoogleDriveSyncManager.SAFETY_COPY_DEVICE_PREFIX
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

    private fun newManager(): GoogleDriveSyncManager {
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun checkAccountHasScope(account: GoogleSignInAccount) = true
            override fun getToken() = "test-token"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) = onComplete()
        }
        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { "test-token" },
            driveApiBaseUrl = server.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = server.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        return manager
    }

    private fun repository() = StellaRepository(AppDatabase.getDatabase(context).stellaDao())

    private fun glucoseJson(id: Long, value: Double) = JSONObject()
        .put("id", id).put("dateString", "10/10/2026").put("timeString", "08:00")
        .put("dateTimeMillis", 1760086800000L + id).put("value", value)
        .put("unit", "mg/dL").put("mealRelation", "Em jejum")

    private fun backupBytes(profileName: String, glucoseCount: Int, version: Int = 2): ByteArray =
        JSONObject()
            .put("backupVersion", version)
            .put("exportDate", "2026-10-14T10:00:00")
            .put("profile", JSONObject().put("id", 1).put("name", profileName))
            .put("glucoseRecords", JSONArray().apply { for (i in 1..glucoseCount) put(glucoseJson(i.toLong(), 100.0 + i)) })
            .toString().toByteArray(Charsets.UTF_8)

    /** Pasta com o backup atual e três cópias, como ficam depois de algum uso. */
    private data class Cloud(val current: String, val cloudOld: String, val cloudNew: String, val device: String)

    private fun populateCloud(): Cloud {
        val current = drive.put(currentName, backupBytes("Atual", 3), "2026-10-10T10:00:00Z")
        val cloudOld = drive.put("${cloudPrefix}20261001_0900.json", backupBytes("Antiga", 1), "2026-10-01T09:00:00Z")
        val device = drive.put("${devicePrefix}20261003_1200.json", backupBytes("Do aparelho", 2), "2026-10-03T12:00:00Z")
        val cloudNew = drive.put("${cloudPrefix}20261005_1000.json", backupBytes("Recente", 2), "2026-10-05T10:00:00Z")
        return Cloud(current, cloudOld, cloudNew, device)
    }

    private fun instant(text: String) = java.time.Instant.parse(text).toEpochMilli()

    private suspend fun assertRefused(what: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Deveria ter recusado: $what")
        } catch (expected: Exception) {
            // ok
        }
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

    private fun copyRequests() = synchronized(drive.requests) {
        drive.requests.count { it.method == "POST" && it.path.orEmpty().contains("/copy") }
    }

    // ------------------------------------------------------------------------------------------
    // Lista de versões
    // ------------------------------------------------------------------------------------------

    @Test
    fun list_currentFirst_thenCopiesNewestFirst_andIgnoresEverythingElse() = runTest {
        val cloud = populateCloud()
        // Ruído que NÃO é versão restaurável
        drive.put("stella_anexo_${"a".repeat(64)}.pdf", AttachmentTestSupport.pdfBytes())
        drive.put(GoogleDriveSyncManager.REPORT_FILE_NAME, "relatório".toByteArray())
        drive.put("${cloudPrefix}20261006_1000.txt", backupBytes("Texto", 1))
        drive.put("stella_prontuario_backup_desconhecido.json", backupBytes("Outro", 1))
        val trashed = drive.put("${cloudPrefix}20261007_1000.json", backupBytes("Lixeira", 1))
        drive.trashedIds.add(trashed)
        drive.put("${cloudPrefix}20261008_1000.json", backupBytes("Outra pasta", 1), parent = "outra_pasta")
        drive.listPageSize = 2 // várias páginas

        val versions = newManager().listBackupVersions(email)

        assertEquals(
            listOf(currentName, "${cloudPrefix}20261005_1000.json", "${devicePrefix}20261003_1200.json", "${cloudPrefix}20261001_0900.json"),
            versions.map { it.fileName }
        )
        assertEquals(
            listOf(cloud.current, cloud.cloudNew, cloud.device, cloud.cloudOld),
            versions.map { it.fileId }
        )
        assertEquals(
            listOf(
                BackupVersionKind.CURRENT,
                BackupVersionKind.CLOUD_BEFORE_OVERWRITE,
                BackupVersionKind.DEVICE_BEFORE_RESTORE,
                BackupVersionKind.CLOUD_BEFORE_OVERWRITE
            ),
            versions.map { it.kind }
        )
        assertEquals(instant("2026-10-10T10:00:00Z"), versions[0].createdAtMillis)
        assertEquals(instant("2026-10-05T10:00:00Z"), versions[1].createdAtMillis)
        assertEquals(backupBytes("Atual", 3).size.toLong(), versions[0].sizeBytes)
    }

    @Test
    fun list_emptyFolder_isEmpty_andOnlyCurrentIsJustOne() = runTest {
        val manager = newManager()
        assertTrue(manager.listBackupVersions(email).isEmpty())

        drive.put(currentName, backupBytes("Atual", 1))

        assertEquals(listOf(BackupVersionKind.CURRENT), manager.listBackupVersions(email).map { it.kind })
    }

    @Test
    fun list_whenDriveGivesNoTime_usesTheTimeInTheCopyName() = runTest {
        drive.put("${cloudPrefix}20261001_0930.json", backupBytes("Antiga", 1), createdTime = "")

        val version = newManager().listBackupVersions(email).single()

        val expected = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).parse("20261001_0930")!!.time
        assertEquals(expected, version.createdAtMillis)
    }

    // ------------------------------------------------------------------------------------------
    // Ver o conteúdo de uma versão
    // ------------------------------------------------------------------------------------------

    @Test
    fun preview_ofAVersion_readsThatFile_andTheDefaultStaysTheCurrentBackup() = runTest {
        val cloud = populateCloud()
        val manager = newManager()

        assertEquals(3, manager.getBackupPreviewFromDrive(email).glucoseCount)
        val old = manager.getBackupPreviewFromDrive(email, versionFileId = cloud.cloudOld)

        assertEquals(1, old.glucoseCount)
        assertEquals("Antiga", old.profileName)
    }

    @Test
    fun preview_refusesIdsThatAreNotBackupVersionsOfThisFolder() = runTest {
        populateCloud()
        val manager = newManager()
        val otherFolder = drive.put("${cloudPrefix}20261008_1000.json", backupBytes("Outra pasta", 1), parent = "outra_pasta")
        val trashed = drive.put("${cloudPrefix}20261007_1000.json", backupBytes("Lixeira", 1))
        drive.trashedIds.add(trashed)
        val attachment = drive.put("stella_anexo_${"b".repeat(64)}.pdf", AttachmentTestSupport.pdfBytes())

        assertRefused("arquivo de outra pasta") { manager.getBackupPreviewFromDrive(email, versionFileId = otherFolder) }
        assertRefused("arquivo na lixeira") { manager.getBackupPreviewFromDrive(email, versionFileId = trashed) }
        assertRefused("arquivo que não é um backup") { manager.getBackupPreviewFromDrive(email, versionFileId = attachment) }
        assertRefused("id que não existe") { manager.getBackupPreviewFromDrive(email, versionFileId = "nao_existe") }
    }

    // ------------------------------------------------------------------------------------------
    // Restaurar uma versão
    // ------------------------------------------------------------------------------------------

    @Test
    fun restore_olderVersion_copiesTheCurrentBackupFirst_restoresThatVersion_andLeavesItPending() = runTest {
        val cloud = populateCloud()
        val currentBytes = drive.files.getValue(cloud.current).bytes.copyOf()
        val manager = newManager()
        val repository = repository()

        val ok = manager.restoreBackupFromDrive(
            repository, replaceExisting = true, protectLocalData = true, versionFileId = cloud.cloudOld
        )

        assertTrue("A restauração deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals("Antiga", repository.getProfileSync()?.name)
        assertEquals(1, repository.getAllGlucoseRecordsSync().size)
        assertEquals(
            "O backup atual ganhou uma cópia datada, com o mesmo conteúdo",
            1,
            drive.files.values.count { it.name.startsWith(cloudPrefix) && it.bytes.contentEquals(currentBytes) }
        )
        assertEquals("O backup atual em si não foi tocado", currentBytes.toList(), drive.files.getValue(cloud.current).bytes.toList())
        assertTrue("Os dados restaurados ainda não estão no backup atual", manager.hasPendingChanges.value)
    }

    @Test
    fun restore_currentVersionById_makesNoExtraCopy_andClearsPending() = runTest {
        val cloud = populateCloud()
        val manager = newManager()
        manager.markPendingChanges()
        val copiesBefore = drive.files.values.count { it.name.startsWith(cloudPrefix) }

        val ok = manager.restoreBackupFromDrive(
            repository(), replaceExisting = true, protectLocalData = true, versionFileId = cloud.current
        )

        assertTrue("A restauração deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals("Atual", repository().getProfileSync()?.name)
        assertEquals(copiesBefore, drive.files.values.count { it.name.startsWith(cloudPrefix) })
        assertFalse(manager.hasPendingChanges.value)
    }

    @Test
    fun restore_olderVersion_ifTheCurrentBackupCannotBeCopied_nothingChanges() = runTest {
        val cloud = populateCloud()
        val repository = repository()
        repository.saveProfile(StellaProfile(name = "Do Aparelho"))
        repository.addGlucoseRecord(GlucoseRecord(dateString = "13/10/2026", timeString = "08:00", value = 105.0, mealRelation = "Em jejum"))
        val before = repository.getBackupSnapshot()
        drive.failCopies = true
        val manager = newManager()

        val ok = manager.restoreBackupFromDrive(
            repository, replaceExisting = true, protectLocalData = true, versionFileId = cloud.cloudOld
        )

        assertFalse(ok)
        val state = manager.syncState.value
        assertTrue("Deveria ser Error, mas foi $state", state is DriveSyncState.Error)
        assertTrue((state as DriveSyncState.Error).message.contains("nada foi alterado"))
        assertEquals("O aparelho continua como estava", before, repository.getBackupSnapshot())
        assertTrue("Nenhuma cópia do aparelho foi enviada", drive.namesStartingWith(devicePrefix).size == 1) // só a de antes
    }

    @Test
    fun restore_olderVersion_incremental_addsTheRecords_andLeavesItPending() = runTest {
        val cloud = populateCloud()
        val repository = repository()
        repository.addGlucoseRecord(GlucoseRecord(dateString = "13/10/2026", timeString = "08:00", value = 105.0, mealRelation = "Em jejum"))
        val manager = newManager()

        val ok = manager.restoreBackupFromDrive(repository, replaceExisting = false, versionFileId = cloud.cloudNew)

        assertTrue("A restauração deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals("1 do aparelho + 2 da versão", 3, repository.getAllGlucoseRecordsSync().size)
        assertTrue(manager.hasPendingChanges.value)
    }

    @Test
    fun restore_idThatIsNotAVersion_isRefused_andNothingChanges() = runTest {
        populateCloud()
        val repository = repository()
        repository.saveProfile(StellaProfile(name = "Do Aparelho"))
        val before = repository.getBackupSnapshot()
        val attachment = drive.put("stella_anexo_${"c".repeat(64)}.pdf", AttachmentTestSupport.pdfBytes())
        val manager = newManager()

        val ok = manager.restoreBackupFromDrive(repository, replaceExisting = true, versionFileId = attachment)

        assertFalse(ok)
        assertEquals(before, repository.getBackupSnapshot())
    }

    @Test
    fun undoARestore_theDeviceCopyCanBeRestoredBack() = runTest {
        val repository = repository()
        repository.saveProfile(StellaProfile(name = "Do Aparelho", notes = "Só existe aqui"))
        repository.addGlucoseRecord(GlucoseRecord(dateString = "13/10/2026", timeString = "08:00", value = 105.0, mealRelation = "Em jejum"))
        drive.put(currentName, backupBytes("Da Nuvem", 3))
        val manager = newManager()

        // 1. Restaura a nuvem por cima do aparelho: o aparelho ganha uma cópia datada.
        assertTrue(manager.restoreBackupFromDrive(repository, replaceExisting = true, protectLocalData = true))
        assertEquals("Da Nuvem", repository.getProfileSync()?.name)
        val deviceCopy = manager.listBackupVersions(email).single { it.kind == BackupVersionKind.DEVICE_BEFORE_RESTORE }

        // 2. Desfaz: restaura a cópia do aparelho.
        val ok = manager.restoreBackupFromDrive(
            repository, replaceExisting = true, protectLocalData = true, versionFileId = deviceCopy.fileId
        )

        assertTrue("Desfazer deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals("Do Aparelho", repository.getProfileSync()?.name)
        assertEquals("Só existe aqui", repository.getProfileSync()?.notes)
        assertEquals(1, repository.getAllGlucoseRecordsSync().size)
    }

    // ------------------------------------------------------------------------------------------
    // ViewModel: o que a tela chama
    // ------------------------------------------------------------------------------------------

    private fun viewModel(manager: GoogleDriveSyncManager) =
        StellaViewModel(context as Application, customDriveSyncManager = manager)

    /**
     * The ViewModel saves a default profile when the database has none. Wait for that before a restore,
     * otherwise it could land after the restore and replace the restored profile.
     */
    private suspend fun settle() {
        repeat(200) {
            mainLooper().idle()
            if (repository().getProfileSync() != null) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("O ViewModel não criou o perfil inicial a tempo")
    }

    @Test
    fun viewModel_loadBackupVersions_publishesTheList() = runTest {
        val cloud = populateCloud()
        val viewModel = viewModel(newManager())

        viewModel.loadBackupVersions()

        assertTrue(awaitCondition { viewModel.backupVersions.value is BackupVersionsState.Loaded })
        val loaded = viewModel.backupVersions.value as BackupVersionsState.Loaded
        assertEquals(cloud.current, loaded.versions.first().fileId)
        assertEquals(4, loaded.versions.size)
    }

    @Test
    fun viewModel_previewBackupVersion_returnsWhatIsInside_orTheReason() = runTest {
        val cloud = populateCloud()
        val viewModel = viewModel(newManager())
        var good: BackupPreviewResult? = null
        var bad: BackupPreviewResult? = null

        viewModel.previewBackupVersion(cloud.cloudOld) { good = it }
        viewModel.previewBackupVersion("nao_existe") { bad = it }

        assertTrue(awaitCondition { good != null && bad != null })
        assertEquals(1, (good as BackupPreviewResult.Loaded).preview.glucoseCount)
        assertTrue(bad is BackupPreviewResult.Failed)
    }

    @Test
    fun viewModel_restoreBackupVersion_restores_andASecondTapIsIgnored() = runTest {
        val cloud = populateCloud()
        val viewModel = viewModel(newManager())
        settle()
        val results = mutableListOf<Boolean>()

        viewModel.restoreBackupVersion(cloud.cloudOld) { results.add(it) }
        viewModel.restoreBackupVersion(cloud.cloudOld) { results.add(it) } // toque duplo

        assertTrue(awaitCondition { results.isNotEmpty() })
        awaitCondition(maxLoops = 20) { false } // deixa passar tempo para um segundo resultado indevido
        assertEquals("Só um resultado", listOf(true), results)
        assertEquals("Antiga", repository().getProfileSync()?.name)
        assertEquals("Uma única cópia do backup atual", 1, copyRequests())
        assertFalse(viewModel.decisionActionInProgress.value)
    }

    @Test
    fun viewModel_withoutAccount_reportsInsteadOfCrashing() = runTest {
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount(): DriveAccountInfo? = null
            override fun hasRequiredScope() = false
            override fun checkAccountHasScope(account: GoogleSignInAccount) = false
            override fun getToken() = "test-token"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) = onComplete()
        }
        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { "test-token" },
            driveApiBaseUrl = server.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = server.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        val viewModel = viewModel(manager)
        var restoreResult: Boolean? = null
        var previewResult: BackupPreviewResult? = null

        viewModel.loadBackupVersions()
        viewModel.restoreBackupVersion("qualquer") { restoreResult = it }
        viewModel.previewBackupVersion("qualquer") { previewResult = it }

        assertTrue(viewModel.backupVersions.value is BackupVersionsState.Error)
        assertEquals(false, restoreResult)
        assertNotNull(previewResult)
        assertTrue(previewResult is BackupPreviewResult.Failed)
        assertEquals("Nada foi pedido ao Drive", 0, server.requestCount)
    }
}
