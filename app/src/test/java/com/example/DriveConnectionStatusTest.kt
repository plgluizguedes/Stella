package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.drive.CapturedBackup
import com.example.data.drive.DriveAccountInfo
import com.example.data.drive.DriveAuthGateway
import com.example.data.drive.DriveConnectionStatus
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.drive.DriveApiException
import com.example.data.model.BackupSnapshot
import com.example.data.model.ExamDocument
import com.example.data.model.StellaProfile
import com.example.viewmodel.StellaViewModel
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.UserRecoverableAuthException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DriveConnectionStatusTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
    }

    private fun createManager(
        email: String = "test@example.com",
        hasDriveScope: Boolean = true,
        token: String = "test-token",
        autoApproveSync: Boolean = true
    ): GoogleDriveSyncManager {
        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { token },
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            accountProvider = { DriveAccountInfo(email = email, hasDriveScope = hasDriveScope) }
        )
        if (autoApproveSync) {
            manager.setSyncApprovedForAccount(email, true)
        }
        return manager
    }

    private fun assertRecordedRequest(folderId: String) {
        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("Requisição HTTP deveria ter sido enviada ao servidor mock", request)
        assertEquals("GET", request!!.method)
        assertTrue(
            "Caminho da requisição deve conter o folderId: ${request.path}",
            request.path?.contains("/files/$folderId") == true
        )
        assertTrue(
            "Requisição deve solicitar fields: ${request.path}",
            request.path?.contains("fields=") == true
        )
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    // =========================================================================
    // TESTE ESTRUTURAL DO ENUM
    // =========================================================================
    @Test
    fun testEnumContainsVerifyingAndUnverifiedStates() {
        val statuses = DriveConnectionStatus.values().map { it.name }
        assertTrue("DriveConnectionStatus must contain VERIFYING", statuses.contains("VERIFYING"))
        assertTrue("DriveConnectionStatus must contain UNVERIFIED", statuses.contains("UNVERIFIED"))
        assertTrue("DriveConnectionStatus must contain NOT_CONNECTED", statuses.contains("NOT_CONNECTED"))
        assertTrue("DriveConnectionStatus must contain CONNECTING", statuses.contains("CONNECTING"))
        assertTrue("DriveConnectionStatus must contain ACCOUNT_CONNECTED_NO_DRIVE", statuses.contains("ACCOUNT_CONNECTED_NO_DRIVE"))
        assertTrue("DriveConnectionStatus must contain PERMISSION_MISSING", statuses.contains("PERMISSION_MISSING"))
        assertTrue("DriveConnectionStatus must contain CONFIGURED", statuses.contains("CONFIGURED"))
        assertTrue("DriveConnectionStatus must contain ERROR", statuses.contains("ERROR"))
    }

    // =========================================================================
    // TESTE 1 — pasta válida (HTTP 200, trashed=false, mimeType=folder)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_validFolder_returnsConfigured() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val responseBody = """
            {
              "id": "$folderId",
              "name": "STELLA - Prontuário Veterinário",
              "trashed": false,
              "mimeType": "application/vnd.google-apps.folder"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.CONFIGURED, result)
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 2 — pasta na lixeira (HTTP 200, trashed=true)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_trashedFolder_removesFolderIdAndReturnsAccountConnectedNoDrive() = runTest {
        val email = "user@example.com"
        val otherEmail = "other@example.com"
        val folderId = "folder_123"
        val otherFolderId = "folder_other_999"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.persistFolderId(otherEmail, otherFolderId)

        val responseBody = """
            {
              "id": "$folderId",
              "name": "STELLA - Prontuário Veterinário",
              "trashed": true,
              "mimeType": "application/vnd.google-apps.folder"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, result)
        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, manager.connectionStatus.value)
        assertNull("Folder ID deve ser removido quando a pasta está na lixeira", manager.getPersistedFolderId(email))
        assertEquals("Folder ID de outra conta NÃO deve ser removido", otherFolderId, manager.getPersistedFolderId(otherEmail))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 3 — HTTP 404 (pasta não encontrada)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_http404_removesFolderIdAndReturnsAccountConnectedNoDrive() = runTest {
        val email = "user@example.com"
        val otherEmail = "other@example.com"
        val folderId = "folder_123"
        val otherFolderId = "folder_other_999"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.persistFolderId(otherEmail, otherFolderId)

        val responseBody = """
            {
              "error": {
                "code": 404,
                "message": "File not found: $folderId",
                "errors": [
                  {
                    "message": "File not found",
                    "domain": "global",
                    "reason": "notFound"
                  }
                ]
              }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody(responseBody))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, result)
        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, manager.connectionStatus.value)
        assertNull("Folder ID deve ser removido em caso de 404", manager.getPersistedFolderId(email))
        assertEquals("Folder ID de outra conta NÃO deve ser removido", otherFolderId, manager.getPersistedFolderId(otherEmail))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 4 — appNotAuthorizedToFile (HTTP 403 com appNotAuthorizedToFile)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_appNotAuthorizedToFile_removesFolderIdAndReturnsAccountConnectedNoDrive() = runTest {
        val email = "user@example.com"
        val otherEmail = "other@example.com"
        val folderId = "folder_123"
        val otherFolderId = "folder_other_999"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.persistFolderId(otherEmail, otherFolderId)

        val responseBody = """
            {
              "error": {
                "code": 403,
                "message": "The user has not granted the app read access",
                "errors": [
                  {
                    "reason": "appNotAuthorizedToFile"
                  }
                ]
              }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody(responseBody))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, result)
        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, manager.connectionStatus.value)
        assertNull("Folder ID deve ser removido quando appNotAuthorizedToFile ocorrer", manager.getPersistedFolderId(email))
        assertEquals("Folder ID de outra conta NÃO deve ser removido", otherFolderId, manager.getPersistedFolderId(otherEmail))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 5 — HTTP 429 (rate limit transitório)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_http429_keepsFolderIdAndReturnsUnverified() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(429).setBody("Rate limit exceeded"))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.UNVERIFIED, result)
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals("Folder ID deve permanecer em erro 429", folderId, manager.getPersistedFolderId(email))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 6 — HTTP 500 (erro de servidor transitório)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_http500_keepsFolderIdAndReturnsUnverified() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.UNVERIFIED, result)
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals("Folder ID deve permanecer em erro 500", folderId, manager.getPersistedFolderId(email))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 7 — HTTP 503 (serviço indisponível transitório)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_http503_keepsFolderIdAndReturnsUnverified() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.UNVERIFIED, result)
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals("Folder ID deve permanecer em erro 503", folderId, manager.getPersistedFolderId(email))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 8 — erro de rede (servidor inacessível / conexão fechada)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_networkError_keepsFolderIdAndReturnsUnverified() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // Encerra o mockWebServer antes da chamada para provocar falha de rede local
        mockWebServer.shutdown()

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.UNVERIFIED, result)
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals("Folder ID deve permanecer em falha de rede", folderId, manager.getPersistedFolderId(email))
    }

    // =========================================================================
    // TESTE 9 — HTTP 401 seguido de falha transitória no retry (mantém folderId e retorna UNVERIFIED)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_http401_keepsFolderIdAndReturnsUnverified() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.UNVERIFIED, result)
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals("Folder ID deve permanecer em erro 401 seguido de falha transitória", folderId, manager.getPersistedFolderId(email))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 10 — HTTP 200 com JSON malformado (mantém folderId e retorna ERROR)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_http200MalformedJson_keepsFolderIdAndReturnsError() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html>502 Bad Gateway</html>"))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.ERROR, result)
        assertEquals(DriveConnectionStatus.ERROR, manager.connectionStatus.value)
        assertEquals("Folder ID NÃO pode ser removido em resposta malformada", folderId, manager.getPersistedFolderId(email))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE 11 — HTTP 200 com JSON válido, mas NÃO é pasta (ex: application/pdf)
    // =========================================================================
    @Test
    fun testVerifyDriveConnection_validJsonNotAFolder_removesFolderIdAndReturnsAccountConnectedNoDrive() = runTest {
        val email = "user@example.com"
        val folderId = "file_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val responseBody = """
            {
              "id": "$folderId",
              "name": "arquivo.pdf",
              "trashed": false,
              "mimeType": "application/pdf"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, result)
        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, manager.connectionStatus.value)
        assertNull("Folder ID deve ser removido quando o mimeType não for de pasta", manager.getPersistedFolderId(email))

        assertRecordedRequest(folderId)
    }

    // =========================================================================
    // TESTE DE PERSISTÊNCIA POR CONTA
    // =========================================================================
    @Test
    fun testFolderPrefsKeyIsolationAndPersistence() = runTest {
        val emailA = "user1@gmail.com"
        val emailB = "user2@gmail.com"
        val keyA = GoogleDriveSyncManager.getFolderPrefsKey(emailA)
        val keyB = GoogleDriveSyncManager.getFolderPrefsKey(emailB)

        assertTrue(keyA.contains("user1"))
        assertTrue(keyB.contains("user2"))
        assertFalse("Chaves de contas diferentes devem ser distintas", keyA == keyB)

        // 1. Configurar conta A com folder_A
        val managerA = createManager(email = emailA)
        managerA.persistFolderId(emailA, "folder_A")

        // 2. Executar verificação válida para A
        val validFolderJsonA = """
            {
              "id": "folder_A",
              "name": "STELLA - Prontuário Veterinário",
              "trashed": false,
              "mimeType": "application/vnd.google-apps.folder"
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(validFolderJsonA))

        val resultA = managerA.verifyDriveConnection()
        assertEquals(DriveConnectionStatus.CONFIGURED, resultA)
        assertEquals("folder_A", managerA.getPersistedFolderId(emailA))

        // 3. Configurar conta B com folder_B
        val managerB = createManager(email = emailB)
        managerB.persistFolderId(emailB, "folder_B")

        // 4. Confirmar que a chave e folderId de B são independentes de A
        assertEquals("folder_A", managerA.getPersistedFolderId(emailA))
        assertEquals("folder_B", managerB.getPersistedFolderId(emailB))
    }

    // =========================================================================
    // CORREÇÃO 1B — TESTES DE AUTENTICAÇÃO, TOKEN 401 EXATO, RETRY E EXCEÇÕES
    // =========================================================================

    @Test
    fun testVerifyDriveConnection_401_invalidatesExactTokenAndRetriesSuccessfully() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val invalidatedTokens = mutableListOf<String>()
        var tokenCounter = 0
        val tokenList = listOf("TOKEN_A", "TOKEN_B")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = tokenList[tokenCounter++]
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        // Resposta 1: 401 Unauthorized para TOKEN_A
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        // Resposta 2: 200 OK para TOKEN_B
        val validFolderJson = """
            {
              "id": "$folderId",
              "name": "STELLA - Prontuário Veterinário",
              "trashed": false,
              "mimeType": "application/vnd.google-apps.folder"
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(validFolderJson))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.CONFIGURED, result)
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.connectionStatus.value)

        // Validação obrigatória da 1B: o token invalidado deve ser EXATAMENTE TOKEN_A
        assertEquals(listOf("TOKEN_A"), invalidatedTokens)

        // Verificar as duas requisições
        val req1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_A", req1?.getHeader("Authorization"))

        val req2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_B", req2?.getHeader("Authorization"))
    }

    @Test
    fun testVerifyDriveConnection_consecutive401_invalidatesBothTokensAndReturnsError() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val invalidatedTokens = mutableListOf<String>()
        var tokenCounter = 0
        val tokenList = listOf("TOKEN_A", "TOKEN_B", "TOKEN_C_UNUSED")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = tokenList[tokenCounter++]
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        // Resposta 1: 401 para TOKEN_A
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized 1"))
        // Resposta 2: 401 no retry para TOKEN_B
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized 2"))

        val result = manager.verifyDriveConnection()

        // Segundo 401 deve definir estado ERROR e não realizar terceiro retry
        assertEquals(DriveConnectionStatus.ERROR, result)
        assertEquals(DriveConnectionStatus.ERROR, manager.connectionStatus.value)
        assertEquals("TOKEN_A deve ser invalidado na primeira falha e TOKEN_B na segunda", listOf("TOKEN_A", "TOKEN_B"), invalidatedTokens)
        assertEquals("Não deve solicitar terceiro token", 2, tokenCounter)

        // Folder ID deve permanecer preservado
        assertEquals(folderId, manager.getPersistedFolderId(email))

        // Confirmar que exatamente duas requisições foram enviadas (sem terceiro retry)
        assertEquals(2, mockWebServer.requestCount)

        val req1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_A", req1?.getHeader("Authorization"))

        val req2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_B", req2?.getHeader("Authorization"))
    }

    @Test
    fun testVerifyDriveConnection_userRecoverableAuthException_preservesIntentAndReturnsPermissionMissing() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.ACTION_RECOVERABLE")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                throw UserRecoverableAuthException("Necessário consentimento do usuário", testIntent)
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, result)
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
        // O Intent recuperável NÃO pode ser descartado
        assertNotNull("Intent recuperável deve ser preservado", manager.recoverableAuthIntent.value)
        assertEquals(testIntent.action, manager.recoverableAuthIntent.value?.action)
        assertEquals(folderId, manager.getPersistedFolderId(email))
    }

    @Test
    fun testVerifyDriveConnection_ioException_preservesFolderIdAndReturnsUnverified() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                throw IOException("Timeout ao conectar com o serviço de autenticação")
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.UNVERIFIED, result)
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
    }

    @Test
    fun testVerifyDriveConnection_googleAuthException_returnsPermissionMissing() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                throw GoogleAuthException("Falha irrecuperável de autenticação Google")
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, result)
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
    }

    @Test
    fun testVerifyDriveConnection_scopeErrorInErrorsArray_returnsPermissionMissing() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val body = """
            {
              "error": {
                "code": 403,
                "message": "The request is missing a valid API key or scope.",
                "errors": [
                  {
                    "domain": "global",
                    "reason": "insufficientPermissions",
                    "message": "Insufficient Permission"
                  }
                ]
              }
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody(body))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, result)
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
    }

    @Test
    fun testVerifyDriveConnection_scopeErrorInDetailsArray_returnsPermissionMissing() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val body = """
            {
              "error": {
                "code": 403,
                "message": "Request had insufficient authentication scopes.",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
                    "domain": "googleapis.com"
                  }
                ]
              }
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody(body))

        val result = manager.verifyDriveConnection()

        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, result)
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
    }

    @Test
    fun testExecuteHttp_401_invalidatesExactTokenAndRetriesWithNewToken() = runTest {
        val invalidatedTokens = mutableListOf<String>()
        var tokenCounter = 0
        val tokenList = listOf("TOKEN_A", "TOKEN_B")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo("user@example.com", true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = tokenList[tokenCounter++]
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"success"}"""))

        val initialToken = gateway.getToken()
        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .addHeader("Authorization", "Bearer $initialToken")
            .get()
            .build()

        val responseBody = manager.executeHttp(request)

        assertEquals("""{"status":"success"}""", responseBody)
        // Token invalidado deve ser EXATAMENTE TOKEN_A
        assertEquals(listOf("TOKEN_A"), invalidatedTokens)

        val req1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_A", req1?.getHeader("Authorization"))

        val req2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_B", req2?.getHeader("Authorization"))
    }

    @Test
    fun testExecuteHttpBytes_401_invalidatesExactTokenAndRetriesWithNewToken() = runTest {
        val invalidatedTokens = mutableListOf<String>()
        var tokenCounter = 0
        val tokenList = listOf("TOKEN_A", "TOKEN_B")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo("user@example.com", true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = tokenList[tokenCounter++]
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )

        val testBytes = byteArrayOf(10, 20, 30, 40)
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(testBytes)))

        val initialTokenBytes = gateway.getToken()
        val request = Request.Builder()
            .url(mockWebServer.url("/test-bytes"))
            .addHeader("Authorization", "Bearer $initialTokenBytes")
            .get()
            .build()

        val responseBytes = manager.executeHttpBytes(request)

        assertTrue(testBytes.contentEquals(responseBytes))
        // Token invalidado deve ser EXATAMENTE TOKEN_A
        assertEquals(listOf("TOKEN_A"), invalidatedTokens)

        val req1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_A", req1?.getHeader("Authorization"))

        val req2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_B", req2?.getHeader("Authorization"))
    }

    // =========================================================================
    // CORREÇÃO 1B.1 — TESTES DE RETRY, SEGUNDO 401 E AUTORIZAÇÃO RECUPERÁVEL
    // =========================================================================

    @Test
    fun testExecuteHttp_consecutive401_invalidatesBothTokensAndThrowsError() = runTest {
        val invalidatedTokens = mutableListOf<String>()
        var tokenCounter = 0
        val tokenList = listOf("TOKEN_A", "TOKEN_B")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo("user@example.com", true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = tokenList[tokenCounter++]
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized 1"))
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized 2"))

        val initialToken = gateway.getToken()
        val request = Request.Builder()
            .url(mockWebServer.url("/test-error"))
            .addHeader("Authorization", "Bearer $initialToken")
            .get()
            .build()

        try {
            manager.executeHttp(request)
            fail("Deveria ter lançado DriveApiException no segundo 401")
        } catch (e: DriveApiException) {
            assertEquals(401, e.statusCode)
        }

        // Exatamente duas requisições enviadas (sem terceira requisição)
        assertEquals(2, mockWebServer.requestCount)

        // Ambos os tokens invalidados na ordem
        assertEquals(listOf("TOKEN_A", "TOKEN_B"), invalidatedTokens)

        val req1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_A", req1?.getHeader("Authorization"))

        val req2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_B", req2?.getHeader("Authorization"))
    }

    @Test
    fun testExecuteHttpBytes_consecutive401_invalidatesBothTokensAndThrowsError() = runTest {
        val invalidatedTokens = mutableListOf<String>()
        var tokenCounter = 0
        val tokenList = listOf("TOKEN_A", "TOKEN_B")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo("user@example.com", true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = tokenList[tokenCounter++]
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized 1"))
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized 2"))

        val initialToken = gateway.getToken()
        val request = Request.Builder()
            .url(mockWebServer.url("/test-bytes-error"))
            .addHeader("Authorization", "Bearer $initialToken")
            .get()
            .build()

        try {
            manager.executeHttpBytes(request)
            fail("Deveria ter lançado DriveApiException no segundo 401")
        } catch (e: DriveApiException) {
            assertEquals(401, e.statusCode)
        }

        // Exatamente duas requisições enviadas (sem terceira requisição)
        assertEquals(2, mockWebServer.requestCount)

        // Ambos os tokens invalidados na ordem
        assertEquals(listOf("TOKEN_A", "TOKEN_B"), invalidatedTokens)

        val req1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_A", req1?.getHeader("Authorization"))

        val req2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer TOKEN_B", req2?.getHeader("Authorization"))
    }

    @Test
    fun testExecuteHttp_userRecoverableAuthException_duringTokenRenewal_preservesIntentAndFolderId() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.RENEWAL_RECOVERABLE")
        val invalidatedTokens = mutableListOf<String>()
        var callCount = 0

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                callCount++
                if (callCount == 1) {
                    return "TOKEN_INITIAL"
                } else {
                    throw UserRecoverableAuthException("Autorização necessária na renovação", testIntent)
                }
            }
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val initialToken = gateway.getToken()
        val request = Request.Builder()
            .url(mockWebServer.url("/test-recoverable-json"))
            .addHeader("Authorization", "Bearer $initialToken")
            .get()
            .build()

        try {
            manager.executeHttp(request)
            fail("Deveria ter lançado UserRecoverableAuthException")
        } catch (e: UserRecoverableAuthException) {
            assertEquals(testIntent.action, e.intent?.action)
        }

        // Informação recuperável deve estar disponível no gerenciador
        assertNotNull("Intent recuperável deve ser preservado", manager.recoverableAuthIntent.value)
        assertEquals(testIntent.action, manager.recoverableAuthIntent.value?.action)

        // Estado deve refletir permissão pendente
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)

        // Token inicial foi invalidado
        assertEquals(listOf("TOKEN_INITIAL"), invalidatedTokens)

        // Sem retry HTTP adicional quando falhou em renovar token
        assertEquals(1, mockWebServer.requestCount)

        // folderId prévio NÃO foi apagado
        assertEquals(folderId, manager.getPersistedFolderId(email))
    }

    @Test
    fun testExecuteHttpBytes_userRecoverableAuthException_duringTokenRenewal_preservesIntentAndFolderId() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.RENEWAL_RECOVERABLE_BYTES")
        val invalidatedTokens = mutableListOf<String>()
        var callCount = 0

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                callCount++
                if (callCount == 1) {
                    return "TOKEN_INITIAL"
                } else {
                    throw UserRecoverableAuthException("Autorização necessária na renovação binária", testIntent)
                }
            }
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val initialToken = gateway.getToken()
        val request = Request.Builder()
            .url(mockWebServer.url("/test-recoverable-bytes"))
            .addHeader("Authorization", "Bearer $initialToken")
            .get()
            .build()

        try {
            manager.executeHttpBytes(request)
            fail("Deveria ter lançado UserRecoverableAuthException")
        } catch (e: UserRecoverableAuthException) {
            assertEquals(testIntent.action, e.intent?.action)
        }

        // Informação recuperável deve estar disponível no gerenciador
        assertNotNull("Intent recuperável deve ser preservado", manager.recoverableAuthIntent.value)
        assertEquals(testIntent.action, manager.recoverableAuthIntent.value?.action)

        // Estado deve refletir permissão pendente
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)

        // Token inicial foi invalidado
        assertEquals(listOf("TOKEN_INITIAL"), invalidatedTokens)

        // Sem retry HTTP adicional quando falhou em renovar token
        assertEquals(1, mockWebServer.requestCount)

        // folderId prévio NÃO foi apagado
        assertEquals(folderId, manager.getPersistedFolderId(email))
    }

    // =========================================================================
    // CORREÇÃO 1B.1 PÓS-AUDITORIA — getOrCreateDriveFolder E backupAttachment
    // =========================================================================

    @Test
    fun testGetOrCreateDriveFolder_userRecoverableAuthException_preservesFolderIdIntentAndPermissionMissing() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.FOLDER_CHECK_RECOVERABLE")
        val invalidatedTokens = mutableListOf<String>()
        var callCount = 0

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                callCount++
                if (callCount == 1) {
                    return "TOKEN_INITIAL"
                } else {
                    throw UserRecoverableAuthException("Autorização necessária ao validar pasta", testIntent)
                }
            }
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        // Check da pasta existente responde 401 -> renovação do token lança UserRecoverableAuthException
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val initialToken = gateway.getToken()
        try {
            manager.getOrCreateDriveFolder(initialToken, GoogleDriveSyncManager.DRIVE_FOLDER_NAME)
            fail("Deveria ter propagado UserRecoverableAuthException")
        } catch (e: UserRecoverableAuthException) {
            assertEquals(testIntent.action, e.intent?.action)
        }

        // 1 & 2. Preservar folderId e associação com a conta atual
        assertEquals("folderId persistido NÃO pode ser apagado em falha de autorização recuperável", folderId, manager.getPersistedFolderId(email))
        assertEquals(email, manager.connectedAccountEmail.value)

        // 3. Preservar Intent recuperável
        assertNotNull("Intent recuperável deve ser preservado", manager.recoverableAuthIntent.value)
        assertEquals(testIntent.action, manager.recoverableAuthIntent.value?.action)

        // 4. Atualizar estado para PERMISSION_MISSING
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)

        // 6. Não procurar ou criar outra pasta (exatamente 1 requisição feita ao servidor)
        assertEquals("Não deve buscar nem criar nova pasta após UserRecoverableAuthException", 1, mockWebServer.requestCount)
        assertEquals(listOf("TOKEN_INITIAL"), invalidatedTokens)
    }

    @Test
    fun testGetOrCreateDriveFolder_transientHttp500_preservesFolderIdAndDoesNotCreateAnotherFolder() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        try {
            manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME)
            fail("Deveria ter lançado DriveApiException em HTTP 500")
        } catch (e: DriveApiException) {
            assertEquals(500, e.statusCode)
        }

        // Falha transitória não apaga folderId nem procura/cria nova pasta
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun testGetOrCreateDriveFolder_http404_replacesDeletedFolderIdWithNewFolder() = runTest {
        val email = "user@example.com"
        val oldFolderId = "folder_deleted"
        val newFolderId = "folder_recreated"
        val manager = createManager(email = email)
        manager.persistFolderId(email, oldFolderId)

        // 1. Check da pasta antiga responde 404 (evidência real de inexistência)
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"File not found"}}"""))
        // 2. Busca por nome retorna lista vazia
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        // 3. Criação da nova pasta retorna newFolderId
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"$newFolderId","name":"STELLA"}"""))

        val resolvedId = manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME)

        assertEquals(newFolderId, resolvedId)
        assertEquals(newFolderId, manager.getPersistedFolderId(email))
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun testEnsureFolderConfigured_userRecoverableAuthException_preservesFolderIdAndPermissionMissing() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.ENSURE_FOLDER_RECOVERABLE")
        var callCount = 0

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                callCount++
                if (callCount == 1) {
                    return "TOKEN_INITIAL"
                } else {
                    throw UserRecoverableAuthException("Necessário reautorizar no ensureFolderConfigured", testIntent)
                }
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val configured = manager.ensureFolderConfigured()

        assertFalse(configured)
        // O catch do chamador NÃO pode sobrescrever PERMISSION_MISSING com ACCOUNT_CONNECTED_NO_DRIVE
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
        assertNotNull(manager.recoverableAuthIntent.value)
        assertEquals(testIntent.action, manager.recoverableAuthIntent.value?.action)
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun testBackupAttachment_userRecoverableAuthException_propagatesAndDoesNotSwallowIntoFailures() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.ATTACHMENT_RECOVERABLE")
        val invalidatedTokens = mutableListOf<String>()
        var callCount = 0

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                callCount++
                if (callCount == 1) {
                    return "TOKEN_INITIAL"
                } else {
                    throw UserRecoverableAuthException("Consentimento necessário no backup de anexo", testIntent)
                }
            }
            override fun invalidateToken(token: String) {
                invalidatedTokens.add(token)
            }
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        val tempFile = File(context.cacheDir, "exame_hemograma.pdf")
        tempFile.writeBytes("%PDF-1.4 test content".toByteArray(Charsets.UTF_8))

        try {
            // 401 durante a busca/upload do anexo -> renovação de token lança UserRecoverableAuthException
            mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

            val failures = mutableListOf<String>()
            val initialToken = gateway.getToken()

            try {
                manager.backupAttachment(
                    token = initialToken,
                    folderId = folderId,
                    uriStr = tempFile.absolutePath,
                    fileTypeHint = "PDF",
                    label = "Exame 'Hemograma'",
                    failures = failures
                )
                fail("backupAttachment deveria propagar UserRecoverableAuthException em vez de retornar null")
            } catch (e: UserRecoverableAuthException) {
                assertEquals(testIntent.action, e.intent?.action)
            }

            // Não deve transformar a exceção recuperável em falha comum de anexo
            assertTrue("Lista de falhas de anexo deve permanecer vazia para UserRecoverableAuthException", failures.isEmpty())
            assertNotNull("Intent recuperável deve ser preservado", manager.recoverableAuthIntent.value)
            assertEquals(testIntent.action, manager.recoverableAuthIntent.value?.action)
            assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
            assertEquals(folderId, manager.getPersistedFolderId(email))
            assertEquals(listOf("TOKEN_INITIAL"), invalidatedTokens)
            assertEquals(1, mockWebServer.requestCount)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testSyncToDrive_userRecoverableAuthExceptionInBackupAttachment_abortsImmediatelyAndPreservesState() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.SYNC_ATTACHMENT_RECOVERABLE")
        var callCount = 0

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                callCount++
                if (callCount == 1) {
                    return "TOKEN_INITIAL"
                } else {
                    throw UserRecoverableAuthException("Autorização necessária ao sincronizar anexo", testIntent)
                }
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)

        val tempDoc1 = File(context.cacheDir, "doc1.pdf").apply {
            writeBytes("%PDF-1.4 doc 1".toByteArray(Charsets.UTF_8))
        }
        val tempDoc2 = File(context.cacheDir, "doc2.pdf").apply {
            writeBytes("%PDF-1.4 doc 2".toByteArray(Charsets.UTF_8))
        }

        try {
            // Requisição 1: getOrCreateDriveFolder valida pasta existente com sucesso (200 OK)
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                )
            )
            // Requisição 2: Primeiro backupAttachment recebe 401 -> getToken() lança UserRecoverableAuthException
            mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

            val snapshot = BackupSnapshot(
                profile = StellaProfile(id = 1, name = "Stella", photoUri = null),
                glucoseList = emptyList(),
                medications = emptyList(),
                administrations = emptyList(),
                appointments = emptyList(),
                documents = listOf(
                    ExamDocument(id = 1L, name = "Exame 1", fileType = "PDF", dateString = "01/10/2026", category = "Exame de sangue", fileUriOrPath = tempDoc1.absolutePath),
                    ExamDocument(id = 2L, name = "Exame 2", fileType = "PDF", dateString = "02/10/2026", category = "Exame de sangue", fileUriOrPath = tempDoc2.absolutePath)
                ),
                feeding = emptyList(),
                weights = emptyList(),
                vaccines = emptyList(),
                reminders = emptyList()
            )
            val captured = CapturedBackup(snapshot = snapshot, changeVersion = 0L, restoreEpoch = 0L)

            val success = manager.syncToDrive(captured)

            assertFalse("Sincronização deve retornar false quando ocorrer UserRecoverableAuthException", success)
            assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
            assertNotNull("Intent recuperável deve estar preservado", manager.recoverableAuthIntent.value)
            assertEquals(testIntent.action, manager.recoverableAuthIntent.value?.action)
            assertEquals("folderId deve permanecer intacto", folderId, manager.getPersistedFolderId(email))
            // Deve ter interrompido imediatamente no primeiro anexo (1 req da pasta + 1 req do doc1 = 2 requisições; doc2 não foi tentado)
            assertEquals(2, mockWebServer.requestCount)
        } finally {
            tempDoc1.delete()
            tempDoc2.delete()
        }
    }

    @Test
    fun testBackupAttachment_commonFailure_recordsFailureWithoutSettingPermissionMissing() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        val tempFile = File(context.cacheDir, "exame_comum.pdf").apply {
            writeBytes("%PDF-1.4 test content".toByteArray(Charsets.UTF_8))
        }

        try {
            // Simula falha comum de servidor (HTTP 500) durante busca/envio do anexo
            mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            val failures = mutableListOf<String>()
            val result = manager.backupAttachment(
                token = "test-token",
                folderId = folderId,
                uriStr = tempFile.absolutePath,
                fileTypeHint = "PDF",
                label = "Exame 'Comum'",
                failures = failures
            )

            // Falha comum retorna null e registra em failures sem classificar como PERMISSION_MISSING
            assertNull(result)
            assertEquals(1, failures.size)
            assertTrue(failures[0].contains("Exame 'Comum'"))
            assertNull("Intent recuperável deve permanecer nulo em falha comum", manager.recoverableAuthIntent.value)
            assertFalse("Estado NÃO deve ser PERMISSION_MISSING em falha comum", manager.connectionStatus.value == DriveConnectionStatus.PERMISSION_MISSING)
            assertEquals(folderId, manager.getPersistedFolderId(email))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testPrepareForAccountSwitchAndSignOut_clearsAccountDataAndRecoverableIntent() = runTest {
        val email = "user@example.com"
        val folderId = "folder_123"
        val testIntent = Intent("com.google.android.gms.auth.RECOVERABLE_BEFORE_SWITCH")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                throw UserRecoverableAuthException("Consentimento necessário", testIntent)
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)

        // Provoca estado PERMISSION_MISSING com Intent recuperável registrado
        val status = manager.verifyDriveConnection()
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, status)
        assertNotNull(manager.recoverableAuthIntent.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))

        // Troca explícita de conta deve remover folderId, limpar conta e limpar Intent recuperável
        var readyCalled = false
        manager.prepareForAccountSwitch { readyCalled = true }

        assertTrue(readyCalled)
        assertNull("folderId da conta anterior deve ser removido na troca de conta", manager.getPersistedFolderId(email))
        assertNull("Email conectado deve ser limpo", manager.connectedAccountEmail.value)
        assertNull("Intent recuperável anterior não deve interferir na troca de conta", manager.recoverableAuthIntent.value)
        assertEquals(DriveConnectionStatus.NOT_CONNECTED, manager.connectionStatus.value)
    }

    // =========================================================================
    // CORREÇÃO 1C — REUTILIZAÇÃO, DESCOBERTA (createdTime ASC) E CONTA EXPLÍCITA
    // =========================================================================

    @Test
    fun testGetOrCreateDriveFolder_validPersistedFolderId_verifiesAndPreservesWithoutSearchingOrCreating() = runTest {
        val email = "user@example.com"
        val folderId = "folder_valid_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA - Prontuário Veterinário","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )

        val resolvedId = manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, email)

        assertEquals(folderId, resolvedId)
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertEquals("Deve realizar apenas 1 requisição de validação sem buscar ou criar outra pasta", 1, mockWebServer.requestCount)

        val checkReq = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(checkReq)
        assertTrue(checkReq!!.path?.contains("/files/$folderId") == true)
        assertTrue(checkReq.path?.contains("mimeType") == true)
        assertTrue(checkReq.path?.contains("trashed") == true)
    }

    @Test
    fun testGetOrCreateDriveFolder_trashedOrAppNotAuthorized_clearsInvalidIdAndSelectsValidFolder() = runTest {
        val email = "user@example.com"
        val trashedId = "folder_trashed"
        val discoveredId = "folder_discovered"
        val manager = createManager(email = email)
        manager.persistFolderId(email, trashedId)

        // Cenário A: trashed == true -> apaga trashedId e descobre pasta válida existente
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$trashedId","name":"STELLA","trashed":true,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"$discoveredId","name":"STELLA","createdTime":"2026-01-10T10:00:00.000Z"}]}"""
            )
        )

        val resolvedAfterTrash = manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, email)
        assertEquals(discoveredId, resolvedAfterTrash)
        assertEquals(discoveredId, manager.getPersistedFolderId(email))

        // Cenário B: HTTP 403 appNotAuthorizedToFile -> apaga discoveredId e cria nova pasta
        val createdId = "folder_created_after_403"
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"errors":[{"reason":"appNotAuthorizedToFile"}]}}"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"$createdId","name":"STELLA"}""")
        )

        val resolvedAfter403 = manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, email)
        assertEquals(createdId, resolvedAfter403)
        assertEquals(createdId, manager.getPersistedFolderId(email))
    }

    @Test
    fun testGetOrCreateDriveFolder_transientAndRateLimitErrors_doNotDeletePersistedFolderId() = runTest {
        val email = "user@example.com"
        val folderId = "folder_keep_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // 1. HTTP 429
        mockWebServer.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        try {
            manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, email)
            fail("Deveria lançar exceção em HTTP 429")
        } catch (_: DriveApiException) {}
        assertEquals(folderId, manager.getPersistedFolderId(email))

        // 2. HTTP 403 rateLimitExceeded
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"errors":[{"reason":"userRateLimitExceeded"}]}}"""
            )
        )
        try {
            manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, email)
            fail("Deveria lançar exceção em HTTP 403 rateLimitExceeded")
        } catch (_: DriveApiException) {}
        assertEquals(folderId, manager.getPersistedFolderId(email))

        // 3. Falha de rede / IOException transitória
        mockWebServer.shutdown()
        try {
            manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, email)
            fail("Deveria lançar exceção em falha de rede")
        } catch (_: Exception) {}
        assertEquals("folderId não deve ser apagado em falha de rede/IOException", folderId, manager.getPersistedFolderId(email))
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
    }

    @Test
    fun testGetOrCreateDriveFolder_multipleFoldersWithSameName_ordersByCreatedTimeAndSelectsOldest() = runTest {
        val email = "user@example.com"
        val manager = createManager(email = email)
        assertNull(manager.getPersistedFolderId(email))

        // Servidor retorna 3 pastas com o mesmo nome fora de ordem cronológica (a mais nova em files[0])
        val searchBody = """
            {
              "files": [
                {
                  "id": "folder_newest",
                  "name": "STELLA - Prontuário Veterinário",
                  "createdTime": "2026-08-15T14:00:00.000Z"
                },
                {
                  "id": "folder_oldest",
                  "name": "STELLA - Prontuário Veterinário",
                  "createdTime": "2025-02-10T08:30:00.000Z"
                },
                {
                  "id": "folder_middle",
                  "name": "STELLA - Prontuário Veterinário",
                  "createdTime": "2026-01-20T11:15:00.000Z"
                }
              ]
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(searchBody))

        val selectedId = manager.getOrCreateDriveFolder("test-token", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, email)

        // Deve escolher a pasta mais antiga (folder_oldest) e NÃO files[0] (folder_newest)
        assertEquals("Deve selecionar a pasta mais antiga por createdTime", "folder_oldest", selectedId)
        assertEquals("folder_oldest", manager.getPersistedFolderId(email))
        assertEquals("Não deve criar pasta adicional quando já encontrou pasta existente", 1, mockWebServer.requestCount)

        val searchReq = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(searchReq)
        assertTrue("Consulta deve ordenar por createdTime", searchReq!!.path?.contains("orderBy=createdTime") == true)
        assertTrue("Consulta deve solicitar createdTime em fields", searchReq.path?.contains("createdTime") == true)
    }

    @Test
    fun testGetOrCreateDriveFolder_explicitAccountIsolation_neverMixesFolderIdsBetweenAccounts() = runTest {
        val emailA = "conta_a@gmail.com"
        val emailB = "conta_b@gmail.com"

        // Inicializa manager conectado inicialmente na Conta A
        var activeEmail = emailA
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = activeEmail, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = "token-$activeEmail"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(emailA, "folder_conta_A")

        // Agora a operação é chamada explicitamente para a Conta B mesmo antes de _connectedAccountEmail ser atualizado
        activeEmail = emailB
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"folder_conta_B","name":"STELLA","createdTime":"2026-03-01T10:00:00.000Z"}]}"""
            )
        )

        val resolvedForB = manager.getOrCreateDriveFolder(
            token = "token-$emailB",
            folderName = GoogleDriveSyncManager.DRIVE_FOLDER_NAME,
            accountEmail = emailB
        )

        assertEquals("folder_conta_B", resolvedForB)
        assertEquals("folder_conta_A deve permanecer intacto para Conta A", "folder_conta_A", manager.getPersistedFolderId(emailA))
        assertEquals("folder_conta_B deve ser persistido para Conta B", "folder_conta_B", manager.getPersistedFolderId(emailB))
    }

    // =========================================================================
    // CORREÇÃO 1D — VIEWMODEL E SEPARAÇÃO DOS FLUXOS VERIFYING / NO_DRIVE
    // =========================================================================

    private suspend fun awaitConnectionStatus(
        viewModel: StellaViewModel,
        expected: DriveConnectionStatus,
        timeoutMs: Long = 3000L
    ) {
        val start = System.currentTimeMillis()
        while (viewModel.driveConnectionStatus.value != expected && System.currentTimeMillis() - start < timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) {
                delay(25)
            }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testStellaViewModel_initWithVerifyingStatus_executesRemoteVerifyAndDoesNotConfigureFolder() = runTest {
        val email = "vm_verify@example.com"
        val folderId = "folder_vm_verify_123"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        assertEquals(DriveConnectionStatus.VERIFYING, manager.connectionStatus.value)

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)

        assertEquals(DriveConnectionStatus.CONFIGURED, viewModel.driveConnectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertEquals("Deve executar apenas 1 requisição de verificação remota sem misturar com ensureFolderConfigured", 1, mockWebServer.requestCount)

        val req = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req)
        assertTrue("A requisição deve ser de verificação direta da pasta", req!!.path?.contains("/files/$folderId") == true)
    }

    @Test
    fun testStellaViewModel_initWithAccountConnectedNoDrive_executesEnsureFolderConfiguredFlow() = runTest {
        val email = "vm_nodrive@example.com"
        val discoveredFolderId = "folder_vm_discovered_456"
        val manager = createManager(email = email)
        manager.persistFolderId(email, null)
        manager.refreshStateFromDevice()

        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, manager.connectionStatus.value)

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"$discoveredFolderId","name":"STELLA","createdTime":"2026-01-01T10:00:00.000Z"}]}"""
            )
        )

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)

        assertEquals(DriveConnectionStatus.CONFIGURED, viewModel.driveConnectionStatus.value)
        assertEquals(discoveredFolderId, manager.getPersistedFolderId(email))
        assertEquals(1, mockWebServer.requestCount)

        val req = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req)
        assertTrue("A requisição deve ser de busca/configuração da pasta", req!!.path?.contains("orderBy=createdTime") == true)
    }

    @Test
    fun testStellaViewModel_verifyDriveConnection_fromUnverified_preservesFolderIdAndConfirmsConnection() = runTest {
        val email = "vm_unverified@example.com"
        val folderId = "folder_vm_unverified_789"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        // 1. Falha transitória inicial (HTTP 503) resulta em UNVERIFIED sem apagar folderId
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.UNVERIFIED)

        assertEquals(DriveConnectionStatus.UNVERIFIED, viewModel.driveConnectionStatus.value)
        assertEquals("folderId não pode ser apagado por causa de UNVERIFIED", folderId, manager.getPersistedFolderId(email))

        // 2. Solicitação explícita de verificação via ViewModel (viewModel.verifyDriveConnection())
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )

        viewModel.verifyDriveConnection()
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)

        assertEquals(DriveConnectionStatus.CONFIGURED, viewModel.driveConnectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun testEnsureFolderConfigured_transientFailureDuringFolderCheckOrSearch_preservesPreviouslyValidFolderId() = runTest {
        val email = "transient_cfg@example.com"
        val validFolderId = "folder_valid_persisted_999"
        val manager = createManager(email = email)

        // 1. Primeiro descobre/configura uma pasta válida com sucesso
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"$validFolderId","name":"STELLA - Prontuário Veterinário","createdTime":"2025-05-01T10:00:00.000Z"}]}"""
            )
        )
        assertTrue(manager.ensureFolderConfigured())
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.connectionStatus.value)
        assertEquals(validFolderId, manager.getPersistedFolderId(email))

        // 2. Em tentativa posterior de busca/configuração da pasta, ocorre falha transitória (HTTP 503)
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        assertFalse(manager.ensureFolderConfigured())

        // O folderId anteriormente válido NÃO pode ser apagado por causa da falha transitória
        assertEquals("folderId anteriormente válido deve ser preservado em falha transitória", validFolderId, manager.getPersistedFolderId(email))
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals("Não deve buscar nem criar nova pasta após falha transitória", 2, mockWebServer.requestCount)
    }

    // =========================================================================
    // ETAPA R2-A — GDRIVE-02, GDRIVE-04 E GDRIVE-07
    // =========================================================================

    private fun createEmptySnapshot(profileName: String = "Stella"): BackupSnapshot {
        return BackupSnapshot(
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
    }

    private suspend fun awaitProfileSavedInDb(expectedName: String, timeoutMs: Long = 3000L): Boolean {
        val dao = com.example.data.db.AppDatabase.getDatabase(context).stellaDao()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val savedName = withContext(Dispatchers.IO) { dao.getProfileSync()?.name }
            if (savedName == expectedName) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                return true
            }
            withContext(Dispatchers.Default) { delay(20) }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return false
    }

    @Test
    fun testR2A_01_localWriteDuringUpload_completesWithoutWaitingAndRemainsPending() = runTest {
        val email = "r2a_write@example.com"
        val folderId = "folder_r2a_01"
        val uploadEnteredLatch = java.util.concurrent.CountDownLatch(1)
        val allowUploadFinishLatch = java.util.concurrent.CountDownLatch(1)

        val slowClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath.contains("/files") && req.method == "POST") {
                    uploadEnteredLatch.countDown()
                    allowUploadFinishLatch.await(5, TimeUnit.SECONDS)
                }
                chain.proceed(req)
            }
            .build()

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = "token-r2a-01"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = slowClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)

        // 1. Verificação inicial no init do ViewModel -> CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )

        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)

        // Marca alteração inicial pendente e prepara respostas do syncToGoogleDrive()
        manager.markPendingChanges()
        assertTrue(viewModel.hasPendingChanges.value)

        // Respostas para o sync: 1) check folder, 2) search backup.json, 3) upload backup.json (POST bloqueado no interceptor),
        // 4) search report.txt, 5) upload report.txt
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"file_backup_id"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"file_report_id"}"""))

        // Inicia o upload
        viewModel.syncToGoogleDrive()

        // Aguarda o upload estar em andamento na rede (fora do dbMutex), drenando o MainLooper para que captureBackup conclua
        var enteredUpload = false
        val startEnter = System.currentTimeMillis()
        while (System.currentTimeMillis() - startEnter < 5000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (withContext(Dispatchers.IO) { uploadEnteredLatch.await(25, TimeUnit.MILLISECONDS) }) {
                enteredUpload = true
                break
            }
        }
        assertTrue("O upload deve ter iniciado na rede após liberar o dbMutex", enteredUpload)

        // Enquanto o upload está bloqueado na rede, realiza uma gravação local via ViewModel (que usa dbMutex)
        viewModel.updateProfile(StellaProfile(id = 1, name = "Stella Gravada Durante Upload"))

        val writeFinishedDuringUpload = awaitProfileSavedInDb("Stella Gravada Durante Upload")

        // A gravação local DEVE terminar enquanto o upload ainda está bloqueado!
        assertTrue("Gravação local deve concluir sem esperar o término do upload", writeFinishedDuringUpload)
        assertEquals("Upload ainda deve estar em andamento", 1L, allowUploadFinishLatch.count)

        // Libera o término do upload antigo
        allowUploadFinishLatch.countDown()

        val startSyncFinish = System.currentTimeMillis()
        while (viewModel.driveSyncState.value !is DriveSyncState.Success && System.currentTimeMillis() - startSyncFinish < 3000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("O upload do snapshot antigo deve concluir com Success", viewModel.driveSyncState.value is DriveSyncState.Success)
        assertTrue(
            "A alteração local feita durante o upload deve permanecer pendente (hasPendingChanges = true)",
            viewModel.hasPendingChanges.value
        )
    }

    @Test
    fun testR2A_02_automaticSync_executesOnlyWhenConfigured() = runTest {
        val email = "r2a_autosync@example.com"
        val folderId = "folder_r2a_02"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // Inicializa ViewModel em UNVERIFIED (simulando falha transitória inicial 503 na verificação)
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.UNVERIFIED)
        assertEquals(1, mockWebServer.requestCount)

        // 1. Em UNVERIFIED: gravação local NÃO deve disparar sync automático após o debounce
        viewModel.updateProfile(StellaProfile(id = 1, name = "Stella Unverified"))
        assertTrue(awaitProfileSavedInDb("Stella Unverified"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Sync automático NÃO deve executar em UNVERIFIED", 1, mockWebServer.requestCount)
        assertTrue("Pendência deve permanecer preservada em UNVERIFIED", viewModel.hasPendingChanges.value)

        // 2. Em ERROR: gravação local NÃO deve disparar sync automático após o debounce
        viewModel.setDriveSignInError("Erro simulado")
        assertEquals(DriveConnectionStatus.ERROR, viewModel.driveConnectionStatus.value)
        viewModel.updateProfile(StellaProfile(id = 1, name = "Stella Error"))
        assertTrue(awaitProfileSavedInDb("Stella Error"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Sync automático NÃO deve executar em ERROR", 1, mockWebServer.requestCount)
        assertTrue("Pendência deve permanecer preservada em ERROR", viewModel.hasPendingChanges.value)

        // 3. Em PERMISSION_MISSING: gravação local NÃO deve disparar sync automático após o debounce
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"message":"Insufficient Permission","errors":[{"reason":"insufficientPermissions"}]}}"""
            )
        )
        manager.verifyDriveConnection()
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, viewModel.driveConnectionStatus.value)
        val reqCountAtPermissionMissing = mockWebServer.requestCount
        viewModel.updateProfile(StellaProfile(id = 1, name = "Stella Permission Missing"))
        assertTrue(awaitProfileSavedInDb("Stella Permission Missing"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Sync automático NÃO deve executar em PERMISSION_MISSING", reqCountAtPermissionMissing, mockWebServer.requestCount)
        assertTrue("Pendência deve permanecer preservada em PERMISSION_MISSING", viewModel.hasPendingChanges.value)

        // 4. Em CONFIGURED: verificação confirma a pasta e gravação local DEVE disparar sync automático após o debounce
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        manager.verifyDriveConnection()
        assertEquals(DriveConnectionStatus.CONFIGURED, viewModel.driveConnectionStatus.value)
        val reqCountBeforeAutoSync = mockWebServer.requestCount

        // Enfileira respostas para o envio automático (check folder + search/upload backup + search/upload report)
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"auto_backup_id"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"auto_report_id"}"""))

        viewModel.updateProfile(StellaProfile(id = 1, name = "Stella Configured AutoSync"))
        assertTrue(awaitProfileSavedInDb("Stella Configured AutoSync"))

        // Avança o relógio do MainLooper em 11s para disparar o debounce de 10s
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)

        val startWaitSync = System.currentTimeMillis()
        while (viewModel.driveSyncState.value !is DriveSyncState.Success && System.currentTimeMillis() - startWaitSync < 3000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("Sync automático deve executar e concluir com Success em CONFIGURED", viewModel.driveSyncState.value is DriveSyncState.Success)
        assertTrue("Deve ter enviado as requisições do sync automático", mockWebServer.requestCount > reqCountBeforeAutoSync)
        assertFalse("Pendência deve ser limpa após sync automático bem-sucedido", viewModel.hasPendingChanges.value)
    }

    @Test
    fun testR2A_03_syncToDriveSuccess_updatesConnectionStatusToConfigured() = runTest {
        val email = "r2a_success@example.com"
        val folderId = "folder_r2a_03"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // Coloca inicialmente em UNVERIFIED
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.verifyDriveConnection())

        // Agora executa syncToDrive com sucesso completo
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"backup_ok"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"report_ok"}"""))

        val captured = CapturedBackup(snapshot = createEmptySnapshot(), changeVersion = 0L, restoreEpoch = 0L)
        val result = manager.syncToDrive(captured)

        assertTrue("syncToDrive deve retornar true", result)
        assertTrue("syncState deve ser Success", manager.syncState.value is DriveSyncState.Success)
        assertEquals("connectionStatus deve ser atualizado para CONFIGURED após sucesso", DriveConnectionStatus.CONFIGURED, manager.connectionStatus.value)
    }

    @Test
    fun testR2A_04_syncToDriveTransientFailures_setUnverifiedAndPreserveFolderIdAndPending() = runTest {
        val email = "r2a_transient@example.com"
        val folderId = "folder_r2a_04"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // Confirma pasta para partir de CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.verifyDriveConnection())
        manager.markPendingChanges()

        val captured = CapturedBackup(snapshot = createEmptySnapshot(), changeVersion = 1L, restoreEpoch = 0L)

        // Caso A: HTTP 429 durante o envio
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"Too Many Requests"}}"""))

        assertFalse(manager.syncToDrive(captured))
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertTrue(manager.hasPendingChanges.value)

        // Restaura CONFIGURED antes do próximo subteste
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.verifyDriveConnection())

        // Caso B: HTTP 503 (5xx) durante o envio
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":503,"message":"Backend Error"}}"""))

        assertFalse(manager.syncToDrive(captured))
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertTrue(manager.hasPendingChanges.value)

        // Restaura CONFIGURED antes do próximo subteste
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.verifyDriveConnection())

        // Caso C: Falha transitória de rede/timeout durante o envio
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertFalse(manager.syncToDrive(captured))
        assertEquals(DriveConnectionStatus.UNVERIFIED, manager.connectionStatus.value)
        assertEquals(folderId, manager.getPersistedFolderId(email))
        assertTrue(manager.hasPendingChanges.value)
    }

    @Test
    fun testR2A_05_syncToDrivePermissionFailure_setsPermissionMissingAndPreservesFolderId() = runTest {
        val email = "r2a_perm@example.com"
        val folderId = "folder_r2a_05"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)

        // Parte de CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        assertEquals(DriveConnectionStatus.CONFIGURED, manager.verifyDriveConnection())
        manager.markPendingChanges()

        // Durante o upload do backup, Drive retorna HTTP 403 insufficientPermissions
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"message":"Insufficient Permission","errors":[{"reason":"insufficientPermissions"}]}}"""
            )
        )

        val captured = CapturedBackup(snapshot = createEmptySnapshot(), changeVersion = 1L, restoreEpoch = 0L)
        val result = manager.syncToDrive(captured)

        assertFalse(result)
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, manager.connectionStatus.value)
        assertEquals("folderId deve ser preservado em erro de permissão", folderId, manager.getPersistedFolderId(email))
        assertTrue("Pendência deve ser preservada", manager.hasPendingChanges.value)
    }

    @Test
    fun testR2A_06_accountSwitchDuringUpload_oldUploadDoesNotContaminateNewAccountSession() = runTest {
        val emailA = "conta_a@example.com"
        val emailB = "conta_b@example.com"
        val folderA = "folder_conta_A"
        val folderB = "folder_conta_B"

        var currentGatewayAccount: DriveAccountInfo? = DriveAccountInfo(email = emailA, hasDriveScope = true)
        val uploadInFlightLatch = java.util.concurrent.CountDownLatch(1)
        val releaseOldUploadLatch = java.util.concurrent.CountDownLatch(1)

        val interceptingClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                // Pausa no último POST do upload da conta A (relatório médico)
                if (req.method == "POST" && req.header("Authorization") == "Bearer token-$emailA") {
                    uploadInFlightLatch.countDown()
                    releaseOldUploadLatch.await(5, TimeUnit.SECONDS)
                }
                chain.proceed(req)
            }
            .build()

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = currentGatewayAccount
            override fun hasRequiredScope() = currentGatewayAccount?.hasDriveScope == true
            override fun getToken(): String = "token-${currentGatewayAccount?.email ?: "none"}"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) {
                currentGatewayAccount = null
                onComplete()
            }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = interceptingClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(emailA, folderA)
        manager.setSyncApprovedForAccount(emailA, true)
        manager.setSyncApprovedForAccount(emailB, true)
        manager.markPendingChanges()

        // Enfileira respostas para o upload da Conta A
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderA","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"backup_conta_a"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"report_conta_a"}"""))

        val capturedA = CapturedBackup(snapshot = createEmptySnapshot("Stella Conta A"), changeVersion = 1L, restoreEpoch = 0L)

        // 1. Inicia upload na Conta A em background
        val uploadJobA = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            manager.syncToDrive(capturedA)
        }

        assertTrue("Upload da Conta A deve estar em andamento", withContext(Dispatchers.IO) {
            uploadInFlightLatch.await(5, TimeUnit.SECONDS)
        })

        // 2. Enquanto o upload da Conta A está em andamento, inicia troca para Conta B
        var switchReady = false
        manager.prepareForAccountSwitch {
            switchReady = true
        }
        assertTrue(switchReady)
        assertNull("folderId da Conta A deve ter sido limpo no prepareForAccountSwitch", manager.getPersistedFolderId(emailA))

        // Conecta Conta B (com folderB persistido para Conta B)
        currentGatewayAccount = DriveAccountInfo(email = emailB, hasDriveScope = true)
        manager.persistFolderId(emailB, folderB)
        manager.refreshStateFromDevice()
        assertEquals(emailB, manager.connectedAccountEmail.value)
        assertEquals(DriveConnectionStatus.VERIFYING, manager.connectionStatus.value)

        // 3. Libera o término do upload antigo da Conta A
        releaseOldUploadLatch.countDown()
        val resultA = uploadJobA.await()

        // 4. O upload antigo da Conta A deve retornar false e NÃO pode contaminar a sessão da Conta B
        assertFalse("Upload antigo da Conta A não deve reportar sucesso após troca de conta", resultA)
        assertEquals("Conta conectada deve continuar sendo a Conta B", emailB, manager.connectedAccountEmail.value)
        assertEquals("Estado da Conta B deve permanecer VERIFYING (não sobrescrito por CONFIGURED da Conta A)", DriveConnectionStatus.VERIFYING, manager.connectionStatus.value)
        assertFalse("syncState NÃO deve ser Success da Conta A", manager.syncState.value is DriveSyncState.Success)
        assertEquals("lastSyncTimestamp NÃO deve ter sido gravado pela Conta A", "Nunca sincronizado", manager.lastSyncTimestamp.value)
        assertNull("folderId da Conta A NÃO pode ter sido regravado", manager.getPersistedFolderId(emailA))
        assertEquals("folderId da Conta B deve permanecer intacto", folderB, manager.getPersistedFolderId(emailB))
        assertTrue("Alterações pendentes devem continuar pendentes para a nova conta", manager.hasPendingChanges.value)
    }

    @Test
    fun testR2A_07_signOutDuringUpload_oldUploadDoesNotWriteLateSuccessOrFolderId() = runTest {
        val email = "r2a_signout@example.com"
        val folderId = "folder_r2a_07"

        var currentGatewayAccount: DriveAccountInfo? = DriveAccountInfo(email = email, hasDriveScope = true)
        val uploadInFlightLatch = java.util.concurrent.CountDownLatch(1)
        val releaseOldUploadLatch = java.util.concurrent.CountDownLatch(1)

        val interceptingClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.method == "POST") {
                    uploadInFlightLatch.countDown()
                    releaseOldUploadLatch.await(5, TimeUnit.SECONDS)
                }
                chain.proceed(req)
            }
            .build()

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = currentGatewayAccount
            override fun hasRequiredScope() = currentGatewayAccount?.hasDriveScope == true
            override fun getToken(): String = "token-signout-test"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) {
                currentGatewayAccount = null
                onComplete()
            }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = interceptingClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        manager.markPendingChanges()

        // Enfileira respostas HTTP 200 completas para o upload
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"backup_signout"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"report_signout"}"""))

        val captured = CapturedBackup(snapshot = createEmptySnapshot("Stella SignOut"), changeVersion = 1L, restoreEpoch = 0L)

        // 1. Inicia upload
        val uploadJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            manager.syncToDrive(captured)
        }

        assertTrue("Upload deve ter entrado em execução", withContext(Dispatchers.IO) {
            uploadInFlightLatch.await(5, TimeUnit.SECONDS)
        })

        // 2. Executa signOut() enquanto o upload está em andamento
        var signOutCompleted = false
        manager.signOut {
            signOutCompleted = true
        }
        assertTrue(signOutCompleted)
        assertEquals(DriveConnectionStatus.NOT_CONNECTED, manager.connectionStatus.value)
        assertEquals(DriveSyncState.Idle, manager.syncState.value)
        assertNull(manager.connectedAccountEmail.value)
        assertNull(manager.getPersistedFolderId(email))

        // 3. Libera o upload antigo para terminar
        releaseOldUploadLatch.countDown()
        val syncResult = uploadJob.await()

        // 4. Verifica que o upload antigo NÃO gravou sucesso tardio, lastSync, connectionStatus ou folderId
        assertFalse("Upload antigo após signOut deve retornar false", syncResult)
        assertEquals("connectionStatus deve permanecer NOT_CONNECTED", DriveConnectionStatus.NOT_CONNECTED, manager.connectionStatus.value)
        assertEquals("syncState deve permanecer Idle (sem Success tardio)", DriveSyncState.Idle, manager.syncState.value)
        assertEquals("lastSyncTimestamp não deve ser atualizado", "Nunca sincronizado", manager.lastSyncTimestamp.value)
        assertNull("connectedAccountEmail deve permanecer null", manager.connectedAccountEmail.value)
        assertNull("folderId não deve ser regravado após signOut", manager.getPersistedFolderId(email))
        assertTrue("Pendência local deve permanecer preservada", manager.hasPendingChanges.value)
    }

    // =========================================================================
    // ETAPA R2-B PART 2 — GDRIVE-10 (VERIFICAÇÃO CONTROLADA NO FOREGROUND)
    // =========================================================================

    private suspend fun awaitRequestCount(expectedCount: Int, timeoutMs: Long = 3000L) {
        val start = System.currentTimeMillis()
        while (mockWebServer.requestCount < expectedCount && System.currentTimeMillis() - start < timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) {
                delay(25)
            }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private suspend fun awaitOperationMutexUnlocked(manager: GoogleDriveSyncManager, timeoutMs: Long = 3000L) {
        val start = System.currentTimeMillis()
        while (manager.operationMutex.isLocked && System.currentTimeMillis() - start < timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) {
                delay(25)
            }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testR2B_GDRIVE10_01_foregroundResumed_startsVerificationWhenEligible() = runTest {
        val email = "gdrive10_01@example.com"
        val folderId = "folder_gdrive10_01"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        // 1. Verificação inicial do init do ViewModel -> CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        assertEquals(1, mockWebServer.requestCount)
        mockWebServer.takeRequest(5, TimeUnit.SECONDS)

        // 2. App retorna ao foreground -> deve disparar verifyDriveConnection()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitRequestCount(2)
        awaitOperationMutexUnlocked(manager)

        assertEquals(2, mockWebServer.requestCount)
        assertEquals(DriveConnectionStatus.CONFIGURED, viewModel.driveConnectionStatus.value)
        assertRecordedRequest(folderId)
    }

    @Test
    fun testR2B_GDRIVE10_02_secondCallWithin5Minutes_executesOnlyOneVerification() = runTest {
        val email = "gdrive10_02@example.com"
        val folderId = "folder_gdrive10_02"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        var fakeNowMs = 1_000_000L

        // 1. Verificação inicial do init
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val viewModel = StellaViewModel(
            application = context as Application,
            customDriveSyncManager = manager,
            currentTimeMillisProvider = { fakeNowMs }
        )
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        assertEquals(1, mockWebServer.requestCount)

        // 2. Primeira chamada onDriveAppResumed() -> executa verificação
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitRequestCount(2)
        awaitOperationMutexUnlocked(manager)
        assertEquals(2, mockWebServer.requestCount)

        // 3. Segunda chamada imediata e outra após 4min59s (< 5 minutos) -> NÃO devem verificar
        viewModel.onDriveAppResumed()
        fakeNowMs += (5 * 60 * 1000L) - 1_000L
        viewModel.onDriveAppResumed()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("Dentro de 5 minutos, apenas 1 verificação automática deve ocorrer", 2, mockWebServer.requestCount)

        // 4. Verificação manual deve continuar funcionando imediatamente mesmo dentro da janela de 5 minutos
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.verifyDriveConnection()
        awaitRequestCount(3)
        assertEquals("Verificação manual não deve ser bloqueada pelo intervalo de 5 minutos", 3, mockWebServer.requestCount)
    }

    @Test
    fun testR2B_GDRIVE10_03_after5Minutes_allowsNewForegroundVerification() = runTest {
        val email = "gdrive10_03@example.com"
        val folderId = "folder_gdrive10_03"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        var fakeNowMs = 10_000_000L

        // 1. Verificação inicial do init
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val viewModel = StellaViewModel(
            application = context as Application,
            customDriveSyncManager = manager,
            currentTimeMillisProvider = { fakeNowMs }
        )
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        assertEquals(1, mockWebServer.requestCount)

        // 2. Primeira verificação de foreground em t = 10_000_000
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitRequestCount(2)
        awaitOperationMutexUnlocked(manager)
        assertEquals(2, mockWebServer.requestCount)

        // 3. Avança exatamente 5 minutos -> nova verificação de foreground permitida
        fakeNowMs += 5 * 60 * 1000L
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitRequestCount(3)
        awaitOperationMutexUnlocked(manager)
        assertEquals("Após 5 minutos, nova verificação de foreground deve ser executada", 3, mockWebServer.requestCount)
    }

    @Test
    fun testR2B_GDRIVE10_04_whileSyncing_doesNotStartForegroundVerification() = runTest {
        val email = "gdrive10_04@example.com"
        val folderId = "folder_gdrive10_04"
        val uploadEnteredLatch = java.util.concurrent.CountDownLatch(1)
        val allowUploadFinishLatch = java.util.concurrent.CountDownLatch(1)

        val interceptingClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.method == "POST") {
                    uploadEnteredLatch.countDown()
                    allowUploadFinishLatch.await(5, TimeUnit.SECONDS)
                }
                chain.proceed(req)
            }
            .build()

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = "token-gdrive10-04"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = interceptingClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        manager.refreshStateFromDevice()

        // 1. Verificação inicial do init -> CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        assertEquals(1, mockWebServer.requestCount)

        // 2. Inicia Sync e aguarda ficar ativo (Syncing + operationMutex bloqueado)
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"backup_id"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"report_id"}"""))

        val captured = CapturedBackup(snapshot = createEmptySnapshot("Stella Syncing"), changeVersion = 1L, restoreEpoch = 0L)
        val syncDeferred = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            manager.syncToDrive(captured)
        }

        assertTrue("Sync deve estar em andamento", withContext(Dispatchers.IO) {
            uploadEnteredLatch.await(5, TimeUnit.SECONDS)
        })
        assertTrue(viewModel.driveSyncState.value is DriveSyncState.Syncing)

        // 3. Chama onDriveAppResumed() durante o Sync -> deve ser ignorado sem esperar o Sync terminar
        viewModel.onDriveAppResumed()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        allowUploadFinishLatch.countDown()
        assertTrue(syncDeferred.await())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // 1 request do init + 5 requests do sync = 6 (nenhuma requisição extra de verifyDriveConnection foi iniciada)
        assertEquals("Não deve iniciar verifyDriveConnection durante Sync nem enfileirar após Sync", 6, mockWebServer.requestCount)
    }

    @Test
    fun testR2B_GDRIVE10_05_whileRestoring_doesNotStartForegroundVerification() = runTest {
        val email = "gdrive10_05@example.com"
        val folderId = "folder_gdrive10_05"
        val restoreEnteredLatch = java.util.concurrent.CountDownLatch(1)
        val allowRestoreFinishLatch = java.util.concurrent.CountDownLatch(1)

        val interceptingClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.query?.contains("alt=media") == true) {
                    restoreEnteredLatch.countDown()
                    allowRestoreFinishLatch.await(5, TimeUnit.SECONDS)
                }
                chain.proceed(req)
            }
            .build()

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String = "token-gdrive10-05"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = interceptingClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        manager.refreshStateFromDevice()

        // 1. Verificação inicial do init -> CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        assertEquals(1, mockWebServer.requestCount)

        // 2. Inicia Restore e aguarda ficar ativo em download (Restoring + operationMutex bloqueado)
        val validBackupJson = """
            {
              "backupVersion": 1,
              "exportedAtTimestamp": 1700000000000,
              "profile": {"id": 1, "name": "Stella Restaurada"}
            }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"files":[{"id":"backup_file_id","name":"stella_backup_dados.json"}]}""")
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(validBackupJson)
        )

        val repo = com.example.data.repository.StellaRepository(
            com.example.data.db.AppDatabase.getDatabase(context).stellaDao()
        )
        val restoreDeferred = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            manager.restoreBackupFromDrive(repo, replaceExisting = true)
        }

        assertTrue("Restore deve estar em andamento", withContext(Dispatchers.IO) {
            restoreEnteredLatch.await(5, TimeUnit.SECONDS)
        })
        assertTrue(viewModel.driveSyncState.value is DriveSyncState.Restoring)

        // 3. Chama onDriveAppResumed() durante o Restore -> deve ser ignorado
        viewModel.onDriveAppResumed()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        allowRestoreFinishLatch.countDown()
        assertTrue(restoreDeferred.await())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // 1 request do init + 3 requests do restore = 4 (nenhuma requisição extra de verifyDriveConnection foi iniciada)
        assertEquals("Não deve iniciar verifyDriveConnection durante Restore nem após seu término", 4, mockWebServer.requestCount)
    }

    @Test
    fun testR2B_GDRIVE10_06_ineligibleStates_doNotStartForegroundVerification() = runTest {
        val email = "gdrive10_06@example.com"
        val folderId = "folder_gdrive10_06"

        // Caso A: NOT_CONNECTED (sem conta conectada)
        val disconnectedGateway = object : DriveAuthGateway {
            override fun getConnectedAccount(): DriveAccountInfo? = null
            override fun hasRequiredScope() = false
            override fun getToken(): String = "unused"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }
        val disconnectedManager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = disconnectedGateway
        )
        val vmNotConnected = StellaViewModel(context as Application, customDriveSyncManager = disconnectedManager)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(DriveConnectionStatus.NOT_CONNECTED, vmNotConnected.driveConnectionStatus.value)
        vmNotConnected.onDriveAppResumed()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("NOT_CONNECTED não deve iniciar verificação", 0, mockWebServer.requestCount)

        // Caso B: ACCOUNT_CONNECTED_NO_DRIVE (após pasta 404 na verificação inicial)
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}"""))
        val vmNoDrive = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(vmNoDrive, DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE)
        assertEquals(1, mockWebServer.requestCount)

        // Em ACCOUNT_CONNECTED_NO_DRIVE, onDriveAppResumed() NÃO deve verificar nem tentar criar/configurar pasta
        vmNoDrive.onDriveAppResumed()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("ACCOUNT_CONNECTED_NO_DRIVE não deve verificar nem criar pasta no foreground", 1, mockWebServer.requestCount)
        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, vmNoDrive.driveConnectionStatus.value)

        // Caso C: VERIFYING e CONNECTING (enquanto uma verificação/configuração já está em curso)
        val slowVerifyLatch = java.util.concurrent.CountDownLatch(1)
        val releaseVerifyLatch = java.util.concurrent.CountDownLatch(1)
        val slowVerifyClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                slowVerifyLatch.countDown()
                releaseVerifyLatch.await(5, TimeUnit.SECONDS)
                chain.proceed(chain.request())
            }
            .build()
        val managerVerifying = GoogleDriveSyncManager(
            context = context,
            httpClient = slowVerifyClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = object : DriveAuthGateway {
                override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
                override fun hasRequiredScope() = true
                override fun getToken(): String = "token"
                override fun invalidateToken(token: String) {}
                override fun signOut(onComplete: () -> Unit) { onComplete() }
            }
        )
        managerVerifying.persistFolderId(email, folderId)
        managerVerifying.refreshStateFromDevice()
        assertEquals(DriveConnectionStatus.VERIFYING, managerVerifying.connectionStatus.value)

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val vmVerifying = StellaViewModel(context as Application, customDriveSyncManager = managerVerifying)
        // Enquanto está em VERIFYING:
        vmVerifying.onDriveAppResumed()
        releaseVerifyLatch.countDown()
        awaitConnectionStatus(vmVerifying, DriveConnectionStatus.CONFIGURED)
        assertEquals("VERIFYING não deve disparar segunda verificação", 2, mockWebServer.requestCount)

        // Caso D: CONNECTING (enquanto configureDriveFolder está em andamento)
        val connectingEnteredLatch = java.util.concurrent.CountDownLatch(1)
        val releaseConnectingLatch = java.util.concurrent.CountDownLatch(1)
        val slowConnectClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                connectingEnteredLatch.countDown()
                releaseConnectingLatch.await(5, TimeUnit.SECONDS)
                chain.proceed(chain.request())
            }
            .build()
        val managerConnecting = GoogleDriveSyncManager(
            context = context,
            httpClient = slowConnectClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = object : DriveAuthGateway {
                override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
                override fun hasRequiredScope() = true
                override fun getToken(): String = "token"
                override fun invalidateToken(token: String) {}
                override fun signOut(onComplete: () -> Unit) { onComplete() }
            }
        )
        managerConnecting.persistFolderId(email, folderId)
        managerConnecting.refreshStateFromDevice()

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val vmConnecting = StellaViewModel(context as Application, customDriveSyncManager = managerConnecting)
        // Aguarda entrar no interceptor da verificação inicial e libera
        withContext(Dispatchers.IO) { connectingEnteredLatch.await(5, TimeUnit.SECONDS) }
        releaseConnectingLatch.countDown()
        awaitConnectionStatus(vmConnecting, DriveConnectionStatus.CONFIGURED)
        assertEquals(3, mockWebServer.requestCount)

        // Agora inicia configureDriveFolder() e segura em CONNECTING
        val connectStepLatch = java.util.concurrent.CountDownLatch(1)
        val releaseConnectStepLatch = java.util.concurrent.CountDownLatch(1)
        val managerConnecting2 = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
                .addInterceptor { chain ->
                    connectStepLatch.countDown()
                    releaseConnectStepLatch.await(5, TimeUnit.SECONDS)
                    chain.proceed(chain.request())
                }
                .build(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = object : DriveAuthGateway {
                override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
                override fun hasRequiredScope() = true
                override fun getToken(): String = "token"
                override fun invalidateToken(token: String) {}
                override fun signOut(onComplete: () -> Unit) { onComplete() }
            }
        )
        managerConnecting2.persistFolderId(email, null)
        managerConnecting2.refreshStateFromDevice()
        assertEquals(DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE, managerConnecting2.connectionStatus.value)

        // init com ACCOUNT_CONNECTED_NO_DRIVE chama ensureFolderConfigured -> entra em CONNECTING
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[{"id":"$folderId","name":"STELLA"}]}"""))
        val vmInConnecting = StellaViewModel(context as Application, customDriveSyncManager = managerConnecting2)
        var enteredConnecting = false
        val startWait = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWait < 3000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (withContext(Dispatchers.IO) { connectStepLatch.await(20, TimeUnit.MILLISECONDS) }) {
                enteredConnecting = true
                break
            }
        }
        assertTrue(enteredConnecting)
        assertEquals(DriveConnectionStatus.CONNECTING, vmInConnecting.driveConnectionStatus.value)

        val countDuringConnecting = mockWebServer.requestCount
        vmInConnecting.onDriveAppResumed()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("CONNECTING não deve iniciar verifyDriveConnection", countDuringConnecting, mockWebServer.requestCount)

        releaseConnectStepLatch.countDown()
        awaitConnectionStatus(vmInConnecting, DriveConnectionStatus.CONFIGURED)
    }

    @Test
    fun testR2B_GDRIVE10_07_eligibleStates_startForegroundVerificationRespectingInterval() = runTest {
        val email = "gdrive10_07@example.com"
        val folderId = "folder_gdrive10_07"
        var hasScope = true
        var fakeNowMs = 1_000_000L

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = hasScope)
            override fun hasRequiredScope() = hasScope
            override fun getToken(): String = "token-gdrive10-07"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        // 1. Inicia em UNVERIFIED (503 na verificação inicial do init)
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Unavailable"))
        val viewModel = StellaViewModel(
            application = context as Application,
            customDriveSyncManager = manager,
            currentTimeMillisProvider = { fakeNowMs }
        )
        awaitConnectionStatus(viewModel, DriveConnectionStatus.UNVERIFIED)
        assertEquals(1, mockWebServer.requestCount)

        // Estado elegível 1: UNVERIFIED -> verifica e vai para CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        awaitOperationMutexUnlocked(manager)
        assertEquals(2, mockWebServer.requestCount)

        // Estado elegível 2: CONFIGURED (após 5 min) -> verifica
        fakeNowMs += 5 * 60 * 1000L
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitRequestCount(3)
        awaitOperationMutexUnlocked(manager)
        assertEquals(3, mockWebServer.requestCount)

        // Estado elegível 3: PERMISSION_MISSING (após 5 min, quando usuário concedeu escopo de volta) -> verifica e recupera CONFIGURED
        hasScope = false
        manager.refreshStateFromDevice()
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, viewModel.driveConnectionStatus.value)
        hasScope = true
        fakeNowMs += 5 * 60 * 1000L
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        awaitOperationMutexUnlocked(manager)
        assertEquals(4, mockWebServer.requestCount)

        // Estado elegível 4: ERROR (após 5 min, sem retry agressivo dentro de 5 min)
        viewModel.setDriveSignInError("Erro anterior")
        assertEquals(DriveConnectionStatus.ERROR, viewModel.driveConnectionStatus.value)

        // Dentro de 5 minutos em ERROR -> não faz retry agressivo
        fakeNowMs += 60 * 1000L
        viewModel.onDriveAppResumed()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("ERROR deve respeitar o intervalo mínimo de 5 minutos", 4, mockWebServer.requestCount)

        // Após completar os 5 minutos em ERROR -> verifica e recupera CONFIGURED
        fakeNowMs += 4 * 60 * 1000L
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        viewModel.onDriveAppResumed()
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        awaitOperationMutexUnlocked(manager)
        assertEquals(5, mockWebServer.requestCount)
    }

    @Test
    fun testR2B_GDRIVE10_08_verifyConfirmsConfiguredWithPending_usesExistingSyncToGoogleDrive() = runTest {
        val email = "gdrive10_08@example.com"
        val folderId = "folder_gdrive10_08"
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        // 1. Inicia em UNVERIFIED (falha transitória inicial)
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.UNVERIFIED)
        assertEquals(1, mockWebServer.requestCount)

        // 2. Registra alteração pendente local
        manager.markPendingChanges()
        assertTrue(viewModel.hasPendingChanges.value)

        // 3. Enfileira resposta de verifyDriveConnection() (CONFIGURED) + 5 respostas do syncToGoogleDrive()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"backup_08"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"report_08"}"""))

        // 4. App retorna ao foreground -> verifica (CONFIGURED) e aciona syncToGoogleDrive()
        viewModel.onDriveAppResumed()

        val start = System.currentTimeMillis()
        while ((viewModel.driveSyncState.value !is DriveSyncState.Success || viewModel.hasPendingChanges.value) &&
            System.currentTimeMillis() - start < 4000L
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(DriveConnectionStatus.CONFIGURED, viewModel.driveConnectionStatus.value)
        assertTrue("Sincronização pendente deve concluir com Success via syncToGoogleDrive()", viewModel.driveSyncState.value is DriveSyncState.Success)
        assertFalse("hasPendingChanges deve ser limpo após o sync", viewModel.hasPendingChanges.value)
        assertEquals(7, mockWebServer.requestCount)
    }

    @Test
    fun testR2B_GDRIVE10_09_transientFailureDuringForegroundVerify_preservesFolderIdAndUnverified() = runTest {
        val email = "gdrive10_09@example.com"
        val folderId = "folder_gdrive10_09"
        var fakeNowMs = 1_000_000L
        val manager = createManager(email = email)
        manager.persistFolderId(email, folderId)
        manager.refreshStateFromDevice()

        // 1. Verificação inicial do init -> CONFIGURED
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        val viewModel = StellaViewModel(
            application = context as Application,
            customDriveSyncManager = manager,
            currentTimeMillisProvider = { fakeNowMs }
        )
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        assertEquals(1, mockWebServer.requestCount)

        manager.markPendingChanges()

        // 2. No foreground, ocorre erro transitório 429 (Rate Limit)
        mockWebServer.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"Rate Limit Exceeded"}}"""))
        viewModel.onDriveAppResumed()
        awaitConnectionStatus(viewModel, DriveConnectionStatus.UNVERIFIED)
        awaitOperationMutexUnlocked(manager)

        assertEquals(DriveConnectionStatus.UNVERIFIED, viewModel.driveConnectionStatus.value)
        assertEquals("folderId deve ser preservado em falha transitória 429", folderId, manager.getPersistedFolderId(email))
        assertEquals("Não deve buscar/criar pasta nem iniciar sync em falha transitória", 2, mockWebServer.requestCount)
        assertTrue("Pendência deve permanecer preservada", viewModel.hasPendingChanges.value)

        // 3. Após 5 minutos, ocorre falha transitória 503 -> preserva folderId e UNVERIFIED
        fakeNowMs += 5 * 60 * 1000L
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        viewModel.onDriveAppResumed()
        awaitRequestCount(3)
        awaitOperationMutexUnlocked(manager)

        assertEquals(DriveConnectionStatus.UNVERIFIED, viewModel.driveConnectionStatus.value)
        assertEquals("folderId deve ser preservado em falha transitória 503", folderId, manager.getPersistedFolderId(email))
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun testR2B_GDRIVE10_10_r2aRegression_foregroundSyncKeepsUploadOutsideDbMutexAndRespectsSession() = runTest {
        val email = "gdrive10_10@example.com"
        val folderId = "folder_gdrive10_10"
        val uploadEnteredLatch = java.util.concurrent.CountDownLatch(1)
        val allowUploadFinishLatch = java.util.concurrent.CountDownLatch(1)

        val slowUploadClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.method == "POST") {
                    uploadEnteredLatch.countDown()
                    allowUploadFinishLatch.await(5, TimeUnit.SECONDS)
                }
                chain.proceed(req)
            }
            .build()

        var currentGatewayAccount: DriveAccountInfo? = DriveAccountInfo(email = email, hasDriveScope = true)
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = currentGatewayAccount
            override fun hasRequiredScope() = currentGatewayAccount?.hasDriveScope == true
            override fun getToken(): String = "token-gdrive10-10"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) {
                currentGatewayAccount = null
                onComplete()
            }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = slowUploadClient,
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        manager.refreshStateFromDevice()

        // 1. Inicia em UNVERIFIED com alteração pendente
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        val viewModel = StellaViewModel(context as Application, customDriveSyncManager = manager)
        awaitConnectionStatus(viewModel, DriveConnectionStatus.UNVERIFIED)
        manager.markPendingChanges()

        // 2. Respostas para o verifyDriveConnection() do foreground + syncToGoogleDrive()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"backup_10"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"report_10"}"""))

        viewModel.onDriveAppResumed()

        // Aguarda o upload disparado pelo foreground entrar na rede
        var enteredUpload = false
        val startEnter = System.currentTimeMillis()
        while (System.currentTimeMillis() - startEnter < 5000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (withContext(Dispatchers.IO) { uploadEnteredLatch.await(25, TimeUnit.MILLISECONDS) }) {
                enteredUpload = true
                break
            }
        }
        assertTrue("Upload disparado após verificação de foreground deve iniciar", enteredUpload)

        // Gravação local durante o upload deve concluir sem bloqueio (dbMutex livre durante upload)
        viewModel.updateProfile(StellaProfile(id = 1, name = "Stella Durante Foreground Sync"))
        assertTrue(
            "Gravação local durante sync disparado pelo foreground não pode ficar bloqueada no dbMutex",
            awaitProfileSavedInDb("Stella Durante Foreground Sync")
        )

        // Desconexão durante esse upload não pode permitir contaminação tardia
        viewModel.disconnectGoogleDrive()
        assertEquals(DriveConnectionStatus.NOT_CONNECTED, viewModel.driveConnectionStatus.value)

        allowUploadFinishLatch.countDown()
        val startFinish = System.currentTimeMillis()
        while (manager.operationMutex.isLocked && System.currentTimeMillis() - startFinish < 3000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("Estado após signOut durante upload deve permanecer NOT_CONNECTED", DriveConnectionStatus.NOT_CONNECTED, viewModel.driveConnectionStatus.value)
        assertFalse("Upload antigo não deve gravar Success após signOut", viewModel.driveSyncState.value is DriveSyncState.Success)
        assertTrue("Alteração local deve permanecer pendente", viewModel.hasPendingChanges.value)
    }

    @Test
    fun testR2B_integration_gdrive03AndGdrive10_recoverableAuthUiLifecycleAndForegroundVerify() = runTest {
        val email = "r2b_integration@example.com"
        val folderId = "folder_r2b_integration"
        var fakeNowMs = 1_000_000L
        var nextRecoverableIntent: Intent? = Intent("com.google.android.gms.auth.ACTION_RECOVER_1")

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = DriveAccountInfo(email = email, hasDriveScope = true)
            override fun hasRequiredScope() = true
            override fun getToken(): String {
                nextRecoverableIntent?.let { intent ->
                    throw UserRecoverableAuthException("Consentimento adicional exigido", intent)
                }
                return "token-r2b-integration"
            }
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }

        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            authGateway = gateway
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        manager.refreshStateFromDevice()

        val viewModel = StellaViewModel(
            application = context as Application,
            customDriveSyncManager = manager,
            currentTimeMillisProvider = { fakeNowMs }
        )
        awaitConnectionStatus(viewModel, DriveConnectionStatus.PERMISSION_MISSING)
        assertNotNull("1. UserRecoverableAuthException deve gerar recoverableAuthIntent", manager.recoverableAuthIntent.value)

        val launchedIntents = mutableListOf<Intent>()
        var pendingResultCallback: ((Int) -> Unit)? = null

        val testRegistry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                input: I,
                options: androidx.core.app.ActivityOptionsCompat?
            ) {
                if (input is Intent) {
                    launchedIntents.add(input)
                    pendingResultCallback = { resultCode ->
                        dispatchResult(requestCode, androidx.activity.result.ActivityResult(resultCode, null))
                    }
                }
            }
        }
        val registryOwner = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry: androidx.activity.result.ActivityResultRegistry = testRegistry
        }

        val controller = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        val activity = controller.get()
        val recompositionTrigger = androidx.compose.runtime.mutableIntStateOf(0)

        activity.runOnUiThread {
            activity.setContentView(
                androidx.compose.ui.platform.ComposeView(activity).apply {
                    setContent {
                        com.example.ui.theme.MyApplicationTheme {
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner
                            ) {
                                // Lê estado para permitir forçar recomposição da árvore onde StellaApp está
                                val tick = recompositionTrigger.intValue
                                if (tick >= 0) {
                                    StellaApp(viewModel = viewModel)
                                }
                            }
                        }
                    }
                }
            )
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // 2 & 3. Intent entregue à UI, lançado uma única vez e limpo imediatamente
        assertEquals("Intent recuperável deve ser lançado exatamente 1 vez pela UI", 1, launchedIntents.size)
        assertEquals("com.google.android.gms.auth.ACTION_RECOVER_1", launchedIntents[0].action)
        assertNull("Intent recuperável deve ser limpo após o lançamento", manager.recoverableAuthIntent.value)

        // Força recomposição da UI e verifica que o mesmo Intent NÃO é relançado
        recompositionTrigger.intValue++
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Recomposição não deve relançar o mesmo Intent", 1, launchedIntents.size)

        // 6 & 7. Cancelamento (RESULT_CANCELED) limpa o Intent, preserva conta e folderId, e não dispara loop no ON_RESUME
        controller.pause()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        pendingResultCallback?.invoke( android.app.Activity.RESULT_CANCELED )
        controller.resume()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { delay(50) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull("Cancelamento deve manter recoverableAuthIntent limpo", manager.recoverableAuthIntent.value)
        assertEquals("Cancelamento deve preservar conta conectada", email, viewModel.driveAccountEmail.value)
        assertEquals("Cancelamento deve preservar folderId", folderId, manager.getPersistedFolderId(email))
        assertEquals("Cancelamento não deve assumir CONFIGURED", DriveConnectionStatus.PERMISSION_MISSING, viewModel.driveConnectionStatus.value)
        assertEquals("Cancelamento seguido de ON_RESUME do diálogo não deve relançar Intent em loop", 1, launchedIntents.size)
        assertEquals(0, mockWebServer.requestCount)

        // 4 & 5. Novo evento de recuperação -> RESULT_OK limpa Intent e provoca verifyDriveConnection() sem assumir CONFIGURED sem verificar
        nextRecoverableIntent = Intent("com.google.android.gms.auth.ACTION_RECOVER_2")
        viewModel.verifyDriveConnection()
        val startWaitIntent2 = System.currentTimeMillis()
        while (launchedIntents.size < 2 && System.currentTimeMillis() - startWaitIntent2 < 3000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }
        assertEquals(2, launchedIntents.size)
        assertEquals("com.google.android.gms.auth.ACTION_RECOVER_2", launchedIntents[1].action)
        assertNull("Intent 2 deve ser limpo após lançamento", manager.recoverableAuthIntent.value)

        // Quando o usuário aprova (RESULT_OK), o token passa a funcionar, mas se o servidor retornar 503 na verificação,
        // o app NÃO pode assumir CONFIGURED: deve refletir o resultado real do verifyDriveConnection() (UNVERIFIED)
        nextRecoverableIntent = null
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        controller.pause()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        pendingResultCallback?.invoke(android.app.Activity.RESULT_OK)
        controller.resume()
        awaitConnectionStatus(viewModel, DriveConnectionStatus.UNVERIFIED)
        assertEquals("RESULT_OK deve executar verifyDriveConnection() real (1 request)", 1, mockWebServer.requestCount)
        assertEquals("Não deve assumir CONFIGURED se a verificação falhar", DriveConnectionStatus.UNVERIFIED, viewModel.driveConnectionStatus.value)

        // Agora novo evento recuperável com RESULT_OK e pasta válida HTTP 200 -> confirma CONFIGURED
        nextRecoverableIntent = Intent("com.google.android.gms.auth.ACTION_RECOVER_3")
        viewModel.verifyDriveConnection()
        val startWaitIntent3 = System.currentTimeMillis()
        while (launchedIntents.size < 3 && System.currentTimeMillis() - startWaitIntent3 < 3000L) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(25) }
        }
        assertEquals(3, launchedIntents.size)
        assertNull(manager.recoverableAuthIntent.value)

        nextRecoverableIntent = null
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        controller.pause()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        pendingResultCallback?.invoke(android.app.Activity.RESULT_OK)
        controller.resume()
        awaitConnectionStatus(viewModel, DriveConnectionStatus.CONFIGURED)
        assertEquals(2, mockWebServer.requestCount)
        assertEquals(DriveConnectionStatus.CONFIGURED, viewModel.driveConnectionStatus.value)

        // GDRIVE-10 via Lifecycle.Event.ON_RESUME real da Activity:
        // Sem pending changes, ao retornar ao foreground, verifica e NÃO faz upload
        fakeNowMs += 5 * 60 * 1000L
        assertFalse(viewModel.hasPendingChanges.value)
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
            )
        )
        controller.pause()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.resume()
        awaitRequestCount(3)
        awaitOperationMutexUnlocked(manager)
        assertEquals("CONFIGURED sem pending changes deve fazer apenas 1 request de verificação e nenhum upload", 3, mockWebServer.requestCount)

        controller.destroy()
    }
}

