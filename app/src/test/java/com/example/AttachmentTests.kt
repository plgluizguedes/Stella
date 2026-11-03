package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.attachments.AddAttachmentResult
import com.example.data.attachments.AttachmentLimits
import com.example.data.attachments.AttachmentManager
import com.example.data.attachments.AttachmentRejectReason
import com.example.data.attachments.AttachmentStorage
import com.example.data.attachments.ImportResult
import com.example.data.attachments.StageResult
import com.example.data.attachments.StagedAttachment
import com.example.data.db.AppDatabase
import com.example.data.drive.DriveAccountInfo
import com.example.data.drive.DriveAuthGateway
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.model.Attachment
import com.example.data.model.AttachmentOwner
import com.example.data.model.ExamDocument
import com.example.data.model.StellaProfile
import com.example.data.model.VeterinaryAppointment
import com.example.data.repository.StellaRepository
import com.example.viewmodel.StellaViewModel
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest

/** Arquivos de teste e limpeza compartilhados pelas classes de teste de anexos. */
internal object AttachmentTestSupport {

    fun pdfBytes(seed: Int = 0, extra: Int = 100): ByteArray =
        "%PDF-1.4\n".toByteArray(Charsets.UTF_8) + ByteArray(extra) { ((it + seed) % 251).toByte() }

    fun pngBytes(seed: Int = 0): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(60) { ((it + seed) % 251).toByte() }

    fun jpgBytes(seed: Int = 0): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(60) { ((it + seed) % 251).toByte() }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Arquivo "escolhido pelo usuário", fora da pasta de anexos do app. */
    fun source(context: Context, name: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "attachment_sources").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    fun attachmentsDir(context: Context): File = File(context.filesDir, "attachments")

    fun wipeAttachmentsDir(context: Context) {
        attachmentsDir(context).listFiles()?.forEach { it.delete() }
    }

    fun filesInAttachmentsDir(context: Context): List<File> =
        attachmentsDir(context).listFiles()?.filter { it.isFile } ?: emptyList()

    /** O Room é um singleton compartilhado: sem zerar, um teste vaza dados para o seguinte. */
    fun resetDatabase(context: Context) {
        val db = AppDatabase.getDatabase(context)
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
            StellaRepository(db.stellaDao()).saveProfile(StellaProfile())
        }
    }

    fun newAppointment() = VeterinaryAppointment(
        dateString = "14/10/2026",
        timeString = "10:00",
        vetName = "Dra. Teste",
        clinicName = "Clínica Teste",
        reason = "Rotina"
    )

    fun newExamDocument() = ExamDocument(
        name = "Hemograma",
        dateString = "14/10/2026",
        category = "Exame de sangue"
    )
}

// =============================================================================================
// 1. ARMAZENAMENTO: cópia para a pasta privada, tipo, tamanho, caminhos seguros, limpeza
// =============================================================================================

@RunWith(RobolectricTestRunner::class)
class AttachmentStorageTest {

