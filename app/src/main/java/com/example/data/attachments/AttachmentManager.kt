package com.example.data.attachments

import android.content.Intent
import com.example.data.model.Attachment
import com.example.data.model.AttachmentOwner
import com.example.data.repository.StellaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A file copied into private storage that is not tied to any record yet (the dialog is still open). */
data class StagedAttachment(
    val localPath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val label: String
)

sealed class StageResult {
    data class Staged(val attachment: StagedAttachment) : StageResult()
    data class Rejected(val reason: AttachmentRejectReason, val message: String) : StageResult()
}

sealed class AddAttachmentResult {
    data class Added(val attachment: Attachment) : AddAttachmentResult()
    data class Rejected(val reason: AttachmentRejectReason, val message: String) : AddAttachmentResult()
}

/**
 * [added] were stored. [notAdded] were not (and their files were removed); [reason] and [message] say
 * why, in words for the user. Both lists empty means there was nothing to do.
 */
data class CommitResult(
    val added: List<Attachment>,
    val notAdded: List<StagedAttachment>,
    val reason: AttachmentRejectReason? = null,
    val message: String? = null
)

/**
 * Several files per consultation or exam/document.
 *
 * Two steps because a record is saved only when the dialog closes:
 *  1. [stage] copies the picked file into private storage while the dialog is open;
 *  2. [commit] ties the staged files to the record once it exists.
 * For a record that already exists, call both one after the other.
 *
 * Database changes (commit, remove) are meant to run under the ViewModel's database lock; [stage]
 * must NOT (it copies up to [AttachmentLimits.MAX_FILE_BYTES]).
 */
class AttachmentManager(
    private val repository: StellaRepository,
    val storage: AttachmentStorage,
    private val maxFilesPerRecord: Int = AttachmentLimits.MAX_FILES_PER_RECORD
) {

    // Files staged by an open dialog. Content-named files are shared, so removing an attachment with the
    // same content elsewhere must not delete a file that a dialog is about to save.
    private val stagedPaths = ConcurrentHashMap<String, Int>()

    suspend fun stage(uri: String, label: String = AttachmentLimits.DEFAULT_LABEL): StageResult =
        withContext(Dispatchers.IO) {
            when (val result = storage.importFromUri(uri)) {
                is ImportResult.Rejected -> StageResult.Rejected(result.reason, result.message)
                is ImportResult.Imported -> {
                    val file = result.file
                    stagedPaths.merge(storage.canonicalPath(file.localPath), 1, Int::plus)
                    StageResult.Staged(
                        StagedAttachment(
                            localPath = file.localPath,
                            displayName = file.displayName,
                            mimeType = file.mimeType,
                            sizeBytes = file.sizeBytes,
                            sha256 = file.sha256,
                            label = label.trim().ifEmpty { AttachmentLimits.DEFAULT_LABEL }
                        )
                    )
                }
            }
        }

    /** The user closed the dialog without saving: drop the staged file if nothing else uses it. */
    suspend fun discardStaged(staged: StagedAttachment) {
        unstage(listOf(staged))
        deleteFilesIfUnreferenced(listOf(staged.localPath))
    }

    suspend fun commit(
        ownerType: String,
        ownerId: Long,
        staged: List<StagedAttachment>
    ): CommitResult {
        if (staged.isEmpty()) return CommitResult(emptyList(), emptyList())

        if (ownerType !in AttachmentOwner.ALL || !repository.ownerExists(ownerType, ownerId)) {
            unstage(staged)
            deleteFilesIfUnreferenced(staged.map { it.localPath })
            return CommitResult(
                added = emptyList(),
                notAdded = staged,
                reason = AttachmentRejectReason.OWNER_NOT_FOUND,
                message = "O registro não existe mais; os arquivos não foram anexados."
            )
        }

        val rows = staged.map {
            Attachment(
                ownerType = ownerType,
                ownerId = ownerId,
                label = it.label,
                displayName = it.displayName,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                sha256 = it.sha256,
                localPath = it.localPath
            )
        }
        val added = repository.insertAttachmentsRespectingLimit(ownerType, ownerId, rows, maxFilesPerRecord)
        val notAdded = staged.drop(added.size)

        unstage(staged)
        if (notAdded.isNotEmpty()) {
            deleteFilesIfUnreferenced(notAdded.map { it.localPath })
        }
        return CommitResult(
            added = added,
            notAdded = notAdded,
            reason = if (notAdded.isEmpty()) null else AttachmentRejectReason.TOO_MANY,
            message = if (notAdded.isEmpty()) null else
                "Cada registro aceita até $maxFilesPerRecord arquivos. ${notAdded.size} não foi(ram) anexado(s)."
        )
    }

    /** Removes the attachment and, when nothing else uses the file, the file too. */
    suspend fun remove(attachmentId: Long): Boolean {
        val row = repository.getAttachmentById(attachmentId) ?: return false
        repository.deleteAttachment(attachmentId)
        deleteFilesIfUnreferenced(listOf(row.localPath))
        return true
    }

    /**
     * After rows were deleted (for example together with their consultation): removes those files that
     * nothing refers to any more. Files still used by another attachment, the profile photo or a staged
     * dialog are kept.
     */
    suspend fun deleteFilesIfUnreferenced(paths: Collection<String>) = withContext(Dispatchers.IO) {
        val referenced = referencedPaths()
        paths.distinct().forEach { path ->
            if (storage.canonicalPath(path) !in referenced) storage.deleteFile(path)
        }
    }

    fun attachmentsFor(ownerType: String, ownerId: Long): Flow<List<Attachment>> =
        repository.getAttachmentsFor(ownerType, ownerId)

    /** Attachments whose file is missing or changed size: the screen can offer to remove or re-attach. */
    suspend fun findBroken(): List<Attachment> = withContext(Dispatchers.IO) {
        repository.getAllAttachmentsSync().filter { !storage.isIntact(it.localPath, it.sizeBytes) }
    }

    /** Deletes old files that nobody uses (see [AttachmentStorage.cleanupUnreferenced]). */
    suspend fun cleanupUnreferenced(): Int = withContext(Dispatchers.IO) {
        if (storage.isEmpty()) 0 else storage.cleanupUnreferenced(referencedPaths())
    }

    fun openIntent(attachment: Attachment): Intent? = storage.openIntent(attachment.localPath, attachment.mimeType)

    /** Everything that points at a file in private storage, in canonical form. */
    private suspend fun referencedPaths(): Set<String> {
        val paths = HashSet<String>()
        repository.getAllAttachmentsSync().forEach { paths.add(it.localPath) }
        repository.getProfileSync()?.photoUri?.takeIf { it.isNotBlank() }?.let { paths.add(it) }
        repository.getAllExamDocumentsSync().forEach { doc ->
            doc.fileUriOrPath?.takeIf { it.isNotBlank() }?.let { paths.add(it) }
        }
        stagedPaths.keys.forEach { paths.add(it) }
        return paths.map { storage.canonicalPath(it) }.toSet()
    }

    private fun unstage(staged: List<StagedAttachment>) {
        staged.forEach { item ->
            val key = storage.canonicalPath(item.localPath)
            stagedPaths.compute(key) { _, count -> if (count == null || count <= 1) null else count - 1 }
        }
    }
}
