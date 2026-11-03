package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.attachments.AttachmentManager
import com.example.data.attachments.AttachmentStorage
import com.example.data.attachments.StageResult
import com.example.data.attachments.StagedAttachment
import com.example.data.db.AppDatabase
import com.example.data.drive.CapturedBackup
import com.example.data.drive.DriveAccountInfo
import com.example.data.drive.DriveAuthGateway
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.model.Attachment
import com.example.data.model.AttachmentOwner
import com.example.data.model.BackupSnapshot
import com.example.data.model.GlucoseRecord
import com.example.data.model.StellaProfile
import com.example.data.repository.StellaRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Etapa 2b: anexos de consultas e exames no backup do Drive (nome por conteúdo, sem reenvio, envio em
 * partes, backup v2) e a restauração deles, inclusive em backups antigos (v1).
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentCloudTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var drive: FakeDrive

    private val email = "anexos@example.com"
    private val folderId = "folder_anexos"

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
        // O banco é um singleton compartilhado: anexos deixados aqui entrariam no backup dos testes de
        // sincronização que rodam depois e os fariam falhar.
        AttachmentTestSupport.resetDatabase(context)
        AttachmentTestSupport.wipeAttachmentsDir(context)
    }

    // ------------------------------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------------------------------

    private fun newManager(
        thresholdBytes: Long = GoogleDriveSyncManager.DEFAULT_RESUMABLE_THRESHOLD_BYTES,
        chunkBytes: Int = GoogleDriveSyncManager.DEFAULT_RESUMABLE_CHUNK_BYTES
    ): GoogleDriveSyncManager {
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
            authGateway = gateway,
            resumableThresholdBytes = thresholdBytes,
            resumableChunkBytes = chunkBytes
        )
        manager.persistFolderId(email, folderId)
        manager.setSyncApprovedForAccount(email, true)
        return manager
    }

    private fun repository() = StellaRepository(AppDatabase.getDatabase(context).stellaDao())

    /** Arquivo já guardado na pasta privada, como se o usuário o tivesse anexado. */
    private fun localAttachment(
        bytes: ByteArray,
        ownerType: String,
        ownerId: Long,
        name: String,
        label: String = "Outro",
        mimeType: String = "application/pdf",
        extension: String = "pdf"
    ): Attachment {
        val sha = AttachmentTestSupport.sha256Hex(bytes)
        val dir = AttachmentTestSupport.attachmentsDir(context).apply { mkdirs() }
        val file = File(dir, "$sha.$extension").apply { writeBytes(bytes) }
        return Attachment(
            ownerType = ownerType,
            ownerId = ownerId,
            label = label,
            displayName = name,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha,
            localPath = file.absolutePath
        )
    }

    private fun snapshot(
        attachments: List<Attachment> = emptyList(),
        photoUri: String? = null
    ) = BackupSnapshot(
        profile = StellaProfile(id = 1, name = "Stella", photoUri = photoUri),
        glucoseList = emptyList(),
        medications = emptyList(),
        administrations = emptyList(),
        appointments = emptyList(),
        documents = emptyList(),
        feeding = emptyList(),
        weights = emptyList(),
        vaccines = emptyList(),
        reminders = emptyList(),
        attachments = attachments
    )

    private suspend fun sync(manager: GoogleDriveSyncManager, snapshot: BackupSnapshot): Boolean =
        manager.syncToDrive(
            CapturedBackup(snapshot = snapshot, changeVersion = 1L, restoreEpoch = 0L, sessionEmail = email)
        )

    private fun cloudName(bytes: ByteArray, extension: String) =
        GoogleDriveSyncManager.attachmentFileName(AttachmentTestSupport.sha256Hex(bytes), extension)

    private fun errorMessage(manager: GoogleDriveSyncManager): String {
        val state = manager.syncState.value
        assertTrue("O estado deveria ser Error, mas foi $state", state is DriveSyncState.Error)
        return (state as DriveSyncState.Error).message
    }

    /** Uma consulta com [receita] e [orientacoes] e um exame com [laudo], guardados de verdade no banco. */
    private data class DeviceData(
        val appointmentId: Long,
        val otherAppointmentId: Long,
        val examId: Long,
        val receita: ByteArray,
        val orientacoes: ByteArray,
        val laudo: ByteArray
    )

    private suspend fun createDeviceData(): DeviceData {
        val repository = repository()
        val attachments = AttachmentManager(repository, AttachmentStorage(context))
        repository.saveProfile(StellaProfile(name = "Stella do Aparelho"))
        val otherId = repository.addAppointment(AttachmentTestSupport.newAppointment().copy(reason = "Rotina"))
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment().copy(reason = "Retorno cardíaco"))
        val examId = repository.addExamDocument(AttachmentTestSupport.newExamDocument())
        val receita = AttachmentTestSupport.pdfBytes(seed = 31)
        val orientacoes = AttachmentTestSupport.pdfBytes(seed = 32)
        val laudo = AttachmentTestSupport.pngBytes(seed = 33)

        suspend fun stage(name: String, bytes: ByteArray, label: String): StagedAttachment {
            val source = AttachmentTestSupport.source(context, name, bytes)
            val result = attachments.stage(source.absolutePath, label)
            assertTrue("Deveria ter preparado '$name', mas foi $result", result is StageResult.Staged)
            return (result as StageResult.Staged).attachment
        }
        attachments.commit(
            AttachmentOwner.APPOINTMENT, appointmentId,
            listOf(stage("receita.pdf", receita, "Receituário"), stage("orientacoes.pdf", orientacoes, "Orientações"))
        )
        attachments.commit(AttachmentOwner.EXAM_DOCUMENT, examId, listOf(stage("laudo.png", laudo, "Laudo")))
        return DeviceData(appointmentId, otherId, examId, receita, orientacoes, laudo)
    }

    private fun wipeDevice() {
        AttachmentTestSupport.resetDatabase(context)
        AttachmentTestSupport.wipeAttachmentsDir(context)
    }

    private fun backupJson(version: Int, build: JSONObject.() -> Unit = {}): String =
        JSONObject().apply {
            put("backupVersion", version)
            put("exportDate", "2026-10-14T10:00:00")
            put("profile", JSONObject().put("id", 1).put("name", "Stella da Nuvem"))
            build()
        }.toString()

    private fun appointmentJson(id: Long, reason: String) = JSONObject()
        .put("id", id).put("dateString", "14/10/2026").put("timeString", "10:00")
        .put("timestamp", 1760436000000L).put("reason", reason)

    /** Entrada de `recordAttachments` com o arquivo já guardado no Drive simulado. */
    private fun recordEntry(
        bytes: ByteArray,
        ownerType: String,
        ownerId: Long,
        displayName: String,
        label: String = "Outro"
    ): JSONObject {
        val driveName = cloudName(bytes, "pdf")
        val id = drive.byName(driveName)?.id ?: drive.put(driveName, bytes)
        return JSONObject()
            .put("driveFileName", driveName).put("driveFileId", id)
            .put("mimeType", "application/pdf").put("sizeBytes", bytes.size.toLong())
            .put("sha256", AttachmentTestSupport.sha256Hex(bytes))
            .put("ownerType", ownerType).put("ownerId", ownerId)
            .put("label", label).put("displayName", displayName)
    }

    private fun putCloudBackup(json: String) {
        drive.put(GoogleDriveSyncManager.BACKUP_FILE_NAME, json.toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------------------------------
    // Envio (aparelho -> Drive)
    // ------------------------------------------------------------------------------------------

    @Test
    fun sync_sendsEveryFileUnderItsContentName_andWritesBackupVersion2() = runTest {
        val manager = newManager()
        val receita = AttachmentTestSupport.pdfBytes(seed = 1)
        val foto = AttachmentTestSupport.pngBytes(seed = 2)
        val perfil = AttachmentTestSupport.jpgBytes(seed = 3)
        val a = localAttachment(receita, AttachmentOwner.APPOINTMENT, 7L, "receita.pdf", "Receituário")
        val b = localAttachment(foto, AttachmentOwner.EXAM_DOCUMENT, 9L, "raio-x.png", "Exame", "image/png", "png")
        val photoFile = File(AttachmentTestSupport.attachmentsDir(context), "${AttachmentTestSupport.sha256Hex(perfil)}.jpg")
            .apply { writeBytes(perfil) }

        val ok = sync(manager, snapshot(listOf(a, b), photoUri = photoFile.absolutePath))

        assertTrue("O envio deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals(receita.toList(), drive.byName(cloudName(receita, "pdf"))!!.bytes.toList())
        assertEquals(foto.toList(), drive.byName(cloudName(foto, "png"))!!.bytes.toList())
        assertEquals("A foto do perfil também vai por conteúdo", perfil.toList(), drive.byName(cloudName(perfil, "jpg"))!!.bytes.toList())
        assertNotNull(drive.byName(GoogleDriveSyncManager.REPORT_FILE_NAME))

        val backup = drive.json(GoogleDriveSyncManager.BACKUP_FILE_NAME)
        assertEquals(2, backup.getInt("backupVersion"))
        val records = backup.getJSONArray("recordAttachments")
        assertEquals(2, records.length())
        val first = records.getJSONObject(0)
        assertEquals(AttachmentOwner.APPOINTMENT, first.getString("ownerType"))
        assertEquals(7L, first.getLong("ownerId"))
        assertEquals("Receituário", first.getString("label"))
        assertEquals("receita.pdf", first.getString("displayName"))
        assertEquals(AttachmentTestSupport.sha256Hex(receita), first.getString("sha256"))
        assertEquals(drive.byName(cloudName(receita, "pdf"))!!.id, first.getString("driveFileId"))
        val legacy = backup.getJSONArray("attachments")
        assertEquals(1, legacy.length())
        assertEquals("PROFILE_PHOTO", legacy.getJSONObject(0).getString("type"))
        assertEquals(AttachmentTestSupport.sha256Hex(perfil), legacy.getJSONObject(0).getString("sha256"))
    }

    @Test
    fun sync_secondRun_doesNotSendFilesAlreadyOnDrive_evenIfTheLocalFileIsGone() = runTest {
        val manager = newManager()
        val receita = AttachmentTestSupport.pdfBytes(seed = 4)
        val a = localAttachment(receita, AttachmentOwner.APPOINTMENT, 7L, "receita.pdf")
        assertTrue(sync(manager, snapshot(listOf(a))))
        drive.requests.clear()
        assertTrue("Sem o arquivo local, ele ainda está na nuvem", File(a.localPath).delete())

        val ok = sync(manager, snapshot(listOf(a)))

        assertTrue("O segundo envio deveria dar certo: ${manager.syncState.value}", ok)
        val uploads = drive.uploadRequests()
        assertEquals("Só o backup e o relatório são trocados", 2, uploads.size)
        assertTrue("Nada de arquivo novo", uploads.all { it.method == "PATCH" })
        assertEquals(
            "O anexo continua listado no backup",
            1,
            drive.json(GoogleDriveSyncManager.BACKUP_FILE_NAME).getJSONArray("recordAttachments").length()
        )
    }

    @Test
    fun sync_sameContentAttachedTwice_isSentOnce() = runTest {
        val manager = newManager()
        val bytes = AttachmentTestSupport.pdfBytes(seed = 5)
        val one = localAttachment(bytes, AttachmentOwner.APPOINTMENT, 1L, "receita.pdf")
        val two = localAttachment(bytes, AttachmentOwner.APPOINTMENT, 2L, "receita-copia.pdf")

        assertTrue(sync(manager, snapshot(listOf(one, two))))

        assertEquals(1, drive.namesStartingWith(GoogleDriveSyncManager.ATTACHMENT_FILE_PREFIX).size)
        assertEquals(2, drive.json(GoogleDriveSyncManager.BACKUP_FILE_NAME).getJSONArray("recordAttachments").length())
    }

    @Test
    fun sync_fileMissingOnDeviceAndNotOnDrive_blocksTheBackup_andNamesTheFile() = runTest {
        val manager = newManager()
        val a = localAttachment(AttachmentTestSupport.pdfBytes(seed = 6), AttachmentOwner.APPOINTMENT, 7L, "laudo.pdf")
        assertTrue(File(a.localPath).delete())

        val ok = sync(manager, snapshot(listOf(a)))

        assertFalse(ok)
        assertTrue(errorMessage(manager).contains("laudo.pdf"))
        assertNull("O backup anterior não é trocado por um incompleto", drive.byName(GoogleDriveSyncManager.BACKUP_FILE_NAME))
    }

    @Test
    fun sync_fileChangedAfterAttaching_isRefused() = runTest {
        val manager = newManager()
        val a = localAttachment(AttachmentTestSupport.pdfBytes(seed = 7), AttachmentOwner.APPOINTMENT, 7L, "alterado.pdf")
        // Mesmo tamanho, outro conteúdo: o nome por conteúdo mentiria se isto fosse enviado.
        File(a.localPath).writeBytes(AttachmentTestSupport.pdfBytes(seed = 8))

        val ok = sync(manager, snapshot(listOf(a)))

        assertFalse(ok)
        assertTrue(errorMessage(manager).contains("alterado.pdf"))
        assertTrue(drive.namesStartingWith(GoogleDriveSyncManager.ATTACHMENT_FILE_PREFIX).isEmpty())
    }

    @Test
    fun sync_rowWithBrokenRecord_isRefusedWithoutAnyUpload() = runTest {
        val manager = newManager()
        val a = localAttachment(AttachmentTestSupport.pdfBytes(seed = 9), AttachmentOwner.APPOINTMENT, 7L, "x.pdf")

        val ok = sync(manager, snapshot(listOf(a.copy(sha256 = "nao-e-um-hash"))))

        assertFalse(ok)
        assertTrue(drive.namesStartingWith(GoogleDriveSyncManager.ATTACHMENT_FILE_PREFIX).isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    // Envio em partes (arquivos acima do limite)
    // ------------------------------------------------------------------------------------------

    private val chunk = 256 * 1024

    private fun bigPdf(seed: Int = 0) = AttachmentTestSupport.pdfBytes(seed = seed, extra = 600 * 1024)

    private fun expectedRanges(total: Int) = listOf(
        "0-${chunk - 1}/$total",
        "$chunk-${2 * chunk - 1}/$total",
        "${2 * chunk}-${total - 1}/$total"
    )

    @Test
    fun sync_largeFile_goesInPartsThroughAResumableSession() = runTest {
        val manager = newManager(thresholdBytes = 1024, chunkBytes = chunk)
        val big = bigPdf(10)
        val a = localAttachment(big, AttachmentOwner.APPOINTMENT, 7L, "grande.pdf")

        val ok = sync(manager, snapshot(listOf(a)))

        assertTrue("O envio deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals(1, drive.sessionsStarted.get())
        assertEquals(expectedRanges(big.size), drive.chunkRanges.toList())
        assertEquals(big.toList(), drive.byName(cloudName(big, "pdf"))!!.bytes.toList())
        assertEquals(0, drive.statusQueries.get())
    }

    @Test
    fun sync_smallFile_stillUsesOneSimpleRequest() = runTest {
        val manager = newManager(thresholdBytes = 1024 * 1024, chunkBytes = chunk)
        val a = localAttachment(AttachmentTestSupport.pdfBytes(seed = 11), AttachmentOwner.APPOINTMENT, 7L, "pequeno.pdf")

        assertTrue(sync(manager, snapshot(listOf(a))))

        assertEquals(0, drive.sessionsStarted.get())
    }

    @Test
    fun sync_resumable_afterAPartWasStoredButTheAnswerWasLost_continuesWithoutResendingIt() = runTest {
        val manager = newManager(thresholdBytes = 1024, chunkBytes = chunk)
        val big = bigPdf(12)
        val a = localAttachment(big, AttachmentOwner.APPOINTMENT, 7L, "grande.pdf")
        drive.failAfterStoringAtStart = chunk.toLong()

        val ok = sync(manager, snapshot(listOf(a)))

        assertTrue("O envio deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals("Cada parte é enviada uma vez só", expectedRanges(big.size), drive.chunkRanges.toList())
        assertEquals("Perguntou ao Drive até onde ele tinha", 1, drive.statusQueries.get())
        assertEquals(1, drive.sessionsStarted.get())
        assertEquals(big.toList(), drive.byName(cloudName(big, "pdf"))!!.bytes.toList())
    }

    @Test
    fun sync_resumable_afterAPartWasNotStored_sendsThatPartAgain_andOnlyThat() = runTest {
        val manager = newManager(thresholdBytes = 1024, chunkBytes = chunk)
        val big = bigPdf(13)
        val a = localAttachment(big, AttachmentOwner.APPOINTMENT, 7L, "grande.pdf")
        drive.failBeforeStoringAtStart = chunk.toLong()

        val ok = sync(manager, snapshot(listOf(a)))

        assertTrue("O envio deveria dar certo: ${manager.syncState.value}", ok)
        val total = big.size
        assertEquals(
            listOf(
                "0-${chunk - 1}/$total",
                "$chunk-${2 * chunk - 1}/$total",
                "$chunk-${2 * chunk - 1}/$total",
                "${2 * chunk}-${total - 1}/$total"
            ),
            drive.chunkRanges.toList()
        )
        assertEquals(1, drive.statusQueries.get())
        assertEquals(big.toList(), drive.byName(cloudName(big, "pdf"))!!.bytes.toList())
    }

    @Test
    fun sync_resumable_lostSession_startsTheFileOverOnce() = runTest {
        val manager = newManager(thresholdBytes = 1024, chunkBytes = chunk)
        val big = bigPdf(14)
        val a = localAttachment(big, AttachmentOwner.APPOINTMENT, 7L, "grande.pdf")
        drive.expireSessionOnFirstChunk = true

        val ok = sync(manager, snapshot(listOf(a)))

        assertTrue("O envio deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals(2, drive.sessionsStarted.get())
        assertEquals(big.toList(), drive.byName(cloudName(big, "pdf"))!!.bytes.toList())
    }

    @Test
    fun sync_resumable_driveNeverAcceptsAPart_givesUp_andTheBackupIsNotReplaced() = runTest {
        val manager = newManager(thresholdBytes = 1024, chunkBytes = chunk)
        val a = localAttachment(bigPdf(15), AttachmentOwner.APPOINTMENT, 7L, "grande.pdf")
        drive.alwaysFailChunks = true

        val ok = sync(manager, snapshot(listOf(a)))

        assertFalse(ok)
        assertTrue(errorMessage(manager).contains("grande.pdf"))
        assertNull(drive.byName(GoogleDriveSyncManager.BACKUP_FILE_NAME))
        assertTrue(drive.namesStartingWith(GoogleDriveSyncManager.ATTACHMENT_FILE_PREFIX).isEmpty())
    }

    @Test
    fun constructor_refusesAPartSizeDriveWouldNotAccept() {
        try {
            GoogleDriveSyncManager(context = context, resumableChunkBytes = 1000)
            fail("Deveria recusar um tamanho que não é múltiplo de 256 KiB")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    // ------------------------------------------------------------------------------------------
    // Restauração (Drive -> aparelho)
    // ------------------------------------------------------------------------------------------

    @Test
    fun roundTrip_replaceRestore_bringsEveryAttachmentBack_withTheSameRecordsAndBytes() = runTest {
        val original = createDeviceData()
        val manager = newManager()
        assertTrue("O envio deveria dar certo: ${manager.syncState.value}", manager.syncAllRecordsToDrive(repository()))
        wipeDevice()
        assertEquals(0, repository().getAllAttachmentsSync().size)

        val ok = manager.restoreBackupFromDrive(repository(), replaceExisting = true)

        assertTrue("A restauração deveria ser completa: ${manager.syncState.value}", ok)
        val repository = repository()
        val storage = AttachmentStorage(context)
        val ofAppointment = repository.getAttachmentsForSync(AttachmentOwner.APPOINTMENT, original.appointmentId)
        assertEquals(setOf("receita.pdf", "orientacoes.pdf"), ofAppointment.map { it.displayName }.toSet())
        assertEquals(setOf("Receituário", "Orientações"), ofAppointment.map { it.label }.toSet())
        val ofExam = repository.getAttachmentsForSync(AttachmentOwner.EXAM_DOCUMENT, original.examId)
        assertEquals(listOf("laudo.png"), ofExam.map { it.displayName })
        assertEquals(0, repository.getAttachmentsForSync(AttachmentOwner.APPOINTMENT, original.otherAppointmentId).size)

        val bytesByName = mapOf(
            "receita.pdf" to original.receita, "orientacoes.pdf" to original.orientacoes, "laudo.png" to original.laudo
        )
        repository.getAllAttachmentsSync().forEach { row ->
            assertTrue("${row.displayName} deveria estar íntegro", storage.isIntact(row.localPath, row.sizeBytes))
            assertEquals(bytesByName.getValue(row.displayName).toList(), File(row.localPath).readBytes().toList())
            assertEquals(
                "Fica na pasta privada, com o nome feito do conteúdo",
                "${row.sha256}.${if (row.mimeType == "image/png") "png" else "pdf"}",
                File(row.localPath).name
            )
        }
    }

    @Test
    fun roundTrip_incrementalRestore_pointsAttachmentsAtTheNewRecordIds() = runTest {
        val original = createDeviceData()
        val manager = newManager()
        assertTrue(manager.syncAllRecordsToDrive(repository()))
        wipeDevice()
        val repository = repository()
        // Registros que já existem neste aparelho: os restaurados ganham ids novos.
        repeat(3) { repository.addAppointment(AttachmentTestSupport.newAppointment().copy(reason = "Já existia $it")) }

        val ok = manager.restoreBackupFromDrive(repository, replaceExisting = false)

        assertTrue("A restauração deveria ser completa: ${manager.syncState.value}", ok)
        val rows = repository.getAllAttachmentsSync()
        assertEquals(3, rows.size)
        val appointments = repository.getAllAppointmentsSync().associateBy { it.id }
        val ofAppointments = rows.filter { it.ownerType == AttachmentOwner.APPOINTMENT }
        assertEquals(2, ofAppointments.size)
        ofAppointments.forEach { row ->
            assertEquals(
                "O anexo tem que ir para a consulta certa, não para a que ganhou o mesmo número",
                "Retorno cardíaco",
                appointments.getValue(row.ownerId).reason
            )
            assertNotEquals("O id antigo não pode ser reaproveitado", original.appointmentId, row.ownerId)
        }
        val exams = repository.getAllExamDocumentsSync().associateBy { it.id }
        val ofExam = rows.single { it.ownerType == AttachmentOwner.EXAM_DOCUMENT }
        assertEquals("Hemograma", exams.getValue(ofExam.ownerId).name)
        appointments.values.filter { it.reason.startsWith("Já existia") }.forEach { existing ->
            assertEquals(0, repository.getAttachmentsForSync(AttachmentOwner.APPOINTMENT, existing.id).size)
        }
    }

    @Test
    fun restore_version1Backup_withNameBasedFiles_stillWorks() = runTest {
        val laudo = AttachmentTestSupport.pdfBytes(seed = 41)
        val fileId = drive.put("anexo_doc_1_Hemograma.pdf", laudo)
        putCloudBackup(
            backupJson(1) {
                put("examDocuments", JSONArray().put(
                    JSONObject().put("id", 1).put("name", "Hemograma").put("fileType", "PDF")
                        .put("dateString", "01/10/2026").put("category", "Exame de sangue")
                ))
                put("attachments", JSONArray().put(
                    JSONObject().put("type", "EXAM_DOCUMENT").put("targetId", 1)
                        .put("driveFileName", "anexo_doc_1_Hemograma.pdf").put("driveFileId", fileId)
                        .put("mimeType", "application/pdf").put("sizeBytes", laudo.size.toLong())
                ))
            }
        )
        val manager = newManager()

        val ok = manager.restoreBackupFromDrive(repository(), replaceExisting = true)

        assertTrue("Um backup v1 deve continuar restaurando: ${manager.syncState.value}", ok)
        val exam = repository().getAllExamDocumentsSync().single()
        assertEquals(laudo.toList(), File(exam.fileUriOrPath!!).readBytes().toList())
        assertEquals(0, repository().getAllAttachmentsSync().size)
    }

    @Test
    fun restore_fileThatDoesNotMatchItsRecordedHash_isNotAccepted_andTheRestoreIsPartial() = runTest {
        val real = AttachmentTestSupport.pdfBytes(seed = 51)
        val tampered = AttachmentTestSupport.pdfBytes(seed = 52) // mesmo tamanho, outro conteúdo
        val entry = recordEntry(real, AttachmentOwner.APPOINTMENT, 3L, "laudo.pdf", "Laudo")
        drive.files[entry.getString("driveFileId")]!!.bytes = tampered
        putCloudBackup(
            backupJson(2) {
                put("appointments", JSONArray().put(appointmentJson(3L, "Consulta restaurada")))
                put("recordAttachments", JSONArray().put(entry))
            }
        )
        val manager = newManager()

        val ok = manager.restoreBackupFromDrive(repository(), replaceExisting = true)

        assertFalse(ok)
        val state = manager.syncState.value
        assertTrue("Deveria terminar como restauração parcial, mas foi $state", state is DriveSyncState.RestorePartial)
        assertTrue((state as DriveSyncState.RestorePartial).failedFiles.any { it.contains("laudo.pdf") })
        val row = repository().getAllAttachmentsSync().single()
        assertEquals("O registro fica para o usuário ver o que falta", "laudo.pdf", row.displayName)
        assertFalse("Conteúdo errado nunca é gravado", File(row.localPath).exists())
    }

    @Test
    fun restore_invalidOrHostileEntries_areIgnored_andNothingLandsOutsideTheAttachmentsFolder() = runTest {
        val good = AttachmentTestSupport.pdfBytes(seed = 61)
        val valid = recordEntry(good, AttachmentOwner.APPOINTMENT, 3L, "bom.pdf", "Receituário")
            .put("localPath", "/etc/passwd") // um backup não escolhe onde o arquivo vai
        val badHash = recordEntry(good, AttachmentOwner.APPOINTMENT, 3L, "hash.pdf").put("sha256", "abc")
        val badOwnerType = recordEntry(good, "ALGO_ESTRANHO", 3L, "tipo.pdf")
        val orphan = recordEntry(good, AttachmentOwner.APPOINTMENT, 99L, "sem-dono.pdf")
        putCloudBackup(
            backupJson(2) {
                put("appointments", JSONArray().put(appointmentJson(3L, "Consulta restaurada")))
                put("recordAttachments", JSONArray().put(valid).put(badHash).put(badOwnerType).put(orphan))
            }
        )
        val manager = newManager()

        val ok = manager.restoreBackupFromDrive(repository(), replaceExisting = true)

        assertFalse("Há entradas inválidas: a restauração é parcial", ok)
        val rows = repository().getAllAttachmentsSync()
        assertEquals(listOf("bom.pdf"), rows.map { it.displayName })
        val local = File(rows.single().localPath)
        assertEquals(AttachmentTestSupport.attachmentsDir(context).canonicalFile, local.canonicalFile.parentFile)
        assertEquals("${AttachmentTestSupport.sha256Hex(good)}.pdf", local.name)
        val failed = (manager.syncState.value as DriveSyncState.RestorePartial).failedFiles
        assertTrue("Cada entrada recusada é relatada: $failed", failed.size >= 3)
    }

    @Test
    fun preview_countsPhotoAndFilesAndRecordAttachments() = runTest {
        val bytes = AttachmentTestSupport.pdfBytes(seed = 71)
        putCloudBackup(
            backupJson(2) {
                put("attachments", JSONArray().put(JSONObject().put("type", "PROFILE_PHOTO")))
                put("recordAttachments", JSONArray()
                    .put(recordEntry(bytes, AttachmentOwner.APPOINTMENT, 1L, "a.pdf"))
                    .put(recordEntry(bytes, AttachmentOwner.EXAM_DOCUMENT, 2L, "b.pdf")))
            }
        )

        val preview = newManager().getBackupPreviewFromDrive(email)

        assertEquals(2, preview.backupVersion)
        assertEquals(3, preview.attachmentCount)
    }

    // ------------------------------------------------------------------------------------------
    // Cópia de segurança do aparelho agora leva os arquivos
    // ------------------------------------------------------------------------------------------

    @Test
    fun restoreOverLocalData_deviceSafetyCopy_alsoKeepsTheAttachmentFiles() = runTest {
        val original = createDeviceData()
        repository().addGlucoseRecord(GlucoseRecord(dateString = "13/10/2026", timeString = "08:00", value = 105.0, mealRelation = "Em jejum"))
        putCloudBackup(backupJson(2))
        val manager = newManager()

        val ok = manager.restoreBackupFromDrive(repository(), replaceExisting = true, protectLocalData = true)

        assertTrue("A restauração deveria dar certo: ${manager.syncState.value}", ok)
        assertEquals("Os dados da nuvem substituíram os do aparelho", 0, repository().getAllAttachmentsSync().size)
        val copyName = drive.namesStartingWith(GoogleDriveSyncManager.SAFETY_COPY_DEVICE_PREFIX).single()
        val copy = drive.json(copyName)
        assertEquals(3, copy.getJSONArray("recordAttachments").length())
        listOf(original.receita to "pdf", original.orientacoes to "pdf", original.laudo to "png").forEach { (bytes, ext) ->
            assertEquals(
                "O documento sobrevive na nuvem",
                bytes.toList(),
                drive.byName(cloudName(bytes, ext))!!.bytes.toList()
            )
        }
    }

    @Test
    fun restoreOverLocalData_ifTheFilesCannotBeSavedFirst_nothingIsChanged() = runTest {
        createDeviceData()
        putCloudBackup(backupJson(2))
        drive.failCreatesWhoseNameContains = GoogleDriveSyncManager.ATTACHMENT_FILE_PREFIX
        val manager = newManager()
        val before = repository().getBackupSnapshot()

        val ok = manager.restoreBackupFromDrive(repository(), replaceExisting = true, protectLocalData = true)

        assertFalse(ok)
        assertTrue(errorMessage(manager).contains("nada foi alterado"))
        assertEquals("O aparelho continua como estava", before, repository().getBackupSnapshot())
        assertTrue(drive.namesStartingWith(GoogleDriveSyncManager.SAFETY_COPY_DEVICE_PREFIX).isEmpty())
    }
}