    private lateinit var context: Context
    private lateinit var storage: AttachmentStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AttachmentTestSupport.wipeAttachmentsDir(context)
        storage = AttachmentStorage(context)
    }

    private fun imported(result: ImportResult): com.example.data.attachments.ImportedFile {
        assertTrue("Deveria ter importado, mas foi $result", result is ImportResult.Imported)
        return (result as ImportResult.Imported).file
    }

    @Test
    fun import_pdf_copiesIntoPrivateFolderNamedByContentHash() {
        val bytes = AttachmentTestSupport.pdfBytes()
        val source = AttachmentTestSupport.source(context, "receita da consulta.pdf", bytes)

        val file = imported(storage.importFromUri(source.absolutePath))

        val expectedHash = AttachmentTestSupport.sha256Hex(bytes)
        assertEquals("application/pdf", file.mimeType)
        assertEquals(bytes.size.toLong(), file.sizeBytes)
        assertEquals(expectedHash, file.sha256)
        assertEquals("receita da consulta.pdf", file.displayName)
        val stored = File(file.localPath)
        assertEquals("$expectedHash.pdf", stored.name)
        assertEquals(AttachmentTestSupport.attachmentsDir(context).canonicalFile, stored.canonicalFile.parentFile)
        assertTrue("A cópia tem de ter o mesmo conteúdo", stored.readBytes().contentEquals(bytes))
        assertTrue("O original não pode ser tocado", source.exists())
    }

    @Test
    fun import_sameContentTwice_storesOnlyOneFile() {
        val bytes = AttachmentTestSupport.pdfBytes(seed = 3)
        val a = AttachmentTestSupport.source(context, "a.pdf", bytes)
        val b = AttachmentTestSupport.source(context, "b.pdf", bytes)

        val first = imported(storage.importFromUri(a.absolutePath))
        val second = imported(storage.importFromUri(b.absolutePath))

        assertEquals(first.localPath, second.localPath)
        assertEquals("b.pdf", second.displayName)
        assertEquals(1, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun import_detectsPngAndJpegByContent_notByName() {
        val png = AttachmentTestSupport.source(context, "foto_sem_extensao", AttachmentTestSupport.pngBytes())
        val jpg = AttachmentTestSupport.source(context, "foto.dat", AttachmentTestSupport.jpgBytes())

        val pngFile = imported(storage.importFromUri(png.absolutePath))
        val jpgFile = imported(storage.importFromUri(jpg.absolutePath))

        assertEquals("image/png", pngFile.mimeType)
        assertTrue(File(pngFile.localPath).name.endsWith(".png"))
        assertEquals("image/jpeg", jpgFile.mimeType)
        assertTrue(File(jpgFile.localPath).name.endsWith(".jpg"))
    }

    @Test
    fun import_fileUri_works() {
        val source = AttachmentTestSupport.source(context, "uri.pdf", AttachmentTestSupport.pdfBytes(seed = 5))

        val file = imported(storage.importFromUri("file://" + source.absolutePath))

        assertEquals("application/pdf", file.mimeType)
        assertTrue(File(file.localPath).isFile)
    }

    @Test
    fun import_unsupportedType_isRejectedAndLeavesNothingBehind() {
        val text = AttachmentTestSupport.source(context, "notas.txt", "isto é só texto, não um PDF".toByteArray())

        val result = storage.importFromUri(text.absolutePath)

        assertTrue(result is ImportResult.Rejected)
        assertEquals(AttachmentRejectReason.UNSUPPORTED_TYPE, (result as ImportResult.Rejected).reason)
        assertTrue("Deve explicar quais tipos valem", result.message.contains("PDF"))
        assertEquals("Nenhum arquivo (nem temporário) pode sobrar", 0, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun import_tooLarge_isRejectedAndLeavesNothingBehind() {
        val small = AttachmentStorage(context, maxFileBytes = 1024)
        val big = AttachmentTestSupport.source(context, "grande.pdf", AttachmentTestSupport.pdfBytes(extra = 5000))

        val result = small.importFromUri(big.absolutePath)

        assertTrue(result is ImportResult.Rejected)
        assertEquals(AttachmentRejectReason.TOO_LARGE, (result as ImportResult.Rejected).reason)
        assertTrue("Deve citar o limite, mas foi '${result.message}'", result.message.contains("1 KB"))
        assertEquals(0, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun import_fileExactlyAtTheLimit_isAccepted() {
        val bytes = AttachmentTestSupport.pdfBytes(extra = 1024 - 9)
        assertEquals(1024, bytes.size)
        val exact = AttachmentStorage(context, maxFileBytes = 1024)
        val source = AttachmentTestSupport.source(context, "no_limite.pdf", bytes)

        assertTrue(exact.importFromUri(source.absolutePath) is ImportResult.Imported)
    }

    @Test
    fun import_emptyFile_isRejected() {
        val empty = AttachmentTestSupport.source(context, "vazio.pdf", ByteArray(0))

        val result = storage.importFromUri(empty.absolutePath)

        assertEquals(AttachmentRejectReason.EMPTY, (result as ImportResult.Rejected).reason)
        assertEquals(0, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun import_missingFile_isRejectedAsUnreadable() {
        val result = storage.importFromUri(File(context.cacheDir, "nao_existe.pdf").absolutePath)

        assertEquals(AttachmentRejectReason.UNREADABLE, (result as ImportResult.Rejected).reason)
        assertEquals(0, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun import_imagesOnly_rejectsPdfWithImageMessage() {
        val pdf = AttachmentTestSupport.source(context, "foto_errada.pdf", AttachmentTestSupport.pdfBytes())

        val result = storage.importFromUri(pdf.absolutePath, AttachmentLimits.IMAGE_MIME_TYPES)

        assertEquals(AttachmentRejectReason.UNSUPPORTED_TYPE, (result as ImportResult.Rejected).reason)
        assertTrue(result.message.contains("imagem"))
    }

    @Test
    fun paths_outsideTheAttachmentsFolder_areNeverReadOrDeleted() {
        val outside = AttachmentTestSupport.source(context, "fora.pdf", AttachmentTestSupport.pdfBytes())

        assertNull(storage.fileFor(outside.absolutePath))
        assertFalse(storage.deleteFile(outside.absolutePath))
        assertTrue("O arquivo de fora não pode ser apagado", outside.exists())
        assertFalse(storage.isIntact(outside.absolutePath, outside.length()))
    }

    @Test
    fun paths_withParentTraversal_areRefused() {
        val outside = AttachmentTestSupport.source(context, "fora2.pdf", AttachmentTestSupport.pdfBytes())
        val sneaky = AttachmentTestSupport.attachmentsDir(context).absolutePath + "/../attachment_sources/fora2.pdf"

        assertNull(storage.fileFor(sneaky))
        assertFalse(storage.deleteFile(sneaky))
        assertTrue(outside.exists())
    }

    @Test
    fun isIntact_detectsMissingAndChangedFiles() {
        val source = AttachmentTestSupport.source(context, "integro.pdf", AttachmentTestSupport.pdfBytes())
        val file = imported(storage.importFromUri(source.absolutePath))

        assertTrue(storage.isIntact(file.localPath, file.sizeBytes))

        File(file.localPath).writeBytes(ByteArray(3))
        assertFalse("Tamanho diferente do registrado", storage.isIntact(file.localPath, file.sizeBytes))

        File(file.localPath).delete()
        assertFalse("Arquivo sumiu", storage.isIntact(file.localPath, file.sizeBytes))
    }

    @Test
    fun cleanup_removesOnlyOldUnreferencedContentFilesAndInterruptedCopies() {
        val dir = storage.directory.apply { mkdirs() }
        val now = 1_700_000_000_000L
        val hour = 60L * 60 * 1000
        fun make(name: String, ageHours: Long) = File(dir, name).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(now - ageHours * hour)
        }
        val orphanOld = make("a".repeat(64) + ".pdf", 48)
        val orphanRecent = make("b".repeat(64) + ".pdf", 1)
        val referencedOld = make("c".repeat(64) + ".pdf", 48)
        val foreignName = make("notas.txt", 48)
        val leftoverOld = make("import_123.part", 48)
        val leftoverRecent = make("import_456.part", 1)

        val deleted = storage.cleanupUnreferenced(setOf(referencedOld.absolutePath), nowMillis = now)

        assertEquals(2, deleted)
        assertFalse("Órfão antigo deve sumir", orphanOld.exists())
        assertFalse("Cópia interrompida antiga deve sumir", leftoverOld.exists())
        assertTrue("Órfão recente pode estar sendo salvo agora", orphanRecent.exists())
        assertTrue("Cópia interrompida recente pode estar em andamento", leftoverRecent.exists())
        assertTrue("Arquivo referenciado nunca é apagado", referencedOld.exists())
        assertTrue("Arquivo que não segue o nome por conteúdo nunca é tocado", foreignName.exists())
    }

    @Test
    fun openIntent_returnsViewIntentForStoredFile_andNullForMissing() {
        val source = AttachmentTestSupport.source(context, "abrir.pdf", AttachmentTestSupport.pdfBytes())
        val file = imported(storage.importFromUri(source.absolutePath))

        val intent = storage.openIntent(file.localPath, file.mimeType)

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("content", intent.data?.scheme)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        File(file.localPath).delete()
        assertNull(storage.openIntent(file.localPath, file.mimeType))
    }
}

// =============================================================================================
// 2. GERENCIADOR: vários anexos por registro, limite, compartilhamento de arquivo, quebrados
// =============================================================================================

@RunWith(RobolectricTestRunner::class)
class AttachmentManagerTest {

    private lateinit var context: Context
    private lateinit var repository: StellaRepository
    private lateinit var storage: AttachmentStorage
    private lateinit var manager: AttachmentManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AttachmentTestSupport.wipeAttachmentsDir(context)
        AttachmentTestSupport.resetDatabase(context)
        repository = StellaRepository(AppDatabase.getDatabase(context).stellaDao())
        storage = AttachmentStorage(context)
        manager = AttachmentManager(repository, storage, maxFilesPerRecord = 2)
    }

    // O banco é um singleton compartilhado: anexos deixados aqui entrariam no backup dos testes de
    // sincronização que rodam depois e os fariam falhar.
    @After
    fun tearDown() {
        AttachmentTestSupport.resetDatabase(context)
        AttachmentTestSupport.wipeAttachmentsDir(context)
    }

    private suspend fun stage(name: String, bytes: ByteArray, label: String = "Outro"): StagedAttachment {
        val source = AttachmentTestSupport.source(context, name, bytes)
        val result = manager.stage(source.absolutePath, label)
        assertTrue("Deveria ter preparado '$name', mas foi $result", result is StageResult.Staged)
        return (result as StageResult.Staged).attachment
    }

    @Test
    fun stageAndCommit_severalFilesToOneAppointment_keepsOrderAndLabels() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val receita = stage("receita.pdf", AttachmentTestSupport.pdfBytes(1), "Receituário")
        val orientacoes = stage("orientacoes.pdf", AttachmentTestSupport.pdfBytes(2), "Orientações")

        val result = manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(receita, orientacoes))

        assertEquals(2, result.added.size)
        assertTrue(result.notAdded.isEmpty())
        assertNull(result.reason)
        val rows = repository.getAttachmentsForSync(AttachmentOwner.APPOINTMENT, appointmentId)
        assertEquals(listOf("Receituário", "Orientações"), rows.map { it.label })
        assertEquals(listOf("receita.pdf", "orientacoes.pdf"), rows.map { it.displayName })
        assertTrue(rows.all { it.ownerId == appointmentId && File(it.localPath).isFile })
    }

    @Test
    fun commit_toExamDocument_works() = runTest {
        val docId = repository.addExamDocument(AttachmentTestSupport.newExamDocument())
        val laudo = stage("laudo.pdf", AttachmentTestSupport.pdfBytes(3), "Laudo")

        val result = manager.commit(AttachmentOwner.EXAM_DOCUMENT, docId, listOf(laudo))

        assertEquals(1, result.added.size)
        assertEquals(1, repository.getAttachmentsForSync(AttachmentOwner.EXAM_DOCUMENT, docId).size)
        assertEquals(
            "Os anexos de um tipo de registro não podem aparecer no outro",
            0,
            repository.getAttachmentsForSync(AttachmentOwner.APPOINTMENT, docId).size
        )
    }

    @Test
    fun commit_overTheLimit_storesTheFirstOnes_rejectsTheRest_andRemovesTheirFiles() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val one = stage("1.pdf", AttachmentTestSupport.pdfBytes(11))
        val two = stage("2.pdf", AttachmentTestSupport.pdfBytes(12))
        val three = stage("3.pdf", AttachmentTestSupport.pdfBytes(13))

        val result = manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(one, two, three))

        assertEquals(listOf("1.pdf", "2.pdf"), result.added.map { it.displayName })
        assertEquals(listOf("3.pdf"), result.notAdded.map { it.displayName })
        assertEquals(AttachmentRejectReason.TOO_MANY, result.reason)
        assertTrue("A mensagem deve citar o limite, mas foi '${result.message}'", result.message.orEmpty().contains("2"))
        assertTrue(File(one.localPath).isFile)
        assertTrue(File(two.localPath).isFile)
        assertFalse("O arquivo que não coube não pode ficar sobrando", File(three.localPath).exists())
    }

    @Test
    fun commit_limitIsPerRecord_acrossSeveralCommits() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val otherId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        manager.commit(
            AttachmentOwner.APPOINTMENT, appointmentId,
            listOf(stage("a.pdf", AttachmentTestSupport.pdfBytes(21)), stage("b.pdf", AttachmentTestSupport.pdfBytes(22)))
        )

        val third = manager.commit(
            AttachmentOwner.APPOINTMENT, appointmentId, listOf(stage("c.pdf", AttachmentTestSupport.pdfBytes(23)))
        )
        val otherRecord = manager.commit(
            AttachmentOwner.APPOINTMENT, otherId, listOf(stage("d.pdf", AttachmentTestSupport.pdfBytes(24)))
        )

        assertEquals(AttachmentRejectReason.TOO_MANY, third.reason)
        assertEquals("Outro registro tem o seu próprio limite", 1, otherRecord.added.size)
    }

    @Test
    fun commit_toMissingOwner_isRejectedAndRemovesTheFile() = runTest {
        val staged = stage("orfao.pdf", AttachmentTestSupport.pdfBytes(31))

        val result = manager.commit(AttachmentOwner.APPOINTMENT, 9999L, listOf(staged))

        assertEquals(AttachmentRejectReason.OWNER_NOT_FOUND, result.reason)
        assertTrue(result.added.isEmpty())
        assertFalse(File(staged.localPath).exists())
        assertEquals(0, repository.getAllAttachmentsSync().size)
    }

    @Test
    fun commit_withUnknownOwnerType_isRejected() = runTest {
        val staged = stage("tipo.pdf", AttachmentTestSupport.pdfBytes(32))

        val result = manager.commit("VACINA", 1L, listOf(staged))

        assertEquals(AttachmentRejectReason.OWNER_NOT_FOUND, result.reason)
        assertEquals(0, repository.getAllAttachmentsSync().size)
    }

    @Test
    fun remove_keepsTheSharedFileUntilTheLastAttachmentIsRemoved() = runTest {
        val first = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val second = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val sameBytes = AttachmentTestSupport.pdfBytes(41)
        val a = manager.commit(AttachmentOwner.APPOINTMENT, first, listOf(stage("igual1.pdf", sameBytes))).added.single()
        val b = manager.commit(AttachmentOwner.APPOINTMENT, second, listOf(stage("igual2.pdf", sameBytes))).added.single()
        assertEquals("O mesmo conteúdo é guardado uma vez só", a.localPath, b.localPath)

        assertTrue(manager.remove(a.id))
        assertTrue("Ainda é usado pelo outro anexo", File(b.localPath).isFile)

        assertTrue(manager.remove(b.id))
        assertFalse("Ninguém usa mais: o arquivo some", File(b.localPath).exists())
        assertFalse("Remover um anexo que não existe devolve falso", manager.remove(b.id))
    }

    @Test
    fun discardStaged_removesTheFile_unlessSomethingElseUsesIt() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val shared = AttachmentTestSupport.pdfBytes(51)
        manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(stage("usado.pdf", shared)))
        val alone = stage("sozinho.pdf", AttachmentTestSupport.pdfBytes(52))
        val sameAsUsed = stage("usado_de_novo.pdf", shared)

        manager.discardStaged(alone)
        manager.discardStaged(sameAsUsed)

        assertFalse("Ninguém usa: some", File(alone.localPath).exists())
        assertTrue("Já anexado a um registro: fica", File(sameAsUsed.localPath).isFile)
    }

    @Test
    fun removingAnAttachment_doesNotDeleteAFileThatAnOpenDialogIsAboutToSave() = runTest {
        val first = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val second = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val bytes = AttachmentTestSupport.pdfBytes(61)
        val attached = manager.commit(AttachmentOwner.APPOINTMENT, first, listOf(stage("x.pdf", bytes))).added.single()

        val stagedElsewhere = stage("x_copia.pdf", bytes) // outro diálogo, mesmo conteúdo
        manager.remove(attached.id)

        assertTrue("O diálogo ainda vai salvar este arquivo", File(stagedElsewhere.localPath).isFile)
        val result = manager.commit(AttachmentOwner.APPOINTMENT, second, listOf(stagedElsewhere))
        assertEquals(1, result.added.size)
        assertTrue(File(result.added.single().localPath).isFile)
    }

    @Test
    fun findBroken_listsAttachmentsWhoseFileIsMissingOrChanged() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val ok = stage("ok.pdf", AttachmentTestSupport.pdfBytes(71))
        val missing = stage("sumiu.pdf", AttachmentTestSupport.pdfBytes(72))
        manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(ok, missing))
        assertTrue(manager.findBroken().isEmpty())

        File(missing.localPath).delete()
        val broken = manager.findBroken()

        assertEquals(listOf("sumiu.pdf"), broken.map { it.displayName })
    }

    @Test
    fun deletingTheOwner_removesItsAttachmentRows_andTheFilesCanBeCleaned() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val docId = repository.addExamDocument(AttachmentTestSupport.newExamDocument())
        val a = manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(stage("c.pdf", AttachmentTestSupport.pdfBytes(81)))).added.single()
        manager.commit(AttachmentOwner.EXAM_DOCUMENT, docId, listOf(stage("e.pdf", AttachmentTestSupport.pdfBytes(82))))

        repository.deleteAppointment(appointmentId)
        manager.deleteFilesIfUnreferenced(listOf(a.localPath))

        assertEquals(0, repository.getAttachmentsForSync(AttachmentOwner.APPOINTMENT, appointmentId).size)
        assertFalse(File(a.localPath).exists())
        assertEquals("O outro registro não é afetado", 1, repository.getAttachmentsForSync(AttachmentOwner.EXAM_DOCUMENT, docId).size)

        repository.deleteExamDocument(docId)
        assertEquals(0, repository.getAllAttachmentsSync().size)
    }

    @Test
    fun cleanupUnreferenced_keepsEverythingStillInUse() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val attached = manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(stage("anexo.pdf", AttachmentTestSupport.pdfBytes(91)))).added.single()
        val staged = stage("diálogo.pdf", AttachmentTestSupport.pdfBytes(92))
        val photo = (storage.importFromUri(
            AttachmentTestSupport.source(context, "foto.png", AttachmentTestSupport.pngBytes(93)).absolutePath
        ) as ImportResult.Imported).file
        repository.saveProfile(StellaProfile(photoUri = photo.localPath))
        val legacy = (storage.importFromUri(
            AttachmentTestSupport.source(context, "antigo.pdf", AttachmentTestSupport.pdfBytes(94)).absolutePath
        ) as ImportResult.Imported).file
        repository.addExamDocument(AttachmentTestSupport.newExamDocument().copy(fileUriOrPath = legacy.localPath))
        val orphan = (storage.importFromUri(
            AttachmentTestSupport.source(context, "lixo.pdf", AttachmentTestSupport.pdfBytes(95)).absolutePath
        ) as ImportResult.Imported).file
        val old = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        listOf(attached.localPath, staged.localPath, photo.localPath, legacy.localPath, orphan.localPath)
            .forEach { File(it).setLastModified(old) }

        val deleted = manager.cleanupUnreferenced()

        assertEquals(1, deleted)
        assertFalse("Só o órfão antigo some", File(orphan.localPath).exists())
        assertTrue(File(attached.localPath).isFile)
        assertTrue("Arquivo de diálogo aberto fica", File(staged.localPath).isFile)
        assertTrue("Foto do perfil fica", File(photo.localPath).isFile)
        assertTrue("Campo antigo de arquivo do documento fica", File(legacy.localPath).isFile)
    }

    @Test
    fun restoreWithReplace_clearsAttachmentRows_soTheyNeverPointAtTheWrongRecord() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(stage("r.pdf", AttachmentTestSupport.pdfBytes(101))))
        assertEquals(1, repository.getAllAttachmentsSync().size)

        repository.restoreDatabase(
            profile = StellaProfile(),
            glucoseList = emptyList(),
            medications = emptyList(),
            administrations = emptyList(),
            appointments = emptyList(),
            documents = emptyList(),
            feeding = emptyList(),
            weights = emptyList(),
            vaccines = emptyList(),
            reminders = emptyList(),
            replaceExisting = true
        )

        assertEquals(0, repository.getAllAttachmentsSync().size)
    }

    @Test
    fun backupSnapshot_includesAttachments() = runTest {
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        manager.commit(AttachmentOwner.APPOINTMENT, appointmentId, listOf(stage("s.pdf", AttachmentTestSupport.pdfBytes(111))))

        val snapshot = repository.getBackupSnapshot()

        assertEquals(1, snapshot.attachments.size)
        assertEquals("s.pdf", snapshot.attachments.single().displayName)
    }
}

