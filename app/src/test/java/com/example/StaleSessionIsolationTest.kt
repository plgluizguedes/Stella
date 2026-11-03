package com.example

// Testes permanentes de R2-A #9 e #10 (isolamento entre a sessão antiga A e a sessão atual B).
//
// Causa comum dos dois defeitos (b8c5753): GoogleDriveSyncManager decidia se uma operação podia
// alterar o estado compartilhado consultando um campo global ("o envio que está em andamento"),
// em vez da sessão capturada pela própria operação.
//
//   #9  : o tratamento do HTTP 401 gravava recoverableAuthIntent / PERMISSION_MISSING sem consultar a
//         sessão; um envio antigo recolocava esse estado depois do signOut.
//   #10 : com o envio de A em andamento, getOrCreateDriveFolder(B) era julgado contra a sessão de A
//         (já inválida), devolvia "" sem buscar a pasta e B terminava CONFIGURED sem folderId.
//
// Testes:
//   - testR2A9_stale401...      : 401 de A depois do signOut não contamina o estado atual (negativo).
//   - testR2A9_legitimate401... : 401 legítimo de B continua produzindo Intent + PERMISSION_MISSING
//                                 enquanto A ainda tem uma operação antiga em voo (positivo). Chama
//                                 executeHttp diretamente de propósito: handleSignInSuccess e
//                                 ensureFolderConfigured também gravam esse estado nos seus próprios
//                                 catch, então só a chamada direta isola a guarda do 401. Uma correção
//                                 que consultasse a sessão de A para decidir sobre B falha aqui.
//   - testR2A9_stale401AfterAccountSwitch... / testR2A9_accountChangesWhileRenewingToken...:
//                                 o 401 de uma operação antiga de A não renova token (getToken devolveria
//                                 o token de B) nem repete a requisição de A com o token de B; o segundo
//                                 cobre a conta mudando durante a renovação.
//   - testR2A10A_...            : login de B com o envio de A em voo persiste folder_B e termina CONFIGURED.
//   - testR2A10B_...            : ensureFolderConfigured de B nas mesmas condições persiste folder_B.
//
// Não verificar só "CONFIGURED": nos dois testes de #10 o que importa é o folderId persistido.

import android.content.Context
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.example.data.drive.CapturedBackup
import com.example.data.drive.DriveAccountInfo
import com.example.data.drive.DriveAuthGateway
import com.example.data.drive.DriveConnectionStatus
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.model.BackupSnapshot
import com.example.data.model.StellaProfile
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

