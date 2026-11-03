package com.example.data.attachments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.data.drive.GoogleDriveSyncManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Limits and defaults the screens show to the user. They live here, in one place. */
object AttachmentLimits {
    /** Largest accepted file. */
    const val MAX_FILE_BYTES = 20L * 1024 * 1024

    /** Most files on one consultation or exam/document. */
    const val MAX_FILES_PER_RECORD = 10

    const val DEFAULT_LABEL = "Outro"

    /** Suggestions for the label picker. The label is free text, these are only shortcuts. */
    val SUGGESTED_LABELS = listOf("Receituário", "Orientações", "Exame", "Laudo", "Vacina", DEFAULT_LABEL)

    val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    val ACCEPTED_MIME_TYPES = IMAGE_MIME_TYPES + "application/pdf"
}

enum class AttachmentRejectReason { TOO_LARGE, UNSUPPORTED_TYPE, EMPTY, UNREADABLE, TOO_MANY, OWNER_NOT_FOUND }

/** A file already copied into the app's private storage. */
data class ImportedFile(
    val localPath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String
)

sealed class ImportResult {
    data class Imported(val file: ImportedFile) : ImportResult()

    /** [message] is written for the user (Portuguese) and can be shown as is. */
    data class Rejected(val reason: AttachmentRejectReason, val message: String) : ImportResult()
}

/**
 * Where attachment files live: `filesDir/attachments`, named `<sha256>.<ext>` so the same content is
 * stored once. A file is COPIED here when the user picks it, so it no longer depends on the access the
 * picker granted (which Android limits, e.g. until the device restarts).
 *
 * Every function that touches a path refuses paths outside [directory].
 */
