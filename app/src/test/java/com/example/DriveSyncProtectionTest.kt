package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.drive.BackupManifestPreview
import com.example.data.drive.CapturedBackup
import com.example.data.drive.DriveAccountInfo
import com.example.data.drive.DriveAuthGateway
import com.example.data.drive.DriveConnectionStatus
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.drive.SafetyCopyResult
import com.example.data.db.AppDatabase
import com.example.data.model.BackupSnapshot
import com.example.data.model.ExamDocument
import com.example.data.model.FeedingRecord
import com.example.data.model.GlucoseRecord
import com.example.data.model.Medication
import com.example.data.model.MedicationAdministration
import com.example.data.model.Reminder
import com.example.data.model.StellaProfile
import com.example.data.model.VaccinePreventionRecord
import com.example.data.model.VeterinaryAppointment
import com.example.data.model.WeightRecord
import com.example.data.repository.StellaRepository
import com.example.viewmodel.DriveSyncDecisionState
import com.example.viewmodel.StellaViewModel
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriveSyncProtectionTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()
        mockWebServer.start()
        resetLocalDatabase()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
    }

    private fun createEmptySnapshot(profileName: String = StellaProfile().name): BackupSnapshot =
        BackupSnapshot(
            profile = StellaProfile(id = 1, name = profileName, photoUri = null),
            glucoseList = emptyList(),
            medications = emptyList(),
            administrations = emptyList(),
            appointments = emptyList(),
            documents = emptyList(),
            feeding = emptyList(),
            weights = emptyList(),
            vaccines = emptyList(),
            reminders = emptyList()
        )

    private fun createManager(
        email: String = "test@example.com",
        hasDriveScope: Boolean = true,
        token: String = "test-token"
    ): GoogleDriveSyncManager {
        var currentAccount: DriveAccountInfo? = DriveAccountInfo(email = email, hasDriveScope = hasDriveScope)
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = currentAccount
            override fun hasRequiredScope() = currentAccount?.hasDriveScope == true
            override fun checkAccountHasScope(account: GoogleSignInAccount) = true
            override fun getToken() = token
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) {
                currentAccount = null
                onComplete()
            }
        }
        return GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { token },
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
    }

    private fun sampleBackupJson(profileName: String = "Stella Nuvem", glucoseCount: Int = 3): String {
        return """
            {
              "backupVersion": 1,
              "appName": "STELLA",
              "exportDate": "2026-10-12T10:00:00",
              "profile": {
                "id": 1,
                "name": "$profileName"
              },
              "glucoseRecords": [
                {"id": 1, "value": 120.0},
                {"id": 2, "value": 115.0},
                {"id": 3, "value": 130.0}
              ],
              "medications": [
                {"id": 1, "name": "Insulina"}
              ]
            }
        """.trimIndent()
    }

    // =========================================================================
    // AUXILIARES: Dispatcher por caminho, esperas robustas e banco limpo
    // =========================================================================

    private fun mainLooper() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

    /**
     * O Room é um singleton compartilhado entre os testes. Sem zerar, o que um teste grava
     * (glicemia, perfil restaurado...) vaza para o seguinte e o resultado depende da ordem.
     */
    private fun resetLocalDatabase() {
        val db = AppDatabase.getDatabase(context)
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            db.clearAllTables()
            StellaRepository(db.stellaDao()).saveProfile(StellaProfile())
        }
    }

    /** Requisições que ENVIAM dados ao Drive (criação ou atualização de arquivo). */
    private fun uploadRequests(recorded: List<RecordedRequest>): List<RecordedRequest> =
        synchronized(recorded) {
            recorded.filter { req ->
                val p = req.path.orEmpty()
                (req.method == "POST" || req.method == "PATCH") && (p.contains("upload") || p.contains("uploadType"))
            }
        }

    /**
     * Nuvem simulada por caminho (não depende da ordem das chamadas). Com [backupFileId] nulo,
     * a pasta existe mas não há backup; com valor, o backup existe e é servido por [backupJson].
     */
    private fun installCloudDispatcher(
        folderId: String,
        backupFileId: String?,
        backupJson: String = sampleBackupJson(),
        copyStatus: Int = 200,
        copyDelayMs: Long = 0L,
        failUploads: Boolean = false
    ): MutableList<RecordedRequest> {
        val recorded: MutableList<RecordedRequest> =
            Collections.synchronizedList(mutableListOf<RecordedRequest>())
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded.add(request)
                val path = request.path.orEmpty()
                val isGet = request.method == "GET"
                return when {
                    isGet && path.contains("files/$folderId") ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    isGet && backupFileId != null && path.contains("files/$backupFileId") ->
                        MockResponse().setResponseCode(200).setBody(backupJson)
                    isGet && path.contains("stella_prontuario_backup.json") ->
                        MockResponse().setResponseCode(200).setBody(
                            if (backupFileId != null) {
                                """{"files":[{"id":"$backupFileId","name":"stella_prontuario_backup.json"}]}"""
                            } else {
                                """{"files":[]}"""
                            }
                        )
                    request.method == "POST" && path.contains("/copy") ->
                        MockResponse()
                            .setResponseCode(copyStatus)
                            .setHeadersDelay(copyDelayMs, TimeUnit.MILLISECONDS)
                            .setBody(
                                if (copyStatus == 200) {
                                    """{"id":"copy_file_id","name":"copia_simulada.json"}"""
                                } else {
                                    """{"error":{"code":$copyStatus,"message":"falha simulada na copia"}}"""
                                }
                            )
                    request.method == "POST" && path.contains("uploadType") && failUploads ->
                        MockResponse().setResponseCode(500).setBody(
                            """{"error":{"code":500,"message":"falha simulada no envio"}}"""
                        )
                    request.method == "POST" && path.contains("uploadType") ->
                        MockResponse().setResponseCode(200).setBody("""{"id":"uploaded_id"}""")
                    request.method == "PATCH" && path.contains("uploadType") ->
                        MockResponse().setResponseCode(200).setBody("""{"id":"updated_id"}""")
                    isGet -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }
        return recorded
    }

    /** Manager com a conta conectada, pasta persistida e status CONFIGURED (ainda NÃO aprovada). */
    private suspend fun configuredManager(email: String, folderId: String): GoogleDriveSyncManager {
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.verifyDriveConnection()
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.connectionStatus.value)
        return manager
    }

    /**
     * Espera (em tempo REAL, no máximo ~10 s) até a condição ser verdadeira, deixando o Looper
     * principal trabalhar a cada volta. Com [advanceVirtualMillis] > 0 também avança o relógio
     * virtual (necessário para o debounce de 10 s do ViewModel disparar).
     */
    private suspend fun awaitCondition(
        maxLoops: Int = 400,
        advanceVirtualMillis: Long = 0L,
        condition: () -> Boolean
    ): Boolean {
        repeat(maxLoops) {
            mainLooper().idle()
            if (condition()) return true
            if (advanceVirtualMillis > 0L) {
                mainLooper().idleFor(advanceVirtualMillis, TimeUnit.MILLISECONDS)
            }
            withContext(Dispatchers.Default) { delay(25) }
        }
        mainLooper().idle()
        return condition()
    }

    /**
     * Deixa passar [virtualSeconds] de tempo virtual (o debounce é de 10 s) e um tempo real
     * extra para as threads de rede terminarem; só depois disso "nenhum envio" vale como prova.
     */
    private suspend fun letTimePass(virtualSeconds: Int = 30) {
        repeat(virtualSeconds) {
            mainLooper().idle()
            mainLooper().idleFor(1, TimeUnit.SECONDS)
            withContext(Dispatchers.Default) { delay(25) }
        }
        withContext(Dispatchers.Default) { delay(300) }
        mainLooper().idle()
    }

    // =========================================================================
    // 1. TESTES DA TRAVA CENTRAL POR CONTA (SharedPreferences)
    // =========================================================================

    @Test
    fun testLock_whenNotApproved_syncToDriveReturnsFalseAndDoesNotUpload() = runTest {
        val email = "locked@example.com"
        val folderId = "folder_locked"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // Trava começa bloqueada (false) por padrão
        assertFalse(manager.isSyncApprovedForAccount(email))

        val captured = CapturedBackup(
            snapshot = createEmptySnapshot(),
            changeVersion = 1L,
            restoreEpoch = 0L,
            sessionEmail = email
        )

        val result = manager.syncToDrive(captured)

        assertFalse("syncToDrive deve retornar false se a trava não estiver liberada para a conta", result)
        assertEquals("Nenhuma requisição de upload deve ser enviada se a trava estiver bloqueada", 0, mockWebServer.requestCount)
    }

    @Test
    fun testLock_whenApproved_syncToDriveUploadsSuccessfully() = runTest {
        val email = "approved@example.com"
        val folderId = "folder_approved"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // Libera a trava explicitamente
        manager.setSyncApprovedForAccount(email, true)
        assertTrue(manager.isSyncApprovedForAccount(email))

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"backup_id"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"report_id"}"""))

        val captured = CapturedBackup(
            snapshot = createEmptySnapshot(),
            changeVersion = 1L,
            restoreEpoch = 0L,
            sessionEmail = email
        )

        val result = manager.syncToDrive(captured)

        assertTrue("syncToDrive deve retornar true quando aprovado", result)
        assertTrue(manager.syncState.value is DriveSyncState.Success)
    }

    @Test
    fun testLock_clearedOnSignOut() {
        val emailA = "user_a_signout@example.com"
        val manager = createManager(email = emailA)
        manager.setSyncApprovedForAccount(emailA, true)
        assertTrue(manager.isSyncApprovedForAccount(emailA))

        manager.signOut()

        assertFalse("Trava de A deve ser removida no signOut", manager.isSyncApprovedForAccount(emailA))
    }

    // Antes este teste fazia signOut e depois prepareForAccountSwitch no MESMO manager: depois do
    // signOut não há mais conta conectada, então não havia o que limpar (erro do teste, não do app).
    @Test
    fun testLock_clearedOnAccountSwitch() {
        val emailA = "user_a_switch@example.com"
        val emailB = "user_b_switch@example.com"
        val manager = createManager(email = emailA)
        manager.setSyncApprovedForAccount(emailA, true)
        assertTrue(manager.isSyncApprovedForAccount(emailA))

        manager.prepareForAccountSwitch { }

        assertFalse("Trava de A deve ser removida no prepareForAccountSwitch", manager.isSyncApprovedForAccount(emailA))
        assertFalse("Nova conta B não deve nascer aprovada", manager.isSyncApprovedForAccount(emailB))
    }

    @Test
    fun testLock_persistsAcrossRestartInSharedPreferences() {
        val email = "persist@example.com"
        val manager1 = createManager(email = email)
        manager1.setSyncApprovedForAccount(email, true)

        // Novo manager recriado com o mesmo Context (simula reinicialização do app)
        val manager2 = createManager(email = email)
        assertTrue("Aprovação deve persistir no SharedPreferences", manager2.isSyncApprovedForAccount(email))

        manager2.clearSyncApprovedForAccount(email)
        val manager3 = createManager(email = email)
        assertFalse("Revogação deve persistir no SharedPreferences", manager3.isSyncApprovedForAccount(email))
    }

    // =========================================================================
    // 2. DETECÇÃO DE BANCO LOCAL VAZIO VS COM DADOS CLÍNICOS
    // =========================================================================

    @Test
    fun testLocalEmptyDetection_defaultProfileWithoutRecords_isEmpty() {
        val emptySnapshot = createEmptySnapshot()
        assertTrue("Snapshot padrão sem dados clínicos deve ser considerado vazio", StellaViewModel.isLocalDatabaseEmpty(emptySnapshot))
        assertEquals(0, StellaViewModel.countLocalRecords(emptySnapshot))
    }

    @Test
    fun testLocalEmptyDetection_withClinicalRecords_isNotEmpty() {
        val snapshotWithGlucose = createEmptySnapshot().copy(
            glucoseList = listOf(
                GlucoseRecord(
                    dateString = "12/10/2026",
                    timeString = "10:00",
                    value = 125.0,
                    mealRelation = "Em jejum"
                )
            )
        )
        assertFalse("Snapshot com glicemia NÃO deve ser considerado vazio", StellaViewModel.isLocalDatabaseEmpty(snapshotWithGlucose))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshotWithGlucose))

        val snapshotWithMed = createEmptySnapshot().copy(
            medications = listOf(
                Medication(name = "Insulina", dosage = "2 UI", frequency = "1x ao dia", scheduledTimes = "08:00", startDate = "01/01/2026")
            )
        )
        assertFalse("Snapshot com medicação NÃO deve ser considerado vazio", StellaViewModel.isLocalDatabaseEmpty(snapshotWithMed))
    }

    @Test
    fun testLocalEmptyDetection_withCustomizedProfile_isNotEmpty() {
        val customizedSnapshot = createEmptySnapshot().copy(
            profile = StellaProfile(name = "Belinha", currentWeight = 8.5)
        )
        assertFalse("Snapshot com perfil personalizado NÃO deve ser considerado vazio", StellaViewModel.isLocalDatabaseEmpty(customizedSnapshot))
    }

    // =========================================================================
    // 3. VERIFICAÇÃO REMOTA E FLUXO DE DECISÃO NO VIEWMODEL
    // =========================================================================

    @Test
    fun testCheckDecision_whenCloudIsEmpty_automaticallyApprovesAndSyncs() = runTest {
        val email = "new_account@example.com"
        val folderId = "folder_new"
        val recorded = installCloudDispatcher(folderId, backupFileId = null)
        val manager = configuredManager(email, folderId)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        viewModel.checkSyncApprovalOrPromptDecision(email)

        assertTrue(
            "Conta deve ser aprovada automaticamente quando a nuvem não tem backup",
            awaitCondition { manager.isSyncApprovedForAccount(email) }
        )
        assertTrue(
            "Deve ocorrer o primeiro envio ao Drive",
            awaitCondition { uploadRequests(recorded).isNotEmpty() }
        )
        assertEquals(DriveSyncDecisionState.None, viewModel.syncDecisionState.value)
    }

    @Test
    fun testCheckDecision_whenCloudHasBackup_doesNotOverwriteAndExposesDecisionPending() = runTest {
        val email = "has_backup@example.com"
        val folderId = "folder_cloud"
        val backupFileId = "file_cloud_backup_123"
        val recorded = installCloudDispatcher(folderId, backupFileId, sampleBackupJson("Stella Nuvem"))
        val manager = configuredManager(email, folderId)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        viewModel.checkSyncApprovalOrPromptDecision(email)

        assertTrue(
            "O estado deve chegar a DecisionPending",
            awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending }
        )
        letTimePass(virtualSeconds = 15)

        assertFalse("Trava de sincronização DEVE continuar bloqueada para não sobrescrever", manager.isSyncApprovedForAccount(email))

        val pending = viewModel.syncDecisionState.value as DriveSyncDecisionState.DecisionPending
        assertEquals(email, pending.accountEmail)
        assertEquals("Stella Nuvem", pending.remotePreview.profileName)
        assertEquals(3, pending.remotePreview.glucoseCount)
        assertEquals(1, pending.remotePreview.medicationCount)
        assertTrue("Aparelho virgem deve ter isLocalEmpty = true", pending.isLocalEmpty)
        assertEquals(0, pending.localRecordCount)
        assertEquals("Nada pode ser enviado enquanto a decisão está pendente", 0, uploadRequests(recorded).size)
    }

    @Test
    fun testDecisionAction_confirmRestore_approvesSyncAndClearsPending() = runTest {
        val email = "restore_action@example.com"
        val folderId = "folder_restore_action"
        val backupFileId = "file_restore_123"
        // Backup mínimo, no mesmo formato que os testes de restauração já existentes usam e que
        // restaura por completo (um backup com registros incompletos termina em RestorePartial).
        val restoreJson = """
            {
              "backupVersion": 1,
              "exportedAtTimestamp": 1700000000000,
              "profile": {"id": 1, "name": "Stella Restaurada"}
            }
        """.trimIndent()
        val recorded = installCloudDispatcher(folderId, backupFileId, restoreJson)
        val manager = configuredManager(email, folderId)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        viewModel.checkSyncApprovalOrPromptDecision(email)
        assertTrue(
            "O estado deve chegar a DecisionPending antes de restaurar",
            awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending }
        )
        assertFalse(manager.isSyncApprovedForAccount(email))

        var restoreResult: Boolean? = null
        viewModel.confirmRestoreFromDrive(replaceExisting = true) { success -> restoreResult = success }

        assertTrue("A restauração deve terminar", awaitCondition { restoreResult != null })
        assertEquals("Restauração deve ter concluído com sucesso", true, restoreResult)
        assertTrue("Após restaurar da nuvem, a trava deve ser liberada para essa conta", manager.isSyncApprovedForAccount(email))
        assertEquals(DriveSyncDecisionState.None, viewModel.syncDecisionState.value)

        letTimePass(virtualSeconds = 15)
        assertEquals("Restaurar da nuvem não pode reenviar nada para a nuvem", 0, uploadRequests(recorded).size)
    }

    @Test
    fun testDecisionAction_confirmOverwrite_approvesSyncAndUploads() = runTest {
        val email = "overwrite_action@example.com"
        val folderId = "folder_overwrite_action"
        val backupFileId = "file_overwrite_123"
        val recorded = installCloudDispatcher(folderId, backupFileId, sampleBackupJson("Stella Nuvem"))
        val manager = configuredManager(email, folderId)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        viewModel.checkSyncApprovalOrPromptDecision(email)
        assertTrue(
            "O estado deve chegar a DecisionPending antes de substituir",
            awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending }
        )
        assertFalse(manager.isSyncApprovedForAccount(email))
        assertEquals("Antes da confirmação nada é enviado", 0, uploadRequests(recorded).size)

        viewModel.confirmOverwriteDriveBackup()

        assertTrue(
            "O envio deve terminar com sucesso depois da confirmação",
            awaitCondition { manager.syncState.value is DriveSyncState.Success }
        )
        assertTrue("Trava deve estar liberada após o usuário confirmar a sobrescrita", manager.isSyncApprovedForAccount(email))
        assertEquals(DriveSyncDecisionState.None, viewModel.syncDecisionState.value)
        val uploads = uploadRequests(recorded)
        assertTrue("Deve haver envio ao Drive depois da confirmação", uploads.isNotEmpty())
        assertTrue("O backup existente deve ser atualizado (PATCH)", uploads.any { it.method == "PATCH" })
    }

    @Test
    fun testDecisionAction_dismiss_keepsLockBlocked() {
        val email = "dismiss_action@example.com"
        val manager = createManager(email = email)

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        viewModel.dismissSyncDecision()

        assertFalse("Trava DEVE continuar bloqueada quando o usuário descarta a decisão", manager.isSyncApprovedForAccount(email))
        assertEquals(DriveSyncDecisionState.None, viewModel.syncDecisionState.value)
    }

    // =========================================================================
    // 4. TESTES DE PROTEÇÃO CONTRA FALHAS DE REDE E CONCORRÊNCIA NA CHECAGEM
    // =========================================================================

    @Test
    fun testCheckRemoteBackup_tokenException_keepsLockBlockedAndStateErrorAndNoUpload() = runTest {
        val email = "token_fail@example.com"
        val folderId = "folder_token_fail"
        val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    request.method == "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }

        var failToken = false
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun checkAccountHasScope(account: GoogleSignInAccount) = true
            override fun getToken(): String {
                if (failToken) throw IllegalStateException("Token failure simulation")
                return "test-token"
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = {
                if (failToken) throw IllegalStateException("Token failure simulation")
                "test-token"
            },
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        recordedRequests.clear()
        failToken = true

        assertFalse(manager.isSyncApprovedForAccount(email))

        viewModel.checkSyncApprovalOrPromptDecision(email)

        var wait = 0
        while (viewModel.syncDecisionState.value !is DriveSyncDecisionState.Error && wait++ < 200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }

        assertFalse("Trava deve continuar fechada", manager.isSyncApprovedForAccount(email))
        assertTrue("Estado deve ser Error", viewModel.syncDecisionState.value is DriveSyncDecisionState.Error)
        val uploadRequests = recordedRequests.filter {
            it.method in listOf("POST", "PATCH", "PUT") || (it.path.orEmpty().contains("upload"))
        }
        assertTrue("Nenhuma requisição de upload deve ser realizada", uploadRequests.isEmpty())
    }

    @Test
    fun testCheckRemoteBackup_http500OnFileSearch_keepsLockBlockedAndStateErrorAndNoUpload() = runTest {
        val email = "http500@example.com"
        val folderId = "folder_http500"
        val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("stella_prontuario_backup.json") -> {
                        MockResponse().setResponseCode(500).setBody(
                            """{"error":{"code":500,"message":"Internal Server Error"}}"""
                        )
                    }
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    request.method == "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }

        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        assertFalse(manager.isSyncApprovedForAccount(email))

        viewModel.checkSyncApprovalOrPromptDecision(email)

        var wait = 0
        while (viewModel.syncDecisionState.value !is DriveSyncDecisionState.Error && wait++ < 200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }

        assertFalse("Trava deve continuar fechada", manager.isSyncApprovedForAccount(email))
        assertTrue("Estado deve ser Error", viewModel.syncDecisionState.value is DriveSyncDecisionState.Error)
        val uploadRequests = recordedRequests.filter {
            it.method in listOf("POST", "PATCH", "PUT") || (it.path.orEmpty().contains("upload"))
        }
        assertTrue("Nenhuma requisição de upload deve ser realizada", uploadRequests.isEmpty())
    }

    @Test
    fun testCheckRemoteBackup_networkDisconnect_keepsLockBlockedAndStateErrorAndNoUpload() = runTest {
        val email = "disconnect@example.com"
        val folderId = "folder_disconnect"
        val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("stella_prontuario_backup.json") -> {
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    }
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    request.method == "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }

        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        assertFalse(manager.isSyncApprovedForAccount(email))

        viewModel.checkSyncApprovalOrPromptDecision(email)

        var wait = 0
        while (viewModel.syncDecisionState.value !is DriveSyncDecisionState.Error && wait++ < 200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }

        assertFalse("Trava deve continuar fechada", manager.isSyncApprovedForAccount(email))
        assertTrue("Estado deve ser Error", viewModel.syncDecisionState.value is DriveSyncDecisionState.Error)
        val uploadRequests = recordedRequests.filter {
            it.method in listOf("POST", "PATCH", "PUT") || (it.path.orEmpty().contains("upload"))
        }
        assertTrue("Nenhuma requisição de upload deve ser realizada", uploadRequests.isEmpty())
    }

    @Test
    fun testCheckRemoteBackup_consecutiveCalls_onlySingleQueryToDrive() = runTest {
        val email = "concurrent@example.com"
        val folderId = "folder_concurrent"
        val searchCount = AtomicInteger(0)

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("stella_prontuario_backup.json") -> {
                        searchCount.incrementAndGet()
                        MockResponse().setResponseCode(200)
                            .setBody("""{"files":[]}""")
                            .setBodyDelay(100, TimeUnit.MILLISECONDS)
                    }
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    request.method == "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }

        var currentAccount: DriveAccountInfo? = DriveAccountInfo(email = email, hasDriveScope = false)
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = currentAccount
            override fun hasRequiredScope() = currentAccount?.hasDriveScope == true
            override fun checkAccountHasScope(account: GoogleSignInAccount) = true
            override fun getToken() = "test-token"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) {
                currentAccount = null
                onComplete()
            }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { "test-token" },
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        currentAccount = DriveAccountInfo(email = email, hasDriveScope = true)

        viewModel.checkSyncApprovalOrPromptDecision(email)
        viewModel.checkSyncApprovalOrPromptDecision(email)

        var wait = 0
        while (viewModel.syncDecisionState.value == DriveSyncDecisionState.Checking && wait++ < 200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }

        assertEquals("Deve consultar o backup no Drive apenas uma vez", 1, searchCount.get())
    }

    // =========================================================================
    // 5. TESTES DE DETECÇÃO DE BANCO LOCAL VAZIO (9 LISTAS + CAMPOS DO PERFIL)
    // =========================================================================

    @Test
    fun testLocalEmpty_withSingleGlucoseRecord_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            glucoseList = listOf(
                GlucoseRecord(
                    dateString = "13/10/2026",
                    timeString = "08:00",
                    value = 110.0,
                    mealRelation = "Em jejum"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleMedication_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            medications = listOf(
                Medication(
                    name = "Insulina NPH",
                    dosage = "2 UI",
                    frequency = "1x ao dia",
                    scheduledTimes = "08:00",
                    startDate = "01/01/2026"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleMedicationAdministration_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            administrations = listOf(
                MedicationAdministration(
                    medicationId = 1L,
                    medicationName = "Insulina NPH",
                    dosage = "2 UI",
                    dateString = "13/10/2026",
                    timeString = "08:00"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleVeterinaryAppointment_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            appointments = listOf(
                VeterinaryAppointment(
                    dateString = "13/10/2026",
                    timeString = "10:00",
                    vetName = "Dra. Paula",
                    clinicName = "Clínica Vet",
                    reason = "Consulta de rotina"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleExamDocument_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            documents = listOf(
                ExamDocument(
                    name = "Hemograma Completo",
                    dateString = "13/10/2026",
                    category = "Exame de sangue"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleFeedingRecord_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            feeding = listOf(
                FeedingRecord(
                    brand = "Royal Canin",
                    foodName = "Mini Adult",
                    startDate = "01/01/2026"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleWeightRecord_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            weights = listOf(
                WeightRecord(
                    dateString = "13/10/2026",
                    weightKg = 8.2
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleVaccineRecord_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            vaccines = listOf(
                VaccinePreventionRecord(
                    name = "V10",
                    dateAdministered = "13/10/2026"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_withSingleReminder_isNotEmptyAndCountIsOne() {
        val snapshot = createEmptySnapshot().copy(
            reminders = listOf(
                Reminder(
                    title = "Aplicar insulina",
                    category = "Medicamento",
                    dateString = "13/10/2026",
                    timeString = "08:00"
                )
            )
        )
        assertFalse(StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(1, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_profileWithOnlySpeciesChanged_isNotEmpty() {
        val defaultProfile = StellaProfile()
        val snapshot = createEmptySnapshot().copy(
            profile = defaultProfile.copy(species = "Gato")
        )
        assertFalse("Perfil com somente species alterado deve ser considerado não vazio", StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(0, StellaViewModel.countLocalRecords(snapshot))
    }

    @Test
    fun testLocalEmpty_profileWithBreedGenderVetRegAndClinicPhoneChanged_isNotEmpty() {
        val defaultProfile = StellaProfile()
        val snapshot = createEmptySnapshot().copy(
            profile = defaultProfile.copy(
                breed = "Poodle",
                gender = "Macho",
                vetRegistrationNumber = "CRMV-SP 12345",
                clinicPhone = "(11) 99999-9999"
            )
        )
        assertFalse("Perfil com breed, gender, vetRegistrationNumber e clinicPhone alterados deve ser considerado não vazio", StellaViewModel.isLocalDatabaseEmpty(snapshot))
        assertEquals(0, StellaViewModel.countLocalRecords(snapshot))
    }

    // =========================================================================
    // 6. TESTES DE SEGURANÇA NA RESTAURAÇÃO DE VERSÕES DE BACKUP
    // =========================================================================

    @Test
    fun testRestore_whenBackupVersionIsNewerThanCurrent_abortsWithoutTouchingDatabase() = runTest {
        val email = "newer_ver@example.com"
        val folderId = "folder_newer"
        val fileId = "file_newer_backup"
        val newerVersion = GoogleDriveSyncManager.CURRENT_BACKUP_VERSION + 1

        val newerBackupJson = """
            {
              "backupVersion": $newerVersion,
              "appName": "STELLA",
              "exportDate": "2026-10-13T10:00:00",
              "profile": {
                "name": "Nuvem Incompativel"
              },
              "glucoseRecords": [
                {"id": 1, "value": 300.0, "dateString": "13/10/2026", "timeString": "12:00"}
              ]
            }
        """.trimIndent()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("files/$fileId") -> {
                        MockResponse().setResponseCode(200).setBody(newerBackupJson)
                    }
                    request.method == "GET" && path.contains("stella_prontuario_backup.json") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$fileId","name":"stella_prontuario_backup.json"}]}"""
                        )
                    }
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                }
            }
        }

        val dao = AppDatabase.getDatabase(context).stellaDao()
        val repository = StellaRepository(dao)

        val initialProfile = StellaProfile(name = "Stella Existente", notes = "Dado local preservado")
        val initialGlucose = GlucoseRecord(dateString = "13/10/2026", timeString = "08:00", value = 105.0, mealRelation = "Em jejum")
        repository.saveProfile(initialProfile)
        repository.addGlucoseRecord(initialGlucose)

        val snapshotBefore = repository.getBackupSnapshot()

        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val result = manager.restoreBackupFromDrive(repository, replaceExisting = true)

        assertFalse("Restauração de versão mais nova deve retornar false", result)

        val syncState = manager.syncState.value
        assertTrue("Estado de sincronização deve ser Error", syncState is DriveSyncState.Error)
        val errorMessage = (syncState as DriveSyncState.Error).message
        val expectedSnippet = "Este backup foi criado por uma versão mais nova do Stella (v$newerVersion). Atualize o app antes de restaurar."
        assertTrue("Mensagem de erro deve conter '$expectedSnippet', mas foi '$errorMessage'", errorMessage.contains(expectedSnippet))

        val snapshotAfter = repository.getBackupSnapshot()
        assertEquals("Banco local NÃO deve ser alterado após restauração rejeitada", snapshotBefore, snapshotAfter)
    }

    @Test
    fun testRestore_whenBackupVersionIsZero_abortsWithoutTouchingDatabase() = runTest {
        val email = "zero_ver@example.com"
        val folderId = "folder_zero"
        val fileId = "file_zero_backup"

        val zeroBackupJson = """
            {
              "backupVersion": 0,
              "appName": "STELLA",
              "exportDate": "2026-10-13T10:00:00",
              "profile": {
                "name": "Nuvem Invalida"
              }
            }
        """.trimIndent()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("files/$fileId") -> {
                        MockResponse().setResponseCode(200).setBody(zeroBackupJson)
                    }
                    request.method == "GET" && path.contains("stella_prontuario_backup.json") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$fileId","name":"stella_prontuario_backup.json"}]}"""
                        )
                    }
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                }
            }
        }

        val dao = AppDatabase.getDatabase(context).stellaDao()
        val repository = StellaRepository(dao)

        val initialProfile = StellaProfile(name = "Stella Existente Zero", notes = "Dado local intacto")
        repository.saveProfile(initialProfile)

        val snapshotBefore = repository.getBackupSnapshot()

        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val result = manager.restoreBackupFromDrive(repository, replaceExisting = true)

        assertFalse("Restauração de versão 0 deve retornar false", result)

        val syncState = manager.syncState.value
        assertTrue("Estado de sincronização deve ser Error", syncState is DriveSyncState.Error)
        val errorMessage = (syncState as DriveSyncState.Error).message
        val expectedSnippet = "Versão de backup inválida ou não suportada (0)."
        assertTrue("Mensagem de erro deve conter '$expectedSnippet', mas foi '$errorMessage'", errorMessage.contains(expectedSnippet))

        val snapshotAfter = repository.getBackupSnapshot()
        assertEquals("Banco local NÃO deve ser alterado após restauração abortada", snapshotBefore, snapshotAfter)
    }

    // =========================================================================
    // 6. TESTES DE BLOQUEIO DE UPLOAD SEM APROVAÇÃO (DEBOUNCE, RESUME, DECISION, DIRECT, SWITCH, RESTART, CONTROLE)
    // =========================================================================

    @Test
    fun testUnapprovedAccount_debounce_mutationDoesNotUploadAfter10s() = runTest {
        val email = "debounce_unapproved@example.com"
        val folderId = "folder_debounce_unapproved"
        // Com backup na nuvem a conta fica PENDENTE de decisão (sem backup ela seria aprovada sozinha).
        val recorded = installCloudDispatcher(folderId, backupFileId = "cloud_backup_debounce")
        val manager = configuredManager(email, folderId)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        assertTrue(
            "A verificação inicial deve parar em DecisionPending",
            awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending }
        )
        assertFalse(manager.isSyncApprovedForAccount(email))
        recorded.clear()

        viewModel.addGlucose(
            dateString = "13/10/2026",
            timeString = "08:00",
            value = 110.0,
            unit = "mg/dL",
            mealRelation = "Em jejum",
            medicationRelation = "Nenhuma",
            notes = "Teste debounce sem aprovacao",
            symptoms = ""
        )
        letTimePass(virtualSeconds = 30)

        assertEquals(
            "Nenhuma requisição de upload deve chegar ao servidor no debounce de conta não aprovada",
            0,
            uploadRequests(recorded).size
        )
        assertTrue(viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending)
    }

    @Test
    fun testUnapprovedAccount_onDriveAppResumed_withPendingChangesDoesNotUpload() = runTest {
        val email = "resume_unapproved@example.com"
        val folderId = "folder_resume_unapproved"
        val recorded = installCloudDispatcher(folderId, backupFileId = "cloud_backup_resume")
        val manager = configuredManager(email, folderId)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        assertTrue(
            "A verificação inicial deve parar em DecisionPending",
            awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending }
        )
        assertFalse(manager.isSyncApprovedForAccount(email))

        manager.markPendingChanges()
        assertTrue(manager.hasPendingChanges.value)
        recorded.clear()

        viewModel.onDriveAppResumed()
        letTimePass(virtualSeconds = 10)

        assertEquals(
            "Nenhuma requisição de upload deve ser realizada no onDriveAppResumed sem aprovação",
            0,
            uploadRequests(recorded).size
        )
    }

    @Test
    fun testUnapprovedAccount_decisionPendingActive_localMutationDoesNotUpload() = runTest {
        val email = "pending_active@example.com"
        val folderId = "folder_pending_active"
        val backupFileId = "backup_file_pending"
        val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("files/$backupFileId") -> {
                        MockResponse().setResponseCode(200).setBody(sampleBackupJson("Stella Nuvem", glucoseCount = 1))
                    }
                    request.method == "GET" && path.contains("stella_prontuario_backup.json") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$backupFileId","name":"stella_prontuario_backup.json"}]}"""
                        )
                    }
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    request.method == "POST" && path.contains("uploadType") -> {
                        MockResponse().setResponseCode(200).setBody("""{"id":"uploaded_id"}""")
                    }
                    request.method == "PATCH" && path.contains("uploadType") -> {
                        MockResponse().setResponseCode(200).setBody("""{"id":"updated_id"}""")
                    }
                    request.method == "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }

        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.verifyDriveConnection()

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        viewModel.checkSyncApprovalOrPromptDecision(email)

        var wait = 0
        while (viewModel.syncDecisionState.value !is DriveSyncDecisionState.DecisionPending && wait++ < 200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }

        assertTrue("Estado deve estar em DecisionPending", viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending)
        assertFalse(manager.isSyncApprovedForAccount(email))

        recordedRequests.clear()

        viewModel.addGlucose(
            dateString = "13/10/2026",
            timeString = "10:00",
            value = 140.0,
            unit = "mg/dL",
            mealRelation = "Pós-prandial",
            medicationRelation = "Nenhuma",
            notes = "Mutação local em DecisionPending",
            symptoms = ""
        )

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val uploadRequests = recordedRequests.filter { req ->
            val p = req.path.orEmpty()
            (req.method == "POST" || req.method == "PATCH") && (p.contains("upload") || p.contains("uploadType"))
        }
        assertEquals("Nenhum upload deve ser realizado com estado DecisionPending ativo", 0, uploadRequests.size)
        assertTrue("Estado deve permanecer DecisionPending", viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending)
    }

    @Test
    fun testUnapprovedAccount_syncToGoogleDriveDirectCall_doesNotUpload() = runTest {
        val email = "direct_unapproved@example.com"
        val folderId = "folder_direct_unapproved"
        val recorded = installCloudDispatcher(folderId, backupFileId = "cloud_backup_direct")
        val manager = configuredManager(email, folderId)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        assertTrue(
            "A verificação inicial deve parar em DecisionPending",
            awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending }
        )
        assertFalse(manager.isSyncApprovedForAccount(email))
        recorded.clear()

        viewModel.syncToGoogleDrive()
        letTimePass(virtualSeconds = 5)

        assertEquals(
            "Chamada direta ao syncToGoogleDrive não deve enviar se a conta não estiver aprovada",
            0,
            uploadRequests(recorded).size
        )
    }

    @Test
    fun testAccountSwitch_accountAApprovedAccountBNot_uploadBlockedOnB() = runTest {
        val emailA = "account_a@example.com"
        val emailB = "account_b@example.com"
        val folderIdB = "folder_b"
        val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("files/$folderIdB") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderIdB","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderIdB","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    request.method == "POST" && path.contains("uploadType") -> {
                        MockResponse().setResponseCode(200).setBody("""{"id":"uploaded_id"}""")
                    }
                    request.method == "PATCH" && path.contains("uploadType") -> {
                        MockResponse().setResponseCode(200).setBody("""{"id":"updated_id"}""")
                    }
                    request.method == "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }

        var currentAccount = DriveAccountInfo(email = emailA, hasDriveScope = true)
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = currentAccount
            override fun hasRequiredScope() = currentAccount.hasDriveScope
            override fun checkAccountHasScope(account: GoogleSignInAccount) = true
            override fun getToken() = "test-token"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) {
                onComplete()
            }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { "test-token" },
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )

        manager.setSyncApprovedForAccount(emailA, true)
        manager.setSyncApprovedForAccount(emailB, false)
        assertTrue(manager.isSyncApprovedForAccount(emailA))
        assertFalse(manager.isSyncApprovedForAccount(emailB))

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        // Troca para a conta B (não aprovada)
        currentAccount = DriveAccountInfo(email = emailB, hasDriveScope = true)
        manager.persistFolderId(emailB, folderIdB)
        manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.CONFIGURED, manager.connectionStatus.value)
        assertFalse("Conta B não deve estar aprovada", manager.isSyncApprovedForAccount(emailB))

        recordedRequests.clear()

        viewModel.syncToGoogleDrive()
        viewModel.addGlucose(
            dateString = "13/10/2026",
            timeString = "11:00",
            value = 130.0,
            unit = "mg/dL",
            mealRelation = "Em jejum",
            medicationRelation = "Nenhuma",
            notes = "Tentativa na conta B",
            symptoms = ""
        )

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val uploadRequests = recordedRequests.filter { req ->
            val p = req.path.orEmpty()
            (req.method == "POST" || req.method == "PATCH") && (p.contains("upload") || p.contains("uploadType"))
        }
        assertEquals("Ao trocar para a conta B (não aprovada), o envio fica bloqueado", 0, uploadRequests.size)
    }

    @Test
    fun testRestart_withDecisionPendingUnapproved_remainsBlockedAndRunsCheckAgain() = runTest {
        val email = "restart_pending@example.com"
        val folderId = "folder_restart_pending"
        val backupFileId = "backup_restart_pending"
        val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val searchCount = AtomicInteger(0)

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("files/$backupFileId") -> {
                        MockResponse().setResponseCode(200).setBody(sampleBackupJson("Stella Nuvem", glucoseCount = 1))
                    }
                    request.method == "GET" && path.contains("stella_prontuario_backup.json") -> {
                        searchCount.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$backupFileId","name":"stella_prontuario_backup.json"}]}"""
                        )
                    }
                    request.method == "GET" && path.contains("files/$folderId") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    }
                    request.method == "GET" && path.contains("name+%3D+%27STELLA%27") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"files":[{"id":"$folderId","name":"STELLA","mimeType":"application/vnd.google-apps.folder"}]}"""
                        )
                    }
                    request.method == "POST" && path.contains("uploadType") -> {
                        MockResponse().setResponseCode(200).setBody("""{"id":"uploaded_id"}""")
                    }
                    request.method == "PATCH" && path.contains("uploadType") -> {
                        MockResponse().setResponseCode(200).setBody("""{"id":"updated_id"}""")
                    }
                    request.method == "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"ok"}""")
                }
            }
        }

        val manager1 = createManager(email = email)
        manager1.persistFolderId(email, folderId)
        val viewModel1 = StellaViewModel(context as Application, customDriveSyncManager = manager1)

        var wait1 = 0
        while (viewModel1.syncDecisionState.value !is DriveSyncDecisionState.DecisionPending && wait1++ < 200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }

        assertTrue("Primeira execução deve ficar em DecisionPending", viewModel1.syncDecisionState.value is DriveSyncDecisionState.DecisionPending)
        assertFalse("Conta não deve estar aprovada", manager1.isSyncApprovedForAccount(email))
        val countBefore = searchCount.get()
        assertTrue(countBefore >= 1)

        recordedRequests.clear()

        // Reinicia o app usando o mesmo Context
        val manager2 = createManager(email = email)
        val viewModel2 = StellaViewModel(context as Application, customDriveSyncManager = manager2)

        var wait2 = 0
        while (viewModel2.syncDecisionState.value !is DriveSyncDecisionState.DecisionPending && wait2++ < 200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }

        assertFalse("Após reinício, conta continua bloqueada", manager2.isSyncApprovedForAccount(email))
        assertTrue("Após reinício, verificação roda de novo e volta a DecisionPending", viewModel2.syncDecisionState.value is DriveSyncDecisionState.DecisionPending)
        assertTrue("Busca deve rodar de novo após reinício", searchCount.get() > countBefore)

        val uploadRequests = recordedRequests.filter { req ->
            val p = req.path.orEmpty()
            (req.method == "POST" || req.method == "PATCH") && (p.contains("upload") || p.contains("uploadType"))
        }
        assertEquals("Nenhum upload durante reinício de conta com decisão pendente", 0, uploadRequests.size)
    }

    @Test
    fun testControlPositive_whenAccountApproved_debounceAndDirectSyncUploadNormally() = runTest {
        val email = "approved_control@example.com"
        val folderId = "folder_approved_control"
        val recorded = installCloudDispatcher(folderId, backupFileId = null)
        val manager = configuredManager(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)

        // Caminho (d): syncToGoogleDrive chamado diretamente
        recorded.clear()
        viewModel.syncToGoogleDrive()
        assertTrue(
            "Caminho (d) deve realizar upload normalmente quando aprovado",
            awaitCondition { uploadRequests(recorded).isNotEmpty() && manager.syncState.value is DriveSyncState.Success }
        )

        // Caminho (a): debounce de 10 s disparado por uma mutação pública.
        // O sucesso do envio anterior continua em syncState, então a espera é pelos UPLOADS novos.
        recorded.clear()
        viewModel.addGlucose(
            dateString = "13/10/2026",
            timeString = "12:00",
            value = 115.0,
            unit = "mg/dL",
            mealRelation = "Em jejum",
            medicationRelation = "Nenhuma",
            notes = "Controle positivo aprovado",
            symptoms = ""
        )
        assertTrue(
            "Caminho (a) deve realizar upload normalmente após o debounce quando aprovado",
            awaitCondition(advanceVirtualMillis = 1_000L) { uploadRequests(recorded).isNotEmpty() }
        )
    }

    // =========================================================================
    // 7. CÓPIAS DE SEGURANÇA (nuvem antes de sobrescrever, aparelho antes de restaurar) E CLIQUE DUPLO
    // =========================================================================

    private fun bodyText(request: RecordedRequest): String = request.body.clone().readUtf8()

    private fun copyRequests(recorded: List<RecordedRequest>): List<RecordedRequest> =
        synchronized(recorded) {
            recorded.filter { it.method == "POST" && it.path.orEmpty().contains("/copy") }
        }

    private fun backupDownloads(recorded: List<RecordedRequest>, fileId: String): Int =
        synchronized(recorded) {
            recorded.count {
                val p = it.path.orEmpty()
                it.method == "GET" && p.contains("files/$fileId") && p.contains("alt=media")
            }
        }

    /** ViewModel já parado na decisão "restaurar ou substituir" (nuvem com backup). */
    private suspend fun decisionViewModel(manager: GoogleDriveSyncManager, email: String): StellaViewModel {
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        viewModel.checkSyncApprovalOrPromptDecision(email)
        assertTrue(
            "O estado deve chegar a DecisionPending",
            awaitCondition { viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending }
        )
        return viewModel
    }

    private val restorableCloudJson = """
        {
          "backupVersion": 1,
          "exportedAtTimestamp": 1700000000000,
          "profile": {"id": 1, "name": "Stella da Nuvem"}
        }
    """.trimIndent()

    private suspend fun putDataOnDevice(): StellaRepository {
        val repository = StellaRepository(AppDatabase.getDatabase(context).stellaDao())
        repository.saveProfile(StellaProfile(name = "Stella do Aparelho", notes = "So existe neste aparelho"))
        repository.addGlucoseRecord(
            GlucoseRecord(dateString = "13/10/2026", timeString = "08:00", value = 105.0, mealRelation = "Em jejum")
        )
        return repository
    }

    @Test
    fun testCopyExistingBackup_whenCloudHasBackup_returnsCreatedWithTheIdDriveGave() = runTest {
        val email = "copy_created@example.com"
        val folderId = "folder_copy_created"
        val recorded = installCloudDispatcher(folderId, backupFileId = "file_source_created")
        val manager = configuredManager(email, folderId)

        val result = manager.copyExistingBackupForSafety(email)

        assertTrue("Deveria ter criado a cópia, mas foi $result", result is SafetyCopyResult.Created)
        assertEquals("copy_file_id", (result as SafetyCopyResult.Created).fileId)
        assertEquals(1, copyRequests(recorded).size)
        assertEquals("Fazer a cópia não envia nenhum arquivo novo", 0, uploadRequests(recorded).size)
    }

    @Test
    fun testCopyExistingBackup_whenCloudHasNoBackup_returnsNothingToCopy() = runTest {
        val email = "copy_nothing@example.com"
        val folderId = "folder_copy_nothing"
        val recorded = installCloudDispatcher(folderId, backupFileId = null)
        val manager = configuredManager(email, folderId)

        val result = manager.copyExistingBackupForSafety(email)

        assertEquals(SafetyCopyResult.NothingToCopy, result)
        assertEquals(0, copyRequests(recorded).size)
        assertEquals(0, uploadRequests(recorded).size)
    }

    @Test
    fun testOverwrite_makesDatedCopyOfCloudBackupBeforeSendingAnything() = runTest {
        val email = "copy_before_overwrite@example.com"
        val folderId = "folder_copy_before"
        val backupFileId = "file_copy_source"
        val recorded = installCloudDispatcher(folderId, backupFileId, sampleBackupJson("Stella Nuvem"))
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)

        viewModel.confirmOverwriteDriveBackup()

        assertTrue(
            "O envio deve terminar com sucesso",
            awaitCondition { manager.syncState.value is DriveSyncState.Success }
        )

        val copies = copyRequests(recorded)
        assertEquals("Deve existir exatamente 1 cópia de segurança", 1, copies.size)
        val copy = copies.single()
        assertTrue(
            "A cópia deve partir do backup que está na nuvem (id $backupFileId): ${copy.path}",
            copy.path.orEmpty().contains("/files/$backupFileId/copy")
        )
        val body = JSONObject(bodyText(copy))
        val name = body.getString("name")
        assertTrue(
            "Nome da cópia deve ser datado (AAAAMMDD_HHmm), mas foi '$name'",
            Regex("""stella_prontuario_backup_antes_de_substituir_\d{8}_\d{4}\.json""").matches(name)
        )
        assertEquals(
            "A cópia deve ficar na mesma pasta do backup (parents explícito)",
            folderId,
            body.getJSONArray("parents").getString(0)
        )

        val all = synchronized(recorded) { recorded.toList() }
        val firstUpload = uploadRequests(recorded).first()
        assertTrue(
            "A cópia tem de acontecer ANTES de qualquer envio que substitua o backup",
            all.indexOf(copy) < all.indexOf(firstUpload)
        )
        assertTrue(manager.isSyncApprovedForAccount(email))
        assertEquals(DriveSyncDecisionState.None, viewModel.syncDecisionState.value)
    }

    @Test
    fun testOverwrite_whenCopyFails_replacesNothingAndKeepsDecisionPending() = runTest {
        val email = "copy_fails@example.com"
        val folderId = "folder_copy_fails"
        val recorded = installCloudDispatcher(folderId, "file_copy_fails", copyStatus = 500)
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)

        viewModel.confirmOverwriteDriveBackup()

        assertTrue(
            "O erro deve ser publicado para a tela",
            awaitCondition { viewModel.uiErrorMessage.value != null }
        )
        letTimePass(virtualSeconds = 15)

        assertTrue(
            "A mensagem deve falar da cópia de segurança, mas foi '${viewModel.uiErrorMessage.value}'",
            viewModel.uiErrorMessage.value.orEmpty().contains("cópia de segurança")
        )
        assertFalse("A trava deve continuar fechada", manager.isSyncApprovedForAccount(email))
        assertTrue(
            "A decisão deve continuar pendente para tentar de novo",
            viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending
        )
        assertEquals("Nada pode ser enviado se a cópia falhou", 0, uploadRequests(recorded).size)
        assertTrue(manager.syncState.value is DriveSyncState.Error)
        assertFalse("O botão deve ser liberado de novo", viewModel.decisionActionInProgress.value)
    }

    @Test
    fun testOverwrite_doubleTap_makesOnlyOneCopy() = runTest {
        val email = "double_overwrite@example.com"
        val folderId = "folder_double_overwrite"
        val recorded = installCloudDispatcher(folderId, "file_double_overwrite")
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)

        viewModel.confirmOverwriteDriveBackup()
        assertTrue("A ação deve constar como em andamento", viewModel.decisionActionInProgress.value)
        viewModel.confirmOverwriteDriveBackup()

        assertTrue(
            "O envio deve terminar",
            awaitCondition { manager.syncState.value is DriveSyncState.Success }
        )
        letTimePass(virtualSeconds = 15)

        assertEquals("O segundo toque não pode gerar outra cópia", 1, copyRequests(recorded).size)
        assertFalse(viewModel.decisionActionInProgress.value)
    }

    @Test
    fun testOverwrite_dismissedWhileCopying_doesNotApproveNorUpload() = runTest {
        val email = "dismiss_while_copying@example.com"
        val folderId = "folder_dismiss_copying"
        val recorded = installCloudDispatcher(folderId, "file_dismiss_copying", copyDelayMs = 700L)
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)

        viewModel.confirmOverwriteDriveBackup()
        assertTrue(
            "O pedido de cópia deve ter chegado à nuvem antes de descartar a decisão",
            awaitCondition { copyRequests(recorded).isNotEmpty() }
        )
        viewModel.dismissSyncDecision()

        assertTrue(
            "A ação deve terminar",
            awaitCondition { !viewModel.decisionActionInProgress.value }
        )
        letTimePass(virtualSeconds = 15)

        assertFalse("Quem descartou a decisão não pode ter a trava aberta", manager.isSyncApprovedForAccount(email))
        assertEquals(0, uploadRequests(recorded).size)
        assertEquals(DriveSyncDecisionState.None, viewModel.syncDecisionState.value)
    }

    @Test
    fun testRestore_whenBothSidesHaveData_uploadsDatedCopyOfDeviceDataBeforeReplacing() = runTest {
        val email = "restore_both_sides@example.com"
        val folderId = "folder_restore_both"
        val backupFileId = "file_restore_both"
        val recorded = installCloudDispatcher(folderId, backupFileId, restorableCloudJson)
        val repository = putDataOnDevice()
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)
        val pending = viewModel.syncDecisionState.value as DriveSyncDecisionState.DecisionPending
        assertFalse("O aparelho tem dados próprios", pending.isLocalEmpty)

        var result: Boolean? = null
        viewModel.confirmRestoreFromDrive(replaceExisting = true) { result = it }

        assertTrue("A restauração deve terminar", awaitCondition { result != null })
        assertEquals(true, result)
        letTimePass(virtualSeconds = 15)

        val uploads = uploadRequests(recorded)
        assertEquals("Deve haver exatamente 1 envio: a cópia dos dados do aparelho", 1, uploads.size)
        val copy = uploads.single()
        assertEquals("POST", copy.method)
        val body = bodyText(copy)
        assertTrue(
            "Nome da cópia deve ser datado (aparelho_antes_de_restaurar_AAAAMMDD_HHmm)",
            Regex("""stella_prontuario_backup_aparelho_antes_de_restaurar_\d{8}_\d{4}\.json""").containsMatchIn(body)
        )
        assertTrue("A cópia deve ir para a pasta do backup", body.contains(folderId))
        assertTrue("A cópia deve conter o perfil do aparelho", body.contains("Stella do Aparelho"))
        assertTrue("A cópia deve conter as anotações do aparelho", body.contains("So existe neste aparelho"))

        val after = repository.getBackupSnapshot()
        assertEquals("Depois da restauração o aparelho tem os dados da nuvem", "Stella da Nuvem", after.profile.name)
        assertEquals(0, after.glucoseList.size)
        assertTrue(manager.isSyncApprovedForAccount(email))
        assertEquals(DriveSyncDecisionState.None, viewModel.syncDecisionState.value)
    }

    @Test
    fun testRestore_whenDeviceCopyFails_restoresNothingAndKeepsLocalData() = runTest {
        val email = "restore_copy_fails@example.com"
        val folderId = "folder_restore_copy_fails"
        installCloudDispatcher(folderId, "file_restore_copy_fails", restorableCloudJson, failUploads = true)
        val repository = putDataOnDevice()
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)
        val before = repository.getBackupSnapshot()

        var result: Boolean? = null
        viewModel.confirmRestoreFromDrive(replaceExisting = true) { result = it }

        assertTrue("A tentativa deve terminar", awaitCondition { result != null })
        assertEquals("Sem a cópia, a restauração não pode acontecer", false, result)

        val after = repository.getBackupSnapshot()
        assertEquals("Os dados do aparelho devem continuar exatamente como estavam", before, after)
        assertTrue(
            "A decisão deve continuar pendente",
            viewModel.syncDecisionState.value is DriveSyncDecisionState.DecisionPending
        )
        assertFalse(manager.isSyncApprovedForAccount(email))
        val state = manager.syncState.value
        assertTrue("O estado deve ser Error, mas foi $state", state is DriveSyncState.Error)
        val errorMessage = (state as DriveSyncState.Error).message
        assertTrue(
            "A mensagem deve explicar a cópia, mas foi '$errorMessage'",
            errorMessage.contains("cópia dos dados deste aparelho")
        )
        assertFalse(viewModel.decisionActionInProgress.value)
    }

    @Test
    fun testRestore_whenDeviceIsEmpty_doesNotCreateDeviceCopy() = runTest {
        val email = "restore_empty_device@example.com"
        val folderId = "folder_restore_empty_device"
        val recorded = installCloudDispatcher(folderId, "file_restore_empty_device", restorableCloudJson)
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)

        var result: Boolean? = null
        viewModel.confirmRestoreFromDrive(replaceExisting = true) { result = it }

        assertTrue(awaitCondition { result != null })
        assertEquals(true, result)
        letTimePass(virtualSeconds = 15)
        assertEquals("Aparelho vazio não tem o que proteger", 0, uploadRequests(recorded).size)
    }

    @Test
    fun testRestore_doubleTap_restoresOnlyOnce() = runTest {
        val email = "double_restore@example.com"
        val folderId = "folder_double_restore"
        val backupFileId = "file_double_restore"
        val recorded = installCloudDispatcher(folderId, backupFileId, restorableCloudJson)
        val manager = configuredManager(email, folderId)
        val viewModel = decisionViewModel(manager, email)
        val downloadsBefore = backupDownloads(recorded, backupFileId)

        var completions = 0
        viewModel.confirmRestoreFromDrive(replaceExisting = true) { completions++ }
        viewModel.confirmRestoreFromDrive(replaceExisting = true) { completions++ }

        assertTrue("A restauração deve terminar", awaitCondition { completions >= 1 })
        letTimePass(virtualSeconds = 15)

        assertEquals("Só o primeiro toque responde", 1, completions)
        assertEquals(
            "O backup só pode ser baixado uma vez para restaurar",
            1,
            backupDownloads(recorded, backupFileId) - downloadsBefore
        )
        assertFalse(viewModel.decisionActionInProgress.value)
    }
}