@RunWith(RobolectricTestRunner::class)
class StaleSessionIsolationTest {

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
        try { mockWebServer.shutdown() } catch (_: Exception) {}
    }

    private fun createEmptySnapshot(profileName: String = "Stella"): BackupSnapshot =
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

    // ---------------------------------------------------------------------------------------
    // HELPER: GoogleSignInAccount com e-mail, sem Mockito/MockK (o projeto não os tem).
    //
    // Monta o Parcel do SafeParcelable e usa GoogleSignInAccount.CREATOR. Os ids de campo abaixo
    // (1 versionCode, 2 id, 4 email, 8 expirationTimeSecs, 9 obfuscatedIdentifier, 10 grantedScopes)
    // pertencem à play-services-auth. O helper falha ALTO se o e-mail não voltar igual, para que uma
    // mudança nesses ids (por exemplo, ao atualizar a biblioteca) nunca seja lida como defeito do app.
    // ---------------------------------------------------------------------------------------
    private fun googleSignInAccount(email: String): GoogleSignInAccount {
        val parcel = Parcel.obtain()
        try {
            val header = SafeParcelWriter.beginObjectHeader(parcel)
            SafeParcelWriter.writeInt(parcel, 1, 3)
            SafeParcelWriter.writeString(parcel, 2, "id-$email", false)
            SafeParcelWriter.writeString(parcel, 4, email, false)
            SafeParcelWriter.writeLong(parcel, 8, 0L)
            SafeParcelWriter.writeString(parcel, 9, "obfuscated-$email", false)
            SafeParcelWriter.writeTypedList(parcel, 10, emptyList<Scope>(), false)
            SafeParcelWriter.finishObjectHeader(parcel, header)
            parcel.setDataPosition(0)
            val account = GoogleSignInAccount.CREATOR.createFromParcel(parcel)
            assertEquals(
                "HELPER INVÁLIDO: o e-mail não voltou do Parcel; confira os ids de campo do SafeParcelable. " +
                    "Nenhuma conclusão sobre o app pode ser tirada deste resultado.",
                email,
                account.email
            )
            return account
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun testHelper_fakeGoogleSignInAccount_exposesEmail() {
        assertEquals("conta_x@example.com", googleSignInAccount("conta_x@example.com").email)
    }

    // ---------------------------------------------------------------------------------------
    // Gateway falso com a semântica do Google Sign-In: signOut() limpa a conta; o seletor de contas,
    // ao concluir, passa a reportar a nova conta (completeGoogleSignIn). checkAccountHasScope precisa
    // ser sobrescrito: o padrão da interface é false (DriveAuthGateway.kt) e faria handleSignInSuccess
    // terminar em PERMISSION_MISSING antes de tocar na pasta.
    // ---------------------------------------------------------------------------------------
    private class FakeGateway : DriveAuthGateway {
        @Volatile var signedIn: DriveAccountInfo? = null

        /** Quando definido, getToken() lança essa exceção (ex.: consentimento recuperável). */
        @Volatile var tokenFailure: Exception? = null

        /** Quantas vezes getToken() foi chamado e quais tokens foram invalidados. */
        val getTokenCalls = AtomicInteger(0)
        val invalidatedTokens = CopyOnWriteArrayList<String>()

        /**
         * Gancho de uma só execução, rodado dentro da PRÓXIMA chamada de getToken(), antes de o token ser
         * calculado: permite simular a conta mudando enquanto o token está sendo renovado.
         */
        private val nextGetTokenHook = AtomicReference<(() -> Unit)?>(null)
        fun runOnNextGetToken(hook: () -> Unit) = nextGetTokenHook.set(hook)

        override fun getConnectedAccount(): DriveAccountInfo? = signedIn
        override fun hasRequiredScope(): Boolean = signedIn?.hasDriveScope == true
        override fun checkAccountHasScope(account: GoogleSignInAccount): Boolean = true
        override fun getToken(): String {
            getTokenCalls.incrementAndGet()
            nextGetTokenHook.getAndSet(null)?.invoke()
            tokenFailure?.let { throw it }
            // Como o Google Sign-In real: devolve o token da conta ATUAL, seja qual for o chamador.
            return "token-${signedIn?.email ?: "none"}"
        }
        override fun invalidateToken(token: String) {
            invalidatedTokens.add(token)
        }
        override fun signOut(onComplete: () -> Unit) {
            signedIn = null
            onComplete()
        }

        /** O que o seletor de contas do Google faz quando o usuário escolhe a conta. */
        fun completeGoogleSignIn(email: String) {
            signedIn = DriveAccountInfo(email = email, hasDriveScope = true)
        }
    }

    /**
     * Cenário comum: conta A com envio em andamento, parado dentro do POST do upload. B não tem pasta
     * persistida. O Drive falso só devolve 401 para a requisição "probe_b" de B (teste positivo do #9).
     */
    private inner class AccountAUploadInFlight {
        val emailA = "conta_a@example.com"
        val emailB = "conta_b@example.com"
        val folderA = "folder_A"
        val folderB = "folder_B"

        val gateway = FakeGateway()
        private val uploadInFlight = CountDownLatch(1)
        private val releaseOldUpload = CountDownLatch(1)

        /** Requisições feitas com o token de B, para provar se B chegou a consultar o Drive. */
        val requestsByB = CopyOnWriteArrayList<String>()

        /** Quando verdadeiro, o POST antigo de A (token de A) volta 401 em vez de 200. */
        @Volatile var oldUploadRespondsWith401 = false

        lateinit var manager: GoogleDriveSyncManager
        lateinit var uploadJobA: Deferred<Boolean>

        suspend fun start() {
            gateway.completeGoogleSignIn(emailA)

            val interceptingClient = GoogleDriveSyncManager.defaultHttpClient().newBuilder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    if (req.method == "POST" && req.header("Authorization") == "Bearer token-$emailA") {
                        uploadInFlight.countDown()
                        releaseOldUpload.await(30, TimeUnit.SECONDS)
                    }
                    chain.proceed(req)
                }
                .build()

            mockWebServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val isB = request.getHeader("Authorization") == "Bearer token-$emailB"
                    val path = request.path.orEmpty()
                    if (isB) requestsByB.add("${request.method} $path")
                    return when {
                        // Requisição legítima de B que o Drive rejeita (teste positivo do #9)
                        isB && request.method == "GET" && path.startsWith("/files/probe_b") ->
                            MockResponse().setResponseCode(401).setBody(
                                """{"error":{"code":401,"message":"Invalid Credentials"}}"""
                            )
                        // Validação de uma pasta persistida: /files/<id>?fields=...
                        request.method == "GET" && path.startsWith("/files/") -> {
                            val id = path.removePrefix("/files/").substringBefore("?")
                            MockResponse().setResponseCode(200).setBody(
                                """{"id":"$id","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                            )
                        }
                        // Qualquer busca (pasta ou arquivo): lista vazia
                        request.method == "GET" ->
                            MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                        // Conta B criando a pasta
                        isB && request.method == "POST" ->
                            MockResponse().setResponseCode(200).setBody("""{"id":"$folderB"}""")
                        // POST antigo da conta A rejeitado (testes do 401 obsoleto)
                        !isB && request.method == "POST" && oldUploadRespondsWith401 ->
                            MockResponse().setResponseCode(401).setBody(
                                """{"error":{"code":401,"message":"Invalid Credentials"}}"""
                            )
                        // Uploads da conta A
                        else ->
                            MockResponse().setResponseCode(200).setBody("""{"id":"upload_ok"}""")
                    }
                }
            }

            manager = GoogleDriveSyncManager(
                context = context,
                httpClient = interceptingClient,
                driveApiBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
                driveUploadBaseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
                authGateway = gateway
            )
            manager.setSyncApprovedForAccount(emailA, true)
            manager.setSyncApprovedForAccount(emailB, true)
            manager.persistFolderId(emailA, folderA)
            manager.markPendingChanges()

            val capturedA = CapturedBackup(snapshot = createEmptySnapshot("Stella A"), changeVersion = 1L, restoreEpoch = 0L)
            uploadJobA = CoroutineScope(Dispatchers.IO).async { manager.syncToDrive(capturedA) }

            assertTrue(
                "O upload da conta A deve estar parado dentro do POST",
                withContext(Dispatchers.IO) { uploadInFlight.await(10, TimeUnit.SECONDS) }
            )
            assertTrue("A deve estar em andamento", uploadJobA.isActive)
        }

        /** Troca real de conta: o mesmo método que StellaViewModel.prepareForDriveAccountSwitch chama. */
        fun switchAwayFromA() {
            var readyToSignIn = false
            manager.prepareForAccountSwitch { readyToSignIn = true }
            assertTrue("prepareForAccountSwitch deve chamar onReadyToSignIn", readyToSignIn)
            assertEquals(DriveConnectionStatus.NOT_CONNECTED, manager.connectionStatus.value)
            assertNull("A foi desconectada: pasta de A limpa", manager.getPersistedFolderId(emailA))
            assertTrue("A deve continuar em andamento durante a troca", uploadJobA.isActive)
        }

        /** Login real de B: o seletor devolve B e o app chama handleSignInSuccess(account). */
        suspend fun signInAsBThroughRealPath(): DriveConnectionStatus {
            gateway.completeGoogleSignIn(emailB)
            val status = manager.handleSignInSuccess(googleSignInAccount(emailB))
            assertEquals("B deve estar estabelecida como conta conectada", emailB, manager.connectedAccountEmail.value)
            assertTrue("A deve continuar em andamento durante o login de B", uploadJobA.isActive)
            return status
        }

        fun releaseOldUploadNow() = releaseOldUpload.countDown()
    }

    /**
     * #10A. A -> upload em andamento -> troca real -> B entra por handleSignInSuccess.
     * B deve achar/criar a pasta, persistir folder_B e terminar CONFIGURED. Antes da correção, a operação
     * de B era julgada contra a sessão de A (inválida): nenhuma requisição, "" como pasta e CONFIGURED
     * sem folderId.
     */
    @Test
    fun testR2A10A_signInAsB_whileOldUploadInFlight_persistsFolderBAndEndsConfigured() = runTest {
        val f = AccountAUploadInFlight()
        try {
            f.start()
            f.switchAwayFromA()

            val status = f.signInAsBThroughRealPath()

            // Verificado ANTES de liberar o envio antigo: o resultado vem só do login de B.
            assertEquals("B deve terminar CONFIGURED", DriveConnectionStatus.CONFIGURED, status)
            assertEquals(DriveConnectionStatus.CONFIGURED, f.manager.connectionStatus.value)
            assertEquals(
                "B ficou CONFIGURED sem folderId persistido (envio antigo de A em andamento)",
                f.folderB,
                f.manager.getPersistedFolderId(f.emailB)
            )
            assertTrue(
                "B deve ter consultado o Drive para achar/criar a pasta; requisições de B: ${f.requestsByB}",
                f.requestsByB.any { it.startsWith("GET /files?") }
            )
            assertTrue("A ainda em andamento ao final da configuração de B", f.uploadJobA.isActive)
        } finally {
            f.releaseOldUploadNow()
        }

        // Depois: o envio antigo termina e não contamina B (mesmas garantias do testR2A_06).
        assertFalse("O envio antigo de A não pode reportar sucesso", f.uploadJobA.await())
        assertEquals(f.emailB, f.manager.connectedAccountEmail.value)
        assertEquals(DriveConnectionStatus.CONFIGURED, f.manager.connectionStatus.value)
        assertFalse(f.manager.syncState.value is DriveSyncState.Success)
        assertEquals("Nunca sincronizado", f.manager.lastSyncTimestamp.value)
        assertNull("folderId de A não pode reaparecer", f.manager.getPersistedFolderId(f.emailA))
        assertEquals(f.folderB, f.manager.getPersistedFolderId(f.emailB))
        assertTrue("Pendência preservada", f.manager.hasPendingChanges.value)
    }

    /**
     * #10B. Igual ao #10A e, com B estabelecida, o caminho real de configuração do Drive
     * (ensureFolderConfigured, o que StellaViewModel.configureDriveFolder chama), ainda com A em voo.
     */
    @Test
    fun testR2A10B_ensureFolderConfiguredForB_whileOldUploadInFlight_persistsFolderB() = runTest {
        val f = AccountAUploadInFlight()
        try {
            f.start()
            f.switchAwayFromA()
            f.signInAsBThroughRealPath()

            // O defeito atuava no caminho de busca/criação da pasta (B sem pasta persistida). Como o login
            // acima já criou folder_B, volta B ao estado "sem pasta" para que ensureFolderConfigured
            // percorra esse caminho de novo, ainda com o envio de A em voo.
            f.manager.persistFolderId(f.emailB, null)
            assertNull(f.manager.getPersistedFolderId(f.emailB))

            val configured = f.manager.ensureFolderConfigured()

            assertTrue("ensureFolderConfigured deve concluir para B", configured)
            assertEquals(DriveConnectionStatus.CONFIGURED, f.manager.connectionStatus.value)
            assertEquals(
                "B ficou CONFIGURED sem folderId persistido após ensureFolderConfigured",
                f.folderB,
                f.manager.getPersistedFolderId(f.emailB)
            )
            assertTrue("A ainda em andamento", f.uploadJobA.isActive)
        } finally {
            f.releaseOldUploadNow()
        }
        assertFalse(f.uploadJobA.await())
        assertEquals(f.folderB, f.manager.getPersistedFolderId(f.emailB))
    }

    /**
     * #9 positivo. A tem uma operação antiga em voo, B é a sessão atual e executa uma requisição
     * legítima que recebe 401 e cujo refresh de token exige consentimento. O fluxo normal de
     * autenticação recuperável de B deve acontecer: recoverableAuthIntent e PERMISSION_MISSING.
     *
     * Protege contra a correção errada que suprimiria todo 401 enquanto houver um envio antigo em voo.
     * (Na versão sem guarda, anterior à correção, este teste também passa; ele existe para falhar se a
     * guarda passar a consultar a sessão de A em vez da sessão da própria operação.)
     */
    @Test
    fun testR2A9_legitimate401OfSessionB_whileOldSessionAInFlight_stillProducesRecoverableIntentAndPermissionMissing() = runTest {
        val f = AccountAUploadInFlight()
        try {
            f.start()
            f.switchAwayFromA()
            f.signInAsBThroughRealPath()
            assertNull("Sem Intent recuperável antes do 401 de B", f.manager.recoverableAuthIntent.value)

            // O token de B é aceito pela primeira requisição, mas o Drive responde 401 e a renovação
            // exige consentimento.
            f.gateway.tokenFailure = UserRecoverableAuthException("B precisa consentir de novo", Intent("r2a9.B_RECOVER"))
            val requestB = Request.Builder()
                .url(mockWebServer.url("/files/probe_b"))
                .header("Authorization", "Bearer token-${f.emailB}")
                .get()
                .build()
            try {
                f.manager.executeHttp(requestB)
                fail("O 401 de B deveria propagar UserRecoverableAuthException")
            } catch (e: UserRecoverableAuthException) {
                assertEquals("r2a9.B_RECOVER", e.intent?.action)
            }

            assertEquals(
                "O 401 legítimo de B deve produzir o Intent recuperável mesmo com A em voo",
                "r2a9.B_RECOVER",
                f.manager.recoverableAuthIntent.value?.action
            )
            assertEquals(
                "O 401 legítimo de B deve produzir PERMISSION_MISSING mesmo com A em voo",
                DriveConnectionStatus.PERMISSION_MISSING,
                f.manager.connectionStatus.value
            )
            assertTrue("A ainda em andamento", f.uploadJobA.isActive)
        } finally {
            f.gateway.tokenFailure = null
            f.releaseOldUploadNow()
        }

        // O término tardio do envio antigo não apaga o estado de autenticação de B.
        assertFalse(f.uploadJobA.await())
        assertEquals("r2a9.B_RECOVER", f.manager.recoverableAuthIntent.value?.action)
        assertEquals(DriveConnectionStatus.PERMISSION_MISSING, f.manager.connectionStatus.value)
    }

    /**
     * #9 negativo. Envio de A recebe 401 DEPOIS do signOut e a renovação do token exige consentimento.
     * O envio antigo não pode recolocar o Intent recuperável nem mudar o status.
     */
    @Test
    fun testR2A9_stale401AfterSignOut_doesNotRestoreRecoverableIntentOrStatus() = runTest {
        val email = "r2a9@example.com"
        val folderId = "folder_r2a_9"

        var currentGatewayAccount: DriveAccountInfo? = DriveAccountInfo(email = email, hasDriveScope = true)
        val uploadInFlightLatch = CountDownLatch(1)
        val releaseOldUploadLatch = CountDownLatch(1)

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

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.contains("/files/$folderId") ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"$folderId","name":"STELLA","trashed":false,"mimeType":"application/vnd.google-apps.folder"}"""
                        )
                    request.method == "GET" ->
                        MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    // O upload do envio antigo volta 401 depois do signOut
                    else ->
                        MockResponse().setResponseCode(401).setBody("""{"error":{"code":401,"message":"Invalid Credentials"}}""")
                }
            }
        }

        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount() = currentGatewayAccount
            override fun hasRequiredScope() = currentGatewayAccount?.hasDriveScope == true
            override fun getToken(): String {
                // Depois do signOut, renovar o token exige consentimento (Intent recuperável)
                if (currentGatewayAccount == null) {
                    throw UserRecoverableAuthException("consentimento exigido", Intent("r2a9.RECOVER"))
                }
                return "token-r2a9"
            }
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

        val captured = CapturedBackup(snapshot = createEmptySnapshot("Stella 9"), changeVersion = 1L, restoreEpoch = 0L)

        val uploadJob = CoroutineScope(Dispatchers.IO).async { manager.syncToDrive(captured) }
        assertTrue(
            "Upload deve estar em andamento",
            withContext(Dispatchers.IO) { uploadInFlightLatch.await(5, TimeUnit.SECONDS) }
        )

        manager.signOut { }
        assertEquals(DriveConnectionStatus.NOT_CONNECTED, manager.connectionStatus.value)
        assertNull(manager.recoverableAuthIntent.value)

        releaseOldUploadLatch.countDown()
        uploadJob.await()

        assertNull(
            "Envio antigo não pode recolocar o Intent recuperável depois do signOut",
            manager.recoverableAuthIntent.value
        )
        assertEquals(
            "Envio antigo não pode mudar o status depois do signOut",
            DriveConnectionStatus.NOT_CONNECTED,
            manager.connectionStatus.value
        )
        assertTrue("Pendência preservada", manager.hasPendingChanges.value)
    }

    /**
     * Isolamento de conta no 401. A tem um upload em voo, o usuário troca para B, o POST antigo de A
     * recebe 401 e authGateway.getToken() devolveria o token de B (como o Google Sign-In real devolve o
     * token da conta ATUAL, seja qual for o chamador). A requisição antiga não pode renovar token nem ser
     * repetida: sem a verificação, o retry reenviaria o upload de A com "Bearer token-B".
     */
    @Test
    fun testR2A9_stale401AfterAccountSwitch_doesNotRenewTokenNorRetryRequestWithTokenOfB() = runTest {
        val f = AccountAUploadInFlight()
        try {
            f.start()
            f.switchAwayFromA()
            f.signInAsBThroughRealPath()

            // Pré-condição: o gateway entregaria o token de B a qualquer chamador (a ameaça é real).
            assertEquals("token-${f.emailB}", f.gateway.getToken())
            val tokenCallsBefore = f.gateway.getTokenCalls.get()
            val requestsByBBefore = f.requestsByB.toList()
            assertTrue("Nenhum token foi invalidado até aqui", f.gateway.invalidatedTokens.isEmpty())

            // O POST antigo de A é liberado e o Drive responde 401.
            f.oldUploadRespondsWith401 = true
            f.releaseOldUploadNow()
            assertFalse("O envio antigo de A não pode reportar sucesso", f.uploadJobA.await())

            assertEquals(
                "O 401 de uma sessão antiga não pode renovar o token (getToken devolveria o de B)",
                tokenCallsBefore,
                f.gateway.getTokenCalls.get()
            )
            assertEquals(
                "Nenhuma requisição nova pode ter saído com o token de B (retry do upload de A)",
                requestsByBBefore,
                f.requestsByB.toList()
            )
            assertEquals(
                "Só o token exato rejeitado (de A) é invalidado; o token de B nunca",
                listOf("token-${f.emailA}"),
                f.gateway.invalidatedTokens.toList()
            )

            // B segue intacta.
            assertEquals(f.emailB, f.manager.connectedAccountEmail.value)
            assertEquals(DriveConnectionStatus.CONFIGURED, f.manager.connectionStatus.value)
            assertNull(f.manager.recoverableAuthIntent.value)
            assertEquals(f.folderB, f.manager.getPersistedFolderId(f.emailB))
            assertNull("folderId de A não pode reaparecer", f.manager.getPersistedFolderId(f.emailA))
        } finally {
            f.releaseOldUploadNow()
        }
    }

    /**
     * Mesma ameaça, mas a conta muda DURANTE a renovação do token: o 401 chega com A ainda válida (a
     * primeira verificação passa), getToken() fica parado e, enquanto isso, o app troca para B. Quando
     * getToken() volta (com o token de B) a sessão de A já é antiga: a segunda verificação, imediatamente
     * antes do retry, precisa impedir o reenvio.
     */
    @Test
    fun testR2A9_accountChangesWhileRenewingToken_doesNotRetryRequestWithRenewedToken() = runTest {
        val f = AccountAUploadInFlight()
        val renewalStarted = CountDownLatch(1)
        val finishRenewal = CountDownLatch(1)
        try {
            f.start()
            f.oldUploadRespondsWith401 = true

            // O getToken() do 401 de A fica parado até o app terminar de trocar para B.
            f.gateway.runOnNextGetToken {
                renewalStarted.countDown()
                finishRenewal.await(15, TimeUnit.SECONDS)
            }
            f.releaseOldUploadNow()
            assertTrue(
                "O 401 de A deve ter chamado getToken() e ficado parado nele",
                withContext(Dispatchers.IO) { renewalStarted.await(10, TimeUnit.SECONDS) }
            )
            assertTrue("A segue em andamento, parada na renovação do token", f.uploadJobA.isActive)

            f.switchAwayFromA()
            f.signInAsBThroughRealPath()
            val requestsByBBefore = f.requestsByB.toList()

            // A renovação termina: getToken() devolve o token da conta atual (B).
            finishRenewal.countDown()
            assertFalse("O envio antigo de A não pode reportar sucesso", f.uploadJobA.await())

            assertEquals(
                "Nenhuma requisição nova pode ter saído com o token renovado (de B)",
                requestsByBBefore,
                f.requestsByB.toList()
            )
            assertEquals(f.emailB, f.manager.connectedAccountEmail.value)
            assertEquals(DriveConnectionStatus.CONFIGURED, f.manager.connectionStatus.value)
            assertNull(f.manager.recoverableAuthIntent.value)
            assertEquals(f.folderB, f.manager.getPersistedFolderId(f.emailB))
            assertNull("folderId de A não pode reaparecer", f.manager.getPersistedFolderId(f.emailA))
        } finally {
            finishRenewal.countDown()
            f.releaseOldUploadNow()
        }
    }
}