// =============================================================================================
// 3. MIGRAÇÃO 1 -> 2 do banco (tabela de anexos)
// =============================================================================================

@RunWith(RobolectricTestRunner::class)
class AttachmentMigrationTest {

    /**
     * O banco de verdade nunca teve a tabela de anexos antes desta versão. O teste cria o banco no formato
     * novo, volta ele ao formato antigo (sem a tabela, user_version = 1) e o abre de novo com a migração.
     * O Room confere, ao abrir, que TODAS as tabelas batem com o esperado: se o SQL da migração estiver
     * diferente da entidade, a abertura falha.
     */
    @Test
    fun migration1To2_addsAttachmentsTable_andKeepsExistingData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_db"
        context.deleteDatabase(dbName)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

        runBlocking {
            val before = open()
            val daoBefore = before.stellaDao()
            daoBefore.saveProfile(StellaProfile(name = "Stella Antiga"))
            val docId = daoBefore.insertExamDocument(AttachmentTestSupport.newExamDocument())
            daoBefore.insertAppointment(AttachmentTestSupport.newAppointment())
            before.close()

            val raw = SQLiteDatabase.openDatabase(
                context.getDatabasePath(dbName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            raw.execSQL("DROP TABLE attachments")
            raw.execSQL("PRAGMA user_version = 1")
            raw.close()

            val after = open()
            val daoAfter = after.stellaDao()
            assertEquals("Stella Antiga", daoAfter.getProfileSync()?.name)
            assertEquals(1, daoAfter.getAllExamDocumentsSync().size)
            assertEquals(1, daoAfter.getAllAppointmentsSync().size)
            assertEquals(0, daoAfter.getAllAttachmentsSync().size)

            val newId = daoAfter.insertAttachment(
                Attachment(
                    ownerType = AttachmentOwner.EXAM_DOCUMENT,
                    ownerId = docId,
                    displayName = "laudo.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 10,
                    sha256 = "abc",
                    localPath = "/x/abc.pdf"
                )
            )
            assertTrue(newId > 0)
            assertEquals(1, daoAfter.countAttachmentsFor(AttachmentOwner.EXAM_DOCUMENT, docId))
            after.close()
        }
        context.deleteDatabase(dbName)
    }
}

// =============================================================================================
// 4. VIEWMODEL: a API que a tela chama
// =============================================================================================

@RunWith(RobolectricTestRunner::class)
class AttachmentViewModelTest {