class AttachmentStorage(
    private val context: Context,
    val directory: File = File(context.applicationContext.filesDir, "attachments"),
    private val maxFileBytes: Long = AttachmentLimits.MAX_FILE_BYTES
) {

    companion object {
        private val CONTENT_NAME = Regex("^[0-9a-f]{64}\\.[a-z0-9]{1,5}$")
        private const val TEMP_PREFIX = "import_"
        private const val TEMP_SUFFIX = ".part"
        const val DEFAULT_ORPHAN_MIN_AGE_MS = 24L * 60 * 60 * 1000
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }

    /**
     * Copies the file behind [uriStr] (a `content://` or `file://` address, or a plain path) into
     * [directory]. Nothing is left behind when the file is rejected.
     */
    fun importFromUri(
        uriStr: String,
        allowedMimeTypes: Set<String> = AttachmentLimits.ACCEPTED_MIME_TYPES
    ): ImportResult {
        directory.mkdirs()
        val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
        try {
            val input: InputStream? = try {
                openInput(uriStr)
            } catch (e: Exception) {
                null
            }
            if (input == null) return unreadable()

            val digest = MessageDigest.getInstance("SHA-256")
            val head = ByteArray(16)
            var headLength = 0
            var total = 0L
            try {
                input.use { source ->
                    temp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (headLength < head.size) {
                                val take = minOf(head.size - headLength, read)
                                System.arraycopy(buffer, 0, head, headLength, take)
                                headLength += take
                            }
                            total += read
                            if (total > maxFileBytes) {
                                return ImportResult.Rejected(
                                    AttachmentRejectReason.TOO_LARGE,
                                    "O arquivo é maior que o limite de ${formatMegabytes(maxFileBytes)}."
                                )
                            }
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: IOException) {
                return unreadable()
            } catch (e: SecurityException) {
                return unreadable()
            }

            if (total == 0L) {
                return ImportResult.Rejected(AttachmentRejectReason.EMPTY, "O arquivo escolhido está vazio.")
            }

            val meta = GoogleDriveSyncManager.sniffFileMeta(head.copyOf(headLength))
            if (meta == null || meta.mimeType !in allowedMimeTypes) {
                return ImportResult.Rejected(
                    AttachmentRejectReason.UNSUPPORTED_TYPE,
                    if (allowedMimeTypes == AttachmentLimits.IMAGE_MIME_TYPES) {
                        "Tipo de arquivo não aceito. Escolha uma imagem JPG ou PNG."
                    } else {
                        "Tipo de arquivo não aceito. Use PDF, JPG ou PNG."
                    }
                )
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(directory, "$hash.${meta.extension}")
            if (target.exists() && target.length() == total) {
                temp.delete() // the very same content is already stored
            } else {
                if (target.exists()) target.delete() // wrong size: a damaged copy, replace it
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
            if (!target.isFile || target.length() != total) {
                return unreadable()
            }

            return ImportResult.Imported(
                ImportedFile(
                    localPath = target.absolutePath,
                    displayName = displayNameFor(uriStr, meta.extension),
                    mimeType = meta.mimeType,
                    sizeBytes = total,
                    sha256 = hash
                )
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** The file, only if [localPath] is inside [directory] and exists. */
    fun fileFor(localPath: String): File? {
        val file = File(localPath)
        return if (isInsideDirectory(file) && file.isFile) file else null
    }

    /** True when the file is there and has the size recorded when it was attached. */
    fun isIntact(localPath: String, expectedSizeBytes: Long): Boolean =
        fileFor(localPath)?.length() == expectedSizeBytes

    /** Deletes one file. Refuses anything outside [directory]. */
    fun deleteFile(localPath: String): Boolean {
        val file = File(localPath)
        return isInsideDirectory(file) && file.isFile && file.delete()
    }

    /** Canonical form used to compare paths that may be spelled differently. */
    fun canonicalPath(path: String): String =
        try {
            File(path).canonicalPath
        } catch (e: IOException) {
            path
        }

    /**
     * Removes files nobody refers to: content-named files that are not in [referencedPaths] and are at
     * least [minAgeMillis] old, plus leftovers of interrupted copies. The age guard protects a file that
     * was just chosen and is not saved yet. Returns how many files were deleted.
     */
    fun cleanupUnreferenced(
        referencedPaths: Set<String>,
        minAgeMillis: Long = DEFAULT_ORPHAN_MIN_AGE_MS,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        val files = directory.listFiles() ?: return 0
        val referenced = referencedPaths.map { canonicalPath(it) }.toSet()
        var deleted = 0
        for (file in files) {
            if (!file.isFile) continue
            val isContentFile = CONTENT_NAME.matches(file.name)
            val isLeftover = file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX)
            if (!isContentFile && !isLeftover) continue
            if (isContentFile && canonicalPath(file.absolutePath) in referenced) continue
            if (nowMillis - file.lastModified() < minAgeMillis) continue
            if (file.delete()) deleted++
        }
        return deleted
    }

    /** True when there is nothing in [directory], so callers can skip looking in the database. */
    fun isEmpty(): Boolean = directory.list().isNullOrEmpty()

    /**
     * Deletes everything in [directory]: every attachment, the profile photo and any leftover of an
     * interrupted copy. Returns true only when nothing is left. Used by "apagar tudo neste aparelho".
     */
    fun deleteAllFiles(): Boolean {
        directory.listFiles()?.forEach { it.deleteRecursively() }
        return isEmpty()
    }

    /**
     * Intent that shows the file in another app (PDF viewer, gallery) through the app's FileProvider.
     * Null when the file is missing. The screen decides how to start it (for example with a chooser).
     */
    fun openIntent(localPath: String, mimeType: String): Intent? {
        val file = fileFor(localPath) ?: return null
        val uri = try {
            FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun isInsideDirectory(file: File): Boolean =
        try {
            file.canonicalFile.parentFile == directory.canonicalFile
        } catch (e: IOException) {
            false
        }

    private fun openInput(uriStr: String): InputStream? =
        if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(uriStr))
        } else {
            File(uriStr).takeIf { it.isFile }?.inputStream()
        }

    private fun displayNameFor(uriStr: String, extension: String): String {
        val raw = try {
            if (uriStr.startsWith("content://")) {
                context.contentResolver
                    .query(Uri.parse(uriStr), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } else {
                File(Uri.parse(uriStr).path ?: uriStr).name
            }
        } catch (e: Exception) {
            null
        }
        val cleaned = raw?.substringAfterLast('/')?.trim()?.take(120)
        return if (cleaned.isNullOrBlank()) "arquivo.$extension" else cleaned
    }

    private fun unreadable() = ImportResult.Rejected(
        AttachmentRejectReason.UNREADABLE,
        "Não foi possível ler o arquivo escolhido. Tente escolher de novo."
    )

    private fun formatMegabytes(bytes: Long): String {
        if (bytes < 1024L * 1024L) return "${bytes / 1024} KB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb == Math.floor(mb)) "${mb.toLong()} MB"
        else String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB", mb)
    }
}