    private lateinit var context: Context
    private lateinit var repository: StellaRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AttachmentTestSupport.wipeAttachmentsDir(context)
        AttachmentTestSupport.resetDatabase(context)
        repository = StellaRepository(AppDatabase.getDatabase(context).stellaDao())
    }

    // Mesmo motivo do AttachmentManagerTest: não deixar anexos no banco compartilhado.
    @After
    fun tearDown() {
        AttachmentTestSupport.resetDatabase(context)
        AttachmentTestSupport.wipeAttachmentsDir(context)
    }

    private fun viewModel(): StellaViewModel {
        val gateway = object : DriveAuthGateway {
            override fun getConnectedAccount(): DriveAccountInfo? = null
            override fun hasRequiredScope() = false
            override fun checkAccountHasScope(account: GoogleSignInAccount) = false
            override fun getToken() = "test-token"
            override fun invalidateToken(token: String) {}
            override fun signOut(onComplete: () -> Unit) { onComplete() }
        }
        val manager = GoogleDriveSyncManager(
            context = context,
            httpClient = GoogleDriveSyncManager.defaultHttpClient(),
            tokenProvider = { "test-token" },
            driveApiBaseUrl = "http://localhost:1",
            driveUploadBaseUrl = "http://localhost:1",
            authGateway = gateway
        )
        return StellaViewModel(context as Application, customDriveSyncManager = manager)
    }

    private suspend fun awaitUntil(maxLoops: Int = 400, condition: () -> Boolean): Boolean {
        repeat(maxLoops) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            withContext(Dispatchers.Default) { delay(25) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun rows(): List<Attachment> = runBlocking { repository.getAllAttachmentsSync() }

    private suspend fun stageVia(vm: StellaViewModel, name: String, bytes: ByteArray, label: String = "Outro"): StagedAttachment {
        val source = AttachmentTestSupport.source(context, name, bytes)
        var result: StageResult? = null
        vm.stageAttachment(source.absolutePath, label) { result = it }
        assertTrue("O arquivo '$name' deve ser preparado", awaitUntil { result != null })
        assertTrue("Deveria ter preparado '$name', mas foi $result", result is StageResult.Staged)
        return (result as StageResult.Staged).attachment
    }

    @Test
    fun addExamDocument_withStagedFiles_attachesAllOfThem() = runTest {
        val vm = viewModel()
        val a = stageVia(vm, "a.pdf", AttachmentTestSupport.pdfBytes(1), "Receituário")
        val b = stageVia(vm, "b.jpg", AttachmentTestSupport.jpgBytes(2), "Exame")

        vm.addExamDocument(
            name = "Consulta de hoje", fileType = "PDF", dateString = "14/10/2026", clinicName = "", vetName = "",
            category = "Receita", notes = "", fileUri = null, attachments = listOf(a, b)
        )

        assertTrue("Os dois anexos devem ser gravados", awaitUntil { rows().size == 2 })
        val docId = runBlocking { repository.getAllExamDocumentsSync().single().id }
        val saved = rows()
        assertTrue(saved.all { it.ownerType == AttachmentOwner.EXAM_DOCUMENT && it.ownerId == docId })
        assertEquals(setOf("Receituário", "Exame"), saved.map { it.label }.toSet())
        assertNull(vm.uiErrorMessage.value)
    }

    @Test
    fun addAppointment_withStagedFiles_attachesAllOfThem() = runTest {
        val vm = viewModel()
        val a = stageVia(vm, "r.pdf", AttachmentTestSupport.pdfBytes(3), "Receituário")
        val b = stageVia(vm, "o.pdf", AttachmentTestSupport.pdfBytes(4), "Orientações")
        val c = stageVia(vm, "e.png", AttachmentTestSupport.pngBytes(5), "Exame")

        val accepted = vm.addAppointment(
            dateString = "14/10/2026", timeString = "10:00", vetName = "Dra. Teste", clinicName = "Clínica",
            reason = "Rotina", weightKg = null, temperatureC = null, notes = "", symptomsReported = "",
            conductRecommended = "", medicationsPrescribed = "", nextAppointmentDate = "",
            attachments = listOf(a, b, c)
        )

        assertTrue(accepted)
        assertTrue("Os três arquivos da mesma consulta devem ser anexados", awaitUntil { rows().size == 3 })
        val appointmentId = runBlocking { repository.getAllAppointmentsSync().single().id }
        assertTrue(rows().all { it.ownerType == AttachmentOwner.APPOINTMENT && it.ownerId == appointmentId })
    }

    @Test
    fun addAppointment_withMoreFilesThanTheLimit_keepsTheLimitAndReportsTheRest() = runTest {
        val vm = viewModel()
        val limit = AttachmentLimits.MAX_FILES_PER_RECORD
        val staged = (1..limit + 1).map { i -> stageVia(vm, "f$i.pdf", AttachmentTestSupport.pdfBytes(200 + i)) }

        vm.addAppointment(
            dateString = "14/10/2026", timeString = "10:00", vetName = "Dra. Teste", clinicName = "Clínica",
            reason = "Rotina", weightKg = null, temperatureC = null, notes = "", symptomsReported = "",
            conductRecommended = "", medicationsPrescribed = "", nextAppointmentDate = "",
            attachments = staged
        )

        assertTrue(awaitUntil { vm.uiErrorMessage.value != null })
        assertEquals(limit, rows().size)
        assertTrue(vm.uiErrorMessage.value.orEmpty().contains("$limit"))
        assertEquals(
            "O arquivo que não coube não pode ficar na pasta",
            limit,
            AttachmentTestSupport.filesInAttachmentsDir(context).size
        )
    }

    @Test
    fun deleteExamDocument_removesItsAttachmentsAndFiles() = runTest {
        val vm = viewModel()
        val a = stageVia(vm, "x.pdf", AttachmentTestSupport.pdfBytes(7))
        vm.addExamDocument(
            name = "Para apagar", fileType = "PDF", dateString = "14/10/2026", clinicName = "", vetName = "",
            category = "Exame de sangue", notes = "", fileUri = null, attachments = listOf(a)
        )
        assertTrue(awaitUntil { rows().size == 1 })
        val docId = runBlocking { repository.getAllExamDocumentsSync().single().id }

        vm.deleteExamDocument(docId)

        assertTrue("Os anexos do documento apagado devem sumir", awaitUntil { rows().isEmpty() })
        assertTrue("O arquivo também", awaitUntil { AttachmentTestSupport.filesInAttachmentsDir(context).isEmpty() })
    }

    @Test
    fun addAttachment_toExistingRecord_andRemove() = runTest {
        val vm = viewModel()
        val appointmentId = repository.addAppointment(AttachmentTestSupport.newAppointment())
        val source = AttachmentTestSupport.source(context, "depois.pdf", AttachmentTestSupport.pdfBytes(8))

        var added: AddAttachmentResult? = null
        vm.addAttachment(AttachmentOwner.APPOINTMENT, appointmentId, source.absolutePath, "Laudo") { added = it }
        assertTrue(awaitUntil { added != null })
        assertTrue("Deveria ter anexado, mas foi $added", added is AddAttachmentResult.Added)
        val attachment = (added as AddAttachmentResult.Added).attachment
        assertEquals("Laudo", attachment.label)
        assertEquals(1, rows().size)

        var removed: Boolean? = null
        vm.removeAttachment(attachment.id) { removed = it }
        assertTrue(awaitUntil { removed != null })
        assertEquals(true, removed)
        assertEquals(0, rows().size)
        assertEquals(0, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun addAttachment_toMissingRecord_isRejected() = runTest {
        val vm = viewModel()
        val source = AttachmentTestSupport.source(context, "sem_dono.pdf", AttachmentTestSupport.pdfBytes(9))

        var result: AddAttachmentResult? = null
        vm.addAttachment(AttachmentOwner.EXAM_DOCUMENT, 4242L, source.absolutePath) { result = it }

        assertTrue(awaitUntil { result != null })
        assertEquals(
            AttachmentRejectReason.OWNER_NOT_FOUND,
            (result as AddAttachmentResult.Rejected).reason
        )
        assertEquals(0, rows().size)
        assertEquals(0, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun setProfilePhoto_copiesTheImage_savesItsPath_andReplacesThePreviousOne() = runTest {
        val vm = viewModel()
        val first = AttachmentTestSupport.source(context, "foto1.png", AttachmentTestSupport.pngBytes(1))
        val second = AttachmentTestSupport.source(context, "foto2.jpg", AttachmentTestSupport.jpgBytes(2))

        var result1: ImportResult? = null
        vm.setProfilePhoto(first.absolutePath) { result1 = it }
        assertTrue(awaitUntil { result1 != null })
        val photo1 = (result1 as ImportResult.Imported).file
        assertEquals(photo1.localPath, runBlocking { repository.getProfileSync()?.photoUri })
        assertTrue("A foto vive na pasta privada, não no endereço do seletor", File(photo1.localPath).isFile)

        var result2: ImportResult? = null
        vm.setProfilePhoto(second.absolutePath) { result2 = it }
        assertTrue(awaitUntil { result2 != null })
        val photo2 = (result2 as ImportResult.Imported).file
        assertEquals(photo2.localPath, runBlocking { repository.getProfileSync()?.photoUri })
        assertFalse("A foto antiga, sem uso, é removida", File(photo1.localPath).exists())
        assertTrue(File(photo2.localPath).isFile)
    }

    @Test
    fun setProfilePhoto_withNonImage_isRejectedAndKeepsTheProfile() = runTest {
        val vm = viewModel()
        val pdf = AttachmentTestSupport.source(context, "nao_e_foto.pdf", AttachmentTestSupport.pdfBytes())

        var result: ImportResult? = null
        vm.setProfilePhoto(pdf.absolutePath) { result = it }

        assertTrue(awaitUntil { result != null })
        assertEquals(AttachmentRejectReason.UNSUPPORTED_TYPE, (result as ImportResult.Rejected).reason)
        assertNull(runBlocking { repository.getProfileSync()?.photoUri })
        assertEquals(0, AttachmentTestSupport.filesInAttachmentsDir(context).size)
    }

    @Test
    fun refreshBrokenAttachments_publishesTheOnesWithoutFile() = runTest {
        val vm = viewModel()
        val a = stageVia(vm, "quebra.pdf", AttachmentTestSupport.pdfBytes(12))
        vm.addExamDocument(
            name = "Doc", fileType = "PDF", dateString = "14/10/2026", clinicName = "", vetName = "",
            category = "Exame de sangue", notes = "", fileUri = null, attachments = listOf(a)
        )
        assertTrue(awaitUntil { rows().size == 1 })
        File(a.localPath).delete()

        vm.refreshBrokenAttachments()

        assertTrue(awaitUntil { vm.brokenAttachments.value.size == 1 })
        assertEquals("quebra.pdf", vm.brokenAttachments.value.single().displayName)
    }
}
