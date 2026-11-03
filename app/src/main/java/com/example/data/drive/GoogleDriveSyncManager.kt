package com.example.data.drive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.data.attachments.AttachmentLimits
import com.example.data.attachments.AttachmentStorage
import com.example.data.model.Attachment
import com.example.data.model.AttachmentOwner
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
import android.content.Intent
import com.example.notifications.MedicationAlarmScheduler
import com.example.notifications.ReminderNotificationManager
import com.example.util.DateTimeUtils
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class DriveConnectionStatus {
    NOT_CONNECTED,              // Nenhuma conta selecionada
    CONNECTING,                 // Autenticação em andamento
    VERIFYING,                  // Conexão precisa ser / está sendo verificada remotamente
    UNVERIFIED,                 // Verificação remota pendente ou falhou por erro transitório (preserva folderId)
    ACCOUNT_CONNECTED_NO_DRIVE, // Conta autenticada, mas Drive ainda não configurado
    PERMISSION_MISSING,         // Permissão do Drive ausente ou revogada
    CONFIGURED,                 // Drive conectado e configurado
    ERROR                       // Falha de autenticação ou acesso
}

sealed class DriveSyncState {
    object Idle : DriveSyncState()
    data class Syncing(val message: String) : DriveSyncState()
    data class Success(val lastSyncDate: String, val folderName: String, val fileCount: Int) : DriveSyncState()
    data class Restoring(val message: String) : DriveSyncState()
    data class RestoreSuccess(val message: String, val recordsCount: Int, val filesCount: Int) : DriveSyncState()
    data class RestorePartial(val message: String, val recordsCount: Int, val filesCount: Int, val failedFiles: List<String>) : DriveSyncState()
    data class Error(val message: String, val statusCode: Int? = null) : DriveSyncState()
}

/** Outcome of copying the backup that already lives on Drive before it is replaced. */
sealed class SafetyCopyResult {
    /** A dated copy was created next to the main backup. */
    data class Created(val fileName: String, val fileId: String) : SafetyCopyResult()

    /** There is no backup on Drive (anymore), so there is nothing to protect. */
    object NothingToCopy : SafetyCopyResult()

    /** The copy could NOT be made. The caller must not replace anything. */
    data class Failed(val message: String) : SafetyCopyResult()
}

data class BackupManifestPreview(
    val backupVersion: Int,
    val exportDate: String,
    val profileName: String,
    val glucoseCount: Int,
    val medicationCount: Int,
    val appointmentCount: Int,
    val documentCount: Int,
    val weightCount: Int,
    val vaccineCount: Int,
    val feedingCount: Int,
    val reminderCount: Int,
    val attachmentCount: Int
)

/** What a backup file in the Drive folder is. Only these are listed as restorable versions. */
enum class BackupVersionKind {
    /** `stella_prontuario_backup.json`: the backup the app keeps up to date. */
    CURRENT,

    /** Dated copy of the cloud backup, made just before this device's data replaced it. */
    CLOUD_BEFORE_OVERWRITE,

    /** Dated copy of this device's data, made just before a restore replaced it. */
    DEVICE_BEFORE_RESTORE
}

/** One backup file on Drive that the user can look at and restore. */
data class BackupVersion(
    val fileId: String,
    val fileName: String,
    val kind: BackupVersionKind,
    /** When the file was written (Drive's own time, else the time in its name). Null if unknown. */
    val createdAtMillis: Long?,
    val sizeBytes: Long?
)

/** What the app keeps in its Drive folder, i.e. what "apagar a nuvem" would delete. */
data class CloudFilesCount(
    val total: Int,
    /** The current backup and the dated copies (the files that hold the readable data). */
    val backupFiles: Int,
    /** Photos, exams and other attachments (`stella_anexo_*`). */
    val attachmentFiles: Int,
    /** Anything else the app put there, such as the readable report. */
    val otherFiles: Int
)

/** Outcome of deleting everything the app keeps on Drive. Only [Deleted] means it is all gone. */
sealed class CloudDeleteResult {
    /** Every file was deleted (a file that was already gone counts). The folder itself is kept. */
    data class Deleted(val filesDeleted: Int) : CloudDeleteResult()

    /**
     * NOT complete. [filesRemaining] is how many files were still there when it stopped (null when it
     * could not even list them). [offline] is true when the reason was missing internet: repeat later.
     * [message] is written for the user (Portuguese).
     */
    data class Failed(
        val filesDeleted: Int,
        val filesRemaining: Int?,
        val message: String,
        val offline: Boolean
    ) : CloudDeleteResult()
}

/**
 * Database snapshot plus the bookkeeping needed to decide, after the upload, whether the
 * "pending changes" flag may be cleared.
 */
data class CapturedBackup(
    val snapshot: BackupSnapshot,
    val changeVersion: Long,
    val restoreEpoch: Long,
    val sessionEpoch: Long = -1L,
    val sessionEmail: String? = null
)

class DriveApiException(
    val statusCode: Int,
    message: String,
    val rawBody: String = "",
    cause: Throwable? = null
) : Exception("HTTP $statusCode: $message", cause)

data class DriveAccountInfo(
    val email: String,
    val hasDriveScope: Boolean = true
)

/**
 * @param httpClient        injectable so the real upload/restore code can be exercised against a local
 *                          HTTP server in tests. Production uses [defaultHttpClient].
 * @param tokenProvider     when null (production) the token comes from the signed-in Google account.
 * @param driveApiBaseUrl   Drive REST base URL (overridable for tests only).
 * @param driveUploadBaseUrl Drive upload base URL (overridable for tests only).
 * @param accountProvider   optional account provider for testing Google Sign-In state. Production uses null.
 * @param authGateway       optional gateway providing single source of truth for Google Drive auth operations.
 */
class GoogleDriveSyncManager(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    tokenProvider: (() -> String)? = null,
    driveApiBaseUrl: String = DEFAULT_DRIVE_API_BASE_URL,
    driveUploadBaseUrl: String = DEFAULT_DRIVE_UPLOAD_BASE_URL,
    accountProvider: (() -> DriveAccountInfo?)? = null,
    authGateway: DriveAuthGateway? = null,
    private val resumableThresholdBytes: Long = DEFAULT_RESUMABLE_THRESHOLD_BYTES,
    private val resumableChunkBytes: Int = DEFAULT_RESUMABLE_CHUNK_BYTES
) {
    init {
        // Drive requires every chunk but the last to be a multiple of 256 KiB.
        require(resumableChunkBytes > 0 && resumableChunkBytes % RESUMABLE_CHUNK_GRANULARITY == 0) {
            "O tamanho de cada parte do envio deve ser múltiplo de 256 KiB."
        }
    }

    val authGateway: DriveAuthGateway = authGateway ?: DefaultDriveAuthGateway(
        context = context,
        tokenProvider = tokenProvider,
        accountProvider = accountProvider
    )

    companion object {
        const val DRIVE_FOLDER_NAME = "STELLA - Prontuário Veterinário"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val BACKUP_FILE_NAME = "stella_prontuario_backup.json"
        const val REPORT_FILE_NAME = "relatorio_medico_stella.txt"
        /**
         * 2 = adds `recordAttachments` (several files per consultation or exam) and names every file on Drive
         * after its content. This app still reads version 1; an older app refuses version 2 on purpose.
         */
        const val CURRENT_BACKUP_VERSION = 2
        const val OLDEST_READABLE_BACKUP_VERSION = 1

        /** Files on Drive are named `stella_anexo_<sha256>.<ext>`: same content, same name, sent once. */
        const val ATTACHMENT_FILE_PREFIX = "stella_anexo_"

        /** Drive recommends simple/multipart upload up to 5 MB and resumable above that. */
        const val DEFAULT_RESUMABLE_THRESHOLD_BYTES = 5L * 1024 * 1024
        const val DEFAULT_RESUMABLE_CHUNK_BYTES = 8 * 1024 * 1024
        private const val RESUMABLE_CHUNK_GRANULARITY = 256 * 1024
        private const val RESUMABLE_MAX_RETRIES_IN_A_ROW = 3
        private const val MAX_LIST_PAGES = 100
        private const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun attachmentFileName(sha256: String, extension: String): String =
            "$ATTACHMENT_FILE_PREFIX${sha256.lowercase(Locale.ROOT)}.$extension"

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        // Safety copies live in the SAME Drive folder as the main backup and are never deleted by the
        // app. Both names start with "stella_prontuario_backup_" so a version list can find them.
        const val SAFETY_COPY_CLOUD_PREFIX = "stella_prontuario_backup_antes_de_substituir_"
        const val SAFETY_COPY_DEVICE_PREFIX = "stella_prontuario_backup_aparelho_antes_de_restaurar_"
        const val DEFAULT_DRIVE_API_BASE_URL = "https://www.googleapis.com/drive/v3"
        const val DEFAULT_DRIVE_UPLOAD_BASE_URL = "https://www.googleapis.com/upload/drive/v3"
        private const val TAG = "DriveSyncManager"
        private const val PREFS_NAME = "stella_drive_sync"
        private const val KEY_PENDING_CHANGES = "has_pending_changes"
        private const val KEY_CONNECTED_ACCOUNT_EMAIL = "connected_account_email"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        private const val KEY_FOLDER_ID_PREFIX = "drive_folder_id_"
        private const val KEY_SYNC_APPROVED_PREFIX = "sync_approved_account_"

        fun getFolderPrefsKey(email: String?): String {
            val normalized = email?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            val safeKey = normalized?.replace(Regex("[^a-z0-9_]"), "_") ?: "default"
            return "$KEY_FOLDER_ID_PREFIX$safeKey"
        }

        fun getSyncApprovedPrefsKey(email: String?): String {
            val normalized = email?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            val safeKey = normalized?.replace(Regex("[^a-z0-9_]"), "_") ?: "default"
            return "$KEY_SYNC_APPROVED_PREFIX$safeKey"
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private val KNOWN_MIME_BY_EXTENSION = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "pdf" to "application/pdf",
            "gif" to "image/gif",
            "webp" to "image/webp"
        )

        private val CANONICAL_EXTENSION_BY_MIME = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "application/pdf" to "pdf",
            "image/gif" to "gif",
            "image/webp" to "webp"
        )

        /** Identifies the real file type from its first bytes. Returns null when unknown. */
        fun sniffFileMeta(bytes: ByteArray?): FileMeta? {
            if (bytes == null || bytes.size < 4) return null
            fun b(i: Int) = bytes[i].toInt() and 0xFF
            if (b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47) return FileMeta("png", "image/png")
            if (b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return FileMeta("jpg", "image/jpeg")
            if (b(0) == 0x25 && b(1) == 0x50 && b(2) == 0x44 && b(3) == 0x46) return FileMeta("pdf", "application/pdf")
            if (b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38) return FileMeta("gif", "image/gif")
            if (bytes.size >= 12 &&
                b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
                b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50
            ) return FileMeta("webp", "image/webp")
            return null
        }

        fun mimeTypeForExtension(extension: String): String? {
            val ext = extension.lowercase(Locale.ROOT)
            KNOWN_MIME_BY_EXTENSION[ext]?.let { return it }
            return try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } catch (_: Exception) {
                null
            }
        }

        fun extensionForMimeType(mimeType: String): String? {
            val mime = normalizeMime(mimeType)
            CANONICAL_EXTENSION_BY_MIME[mime]?.let { return it }
            return try {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            } catch (_: Exception) {
                null
            }
        }

        private fun normalizeMime(mimeType: String): String {
            val m = mimeType.trim().lowercase(Locale.ROOT).substringBefore(';')
            return if (m == "image/jpg") "image/jpeg" else m
        }

        /** Extension (lowercase, 1..5 alphanumerics) found in a file name, path or URI. */
        private fun extensionOf(name: String): String? {
            val clean = name.substringBefore('?').substringBefore('#')
            val lastSegment = clean.substringAfterLast('/')
            if (!lastSegment.contains('.')) return null
            val ext = lastSegment.substringAfterLast('.').lowercase(Locale.ROOT)
            if (ext.isEmpty() || ext.length > 5 || !ext.all { it.isLetterOrDigit() }) return null
            return ext
        }

        fun metaFromName(name: String): FileMeta? {
            val ext = extensionOf(name) ?: return null
            return FileMeta(ext, mimeTypeForExtension(ext) ?: "application/octet-stream")
        }

        private fun metaFromHint(hint: String?): FileMeta? {
            return when (hint?.trim()?.uppercase(Locale.ROOT)) {
                "PNG" -> FileMeta("png", "image/png")
                "PDF" -> FileMeta("pdf", "application/pdf")
                "JPG", "JPEG" -> FileMeta("jpg", "image/jpeg")
                else -> null
            }
        }

        fun isScopeOrPermissionError(bodyStr: String): Boolean {
            if (bodyStr.isBlank()) return false
            return try {
                val root = JSONObject(bodyStr)
                val errorObj = root.optJSONObject("error") ?: root

                // 1. error.errors[].reason
                val errorsArray = errorObj.optJSONArray("errors")
                if (errorsArray != null) {
                    for (i in 0 until errorsArray.length()) {
                        val item = errorsArray.optJSONObject(i)
                        val reason = item?.optString("reason", "") ?: ""
                        if (isPermissionReason(reason)) return true
                    }
                }

                // 2. error.details[].reason
                val detailsArray = errorObj.optJSONArray("details")
                if (detailsArray != null) {
                    for (i in 0 until detailsArray.length()) {
                        val item = detailsArray.optJSONObject(i)
                        val reason = item?.optString("reason", "") ?: ""
                        if (isPermissionReason(reason)) return true
                    }
                }

                // 3. Status/message
                val status = errorObj.optString("status", "")
                if (isPermissionReason(status)) return true
                val message = errorObj.optString("message", "")
                if (isPermissionReason(message)) return true

                false
            } catch (_: Exception) {
                isPermissionReason(bodyStr)
            }
        }

        private fun isPermissionReason(reason: String): Boolean {
            if (reason.isBlank()) return false
            val r = reason.lowercase(Locale.ROOT)
            return r.contains("insufficientpermissions") ||
                r.contains("insufficientfilepermissions") ||
                r.contains("access_token_scope_insufficient") ||
                r.contains("insufficient_scope") ||
                r.contains("scope_insufficient")
        }

        fun isAppNotAuthorizedToFile(bodyStr: String): Boolean {
            if (bodyStr.isBlank()) return false
            return try {
                val root = JSONObject(bodyStr)
                val errorObj = root.optJSONObject("error") ?: root
                val errorsArray = errorObj.optJSONArray("errors")
                if (errorsArray != null) {
                    for (i in 0 until errorsArray.length()) {
                        val item = errorsArray.optJSONObject(i)
                        if (item?.optString("reason", "")?.equals("appNotAuthorizedToFile", ignoreCase = true) == true) {
                            return true
                        }
                    }
                }
                val detailsArray = errorObj.optJSONArray("details")
                if (detailsArray != null) {
                    for (i in 0 until detailsArray.length()) {
                        val item = detailsArray.optJSONObject(i)
                        if (item?.optString("reason", "")?.equals("appNotAuthorizedToFile", ignoreCase = true) == true) {
                            return true
                        }
                    }
                }
                bodyStr.contains("appNotAuthorizedToFile", ignoreCase = true)
            } catch (_: Exception) {
                bodyStr.contains("appNotAuthorizedToFile", ignoreCase = true)
            }
        }

        fun isRateLimitOrQuotaError(bodyStr: String): Boolean {
            if (bodyStr.isBlank()) return false
            return try {
                val root = JSONObject(bodyStr)
                val errorObj = root.optJSONObject("error") ?: root
                val errorsArray = errorObj.optJSONArray("errors")
                if (errorsArray != null) {
                    for (i in 0 until errorsArray.length()) {
                        val item = errorsArray.optJSONObject(i)
                        val reason = item?.optString("reason", "") ?: ""
                        if (isRateOrQuotaReason(reason)) return true
                    }
                }
                val detailsArray = errorObj.optJSONArray("details")
                if (detailsArray != null) {
                    for (i in 0 until detailsArray.length()) {
                        val item = detailsArray.optJSONObject(i)
                        val reason = item?.optString("reason", "") ?: ""
                        if (isRateOrQuotaReason(reason)) return true
                    }
                }
                isRateOrQuotaReason(bodyStr)
            } catch (_: Exception) {
                isRateOrQuotaReason(bodyStr)
            }
        }

        private fun isRateOrQuotaReason(text: String): Boolean {
            val t = text.lowercase(Locale.ROOT)
            return t.contains("ratelimitexceeded") ||
                t.contains("userratelimitexceeded") ||
                t.contains("quotaexceeded") ||
                t.contains("dailylimitexceeded") ||
                t.contains("rate limit")
        }

        fun extractErrorMessage(bodyStr: String): String {
            return try {
                val json = JSONObject(bodyStr)
                json.optJSONObject("error")?.optString("message") ?: bodyStr
            } catch (_: Exception) {
                bodyStr
            }
        }
    }

    data class FileMeta(val extension: String, val mimeType: String)

    private val driveApiUrl = driveApiBaseUrl.trimEnd('/')
    private val driveUploadUrl = driveUploadBaseUrl.trimEnd('/')

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _syncState = MutableStateFlow<DriveSyncState>(DriveSyncState.Idle)
    val syncState: StateFlow<DriveSyncState> = _syncState.asStateFlow()

    private val _connectionStatus = MutableStateFlow<DriveConnectionStatus>(DriveConnectionStatus.NOT_CONNECTED)
    val connectionStatus: StateFlow<DriveConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedAccountEmail = MutableStateFlow<String?>(prefs.getString(KEY_CONNECTED_ACCOUNT_EMAIL, null))
    val connectedAccountEmail: StateFlow<String?> = _connectedAccountEmail.asStateFlow()

    private val _recoverableAuthIntent = MutableStateFlow<Intent?>(null)
    val recoverableAuthIntent: StateFlow<Intent?> = _recoverableAuthIntent.asStateFlow()

    fun clearRecoverableAuthIntent() {
        _recoverableAuthIntent.value = null
    }

    private val _lastSyncTimestamp = MutableStateFlow<String?>(prefs.getString(KEY_LAST_SYNC_TIMESTAMP, "Nunca sincronizado"))
    val lastSyncTimestamp: StateFlow<String?> = _lastSyncTimestamp.asStateFlow()

    // Survives process death: a change saved locally must not silently stop being "pending".
    private val _hasPendingChanges = MutableStateFlow(prefs.getBoolean(KEY_PENDING_CHANGES, false))
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges.asStateFlow()

    // Incremented on every local change. A finished upload only clears the pending flag if no
    // change happened after the snapshot it uploaded.
    private val changeVersion = AtomicLong(0L)

    // Incremented when a restore rewrites the local database. A snapshot captured before a
    // restore must never be uploaded over the backup that was just restored.
    private val restoreEpoch = AtomicLong(0L)

    // Incremented on sign-out, account switch preparation, or account change so an in-flight
    // upload from a previous session can never write results into the new or disconnected session.
    private val sessionEpoch = AtomicLong(0L)

    // internal (not private) only because it appears in the signature of internal functions
    // (executeHttp, getOrCreateDriveFolder, backupAttachment). Each operation carries its own
    // snapshot; there is deliberately no global "active session" field.
    internal data class SyncSessionSnapshot(
        val epoch: Long,
        val accountEmail: String?,
        val gatewayEmail: String?
    )

    private fun isSyncSessionValid(session: SyncSessionSnapshot): Boolean {
        if (sessionEpoch.get() != session.epoch) return false
        val currentGatewayAccount = authGateway.getConnectedAccount()
        val currentGatewayEmail = currentGatewayAccount?.email?.trim()?.takeIf { it.isNotEmpty() }
        val currentConnectedEmail = _connectedAccountEmail.value?.trim()?.takeIf { it.isNotEmpty() }

        if (_connectionStatus.value == DriveConnectionStatus.NOT_CONNECTED &&
            currentGatewayAccount == null &&
            currentConnectedEmail == null
        ) {
            return false
        }

        if (session.gatewayEmail != null) {
            if (currentGatewayEmail == null || !currentGatewayEmail.equals(session.gatewayEmail, ignoreCase = true)) {
                return false
            }
        }

        if (session.accountEmail != null) {
            val activeEmail = currentGatewayEmail ?: currentConnectedEmail
            if (activeEmail == null || !activeEmail.equals(session.accountEmail, ignoreCase = true)) {
                return false
            }
        }

        return true
    }

    /**
     * Whether an operation may still write into the shared connection state.
     *
     * - [session] != null: the operation belongs to the sync session captured by [syncToDrive]; it is
     *   current only while that exact session is valid. An old session A is therefore rejected even
     *   when it ends later, and it never decides anything for an operation of another session.
     * - [session] == null: the operation (login, ensureFolderConfigured, preview, restore...) has no sync
     *   session of its own and counts as belonging to the current one. When [epochAtStart] is given it
     *   is only rejected if the session epoch changed while it was running; without it, it is accepted.
     */
    private fun isOperationCurrent(session: SyncSessionSnapshot?, epochAtStart: Long? = null): Boolean = when {
        session != null -> isSyncSessionValid(session)
        epochAtStart != null -> sessionEpoch.get() == epochAtStart
        else -> true
    }

    // Sync and restore never run at the same time.
    internal val operationMutex = Mutex()

    private fun resolveAccountEmail(explicitEmail: String? = null): String? {
        return explicitEmail?.trim()?.takeIf { it.isNotEmpty() }
            ?: authGateway.getConnectedAccount()?.email?.trim()?.takeIf { it.isNotEmpty() }
            ?: _connectedAccountEmail.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getPersistedFolderId(email: String? = resolveAccountEmail()): String? {
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = getFolderPrefsKey(normalizedEmail)
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    fun persistFolderId(email: String?, folderId: String?) {
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val key = getFolderPrefsKey(normalizedEmail)
        prefs.edit().apply {
            if (!folderId.isNullOrBlank()) putString(key, folderId) else remove(key)
        }.apply()
    }

    fun isSyncApprovedForAccount(email: String? = resolveAccountEmail()): Boolean {
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val key = getSyncApprovedPrefsKey(normalizedEmail)
        return prefs.getBoolean(key, false)
    }

    fun setSyncApprovedForAccount(email: String?, approved: Boolean) {
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val key = getSyncApprovedPrefsKey(normalizedEmail)
        prefs.edit().putBoolean(key, approved).apply()
    }

    fun clearSyncApprovedForAccount(email: String?) {
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val key = getSyncApprovedPrefsKey(normalizedEmail)
        prefs.edit().remove(key).apply()
    }

    init {
        refreshStateFromDevice()
    }

    fun refreshStateFromDevice() {
        try {
            val previousEmail = _connectedAccountEmail.value
            val accountData = authGateway.getConnectedAccount()

            if (accountData == null) {
                if (previousEmail != null) {
                    sessionEpoch.incrementAndGet()
                    clearSyncApprovedForAccount(previousEmail)
                }
                _connectedAccountEmail.value = null
                _connectionStatus.value = DriveConnectionStatus.NOT_CONNECTED
                prefs.edit().remove(KEY_CONNECTED_ACCOUNT_EMAIL).apply()
            } else {
                val email = accountData.email
                if (previousEmail != null && !previousEmail.equals(email, ignoreCase = true)) {
                    sessionEpoch.incrementAndGet()
                    clearSyncApprovedForAccount(previousEmail)
                }
                _connectedAccountEmail.value = email
                prefs.edit().putString(KEY_CONNECTED_ACCOUNT_EMAIL, email).apply()

                if (!accountData.hasDriveScope) {
                    _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                } else {
                    val persistedId = getPersistedFolderId(email)
                    if (!persistedId.isNullOrBlank()) {
                        _connectionStatus.value = DriveConnectionStatus.VERIFYING
                    } else {
                        _connectionStatus.value = DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read last signed-in Google account: ${e.message}")
            _connectionStatus.value = DriveConnectionStatus.ERROR
        }
    }

    private fun persistPending(value: Boolean) {
        _hasPendingChanges.value = value
        prefs.edit().putBoolean(KEY_PENDING_CHANGES, value).apply()
    }

    /** Call after a local change has been committed to Room. */
    fun markPendingChanges() {
        changeVersion.incrementAndGet()
        persistPending(true)
    }

    /** Explicitly clears the pending changes flag (e.g. after a full restore matching Drive). */
    fun clearPendingChanges() {
        persistPending(false)
    }

    private fun clearPendingIfUnchanged(versionAtSnapshot: Long) {
        if (changeVersion.get() == versionAtSnapshot) {
            persistPending(false)
        }
    }

    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    suspend fun handleSignInSuccess(account: GoogleSignInAccount): DriveConnectionStatus = withContext(Dispatchers.IO) {
        val email = account.email
        sessionEpoch.incrementAndGet()
        _connectedAccountEmail.value = email
        prefs.edit().putString(KEY_CONNECTED_ACCOUNT_EMAIL, email).apply()

        val hasScope = authGateway.checkAccountHasScope(account)
        if (!hasScope) {
            Log.w(TAG, "Conta autenticada mas permissão drive.file não concedida.")
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            _syncState.value = DriveSyncState.Error("Acesso ao Google Drive não autorizado. Reautorize o aplicativo.")
            return@withContext DriveConnectionStatus.PERMISSION_MISSING
        }

        _connectionStatus.value = DriveConnectionStatus.CONNECTING
        try {
            val token = getValidAuthToken()
            val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, email)
            persistFolderId(email, folderId)
            Log.d(TAG, "Pasta do Google Drive configurada com sucesso")
            _connectionStatus.value = DriveConnectionStatus.CONFIGURED
            if (_syncState.value is DriveSyncState.Error) {
                _syncState.value = DriveSyncState.Idle
            }
            DriveConnectionStatus.CONFIGURED
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Autorização recuperável necessária ao configurar pasta após login: ${e.message}")
            _recoverableAuthIntent.value = e.intent
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            _syncState.value = DriveSyncState.Error("Autorização adicional necessária para acessar o Google Drive.")
            DriveConnectionStatus.PERMISSION_MISSING
        } catch (e: GoogleAuthException) {
            Log.w(TAG, "Erro de autenticação Google ao configurar pasta após login: ${e.message}")
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            _syncState.value = DriveSyncState.Error("Permissão ausente para acessar o Google Drive: ${e.localizedMessage ?: e.message}")
            DriveConnectionStatus.PERMISSION_MISSING
        } catch (e: Exception) {
            Log.e(TAG, "Conta autenticada, mas falha ao configurar pasta: ${e.message}")
            if (_connectionStatus.value != DriveConnectionStatus.PERMISSION_MISSING &&
                _connectionStatus.value != DriveConnectionStatus.UNVERIFIED
            ) {
                val hasPersistedFolder = !getPersistedFolderId(email).isNullOrBlank()
                _connectionStatus.value = if (hasPersistedFolder) {
                    DriveConnectionStatus.UNVERIFIED
                } else {
                    DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                }
            }
            _syncState.value = DriveSyncState.Error("Conta autenticada, mas não foi possível configurar a pasta no Drive: ${e.localizedMessage ?: e.message}")
            _connectionStatus.value
        }
    }

    suspend fun ensureFolderConfigured(): Boolean = withContext(Dispatchers.IO) {
        val accountData = authGateway.getConnectedAccount()
        if (accountData == null) {
            _connectedAccountEmail.value = null
            _connectionStatus.value = DriveConnectionStatus.NOT_CONNECTED
            return@withContext false
        }
        val email = accountData.email
        _connectedAccountEmail.value = email
        prefs.edit().putString(KEY_CONNECTED_ACCOUNT_EMAIL, email).apply()

        if (!accountData.hasDriveScope) {
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            return@withContext false
        }

        _connectionStatus.value = DriveConnectionStatus.CONNECTING
        try {
            val token = getValidAuthToken()
            val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, email)
            persistFolderId(email, folderId)
            _connectionStatus.value = DriveConnectionStatus.CONFIGURED
            if (_syncState.value is DriveSyncState.Error) {
                _syncState.value = DriveSyncState.Idle
            }
            true
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Autorização recuperável necessária ao configurar pasta do Drive: ${e.message}")
            _recoverableAuthIntent.value = e.intent
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            _syncState.value = DriveSyncState.Error("Autorização necessária para acessar o Google Drive: ${e.localizedMessage ?: e.message}")
            false
        } catch (e: GoogleAuthException) {
            Log.w(TAG, "Erro de autenticação Google ao configurar pasta do Drive: ${e.message}")
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            _syncState.value = DriveSyncState.Error("Permissão ausente para acessar o Google Drive: ${e.localizedMessage ?: e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao configurar pasta do Drive: ${e.message}")
            if (_connectionStatus.value != DriveConnectionStatus.PERMISSION_MISSING &&
                _connectionStatus.value != DriveConnectionStatus.UNVERIFIED
            ) {
                val hasPersistedFolder = !getPersistedFolderId(email).isNullOrBlank()
                _connectionStatus.value = if (hasPersistedFolder) {
                    DriveConnectionStatus.UNVERIFIED
                } else {
                    DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                }
            }
            _syncState.value = DriveSyncState.Error("Não foi possível configurar a pasta no Drive: ${e.localizedMessage ?: e.message}")
            false
        }
    }

    suspend fun verifyDriveConnection(): DriveConnectionStatus = withContext(Dispatchers.IO) {
        val accountData = authGateway.getConnectedAccount()
        if (accountData == null) {
            _connectedAccountEmail.value = null
            _connectionStatus.value = DriveConnectionStatus.NOT_CONNECTED
            return@withContext DriveConnectionStatus.NOT_CONNECTED
        }

        val email = accountData.email
        _connectedAccountEmail.value = email

        if (!accountData.hasDriveScope) {
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            return@withContext DriveConnectionStatus.PERMISSION_MISSING
        }

        val folderId = getPersistedFolderId(email)
        if (folderId.isNullOrBlank()) {
            _connectionStatus.value = DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
            return@withContext DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
        }

        _connectionStatus.value = DriveConnectionStatus.VERIFYING

        val url = "$driveApiUrl/files/$folderId?fields=id,name,trashed,mimeType"

        var token = try {
            authGateway.getToken()
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Recuperação necessária de autorização da conta Google")
            _recoverableAuthIntent.value = e.intent
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            return@withContext DriveConnectionStatus.PERMISSION_MISSING
        } catch (e: GoogleAuthException) {
            Log.w(TAG, "Erro de autenticação Google ao obter token: ${e.message}")
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            return@withContext DriveConnectionStatus.PERMISSION_MISSING
        } catch (e: IOException) {
            Log.w(TAG, "Falha de rede/I/O ao obter token para verificação: ${e.message}")
            _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
            return@withContext DriveConnectionStatus.UNVERIFIED
        } catch (e: Exception) {
            Log.w(TAG, "Falha genérica ao obter token para verificação: ${e.message}")
            _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
            return@withContext DriveConnectionStatus.UNVERIFIED
        }

        var attempts = 0
        while (attempts < 2) {
            attempts++
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val (code, bodyStr) = response.use { res ->
                    Pair(res.code, res.body?.string() ?: "")
                }

                if (code == 401) {
                    Log.w(TAG, "HTTP 401 na verificação. Invalidando o token exato utilizado (tentativa $attempts).")
                    authGateway.invalidateToken(token)

                    if (attempts < 2) {
                        token = try {
                            authGateway.getToken()
                        } catch (e: UserRecoverableAuthException) {
                            _recoverableAuthIntent.value = e.intent
                            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                            return@withContext DriveConnectionStatus.PERMISSION_MISSING
                        } catch (e: GoogleAuthException) {
                            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                            return@withContext DriveConnectionStatus.PERMISSION_MISSING
                        } catch (e: Exception) {
                            _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                            return@withContext DriveConnectionStatus.UNVERIFIED
                        }
                        continue
                    } else {
                        Log.e(TAG, "Segundo HTTP 401 consecutivo na verificação. Definindo estado ERROR.")
                        _connectionStatus.value = DriveConnectionStatus.ERROR
                        return@withContext DriveConnectionStatus.ERROR
                    }
                }

                return@withContext handleVerifyResponse(code, bodyStr, email, folderId)

            } catch (e: Exception) {
                // Timeout, erro de rede: NÃO remove folderId
                Log.w(TAG, "Falha de rede/timeout ao verificar pasta no Drive. Mantendo folderId. Estado: UNVERIFIED.")
                _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                return@withContext DriveConnectionStatus.UNVERIFIED
            }
        }

        return@withContext _connectionStatus.value
    }

    private fun handleVerifyResponse(
        code: Int,
        bodyStr: String,
        email: String,
        folderId: String
    ): DriveConnectionStatus {
        when {
            code == 200 -> {
                val json = try {
                    JSONObject(bodyStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Resposta HTTP 200 malformada/não interpretável como JSON. Mantendo folderId local.")
                    _connectionStatus.value = DriveConnectionStatus.ERROR
                    return DriveConnectionStatus.ERROR
                }

                val isTrashed = json.optBoolean("trashed", false)
                val mimeType = json.optString("mimeType", "")

                return if (!isTrashed && mimeType == "application/vnd.google-apps.folder") {
                    Log.d(TAG, "Pasta do Drive verificada com sucesso")
                    _connectionStatus.value = DriveConnectionStatus.CONFIGURED
                    DriveConnectionStatus.CONFIGURED
                } else {
                    Log.w(TAG, "Pasta no Drive na lixeira ou mimeType incompatível. Removendo folderId local.")
                    persistFolderId(email, null)
                    _connectionStatus.value = DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                    DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                }
            }

            code == 404 -> {
                Log.w(TAG, "Pasta do Drive não encontrada (HTTP 404). Removendo folderId local.")
                persistFolderId(email, null)
                _connectionStatus.value = DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                return DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
            }

            code == 403 -> {
                val isAppNotAuthorized = isAppNotAuthorizedToFile(bodyStr)
                val isPermission = isScopeOrPermissionError(bodyStr)
                val isRateOrQuota = isRateLimitOrQuotaError(bodyStr)

                return when {
                    isAppNotAuthorized -> {
                        Log.w(TAG, "Aplicativo não autorizado a acessar o arquivo no Drive (appNotAuthorizedToFile). Removendo folderId local.")
                        persistFolderId(email, null)
                        _connectionStatus.value = DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                        DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                    }
                    isPermission -> {
                        Log.w(TAG, "Permissão ou escopo insuficiente para acessar o Google Drive (HTTP 403).")
                        _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                        DriveConnectionStatus.PERMISSION_MISSING
                    }
                    isRateOrQuota -> {
                        Log.w(TAG, "Limite de taxa ou cota no Google Drive (HTTP 403 rateLimit/quota). Mantendo folderId. Estado: UNVERIFIED.")
                        _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                        DriveConnectionStatus.UNVERIFIED
                    }
                    else -> {
                        Log.w(TAG, "Erro 403 não definitivo ao verificar Google Drive. Mantendo folderId. Estado: UNVERIFIED.")
                        _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                        DriveConnectionStatus.UNVERIFIED
                    }
                }
            }

            code == 429 || (code in 500..599) -> {
                Log.w(TAG, "Erro transitório no Drive (HTTP $code). Mantendo folderId. Estado: UNVERIFIED.")
                _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                return DriveConnectionStatus.UNVERIFIED
            }

            else -> {
                Log.e(TAG, "Erro inesperado ao verificar pasta no Drive (HTTP $code).")
                _connectionStatus.value = DriveConnectionStatus.ERROR
                return DriveConnectionStatus.ERROR
            }
        }
    }

    fun setSignInError(errorMessage: String) {
        _connectionStatus.value = DriveConnectionStatus.ERROR
        _syncState.value = DriveSyncState.Error("Falha na autenticação com a conta Google: $errorMessage")
    }

    fun prepareForAccountSwitch(onReadyToSignIn: () -> Unit) {
        val emailBeforeSignOut = resolveAccountEmail()
        sessionEpoch.incrementAndGet()
        authGateway.signOut {
            sessionEpoch.incrementAndGet()
            val email = emailBeforeSignOut ?: _connectedAccountEmail.value
            persistFolderId(email, null)
            clearSyncApprovedForAccount(email)
            _recoverableAuthIntent.value = null
            _connectedAccountEmail.value = null
            _connectionStatus.value = DriveConnectionStatus.NOT_CONNECTED
            _syncState.value = DriveSyncState.Idle
            prefs.edit().remove(KEY_CONNECTED_ACCOUNT_EMAIL).apply()
            onReadyToSignIn()
        }
    }

    fun signOut(onComplete: () -> Unit = {}) {
        val emailBeforeSignOut = resolveAccountEmail()
        sessionEpoch.incrementAndGet()
        authGateway.signOut {
            sessionEpoch.incrementAndGet()
            val email = emailBeforeSignOut ?: _connectedAccountEmail.value
            persistFolderId(email, null)
            clearSyncApprovedForAccount(email)
            _recoverableAuthIntent.value = null
            _connectedAccountEmail.value = null
            _connectionStatus.value = DriveConnectionStatus.NOT_CONNECTED
            _syncState.value = DriveSyncState.Idle
            prefs.edit().remove(KEY_CONNECTED_ACCOUNT_EMAIL).apply()
            onComplete()
        }
    }

    private fun getValidAuthToken(): String {
        return try {
            authGateway.getToken()
        } catch (e: UserRecoverableAuthException) {
            _recoverableAuthIntent.value = e.intent
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            throw e
        } catch (e: GoogleAuthException) {
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            throw e
        }
    }

    fun invalidateCachedToken(token: String? = null) {
        if (!token.isNullOrBlank()) {
            authGateway.invalidateToken(token)
        }
    }

    private fun <T> executeHttpInternal(
        initialRequest: Request,
        session: SyncSessionSnapshot? = null,
        responseParser: (Response) -> T
    ): T {
        // Read before the request leaves. An operation without a sync session counts as belonging to
        // the current session and is only considered stale if the epoch moves while it is running.
        val epochAtStart = sessionEpoch.get()
        val initialToken = initialRequest.header("Authorization")?.removePrefix("Bearer ")?.trim()

        val response = try {
            httpClient.newCall(initialRequest).execute()
        } catch (e: Exception) {
            throw IllegalStateException("Erro de rede ao conectar com o Google Drive: ${e.localizedMessage}", e)
        }

        if (response.code == 401) {
            response.close()
            if (!initialToken.isNullOrBlank()) {
                Log.w(TAG, "HTTP 401 recebido. Invalidando token exato da requisição.")
                authGateway.invalidateToken(initialToken)
            }

            // An operation of an old session must neither renew a token nor repeat its request:
            // authGateway.getToken() answers for the CURRENT account, so the retry would send the old
            // session's request (its folder, its data) to the Drive of whoever is signed in now.
            // Invalidating the exact token above stays: it is the token that was just rejected.
            if (!isOperationCurrent(session, epochAtStart)) {
                Log.w(TAG, "HTTP 401 de operação de sessão antiga. Descartando sem renovar o token nem repetir a requisição.")
                throw DriveApiException(401, "Não autorizado (HTTP 401). Operação de uma sessão anterior descartada sem renovar o token.")
            }

            val newToken = try {
                authGateway.getToken()
            } catch (e: UserRecoverableAuthException) {
                Log.w(TAG, "Autorização recuperável necessária ao renovar token após HTTP 401: ${e.message}")
                if (isOperationCurrent(session, epochAtStart)) {
                    _recoverableAuthIntent.value = e.intent
                    _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                }
                throw e
            } catch (e: GoogleAuthException) {
                Log.w(TAG, "Erro de autenticação Google ao renovar token após HTTP 401: ${e.message}")
                if (isOperationCurrent(session, epochAtStart)) {
                    _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                }
                throw DriveApiException(401, "Não autorizado (HTTP 401). Falha ao renovar token: ${e.message}")
            } catch (e: IOException) {
                Log.w(TAG, "Falha de rede/I/O ao renovar token após HTTP 401: ${e.message}")
                if (isOperationCurrent(session, epochAtStart)) {
                    _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                }
                throw DriveApiException(401, "Não autorizado (HTTP 401). Falha de rede ao renovar token: ${e.message}", cause = e)
            } catch (e: Exception) {
                throw DriveApiException(401, "Não autorizado (HTTP 401). Falha ao renovar token: ${e.message}", cause = e)
            }

            // The account can change while the token is being renewed: check again before the retry.
            // newToken belongs to the current account, so it is NOT invalidated here.
            if (!isOperationCurrent(session, epochAtStart)) {
                Log.w(TAG, "Sessão alterada durante a renovação do token. Descartando a requisição antiga sem repeti-la.")
                throw DriveApiException(401, "Não autorizado (HTTP 401). A sessão mudou durante a renovação do token; requisição antiga descartada.")
            }

            val retryRequest = initialRequest.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()

            val retryResponse = try {
                httpClient.newCall(retryRequest).execute()
            } catch (e: Exception) {
                throw IllegalStateException("Erro de rede no retry ao conectar com o Google Drive: ${e.localizedMessage}", e)
            }

            return retryResponse.use { res ->
                val code = res.code
                if (!res.isSuccessful) {
                    if (code == 401) {
                        authGateway.invalidateToken(newToken)
                    }
                    val bodyStr = res.body?.string() ?: ""
                    val errorDetail = extractErrorMessage(bodyStr)
                    throw DriveApiException(code, "Falha na requisição ao Drive após retry ($code): $errorDetail", bodyStr)
                }
                responseParser(res)
            }
        }

        return response.use { res ->
            val code = res.code
            if (!res.isSuccessful) {
                val bodyStr = res.body?.string() ?: ""
                val errorDetail = extractErrorMessage(bodyStr)
                throw DriveApiException(code, "Falha na requisição ao Drive ($code): $errorDetail", bodyStr)
            }
            responseParser(res)
        }
    }

    internal fun executeHttp(request: Request, session: SyncSessionSnapshot? = null): String {
        return executeHttpInternal(request, session) { it.body?.string() ?: "" }
    }

    internal fun executeHttpBytes(request: Request, session: SyncSessionSnapshot? = null): ByteArray {
        return executeHttpInternal(request, session) { res ->
            res.body?.bytes() ?: throw IllegalStateException("Arquivo retornado pelo Google Drive está vazio.")
        }
    }

    // ---------------------------------------------------------------------------------------
    // SYNC (local -> Drive)
    // ---------------------------------------------------------------------------------------

    /**
     * Reads a consistent snapshot of the database (single Room transaction). Fast and local:
     * safe to call while holding a database lock, unlike the upload itself.
     */
    suspend fun captureBackup(repository: StellaRepository): CapturedBackup {
        // Read the counters BEFORE the snapshot: a write racing with us can only make the
        // pending flag stay set (extra sync), never be cleared wrongly.
        val version = changeVersion.get()
        val epoch = restoreEpoch.get()
        val currentSessionEpoch = sessionEpoch.get()
        val currentEmail = resolveAccountEmail()
        return CapturedBackup(
            snapshot = repository.getBackupSnapshot(),
            changeVersion = version,
            restoreEpoch = epoch,
            sessionEpoch = currentSessionEpoch,
            sessionEmail = currentEmail
        )
    }

    suspend fun syncAllRecordsToDrive(repository: StellaRepository): Boolean =
        syncToDrive(captureBackup(repository))

    /**
     * Uploads a captured snapshot. Returns true ONLY when every attachment, the backup manifest and
     * the readable report were uploaded and each HTTP response was 2xx.
     */
    suspend fun syncToDrive(captured: CapturedBackup): Boolean {
        val initialSession = SyncSessionSnapshot(
            epoch = if (captured.sessionEpoch >= 0L) captured.sessionEpoch else sessionEpoch.get(),
            accountEmail = captured.sessionEmail ?: resolveAccountEmail(),
            gatewayEmail = authGateway.getConnectedAccount()?.email?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (!isSyncApprovedForAccount(initialSession.accountEmail)) {
            Log.w(TAG, "Envio bloqueado: sincronização ainda não liberada para a conta atual")
            return false
        }
        return withContext(Dispatchers.IO) {
            operationMutex.withLock { runSync(captured, initialSession) }
        }
    }

    suspend fun checkRemoteBackupExists(email: String? = resolveAccountEmail()): Boolean = withContext(Dispatchers.IO) {
        val accountEmail = email?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("E-mail da conta Google não informado para verificação de backup.")
        val token = getValidAuthToken()
        val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail)
        findFileInFolder(token, folderId, BACKUP_FILE_NAME) != null
    }

    /** `<prefix>AAAAMMDD_HHmm.json`, always with ASCII digits whatever the device language. */
    private fun safetyCopyFileName(prefix: String): String =
        prefix + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".json"

    /**
     * Makes a dated copy of the backup that is on Drive right now, in the same folder, BEFORE the user
     * replaces it with this device's data. Done by Drive itself (`files.copy`), so nothing is downloaded.
     * Earlier copies are never touched or deleted.
     *
     * The caller must NOT replace the cloud backup unless the result is [SafetyCopyResult.Created] or
     * [SafetyCopyResult.NothingToCopy]. A failure is also published in [syncState].
     */
    suspend fun copyExistingBackupForSafety(
        email: String? = resolveAccountEmail()
    ): SafetyCopyResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                val accountEmail = email?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("E-mail da conta Google não informado para criar a cópia de segurança.")
                _syncState.value = DriveSyncState.Syncing("Criando cópia de segurança do backup que está na nuvem...")
                val token = getValidAuthToken()
                val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail)
                val result = copyCurrentBackupInFolder(token, folderId)
                _syncState.value = DriveSyncState.Idle
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserRecoverableAuthException) {
                Log.w(TAG, "Autorização recuperável necessária ao criar a cópia de segurança: ${e.message}")
                _recoverableAuthIntent.value = e.intent
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                safetyCopyFailure(e)
            } catch (e: GoogleAuthException) {
                Log.w(TAG, "Erro de autenticação Google ao criar a cópia de segurança: ${e.message}")
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                safetyCopyFailure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao criar a cópia de segurança do backup da nuvem", e)
                safetyCopyFailure(e)
            }
        }
    }

    /**
     * The copy itself, for callers that already hold [operationMutex]. Throws on any failure and never
     * touches the sync state. [SafetyCopyResult.Failed] is never returned from here.
     */
    private fun copyCurrentBackupInFolder(token: String, folderId: String): SafetyCopyResult {
        val sourceId = findFileInFolder(token, folderId, BACKUP_FILE_NAME)
            ?: return SafetyCopyResult.NothingToCopy
        val copyName = safetyCopyFileName(SAFETY_COPY_CLOUD_PREFIX)
        // `parents` is passed explicitly: the docs do not say where a copy lands by default.
        val body = JSONObject().apply {
            put("name", copyName)
            put("parents", JSONArray().put(folderId))
        }
        val request = Request.Builder()
            .url("$driveApiUrl/files/$sourceId/copy?fields=id,name")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        val created = JSONObject(executeHttp(request))
        val copyId = created.optString("id")
        if (copyId.isBlank()) {
            throw IllegalStateException("O Google Drive não devolveu o identificador da cópia de segurança.")
        }
        return SafetyCopyResult.Created(created.optString("name", copyName), copyId)
    }

    private fun safetyCopyFailure(e: Exception): SafetyCopyResult.Failed {
        val detail = e.localizedMessage ?: e.message ?: "erro desconhecido"
        val message = "Não foi possível criar a cópia de segurança do backup que está na nuvem. " +
            "Nada foi alterado. ($detail)"
        _syncState.value = DriveSyncState.Error(message)
        return SafetyCopyResult.Failed(message)
    }


    private fun applySyncFailureConnectionStatus(e: Throwable, email: String?) {
        when (e) {
            is UserRecoverableAuthException -> {
                _recoverableAuthIntent.value = e.intent
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            is GoogleAuthException -> {
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            is DriveApiException -> {
                val code = e.statusCode
                val body = e.rawBody
                when {
                    e.cause is IOException -> {
                        _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                    }
                    code == 404 -> {
                        persistFolderId(email, null)
                        _connectionStatus.value = DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                    }
                    code == 403 -> {
                        when {
                            isAppNotAuthorizedToFile(body) -> {
                                persistFolderId(email, null)
                                _connectionStatus.value = DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                            }
                            isScopeOrPermissionError(body) -> {
                                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                            }
                            else -> {
                                _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                            }
                        }
                    }
                    code == 429 || (code in 500..599) -> {
                        _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                    }
                    else -> {
                        _connectionStatus.value = DriveConnectionStatus.ERROR
                    }
                }
            }
            else -> {
                if (e is IOException || e.cause is IOException) {
                    _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                } else if (
                    _connectionStatus.value != DriveConnectionStatus.PERMISSION_MISSING &&
                    _connectionStatus.value != DriveConnectionStatus.UNVERIFIED &&
                    _connectionStatus.value != DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE
                ) {
                    _connectionStatus.value = DriveConnectionStatus.ERROR
                }
            }
        }
    }

    private fun runSync(captured: CapturedBackup, session: SyncSessionSnapshot): Boolean {
        return try {
            if (!isSyncSessionValid(session)) {
                Log.w(TAG, "Sessão alterada ou desconectada antes do início do envio. Abortando sync antigo.")
                return false
            }

            if (captured.restoreEpoch != restoreEpoch.get()) {
                _syncState.value = DriveSyncState.Error(
                    "O prontuário foi restaurado durante a preparação do envio. Sincronize novamente para enviar os dados atuais."
                )
                return false
            }

            val snapshot = captured.snapshot
            val profile = snapshot.profile
            val documents = snapshot.documents

            val accountEmail = session.accountEmail ?: resolveAccountEmail()
            if (accountEmail != null && isSyncSessionValid(session)) {
                _connectedAccountEmail.value = accountEmail
            }

            _syncState.value = DriveSyncState.Syncing("Verificando autenticação com o Google...")
            val token = authGateway.getToken()

            if (!isSyncSessionValid(session)) {
                Log.w(TAG, "Sessão alterada ou desconectada após obter token. Abortando sync antigo.")
                return false
            }

            _syncState.value = DriveSyncState.Syncing("Acessando pasta '$DRIVE_FOLDER_NAME' no Drive...")
            val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail, session)

            if (!isSyncSessionValid(session)) {
                Log.w(TAG, "Sessão alterada ou desconectada após acessar pasta. Abortando sync antigo.")
                return false
            }

            _syncState.value = DriveSyncState.Syncing("Sincronizando anexos e fotos...")
            val failedAttachments = mutableListOf<String>()
            val fileManifests = backupAllFiles(
                token = token,
                folderId = folderId,
                snapshot = snapshot,
                session = session,
                failures = failedAttachments,
                tolerateMissingLocalFiles = false
            ) ?: return false

            if (!isSyncSessionValid(session)) {
                Log.w(TAG, "Sessão alterada ou desconectada durante o envio de anexos. Abortando sync antigo.")
                return false
            }

            // A missing or failed attachment means the backup is NOT complete: never report Success,
            // and leave the pending flag set. The previous backup on Drive is left untouched.
            if (failedAttachments.isNotEmpty()) {
                val errorMsg = "Falha ao sincronizar ${failedAttachments.size} anexo(s) obrigatório(s): " +
                    failedAttachments.joinToString("; ")
                Log.e(TAG, errorMsg)
                _syncState.value = DriveSyncState.Error(errorMsg)
                return false
            }

            _syncState.value = DriveSyncState.Syncing("Gerando manifesto de backup estruturado...")
            val backupJson = buildCompleteVersionedBackup(
                profile, snapshot.glucoseList, snapshot.medications, snapshot.administrations,
                snapshot.appointments, documents, snapshot.feeding, snapshot.weights,
                snapshot.vaccines, snapshot.reminders,
                fileManifests.legacy,
                fileManifests.records
            )

            val humanReport = buildTextReport(
                profile, snapshot.glucoseList, snapshot.medications, snapshot.appointments,
                documents, snapshot.feeding, snapshot.weights
            )

            if (!isSyncSessionValid(session)) return false

            _syncState.value = DriveSyncState.Syncing("Enviando backup para o Google Drive...")
            uploadFileToDrive(
                token = token,
                folderId = folderId,
                fileName = BACKUP_FILE_NAME,
                mimeType = "application/json",
                content = backupJson.toByteArray(Charsets.UTF_8),
                session = session
            )

            if (!isSyncSessionValid(session)) return false

            uploadFileToDrive(
                token = token,
                folderId = folderId,
                fileName = REPORT_FILE_NAME,
                mimeType = "text/plain",
                content = humanReport.toByteArray(Charsets.UTF_8),
                session = session
            )

            // Final session validation before recording any success state, lastSync, connectionStatus or clearing pending
            if (!isSyncSessionValid(session)) {
                Log.w(TAG, "Sessão alterada ou desconectada antes da gravação final do envio. Descartando resultado da sessão antiga.")
                return false
            }

            // Only reached when every HTTP operation above returned 2xx and the session is still valid.
            val dateStr = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()).format(Date())
            _lastSyncTimestamp.value = dateStr
            prefs.edit().putString(KEY_LAST_SYNC_TIMESTAMP, dateStr).apply()
            clearPendingIfUnchanged(captured.changeVersion)
            _connectionStatus.value = DriveConnectionStatus.CONFIGURED

            val totalSyncedItems = snapshot.glucoseList.size + snapshot.medications.size +
                snapshot.appointments.size + documents.size + snapshot.weights.size + snapshot.vaccines.size

            _syncState.value = DriveSyncState.Success(
                lastSyncDate = dateStr,
                folderName = DRIVE_FOLDER_NAME,
                fileCount = totalSyncedItems
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserRecoverableAuthException) {
            if (!isSyncSessionValid(session)) return false
            Log.w(TAG, "Autorização recuperável necessária durante a sincronização: ${e.message}")
            applySyncFailureConnectionStatus(e, session.accountEmail)
            _syncState.value = DriveSyncState.Error(
                message = e.localizedMessage ?: "Autorização necessária para sincronizar com o Google Drive"
            )
            false
        } catch (e: GoogleAuthException) {
            if (!isSyncSessionValid(session)) return false
            Log.w(TAG, "Erro de autenticação Google durante a sincronização: ${e.message}")
            applySyncFailureConnectionStatus(e, session.accountEmail)
            _syncState.value = DriveSyncState.Error(
                message = e.localizedMessage ?: "Permissão ausente para sincronizar com o Google Drive"
            )
            false
        } catch (e: DriveApiException) {
            if (!isSyncSessionValid(session)) return false
            Log.e(TAG, "Drive HTTP error", e)
            applySyncFailureConnectionStatus(e, session.accountEmail)
            _syncState.value = DriveSyncState.Error(
                message = e.message ?: "Erro HTTP ao comunicar com o Google Drive",
                statusCode = e.statusCode
            )
            false
        } catch (e: Exception) {
            if (!isSyncSessionValid(session)) return false
            Log.e(TAG, "Drive general error", e)
            applySyncFailureConnectionStatus(e, session.accountEmail)
            _syncState.value = DriveSyncState.Error(
                message = e.localizedMessage ?: "Erro inesperado ao sincronizar com o Google Drive"
            )
            false
        }
    }

    private class BackupFileManifests(val legacy: JSONArray, val records: JSONArray)

    /** A file that cannot be read on this device (missing, moved, changed). */
    private class LocalFileUnavailableException(message: String) : Exception(message)

    /** The upload session Drive gave us is gone (expired or unknown): start the whole file again. */
    private class ResumableSessionLostException(message: String) : Exception(message)

    /** What is already on Drive under a given name. [size] is null when Drive did not report one. */
    private class RemoteFile(val id: String, val size: Long?)

    /** Content to upload: kept in memory, or read piece by piece from a private file. */
    private class UploadPayload private constructor(
        val size: Long,
        private val bytes: ByteArray?,
        private val file: File?
    ) {
        fun readAll(): ByteArray = bytes ?: file!!.readBytes()

        fun readRange(offset: Long, length: Int): ByteArray {
            if (bytes != null) return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            RandomAccessFile(file!!, "r").use { raf ->
                raf.seek(offset)
                val out = ByteArray(length)
                raf.readFully(out)
                return out
            }
        }

        companion object {
            fun of(bytes: ByteArray) = UploadPayload(bytes.size.toLong(), bytes, null)
            fun of(file: File, size: Long) = UploadPayload(size, null, file)
        }
    }

    private val attachmentStorage by lazy { AttachmentStorage(context) }

    /**
     * Sends every file the backup refers to: the profile photo, the legacy exam files and the attachments of
     * consultations and exams. Each file goes to Drive under a name made from its content, and is NOT sent
     * again when a file of that name and size is already there.
     *
     * Returns null when the sign-in session changed halfway (the caller must drop the result). Reasons a
     * file could not be saved are added to [failures]. With [tolerateMissingLocalFiles] a file that is no
     * longer readable on this device is skipped instead of counted as a failure: it is used for the copy of
     * the device's data, where a file that is already lost has nothing left to protect.
     */
    private fun backupAllFiles(
        token: String,
        folderId: String,
        snapshot: BackupSnapshot,
        session: SyncSessionSnapshot?,
        failures: MutableList<String>,
        tolerateMissingLocalFiles: Boolean
    ): BackupFileManifests? {
        fun stale() = session != null && !isSyncSessionValid(session)
        val legacy = JSONArray()
        val records = JSONArray()
        val profile = snapshot.profile

        if (!profile.photoUri.isNullOrBlank()) {
            if (stale()) return null
            val entry = backupAttachment(
                token = token,
                folderId = folderId,
                uriStr = profile.photoUri,
                fileTypeHint = null,
                label = "Foto de perfil",
                failures = failures,
                session = session,
                tolerateMissingLocalFile = tolerateMissingLocalFiles
            )
            entry?.let {
                it.put("type", "PROFILE_PHOTO")
                it.put("targetId", profile.id)
                legacy.put(it)
            }
        }

        snapshot.documents.forEach { doc ->
            if (!doc.fileUriOrPath.isNullOrBlank()) {
                if (stale()) return null
                val entry = backupAttachment(
                    token = token,
                    folderId = folderId,
                    uriStr = doc.fileUriOrPath,
                    fileTypeHint = doc.fileType,
                    label = "Exame '${doc.name}'",
                    failures = failures,
                    session = session,
                    tolerateMissingLocalFile = tolerateMissingLocalFiles
                )
                entry?.let {
                    it.put("type", "EXAM_DOCUMENT")
                    it.put("targetId", doc.id)
                    it.put("originalName", doc.name)
                    legacy.put(it)
                }
            }
        }

        snapshot.attachments.forEach { att ->
            if (stale()) return null
            backupRecordAttachment(token, folderId, att, failures, session, tolerateMissingLocalFiles)
                ?.let { records.put(it) }
        }
        return BackupFileManifests(legacy, records)
    }

    /**
     * Reads a local file (profile photo or legacy exam file) and uploads it under a name made from its
     * content. Returns the manifest entry (with the real Drive file id, MIME type, size and SHA-256) or null
     * after recording the reason in [failures].
     */
    internal fun backupAttachment(
        token: String,
        folderId: String,
        uriStr: String,
        fileTypeHint: String?,
        label: String,
        failures: MutableList<String>,
        session: SyncSessionSnapshot? = null,
        tolerateMissingLocalFile: Boolean = false
    ): JSONObject? {
        val bytes = readUriBytes(uriStr)
        if (bytes == null || bytes.isEmpty()) {
            if (tolerateMissingLocalFile) {
                Log.w(TAG, "$label: arquivo local ilegível; não entra na cópia de segurança do aparelho.")
            } else {
                failures.add("$label: não foi possível ler o arquivo local em '$uriStr'")
            }
            return null
        }
        val meta = detectFileMeta(uriStr, fileTypeHint, bytes)
        val sha256 = sha256Hex(bytes)
        val driveFileName = attachmentFileName(sha256, meta.extension)
        return guardedUpload(label, driveFileName, session, failures, tolerateMissingLocalFile) {
            val fileId = uploadContentFile(token, folderId, driveFileName, meta.mimeType, UploadPayload.of(bytes), session)
            JSONObject().apply {
                put("driveFileName", driveFileName)
                put("driveFileId", fileId)
                put("mimeType", meta.mimeType)
                put("sizeBytes", bytes.size.toLong())
                put("sha256", sha256)
            }
        }
    }

    /**
     * Uploads the file of one attachment of a consultation or exam. Nothing is read from the device when
     * Drive already has the file (same content name, same size).
     */
    private fun backupRecordAttachment(
        token: String,
        folderId: String,
        att: Attachment,
        failures: MutableList<String>,
        session: SyncSessionSnapshot?,
        tolerateMissingLocalFile: Boolean
    ): JSONObject? {
        val label = "Anexo '${att.displayName}'"
        val sha256 = att.sha256.lowercase(Locale.ROOT)
        val extension = extensionForMimeType(att.mimeType)
        if (!SHA256_HEX.matches(sha256) || att.sizeBytes <= 0L || extension == null) {
            failures.add("$label: o registro do anexo está incompleto e não pode ser enviado")
            return null
        }
        val driveFileName = attachmentFileName(sha256, extension)
        return guardedUpload(label, driveFileName, session, failures, tolerateMissingLocalFile) {
            val remote = findRemoteFile(token, folderId, driveFileName, session)
            val fileId = if (remote != null && remote.size == att.sizeBytes) {
                remote.id // already on Drive: nothing to read and nothing to send
            } else {
                val file = attachmentStorage.fileFor(att.localPath)
                    ?: throw LocalFileUnavailableException("o arquivo não está mais neste aparelho")
                if (file.length() != att.sizeBytes) {
                    throw LocalFileUnavailableException("o arquivo neste aparelho tem tamanho diferente do registrado")
                }
                if (sha256Hex(file) != sha256) {
                    throw LocalFileUnavailableException("o arquivo neste aparelho foi alterado depois de anexado")
                }
                writeContent(token, folderId, driveFileName, att.mimeType, UploadPayload.of(file, att.sizeBytes), remote, session)
            }
            JSONObject().apply {
                put("driveFileName", driveFileName)
                put("driveFileId", fileId)
                put("mimeType", normalizeMime(att.mimeType))
                put("sizeBytes", att.sizeBytes)
                put("sha256", sha256)
                put("ownerType", att.ownerType)
                put("ownerId", att.ownerId)
                put("label", att.label)
                put("displayName", att.displayName)
                put("createdAt", att.createdAt)
            }
        }
    }

    /**
     * Runs one file upload and applies the error rules every file shares: a sign-in problem stops the whole
     * sync (and is rethrown), cancellation is never swallowed, and any other problem is recorded in
     * [failures] so the backup is never reported as complete.
     */
    private fun guardedUpload(
        label: String,
        driveFileName: String,
        session: SyncSessionSnapshot?,
        failures: MutableList<String>,
        tolerateMissingLocalFile: Boolean,
        block: () -> JSONObject
    ): JSONObject? {
        return try {
            block()
        } catch (e: LocalFileUnavailableException) {
            if (tolerateMissingLocalFile) {
                Log.w(TAG, "$label: ${e.message}; não entra na cópia de segurança do aparelho.")
            } else {
                failures.add("$label: ${e.message}")
            }
            null
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Autorização recuperável necessária ao enviar anexo '$driveFileName': ${e.message}")
            if (isOperationCurrent(session)) {
                _recoverableAuthIntent.value = e.intent
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            throw e
        } catch (e: GoogleAuthException) {
            Log.w(TAG, "Erro de autenticação Google ao enviar anexo '$driveFileName': ${e.message}")
            if (isOperationCurrent(session)) {
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isOperationCurrent(session)) {
                applySyncFailureConnectionStatus(e, session?.accountEmail ?: resolveAccountEmail())
            }
            failures.add("$label ($driveFileName): ${e.localizedMessage ?: e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // PREVIEW / RESTORE (Drive -> local)
    // ---------------------------------------------------------------------------------------

    /**
     * What the backup on Drive holds. By default the current backup; with [versionFileId] one of the files
     * returned by [listBackupVersions]. Nothing is changed on this device.
     */
    suspend fun getBackupPreviewFromDrive(
        explicitEmail: String? = null,
        versionFileId: String? = null
    ): BackupManifestPreview = withContext(Dispatchers.IO) {
        val accountEmail = resolveAccountEmail(explicitEmail)
        val token = getValidAuthToken()
        val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail)
        val fileId = if (versionFileId != null) {
            resolveVersionFile(token, folderId, versionFileId).fileId
        } else {
            findFileInFolder(token, folderId, BACKUP_FILE_NAME)
                ?: throw IllegalStateException("Nenhum backup encontrado na pasta '$DRIVE_FOLDER_NAME' do seu Google Drive.")
        }

        val request = Request.Builder()
            .url("$driveApiUrl/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val jsonStr = executeHttp(request)
        val root = JSONObject(jsonStr)

        val backupVer = root.optInt("backupVersion", 1)
        val exportDate = root.optString("exportDate", "Desconhecida")
        val profileJson = root.optJSONObject("profile")
        val profileName = profileJson?.optString("name") ?: StellaProfile().name

        BackupManifestPreview(
            backupVersion = backupVer,
            exportDate = exportDate,
            profileName = profileName,
            glucoseCount = root.optJSONArray("glucoseRecords")?.length() ?: 0,
            medicationCount = root.optJSONArray("medications")?.length() ?: 0,
            appointmentCount = root.optJSONArray("appointments")?.length() ?: 0,
            documentCount = root.optJSONArray("examDocuments")?.length() ?: 0,
            weightCount = root.optJSONArray("weightRecords")?.length() ?: 0,
            vaccineCount = root.optJSONArray("vaccines")?.length() ?: 0,
            feedingCount = root.optJSONArray("feedingRecords")?.length() ?: 0,
            reminderCount = root.optJSONArray("reminders")?.length() ?: 0,
            attachmentCount = (root.optJSONArray("attachments")?.length() ?: 0) +
                (root.optJSONArray("recordAttachments")?.length() ?: 0)
        )
    }

    // ---- Versions of the backup ------------------------------------------------------------------

    /**
     * Every backup file in the Drive folder the user can go back to: the current backup first, then the
     * dated copies, newest first. Only metadata is read (no file is downloaded); use
     * [getBackupPreviewFromDrive] with a version's id to see what is inside one.
     */
    suspend fun listBackupVersions(explicitEmail: String? = null): List<BackupVersion> = withContext(Dispatchers.IO) {
        val accountEmail = resolveAccountEmail(explicitEmail)
        val token = getValidAuthToken()
        val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail)
        listFolderFiles(token, folderId)
            .mapNotNull { file -> backupVersionOf(file) }
            .sortedWith(
                compareBy<BackupVersion> { it.kind != BackupVersionKind.CURRENT }
                    .thenByDescending { it.createdAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.fileName }
            )
    }

    private fun versionKindOf(fileName: String): BackupVersionKind? = when {
        fileName == BACKUP_FILE_NAME -> BackupVersionKind.CURRENT
        fileName.startsWith(SAFETY_COPY_CLOUD_PREFIX) && fileName.endsWith(".json") -> BackupVersionKind.CLOUD_BEFORE_OVERWRITE
        fileName.startsWith(SAFETY_COPY_DEVICE_PREFIX) && fileName.endsWith(".json") -> BackupVersionKind.DEVICE_BEFORE_RESTORE
        else -> null
    }

    /** The time in a copy's name (`AAAAMMDD_HHmm`, device time), used only when Drive gave no time. */
    private fun timeFromCopyName(fileName: String): Long? {
        val stamp = Regex("(\\d{8}_\\d{4})\\.json$").find(fileName)?.groupValues?.get(1) ?: return null
        return try {
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).parse(stamp)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun backupVersionOf(file: DriveFileInfo): BackupVersion? {
        val kind = versionKindOf(file.name) ?: return null
        val time = if (kind == BackupVersionKind.CURRENT) {
            file.modifiedTimeMillis ?: file.createdTimeMillis
        } else {
            file.createdTimeMillis ?: file.modifiedTimeMillis ?: timeFromCopyName(file.name)
        }
        return BackupVersion(file.id, file.name, kind, time, file.size)
    }

    /**
     * Confirms that [fileId] is a backup file of THIS folder (not trashed, one of the known names) before
     * anything is read or restored from it. A file id that came from outside the list is refused.
     */
    private fun resolveVersionFile(token: String, folderId: String, fileId: String): BackupVersion {
        val request = Request.Builder()
            .url("$driveApiUrl/files/$fileId?fields=id,name,size,parents,trashed,createdTime,modifiedTime")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val json = JSONObject(executeHttp(request))
        val parents = json.optJSONArray("parents")
        val inFolder = parents != null && (0 until parents.length()).any { parents.optString(it) == folderId }
        val version = if (!json.optBoolean("trashed", false) && inFolder) {
            backupVersionOf(
                DriveFileInfo(
                    id = json.optString("id").ifBlank { fileId },
                    name = json.optString("name"),
                    size = json.optString("size").toLongOrNull(),
                    createdTimeMillis = parseCreatedTimeMillis(json.optString("createdTime")),
                    modifiedTimeMillis = parseCreatedTimeMillis(json.optString("modifiedTime"))
                )
            )
        } else {
            null
        }
        return version ?: throw IllegalStateException("Esta versão não faz parte do backup do Stella nesta conta do Google Drive.")
    }

    // ---- Deleting what the app keeps on Drive ----------------------------------------------------

    /**
     * How many files the app has in its Drive folder: what [deleteAllCloudFiles] would delete. With the
     * `drive.file` scope the app only sees the files it created itself, so nothing else is counted.
     * Like the other Drive reads, it creates the (empty) folder when the account has none yet.
     */
    suspend fun countCloudFiles(explicitEmail: String? = null): CloudFilesCount = withContext(Dispatchers.IO) {
        val accountEmail = resolveAccountEmail(explicitEmail)
        val token = getValidAuthToken()
        val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail)
        val files = listFolderFiles(token, folderId).filterNot { it.isFolder }
        val attachments = files.count { it.name.startsWith(ATTACHMENT_FILE_PREFIX) }
        val backups = files.count { versionKindOf(it.name) != null }
        CloudFilesCount(
            total = files.size,
            backupFiles = backups,
            attachmentFiles = attachments,
            otherFiles = files.size - attachments - backups
        )
    }

    /**
     * PERMANENTLY deletes (no trash, no undo) every file the app has in its Drive folder, one by one, and
     * keeps the folder. Only [CloudDeleteResult.Deleted] means everything is gone; on anything else the
     * caller must not tell the user it is done, and can simply call this again later (it deletes what is
     * left). Files are deleted one at a time on purpose: Drive deletes everything inside a folder that is
     * deleted, and a folder is never touched here.
     *
     * It does not disconnect the account (see [disconnect]). A sync that is running or waiting is
     * dropped first, so nothing is uploaded while the folder is being emptied.
     */
    suspend fun deleteAllCloudFiles(explicitEmail: String? = null): CloudDeleteResult = withContext(Dispatchers.IO) {
        // Any sync captured before this point belongs to an older session and gives up.
        sessionEpoch.incrementAndGet()
        operationMutex.withLock {
            var deleted = 0
            var total: Int? = null
            val accountEmail = resolveAccountEmail(explicitEmail)
            try {
                _syncState.value = DriveSyncState.Syncing("Apagando os arquivos do Stella no Google Drive...")
                val token = getValidAuthToken()
                val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail)
                val files = listFolderFiles(token, folderId)
                    .filterNot { it.isFolder }
                    // The backups hold the readable data: they go first, the attachments after.
                    .sortedBy { it.name.startsWith(ATTACHMENT_FILE_PREFIX) }
                total = files.size

                var firstFailure: String? = null
                var offline = false
                for (file in files) {
                    try {
                        deleteDriveFile(token, file.id)
                        deleted++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GoogleAuthException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao apagar '${file.name}' do Drive: ${e.message}")
                        if (firstFailure == null) firstFailure = e.localizedMessage ?: e.message ?: "erro desconhecido"
                        if (isNetworkFailure(e)) {
                            offline = true
                            break
                        }
                        // The same problem would hit every file: stop instead of repeating it.
                        if (isGlobalDriveFailure(e)) break
                    }
                }

                if (firstFailure == null) {
                    // The cloud is empty now: "last synced on ..." would no longer be true.
                    _lastSyncTimestamp.value = "Nunca sincronizado"
                    prefs.edit().remove(KEY_LAST_SYNC_TIMESTAMP).apply()
                    _syncState.value = DriveSyncState.Idle
                    CloudDeleteResult.Deleted(deleted)
                } else {
                    cloudDeleteFailure(deleted, total, offline, firstFailure)
                }
            } catch (e: CancellationException) {
                _syncState.value = DriveSyncState.Error("Apagar os arquivos da nuvem foi interrompido antes de terminar.")
                throw e
            } catch (e: UserRecoverableAuthException) {
                Log.w(TAG, "Autorização recuperável necessária ao apagar a nuvem: ${e.message}")
                _recoverableAuthIntent.value = e.intent
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                cloudDeleteFailure(deleted, total, false, e.localizedMessage ?: "Autorização necessária")
            } catch (e: GoogleAuthException) {
                Log.w(TAG, "Erro de autenticação Google ao apagar a nuvem: ${e.message}")
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                cloudDeleteFailure(deleted, total, false, e.localizedMessage ?: "Permissão ausente")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao apagar os arquivos da nuvem", e)
                applySyncFailureConnectionStatus(e, accountEmail)
                cloudDeleteFailure(deleted, total, isNetworkFailure(e), e.localizedMessage ?: e.message)
            }
        }
    }

    private fun cloudDeleteFailure(deleted: Int, total: Int?, offline: Boolean, detail: String?): CloudDeleteResult.Failed {
        val progress = if (total != null) " Foram apagados $deleted de $total arquivo(s)." else ""
        val reason = if (detail.isNullOrBlank()) "" else " ($detail)"
        val message = if (offline) {
            "Sem conexão com a internet: a nuvem NÃO foi apagada por completo.$progress Repita quando estiver online.$reason"
        } else {
            "Não foi possível apagar todos os arquivos da nuvem.$progress Tente de novo.$reason"
        }
        _syncState.value = DriveSyncState.Error(message)
        return CloudDeleteResult.Failed(
            filesDeleted = deleted,
            filesRemaining = total?.let { it - deleted },
            message = message,
            offline = offline
        )
    }

    /** Permanent delete of one file. A file that is already gone (404) counts as deleted. */
    private fun deleteDriveFile(token: String, fileId: String) {
        val request = Request.Builder()
            .url("$driveApiUrl/files/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        try {
            executeHttp(request)
        } catch (e: DriveApiException) {
            if (e.statusCode != 404) throw e
        }
    }

    private fun isNetworkFailure(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is IOException }

    /** A problem that is not about one file: missing authorization, rate limit or a Drive outage. */
    private fun isGlobalDriveFailure(e: Throwable): Boolean {
        val code = (e as? DriveApiException)?.statusCode ?: return false
        return code == 401 || code == 429 || code in 500..599
    }

    /**
     * Signs the Google account out of the app and forgets the Drive folder and the sync approval for it.
     * Returns once Google Sign-In has finished. Nothing is deleted from Drive or from this device.
     */
    suspend fun disconnect() {
        suspendCancellableCoroutine<Unit> { continuation ->
            signOut { if (continuation.isActive) continuation.resume(Unit) }
        }
    }

    /**
     * Closes the sync lock for [email] until the user decides again (the same lock a new account starts
     * with), and drops any snapshot captured before. Used when the data on this device was erased or left
     * half erased while the account stays connected: without it the next edit would be uploaded over the
     * backup on Drive.
     */
    fun blockSyncUntilDecision(email: String?) {
        restoreEpoch.incrementAndGet()
        setSyncApprovedForAccount(email, false)
    }

    /**
     * Returns true ONLY for a complete restore. Any problem (attachment that could not be recovered,
     * record with an unusable date, administration that could not be linked to a medication,
     * alarms that could not be rescheduled) ends in [DriveSyncState.RestorePartial] and returns false.
     * A failure before the database is touched ends in [DriveSyncState.Error] and leaves local data as is.
     *
     * With [protectLocalData] (and [replaceExisting]), when this device holds data of its own, a dated copy
     * of it is first uploaded to the Drive folder. If that upload fails, nothing is restored and local data
     * stays as it was. Callers that go through the ViewModel always ask for this protection.
     *
     * With [versionFileId] (an id from [listBackupVersions]) that file is restored instead of the current
     * backup. Going back to a version that is not the current backup first makes a dated copy of the current
     * one on Drive, because the restored data will replace it at the next sync; if that copy fails nothing is
     * restored. After such a restore the data counts as not yet synced.
     */
    suspend fun restoreBackupFromDrive(
        repository: StellaRepository,
        replaceExisting: Boolean,
        protectLocalData: Boolean = false,
        versionFileId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                doRestore(repository, replaceExisting, protectLocalData, versionFileId)
            } catch (e: CancellationException) {
                _syncState.value = DriveSyncState.Error("A restauração foi interrompida antes de terminar.")
                throw e
            } catch (e: UserRecoverableAuthException) {
                Log.w(TAG, "Autorização recuperável necessária na restauração: ${e.message}")
                _recoverableAuthIntent.value = e.intent
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                _syncState.value = DriveSyncState.Error(
                    message = "Falha na restauração: ${e.localizedMessage ?: "Autorização necessária"}"
                )
                false
            } catch (e: GoogleAuthException) {
                Log.w(TAG, "Erro de autenticação Google na restauração: ${e.message}")
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                _syncState.value = DriveSyncState.Error(
                    message = "Falha na restauração: ${e.localizedMessage ?: "Permissão ausente"}"
                )
                false
            } catch (e: Exception) {
                Log.e(TAG, "Restore error", e)
                _syncState.value = DriveSyncState.Error(
                    message = "Falha na restauração: ${e.localizedMessage ?: "Erro desconhecido"}"
                )
                false
            }
        }
    }

    private suspend fun doRestore(
        repository: StellaRepository,
        replaceExisting: Boolean,
        protectLocalData: Boolean,
        versionFileId: String?
    ): Boolean {
        _syncState.value = DriveSyncState.Restoring("Autenticando e localizando backup no Drive...")
        val accountEmail = resolveAccountEmail()
        val token = getValidAuthToken()
        val folderId = getOrCreateDriveFolder(token, DRIVE_FOLDER_NAME, accountEmail)
        val chosenVersion = versionFileId?.let { resolveVersionFile(token, folderId, it) }
        val fileId = chosenVersion?.fileId
            ?: findFileInFolder(token, folderId, BACKUP_FILE_NAME)
            ?: throw IllegalStateException("Arquivo '$BACKUP_FILE_NAME' não encontrado no Google Drive.")
        // A version other than the current backup: the data restored from it will replace the current
        // backup at the next sync, so the current one is copied aside first.
        val restoringOlderVersion = chosenVersion != null && chosenVersion.kind != BackupVersionKind.CURRENT

        _syncState.value = DriveSyncState.Restoring("Baixando dados do prontuário...")
        val downloadRequest = Request.Builder()
            .url("$driveApiUrl/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val root = JSONObject(executeHttp(downloadRequest))

        val version = root.optInt("backupVersion", 0)
        if (version < OLDEST_READABLE_BACKUP_VERSION) {
            throw IllegalStateException("Versão de backup inválida ou não suportada ($version).")
        }
        if (version > CURRENT_BACKUP_VERSION) {
            throw IllegalStateException("Este backup foi criado por uma versão mais nova do Stella (v$version). Atualize o app antes de restaurar.")
        }

        if (restoringOlderVersion) {
            _syncState.value = DriveSyncState.Restoring("Guardando uma cópia do backup atual da nuvem...")
            try {
                val saved = copyCurrentBackupInFolder(token, folderId)
                Log.i(TAG, "Cópia do backup atual feita antes de restaurar outra versão: $saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: GoogleAuthException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Não foi possível guardar uma cópia do backup atual da nuvem. " +
                        "A restauração foi cancelada e nada foi alterado. (${e.localizedMessage ?: e.message})",
                    e
                )
            }
        }

        // The cloud backup exists and can be read. Before the first change to this device, keep what is
        // on it. Only when both sides have data: an empty device has nothing to lose.
        if (replaceExisting && protectLocalData) {
            val localSnapshot = repository.getBackupSnapshot()
            if (!localSnapshot.hasNoUserData()) {
                _syncState.value = DriveSyncState.Restoring("Guardando uma cópia dos dados deste aparelho no Drive...")
                try {
                    // The files go too (photo, exams, attachments), by content name: what Drive already has is
                    // not sent again, and a restore that replaces this device's data cannot lose a document.
                    val copyFailures = mutableListOf<String>()
                    val copyManifests = backupAllFiles(
                        token = token,
                        folderId = folderId,
                        snapshot = localSnapshot,
                        session = null,
                        failures = copyFailures,
                        tolerateMissingLocalFiles = true
                    )
                    if (copyManifests == null || copyFailures.isNotEmpty()) {
                        throw IllegalStateException(copyFailures.joinToString("; ").ifBlank { "arquivos não enviados" })
                    }
                    val copyId = createNewFileInFolder(
                        token = token,
                        folderId = folderId,
                        fileName = safetyCopyFileName(SAFETY_COPY_DEVICE_PREFIX),
                        mimeType = "application/json",
                        content = buildCompleteVersionedBackup(
                            localSnapshot.profile, localSnapshot.glucoseList, localSnapshot.medications,
                            localSnapshot.administrations, localSnapshot.appointments, localSnapshot.documents,
                            localSnapshot.feeding, localSnapshot.weights, localSnapshot.vaccines,
                            localSnapshot.reminders,
                            copyManifests.legacy,
                            copyManifests.records
                        ).toByteArray(Charsets.UTF_8)
                    )
                    Log.i(TAG, "Cópia de segurança dos dados do aparelho criada no Drive (id=$copyId)")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GoogleAuthException) {
                    // Includes UserRecoverableAuthException: restoreBackupFromDrive handles both.
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Não foi possível guardar uma cópia dos dados deste aparelho no Drive. " +
                            "A restauração foi cancelada e nada foi alterado. (${e.localizedMessage ?: e.message})",
                        e
                    )
                }
            }
        }

        // Everything that is not 100% restored is recorded here and decides the final state.
        val issues = mutableListOf<String>()

        // ---- Attachments -------------------------------------------------------------------
        _syncState.value = DriveSyncState.Restoring("Restaurando anexos e imagens...")
        val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val attachmentsArray = root.optJSONArray("attachments") ?: JSONArray()
        val attachments = (0 until attachmentsArray.length()).map { attachmentsArray.getJSONObject(it) }

        val examNamesById = HashMap<Long, String>()
        val dArrayForNames = root.optJSONArray("examDocuments") ?: JSONArray()
        for (i in 0 until dArrayForNames.length()) {
            val o = dArrayForNames.getJSONObject(i)
            examNamesById[o.optLong("id", 0L)] = o.optString("name", "")
        }

        val restoredExamPaths = HashMap<Long, String>()
        var restoredPhotoPath: String? = null
        var restoredFilesCount = 0

        for (att in attachments) {
            val type = att.optString("type")
            val targetId = att.optLong("targetId", 0L)
            val label = when (type) {
                "PROFILE_PHOTO" -> "Foto de perfil"
                "EXAM_DOCUMENT" -> "Exame '${examNamesById[targetId] ?: att.optString("originalName")}'"
                else -> "Anexo '${att.optString("driveFileName")}'"
            }
            val localFile = restoreStep(att.optString("driveFileName"), label, issues) {
                downloadAttachment(token, folderId, att, attachmentsDir)
            }
            if (localFile != null) {
                restoredFilesCount++
                when (type) {
                    "PROFILE_PHOTO" -> restoredPhotoPath = localFile.absolutePath
                    "EXAM_DOCUMENT" -> restoredExamPaths[targetId] = localFile.absolutePath
                }
            }
        }

        // Files attached to consultations and exams (backup version 2). The same content is fetched once.
        val recordsArray = root.optJSONArray("recordAttachments") ?: JSONArray()
        val recordAttachments = mutableListOf<Attachment>()
        val fetchedBySha = HashMap<String, Boolean>()
        for (i in 0 until recordsArray.length()) {
            val entry = recordsArray.optJSONObject(i)
            if (entry == null) {
                issues.add("Anexo ${i + 1}: registro ilegível no backup")
                continue
            }
            val label = "Anexo '${entry.optString("displayName").ifBlank { entry.optString("driveFileName") }}'"
            val parsed = parseRecordAttachment(entry, attachmentsDir)
            if (parsed == null) {
                issues.add("$label: registro incompleto ou inválido no backup")
                continue
            }
            val alreadyFetched = fetchedBySha[parsed.sha256]
            val fetched = alreadyFetched ?: run {
                val file = restoreStep(parsed.sha256, label, issues) {
                    downloadAttachmentContent(token, folderId, entry, attachmentsDir, parsed.sha256, parsed.extension)
                }
                (file != null).also {
                    fetchedBySha[parsed.sha256] = it
                    if (it) restoredFilesCount++
                }
            }
            // The row is kept even when its file could not be fetched: the user still sees which document
            // is missing (and the file may well be on Drive), and a later sync does not drop it from the backup.
            recordAttachments.add(parsed.attachment)
            if (!fetched && alreadyFetched != null) {
                issues.add("$label: o arquivo não pôde ser recuperado")
            }
        }

        // ---- Parse entities (ORIGINAL ids are kept; the DAO decides replace vs. remap) ------
        _syncState.value = DriveSyncState.Restoring("Reconstruindo banco de dados local...")
        val profileJson = root.getJSONObject("profile")

        val stalePhotoUri = profileJson.optString("photoUri").takeIf { it.isNotBlank() }
        val hasPhotoInManifest = attachments.any { it.optString("type") == "PROFILE_PHOTO" }
        if (restoredPhotoPath == null && stalePhotoUri != null && !hasPhotoInManifest && !isLocallyReadable(stalePhotoUri)) {
            issues.add("Foto de perfil: o arquivo não está no backup e não existe neste aparelho")
        }

        val profile = StellaProfile(
            id = 1,
            name = profileJson.optString("name", StellaProfile().name),
            photoUri = restoredPhotoPath ?: stalePhotoUri,
            species = profileJson.optString("species", "Cachorro"),
            breed = profileJson.optString("breed", "SRD"),
            gender = profileJson.optString("gender", StellaProfile().gender),
            birthDate = profileJson.optString("birthDate", ""),
            currentWeight = profileJson.optDouble("currentWeight", 0.0),
            color = profileJson.optString("color", ""),
            microchip = profileJson.optString("microchip", ""),
            vetRegistrationNumber = profileJson.optString("vetRegistrationNumber", ""),
            vetName = profileJson.optString("vetName", ""),
            clinicName = profileJson.optString("clinicName", ""),
            clinicPhone = profileJson.optString("clinicPhone", ""),
            notes = profileJson.optString("notes", ""),
            curiosities = profileJson.optString("curiosities", ""),
            personality = profileJson.optString("personality", "")
        )

        val glucoseList = mutableListOf<GlucoseRecord>()
        val gArray = root.optJSONArray("glucoseRecords") ?: JSONArray()
        for (i in 0 until gArray.length()) {
            val o = gArray.getJSONObject(i)
            val dateString = o.optString("dateString", "")
            val timeString = o.optString("timeString", "")
            glucoseList.add(
                GlucoseRecord(
                    id = o.optLong("id", 0L),
                    dateTimeMillis = resolveTimestamp(o, "dateTimeMillis", dateString, timeString, "Glicemia de $dateString $timeString", issues),
                    dateString = dateString,
                    timeString = timeString,
                    value = o.optDouble("value", 0.0),
                    unit = o.optString("unit", "mg/dL"),
                    mealRelation = o.optString("mealRelation", ""),
                    medicationRelation = o.optString("medicationRelation", ""),
                    notes = o.optString("notes", ""),
                    symptoms = o.optString("symptoms", ""),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }

        val medications = mutableListOf<Medication>()
        val mArray = root.optJSONArray("medications") ?: JSONArray()
        for (i in 0 until mArray.length()) {
            val o = mArray.getJSONObject(i)
            medications.add(
                Medication(
                    id = o.optLong("id", 0L),
                    name = o.optString("name", ""),
                    activeIngredient = o.optString("activeIngredient", ""),
                    dosage = o.optString("dosage", ""),
                    unit = o.optString("unit", "comprimido"),
                    administrationRoute = o.optString("administrationRoute", "Oral"),
                    quantity = o.optString("quantity", "1"),
                    frequency = o.optString("frequency", ""),
                    scheduledTimes = o.optString("scheduledTimes", ""),
                    startDate = o.optString("startDate", ""),
                    endDate = o.optString("endDate", ""),
                    vetName = o.optString("vetName", ""),
                    reason = o.optString("reason", ""),
                    notes = o.optString("notes", ""),
                    isVitaminOrSupplement = o.optBoolean("isVitaminOrSupplement", false),
                    status = o.optString("status", "ATIVO"),
                    suspensionReason = o.optString("suspensionReason", ""),
                    suspensionDate = o.optString("suspensionDate", ""),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }

        val administrations = mutableListOf<MedicationAdministration>()
        val aArray = root.optJSONArray("administrations") ?: JSONArray()
        for (i in 0 until aArray.length()) {
            val o = aArray.getJSONObject(i)
            val dateString = o.optString("dateString", "")
            val timeString = o.optString("timeString", "")
            administrations.add(
                MedicationAdministration(
                    id = o.optLong("id", 0L),
                    medicationId = o.optLong("medicationId", 0L),
                    medicationName = o.optString("medicationName", ""),
                    dosage = o.optString("dosage", ""),
                    timestamp = resolveTimestamp(o, "timestamp", dateString, timeString, "Administração de '${o.optString("medicationName", "")}' em $dateString $timeString", issues),
                    dateString = dateString,
                    timeString = timeString,
                    registeredBy = o.optString("registeredBy", "Tutor"),
                    notes = o.optString("notes", "")
                )
            )
        }

        val appointments = mutableListOf<VeterinaryAppointment>()
        val aptArray = root.optJSONArray("appointments") ?: JSONArray()
        for (i in 0 until aptArray.length()) {
            val o = aptArray.getJSONObject(i)
            val dateString = o.optString("dateString", "")
            val timeString = o.optString("timeString", "")
            appointments.add(
                VeterinaryAppointment(
                    id = o.optLong("id", 0L),
                    dateString = dateString,
                    timeString = timeString,
                    timestamp = resolveTimestamp(o, "timestamp", dateString, timeString, "Consulta de $dateString $timeString", issues),
                    vetName = o.optString("vetName", ""),
                    clinicName = o.optString("clinicName", ""),
                    reason = o.optString("reason", ""),
                    weightKg = if (o.has("weightKg") && !o.isNull("weightKg")) o.optDouble("weightKg") else null,
                    temperatureC = if (o.has("temperatureC") && !o.isNull("temperatureC")) o.optDouble("temperatureC") else null,
                    notes = o.optString("notes", ""),
                    symptomsReported = o.optString("symptomsReported", ""),
                    conductRecommended = o.optString("conductRecommended", ""),
                    medicationsPrescribed = o.optString("medicationsPrescribed", ""),
                    nextAppointmentDate = o.optString("nextAppointmentDate", ""),
                    attachedFileCount = o.optInt("attachedFileCount", 0),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }

        val documents = mutableListOf<ExamDocument>()
        val dArray = root.optJSONArray("examDocuments") ?: JSONArray()
        for (i in 0 until dArray.length()) {
            val o = dArray.getJSONObject(i)
            val docId = o.optLong("id", 0L)
            val docName = o.optString("name", "")
            val staleDocPath = o.optString("fileUriOrPath").takeIf { it.isNotBlank() }
            val restoredPath = restoredExamPaths[docId]
            val hasDocInManifest = attachments.any { it.optString("type") == "EXAM_DOCUMENT" && it.optLong("targetId") == docId }
            if (restoredPath == null && staleDocPath != null && !hasDocInManifest && !isLocallyReadable(staleDocPath)) {
                issues.add("Exame '$docName': o arquivo não está no backup e não existe neste aparelho")
            }

            documents.add(
                ExamDocument(
                    id = docId,
                    name = docName,
                    fileType = o.optString("fileType", "Documento"),
                    dateString = o.optString("dateString", ""),
                    clinicName = o.optString("clinicName", ""),
                    vetName = o.optString("vetName", ""),
                    category = o.optString("category", "Documento"),
                    notes = o.optString("notes", ""),
                    fileUriOrPath = restoredPath ?: staleDocPath,
                    isArchived = o.optBoolean("isArchived", false),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }

        val feeding = mutableListOf<FeedingRecord>()
        val fArray = root.optJSONArray("feedingRecords") ?: JSONArray()
        for (i in 0 until fArray.length()) {
            val o = fArray.getJSONObject(i)
            feeding.add(
                FeedingRecord(
                    id = o.optLong("id", 0L),
                    brand = o.optString("brand", ""),
                    foodName = o.optString("foodName", ""),
                    type = o.optString("type", "Seca"),
                    line = o.optString("line", "Super Premium"),
                    flavor = o.optString("flavor", ""),
                    dailyAmount = o.optString("dailyAmount", ""),
                    portionAmount = o.optString("portionAmount", ""),
                    mealsPerDay = o.optInt("mealsPerDay", 2),
                    scheduleTimes = o.optString("scheduleTimes", ""),
                    startDate = o.optString("startDate", ""),
                    endDate = o.optString("endDate", ""),
                    isCurrent = o.optBoolean("isCurrent", false),
                    notes = o.optString("notes", ""),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }

        val weights = mutableListOf<WeightRecord>()
        val wArray = root.optJSONArray("weightRecords") ?: JSONArray()
        for (i in 0 until wArray.length()) {
            val o = wArray.getJSONObject(i)
            val dateString = o.optString("dateString", "")
            weights.add(
                WeightRecord(
                    id = o.optLong("id", 0L),
                    timestamp = resolveTimestamp(o, "timestamp", dateString, "12:00", "Peso de $dateString", issues),
                    dateString = dateString,
                    weightKg = o.optDouble("weightKg", 0.0),
                    notes = o.optString("notes", "")
                )
            )
        }

        val vaccines = mutableListOf<VaccinePreventionRecord>()
        val vArray = root.optJSONArray("vaccines") ?: JSONArray()
        for (i in 0 until vArray.length()) {
            val o = vArray.getJSONObject(i)
            vaccines.add(
                VaccinePreventionRecord(
                    id = o.optLong("id", 0L),
                    name = o.optString("name", ""),
                    type = o.optString("type", "Vacina"),
                    dateAdministered = o.optString("dateAdministered", ""),
                    dose = o.optString("dose", ""),
                    nextDueDate = o.optString("nextDueDate", ""),
                    vetName = o.optString("vetName", ""),
                    clinicName = o.optString("clinicName", ""),
                    notes = o.optString("notes", ""),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }

        val reminders = mutableListOf<Reminder>()
        val rArray = root.optJSONArray("reminders") ?: JSONArray()
        for (i in 0 until rArray.length()) {
            val o = rArray.getJSONObject(i)
            val dateString = o.optString("dateString", "")
            val timeString = o.optString("timeString", "")
            reminders.add(
                Reminder(
                    id = o.optLong("id", 0L),
                    title = o.optString("title", ""),
                    category = o.optString("category", "Medicamento"),
                    dateString = dateString,
                    timeString = timeString,
                    timestamp = resolveTimestamp(o, "timestamp", dateString, timeString, "Lembrete '${o.optString("title", "")}' de $dateString $timeString", issues),
                    status = o.optString("status", "PENDENTE"),
                    executionTimestamp = if (o.has("executionTimestamp") && !o.isNull("executionTimestamp")) o.optLong("executionTimestamp") else null,
                    notes = o.optString("notes", "")
                )
            )
        }

        // ---- Database (one transaction) ------------------------------------------------------
        val notifManager = ReminderNotificationManager(context)
        if (replaceExisting) {
            // These reminders are about to be deleted: their alarms must not outlive them.
            repository.getAllRemindersSync().forEach { notifManager.cancelNotification(it.id) }
            val medicationAlarms = MedicationAlarmScheduler(context)
            repository.getAllMedicationsSync().forEach { medicationAlarms.cancelFor(it.id) }
        }

        val result = repository.restoreDatabase(
            profile = profile,
            glucoseList = glucoseList,
            medications = medications,
            administrations = administrations,
            appointments = appointments,
            documents = documents,
            feeding = feeding,
            weights = weights,
            vaccines = vaccines,
            reminders = reminders,
            replaceExisting = replaceExisting,
            attachments = recordAttachments
        )
        restoreEpoch.incrementAndGet()

        if (result.skippedAttachments.isNotEmpty()) {
            issues.add(
                "${result.skippedAttachments.size} anexo(s) ignorado(s): " +
                    "a consulta ou o exame correspondente não está no backup"
            )
        }
        if (result.skippedAdministrations.isNotEmpty()) {
            issues.add(
                "${result.skippedAdministrations.size} registro(s) de administração ignorado(s): " +
                    "o medicamento correspondente não está no backup"
            )
        }

        // ---- Alarms ---------------------------------------------------------------------------
        try {
            notifManager.rescheduleAllPendingReminders(repository.getAllRemindersSync())
            MedicationAlarmScheduler(context).scheduleAll(repository.getAllMedicationsSync())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reschedule reminders after restore: ${e.message}")
            issues.add("Lembretes restaurados, mas os alarmes não puderam ser reagendados: ${e.localizedMessage ?: e.message}")
        }

        val totalRecords = glucoseList.size + medications.size + result.restoredAdministrations +
            appointments.size + documents.size + weights.size + vaccines.size + feeding.size + reminders.size

        return if (issues.isEmpty()) {
            // Replace-restore of the current backup: local == backup. Incremental: local now has more than
            // the backup. Going back to another version: local differs from the current backup.
            if (replaceExisting && !restoringOlderVersion) {
                changeVersion.incrementAndGet()
                persistPending(false)
            } else {
                markPendingChanges()
            }
            _syncState.value = DriveSyncState.RestoreSuccess(
                message = "Prontuário restaurado com sucesso!",
                recordsCount = totalRecords,
                filesCount = restoredFilesCount
            )
            true
        } else {
            markPendingChanges()
            _syncState.value = DriveSyncState.RestorePartial(
                message = "Restauração concluída com ressalvas: ${issues.size} item(ns) não puderam ser recuperados por completo.",
                recordsCount = totalRecords,
                filesCount = restoredFilesCount,
                failedFiles = issues
            )
            false
        }
    }

    /**
     * Uses the stored timestamp when present; otherwise derives it from the date/time strings.
     * Never substitutes "now": if neither is usable the problem is reported and 0 is stored.
     */
    private fun resolveTimestamp(
        o: JSONObject,
        key: String,
        dateString: String,
        timeString: String,
        label: String,
        issues: MutableList<String>
    ): Long {
        val stored = if (o.has(key) && !o.isNull(key)) o.optLong(key, 0L) else 0L
        if (stored > 0L) return stored
        val parsed = DateTimeUtils.parseDateTimeToMillis(dateString, timeString)
        if (parsed != null) return parsed
        issues.add("$label: data/hora ausente ou inválida no backup")
        return 0L
    }

    private class FetchedAttachment(val bytes: ByteArray, val safeName: String, val mimeType: String?)

    private class ParsedRecordAttachment(val attachment: Attachment, val sha256: String, val extension: String)

    /**
     * Runs one restore step for a file. A sign-in problem stops the whole restore (rethrown); anything else
     * is recorded in [issues] and the step yields null, so the restore ends as partial, never as complete.
     */
    private inline fun <T> restoreStep(driveName: String, label: String, issues: MutableList<String>, block: () -> T): T? {
        return try {
            block()
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Autorização recuperável necessária ao restaurar anexo $driveName: ${e.message}")
            _recoverableAuthIntent.value = e.intent
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            throw e
        } catch (e: GoogleAuthException) {
            Log.w(TAG, "Erro de autenticação Google ao restaurar anexo $driveName: ${e.message}")
            _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao restaurar anexo $driveName: ${e.message}")
            issues.add("$label: ${e.localizedMessage ?: e.message}")
            null
        }
    }

    /**
     * Reads one entry of `recordAttachments`. The local path is NEVER taken from the backup: it is built
     * here from the SHA-256 and the type, so a damaged or hostile backup cannot point outside the folder.
     * Returns null when the entry is incomplete or not acceptable.
     */
    private fun parseRecordAttachment(o: JSONObject, attachmentsDir: File): ParsedRecordAttachment? {
        val sha256 = o.optString("sha256").lowercase(Locale.ROOT)
        val mimeType = normalizeMime(o.optString("mimeType"))
        val sizeBytes = if (o.has("sizeBytes")) o.optLong("sizeBytes", -1L) else -1L
        val ownerType = o.optString("ownerType")
        val ownerId = o.optLong("ownerId", 0L)
        val extension = extensionForMimeType(mimeType)
        if (!SHA256_HEX.matches(sha256) ||
            mimeType !in AttachmentLimits.ACCEPTED_MIME_TYPES ||
            sizeBytes <= 0L ||
            ownerType !in AttachmentOwner.ALL ||
            ownerId <= 0L ||
            extension == null
        ) return null
        val attachment = Attachment(
            id = 0L,
            ownerType = ownerType,
            ownerId = ownerId,
            label = o.optString("label").ifBlank { AttachmentLimits.DEFAULT_LABEL }.take(80),
            displayName = o.optString("displayName").ifBlank { "arquivo.$extension" }.take(120),
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            localPath = File(attachmentsDir, "$sha256.$extension").absolutePath,
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
        return ParsedRecordAttachment(attachment, sha256, extension)
    }

    /**
     * Downloads one file of the backup and checks the real result: it must exist on Drive, be non-empty,
     * match the size and type recorded at backup time and, when the backup recorded one, its SHA-256.
     * Throws with a readable reason otherwise.
     */
    private fun fetchAttachmentBytes(token: String, folderId: String, att: JSONObject): FetchedAttachment {
        val rawName = att.optString("driveFileName")
        var safeName = File(rawName).name
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw IllegalStateException("nome de arquivo inválido no backup ('$rawName')")
        }
        val expectedMime = att.optString("mimeType").takeIf { it.isNotBlank() }?.let { normalizeMime(it) }
        val expectedSize = if (att.has("sizeBytes")) att.optLong("sizeBytes", -1L) else -1L
        val expectedSha = att.optString("sha256").lowercase(Locale.ROOT).takeIf { SHA256_HEX.matches(it) }
        val storedId = att.optString("driveFileId").takeIf { it.isNotBlank() }

        var bytes: ByteArray? = null
        if (storedId != null) {
            try {
                bytes = downloadDriveFile(token, storedId)
            } catch (e: DriveApiException) {
                // Id no longer valid (file recreated/removed): fall back to the name lookup below.
                if (e.statusCode != 404) throw e
            }
        }
        if (bytes == null) {
            val idByName = findFileInFolder(token, folderId, safeName)
                ?: throw IllegalStateException("'$safeName' não encontrado no Google Drive")
            bytes = downloadDriveFile(token, idByName)
        }
        val data: ByteArray = bytes
            ?: throw IllegalStateException("'$safeName' não pôde ser baixado do Google Drive")

        if (data.isEmpty()) throw IllegalStateException("'$safeName' está vazio no Google Drive")
        if (expectedSize >= 0L && data.size.toLong() != expectedSize) {
            throw IllegalStateException("tamanho diferente do backup (esperado $expectedSize bytes, recebido ${data.size})")
        }
        val sniffed = sniffFileMeta(data)
        if (sniffed != null && expectedMime != null && sniffed.mimeType != expectedMime) {
            throw IllegalStateException("o conteúdo (${sniffed.mimeType}) não corresponde ao tipo registrado no backup ($expectedMime)")
        }
        if (expectedSha != null && sha256Hex(data) != expectedSha) {
            throw IllegalStateException("o conteúdo baixado não confere com o registrado no backup (arquivo corrompido)")
        }

        // Older manifests may lack the extension: recover it from the recorded MIME type.
        if (!safeName.contains('.') && expectedMime != null) {
            extensionForMimeType(expectedMime)?.let { safeName = "$safeName.$it" }
        }
        return FetchedAttachment(data, safeName, expectedMime ?: sniffed?.mimeType)
    }

    /** Photo or legacy exam file. A backup that records the SHA-256 is stored by content, like new attachments. */
    private fun downloadAttachment(token: String, folderId: String, att: JSONObject, attachmentsDir: File): File {
        val fetched = fetchAttachmentBytes(token, folderId, att)
        val sha = att.optString("sha256").lowercase(Locale.ROOT).takeIf { SHA256_HEX.matches(it) }
        if (sha != null) {
            val extension = fetched.mimeType?.let { extensionForMimeType(it) }
                ?: extensionOf(fetched.safeName)
                ?: "bin"
            return writeContentFile(attachmentsDir, sha, extension, fetched.bytes)
        }
        return writeAttachmentFile(attachmentsDir, fetched.safeName, fetched.bytes)
    }

    private fun downloadAttachmentContent(
        token: String,
        folderId: String,
        att: JSONObject,
        attachmentsDir: File,
        sha256: String,
        extension: String
    ): File {
        val fetched = fetchAttachmentBytes(token, folderId, att)
        return writeContentFile(attachmentsDir, sha256, extension, fetched.bytes)
    }

    /**
     * Stores [bytes] as `<sha256>.<extension>`: the name the rest of the app gives a file with this content.
     * A file already there with the same content is kept; a damaged one is replaced.
     */
    private fun writeContentFile(dir: File, sha256: String, extension: String, bytes: ByteArray): File {
        dir.mkdirs()
        val target = File(dir, "$sha256.$extension")
        if (target.isFile && target.length() == bytes.size.toLong() && sha256Hex(target) == sha256) return target
        // Written aside and renamed, so an interruption never leaves a half-written file under the real name.
        val temp = File.createTempFile("import_", ".part", dir)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
        } finally {
            temp.delete()
        }
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            throw IllegalStateException("não foi possível gravar '${target.name}' no armazenamento do aparelho")
        }
        return target
    }

    private fun downloadDriveFile(token: String, driveFileId: String): ByteArray {
        val request = Request.Builder()
            .url("$driveApiUrl/files/$driveFileId?alt=media")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return executeHttpBytes(request)
    }

    /** Never overwrites a different existing file; identical content is reused. */
    private fun writeAttachmentFile(dir: File, safeName: String, bytes: ByteArray): File {
        val base = safeName.substringBeforeLast('.', safeName)
        val ext = if (safeName.contains('.')) safeName.substringAfterLast('.') else ""
        var candidate = File(dir, safeName)
        var n = 1
        while (candidate.exists() && !candidate.readBytes().contentEquals(bytes)) {
            candidate = File(dir, if (ext.isEmpty()) "${base}_$n" else "${base}_$n.$ext")
            n++
        }
        if (!candidate.exists()) {
            FileOutputStream(candidate).use { it.write(bytes) }
        }
        if (!candidate.exists() || candidate.length() != bytes.size.toLong()) {
            throw IllegalStateException("não foi possível gravar '${candidate.name}' no armazenamento do aparelho")
        }
        return candidate
    }

    private fun isLocallyReadable(uriStr: String): Boolean {
        return try {
            if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { true } ?: false
            } else {
                val f = File(uriStr)
                f.exists() && f.canRead()
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Drive REST helpers
    // ---------------------------------------------------------------------------------------

    private fun escapeDriveQueryValue(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun parseCreatedTimeMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { return it }
        try {
            return java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {}
        try {
            return java.time.OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        return null
    }

    private fun selectOldestFolderId(files: JSONArray?): String? {
        if (files == null || files.length() == 0) return null
        data class CandidateFolder(
            val id: String,
            val createdTimeRaw: String?,
            val createdTimeMillis: Long?,
            val originalIndex: Int
        )
        val candidates = mutableListOf<CandidateFolder>()
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val id = item.optString("id", "").trim()
            if (id.isEmpty()) continue
            if (item.optBoolean("trashed", false)) continue
            val mime = item.optString("mimeType", "")
            if (mime.isNotEmpty() && mime != "application/vnd.google-apps.folder") continue

            val createdRaw = item.optString("createdTime", "").trim().takeIf { it.isNotEmpty() }
            candidates.add(
                CandidateFolder(
                    id = id,
                    createdTimeRaw = createdRaw,
                    createdTimeMillis = parseCreatedTimeMillis(createdRaw),
                    originalIndex = i
                )
            )
        }
        val oldest = candidates.minWithOrNull(
            Comparator { a, b ->
                when {
                    a.createdTimeMillis != null && b.createdTimeMillis != null -> {
                        val cmp = a.createdTimeMillis.compareTo(b.createdTimeMillis)
                        if (cmp != 0) cmp else a.originalIndex.compareTo(b.originalIndex)
                    }
                    a.createdTimeMillis != null -> -1
                    b.createdTimeMillis != null -> 1
                    a.createdTimeRaw != null && b.createdTimeRaw != null -> {
                        val cmp = a.createdTimeRaw.compareTo(b.createdTimeRaw)
                        if (cmp != 0) cmp else a.originalIndex.compareTo(b.originalIndex)
                    }
                    a.createdTimeRaw != null -> -1
                    b.createdTimeRaw != null -> 1
                    else -> a.originalIndex.compareTo(b.originalIndex)
                }
            }
        )
        return oldest?.id
    }

    /**
     * [session] is the sync session the caller belongs to (null for login, ensureFolderConfigured,
     * preview, restore...). It decides whether this call may still change shared state; it is never
     * inferred from whichever sync happens to be running, so an old session A in flight cannot
     * block a legitimate operation of session B.
     */
    internal fun getOrCreateDriveFolder(
        token: String,
        folderName: String,
        accountEmail: String? = null,
        session: SyncSessionSnapshot? = null
    ): String {
        val email = resolveAccountEmail(accountEmail)
        val persistedId = getPersistedFolderId(email)
        if (!persistedId.isNullOrBlank()) {
            try {
                val checkUrl = "$driveApiUrl/files/$persistedId?fields=id,name,trashed,mimeType"
                val checkRequest = Request.Builder()
                    .url(checkUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
                val json = JSONObject(executeHttp(checkRequest, session))
                if (!isOperationCurrent(session)) {
                    return persistedId
                }
                val isTrashed = json.optBoolean("trashed", false)
                val mimeType = json.optString("mimeType", "")
                if (!isTrashed && mimeType == "application/vnd.google-apps.folder") {
                    persistFolderId(email, persistedId)
                    return persistedId
                }
                Log.w(TAG, "Persisted folder ID $persistedId is trashed or not a folder. Clearing local folderId.")
                persistFolderId(email, null)
            } catch (e: UserRecoverableAuthException) {
                Log.w(TAG, "Autorização recuperável necessária ao validar pasta $persistedId: ${e.message}")
                if (isOperationCurrent(session)) {
                    _recoverableAuthIntent.value = e.intent
                    _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                }
                throw e
            } catch (e: GoogleAuthException) {
                Log.w(TAG, "Erro de autenticação Google ao validar pasta $persistedId: ${e.message}")
                if (isOperationCurrent(session)) {
                    _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                }
                throw e
            } catch (e: DriveApiException) {
                if (!isOperationCurrent(session)) {
                    throw e
                }
                val code = e.statusCode
                val body = e.rawBody
                when {
                    code == 404 || (code == 403 && isAppNotAuthorizedToFile(body)) -> {
                        Log.w(TAG, "Persisted folder ID $persistedId is no longer valid ($code). Clearing local folderId.")
                        persistFolderId(email, null)
                    }
                    code == 403 && isScopeOrPermissionError(body) -> {
                        Log.w(TAG, "Permissão/escopo insuficiente (HTTP 403) ao validar pasta $persistedId. Mantendo folderId.")
                        _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
                        throw e
                    }
                    code == 401 -> {
                        Log.w(TAG, "Falha de autenticação HTTP 401 ao validar pasta $persistedId. Mantendo folderId.")
                        throw e
                    }
                    code == 429 || (code in 500..599) || code == 403 -> {
                        Log.w(TAG, "Erro transitório, limite de cota ou 403 não definitivo ($code) ao validar pasta $persistedId. Mantendo folderId.")
                        _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                        throw e
                    }
                    else -> {
                        Log.w(TAG, "Erro HTTP $code ao validar pasta $persistedId. Mantendo folderId.")
                        throw e
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha de rede ou transitória ao validar pasta $persistedId: ${e.message}. Mantendo folderId.")
                if (isOperationCurrent(session) && (e is IOException || e.cause is IOException)) {
                    _connectionStatus.value = DriveConnectionStatus.UNVERIFIED
                }
                throw e
            }
        }

        if (!isOperationCurrent(session)) {
            return persistedId ?: ""
        }

        val query = "name = '${escapeDriveQueryValue(folderName)}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "$driveApiUrl/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&orderBy=createdTime&fields=files(id,name,createdTime)"

        val searchRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val searchResponse = try {
            executeHttp(searchRequest, session)
        } catch (e: UserRecoverableAuthException) {
            if (isOperationCurrent(session)) {
                _recoverableAuthIntent.value = e.intent
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            throw e
        } catch (e: GoogleAuthException) {
            if (isOperationCurrent(session)) {
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            throw e
        }

        if (!isOperationCurrent(session)) {
            return ""
        }

        val json = JSONObject(searchResponse)
        val files = json.optJSONArray("files")
        val foundId = selectOldestFolderId(files)
        if (foundId != null) {
            if (isOperationCurrent(session)) {
                persistFolderId(email, foundId)
            }
            return foundId
        }

        val folderMeta = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
        }

        val createRequest = Request.Builder()
            .url("$driveApiUrl/files")
            .addHeader("Authorization", "Bearer $token")
            .post(folderMeta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        val createResponse = try {
            executeHttp(createRequest, session)
        } catch (e: UserRecoverableAuthException) {
            if (isOperationCurrent(session)) {
                _recoverableAuthIntent.value = e.intent
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            throw e
        } catch (e: GoogleAuthException) {
            if (isOperationCurrent(session)) {
                _connectionStatus.value = DriveConnectionStatus.PERMISSION_MISSING
            }
            throw e
        }

        val newId = JSONObject(createResponse).getString("id")
        if (isOperationCurrent(session)) {
            persistFolderId(email, newId)
        }
        return newId
    }

    private class DriveFileInfo(
        val id: String,
        val name: String,
        val size: Long?,
        val createdTimeMillis: Long?,
        val modifiedTimeMillis: Long?,
        val mimeType: String = ""
    ) {
        val isFolder: Boolean get() = mimeType == DRIVE_FOLDER_MIME_TYPE
    }

    /** Every file directly in the folder (not trashed), following Drive's pages. */
    private fun listFolderFiles(
        token: String,
        folderId: String,
        session: SyncSessionSnapshot? = null
    ): List<DriveFileInfo> {
        val query = "'$folderId' in parents and trashed = false"
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val result = mutableListOf<DriveFileInfo>()
        var pageToken: String? = null
        var pages = 0
        do {
            var url = "$driveApiUrl/files?q=$encodedQuery&pageSize=1000" +
                "&fields=nextPageToken,files(id,name,mimeType,size,createdTime,modifiedTime)"
            if (pageToken != null) url += "&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val json = JSONObject(executeHttp(request, session))
            val files = json.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    result.add(
                        DriveFileInfo(
                            id = f.getString("id"),
                            name = f.optString("name"),
                            size = f.optString("size").toLongOrNull(),
                            createdTimeMillis = parseCreatedTimeMillis(f.optString("createdTime")),
                            modifiedTimeMillis = parseCreatedTimeMillis(f.optString("modifiedTime")),
                            mimeType = f.optString("mimeType")
                        )
                    )
                }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < MAX_LIST_PAGES)
        if (pageToken != null) {
            throw IllegalStateException("A pasta do Drive tem arquivos demais para listar.")
        }
        return result
    }

    private fun findFileInFolder(
        token: String,
        folderId: String,
        fileName: String,
        session: SyncSessionSnapshot? = null
    ): String? {
        val query = "name = '${escapeDriveQueryValue(fileName)}' and '$folderId' in parents and trashed = false"
        val url = "$driveApiUrl/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)"

        val searchRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val json = JSONObject(executeHttp(searchRequest, session))
        val files = json.optJSONArray("files")
        if (files != null && files.length() > 0) {
            return files.getJSONObject(0).getString("id")
        }
        return null
    }

    /**
     * Detects extension and MIME type of a local file WITHOUT guessing "JPEG" for unknown data.
     * Order: real content (magic bytes) > record hint > file name / display name > content resolver.
     * Unknown types keep their original extension when there is one, else `bin` + octet-stream.
     */
    fun detectFileMeta(uriStr: String, fileTypeHint: String? = null, bytes: ByteArray? = null): FileMeta {
        val byName = metaFromName(uriStr) ?: displayNameMeta(uriStr)

        // When the name's extension agrees with the detected type, keep the original spelling (.jpeg).
        fun preferName(candidate: FileMeta): FileMeta =
            if (byName != null && byName.mimeType == candidate.mimeType) byName else candidate

        sniffFileMeta(bytes)?.let { return preferName(it) }
        metaFromHint(fileTypeHint)?.let { return preferName(it) }
        byName?.let { return it }

        if (uriStr.startsWith("content://")) {
            val type = try {
                context.contentResolver.getType(Uri.parse(uriStr))
            } catch (_: Exception) {
                null
            }
            if (type != null) {
                val mime = normalizeMime(type)
                return FileMeta(extensionForMimeType(mime) ?: "bin", mime)
            }
        }

        return FileMeta("bin", "application/octet-stream")
    }

    private fun displayNameMeta(uriStr: String): FileMeta? {
        if (!uriStr.startsWith("content://")) return null
        return try {
            val name = context.contentResolver
                .query(Uri.parse(uriStr), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            name?.let { metaFromName(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates or updates a file in the Drive folder. Every response is validated as 2xx by
     * [executeHttp]; anything else throws, so callers can never mistake a failed upload for success.
     */
    private fun uploadFileToDrive(
        token: String,
        folderId: String,
        fileName: String,
        mimeType: String,
        content: ByteArray,
        session: SyncSessionSnapshot? = null
    ): String {
        val existingFileId = findFileInFolder(token, folderId, fileName, session)

        if (existingFileId != null) {
            val updateRequest = Request.Builder()
                .url("$driveUploadUrl/files/$existingFileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(content.toRequestBody(mimeType.toMediaType()))
                .build()
            val updateResponse = executeHttp(updateRequest, session)
            return try {
                JSONObject(updateResponse).optString("id").takeIf { it.isNotBlank() } ?: existingFileId
            } catch (_: Exception) {
                existingFileId
            }
        }

        return createNewFileInFolder(token, folderId, fileName, mimeType, content, session)
    }

    /**
     * ALWAYS creates a new file (never looks for one with the same name, never overwrites). Used by
     * [uploadFileToDrive] when nothing exists yet, and by the dated safety copies, which must never
     * replace an earlier copy.
     */
    private fun createNewFileInFolder(
        token: String,
        folderId: String,
        fileName: String,
        mimeType: String,
        content: ByteArray,
        session: SyncSessionSnapshot? = null
    ): String {
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("parents", JSONArray().put(folderId))
        }

        // Drive's multipart upload is multipart/related (metadata part, then media part).
        val multipartBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(content.toRequestBody(mimeType.toMediaType()))
            .build()

        val uploadRequest = Request.Builder()
            .url("$driveUploadUrl/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $token")
            .post(multipartBody)
            .build()

        val createdJson = JSONObject(executeHttp(uploadRequest, session))
        val createdId = createdJson.optString("id")
        if (createdId.isBlank()) {
            throw IllegalStateException("O Google Drive não devolveu o identificador do arquivo enviado ('$fileName').")
        }
        return createdId
    }

    /** Looks a file up by name in the folder, with the size Drive reports for it. */
    private fun findRemoteFile(
        token: String,
        folderId: String,
        fileName: String,
        session: SyncSessionSnapshot? = null
    ): RemoteFile? {
        val query = "name = '${escapeDriveQueryValue(fileName)}' and '$folderId' in parents and trashed = false"
        val url = "$driveApiUrl/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,size)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val files = JSONObject(executeHttp(request, session)).optJSONArray("files")
        if (files == null || files.length() == 0) return null
        val first = files.getJSONObject(0)
        return RemoteFile(first.getString("id"), first.optString("size").toLongOrNull())
    }

    /**
     * Sends [payload] under [fileName] unless a file of that name AND size is already in the folder.
     * The name carries the content (SHA-256), so the same name with the same size is the same file.
     * Returns the Drive file id.
     */
    private fun uploadContentFile(
        token: String,
        folderId: String,
        fileName: String,
        mimeType: String,
        payload: UploadPayload,
        session: SyncSessionSnapshot? = null
    ): String {
        val remote = findRemoteFile(token, folderId, fileName, session)
        if (remote != null && remote.size == payload.size) return remote.id
        return writeContent(token, folderId, fileName, mimeType, payload, remote, session)
    }

    /**
     * Creates the file, or replaces a same-named file of a different size ([existing], a damaged leftover).
     * Up to the threshold it is one request; above it the upload is resumable, in parts.
     */
    private fun writeContent(
        token: String,
        folderId: String,
        fileName: String,
        mimeType: String,
        payload: UploadPayload,
        existing: RemoteFile?,
        session: SyncSessionSnapshot?
    ): String {
        if (payload.size > resumableThresholdBytes) {
            return uploadResumable(token, folderId, fileName, mimeType, payload, existing, session)
        }
        if (existing == null) {
            return createNewFileInFolder(token, folderId, fileName, mimeType, payload.readAll(), session)
        }
        val updateRequest = Request.Builder()
            .url("$driveUploadUrl/files/${existing.id}?uploadType=media")
            .addHeader("Authorization", "Bearer $token")
            .patch(payload.readAll().toRequestBody(mimeType.toMediaType()))
            .build()
        val response = executeHttp(updateRequest, session)
        return try {
            JSONObject(response).optString("id").takeIf { it.isNotBlank() } ?: existing.id
        } catch (_: Exception) {
            existing.id
        }
    }

    // ---- Resumable upload (files above the threshold) -----------------------------------------

    private sealed class ChunkOutcome {
        /** Drive has the whole file; [body] is its JSON. */
        class Complete(val body: String) : ChunkOutcome()

        /** Drive stored the file up to (not including) [nextOffset]. */
        class Incomplete(val nextOffset: Long) : ChunkOutcome()

        /** A passing problem (network, 5xx, 429...): ask Drive how much it has and go on. */
        object Retry : ChunkOutcome()

        /** The upload session no longer exists. */
        object Lost : ChunkOutcome()

        class Fatal(val code: Int, val body: String) : ChunkOutcome()
    }

    private fun uploadResumable(
        token: String,
        folderId: String,
        fileName: String,
        mimeType: String,
        payload: UploadPayload,
        existing: RemoteFile?,
        session: SyncSessionSnapshot?
    ): String {
        var lost: ResumableSessionLostException? = null
        // A lost session is the only case that starts over, and only once.
        repeat(2) {
            val sessionUri = startResumableSession(token, folderId, fileName, mimeType, payload.size, existing, session)
            try {
                val body = sendResumableChunks(sessionUri, mimeType, payload, session)
                return JSONObject(body).optString("id").takeIf { it.isNotBlank() }
                    ?: existing?.id
                    ?: throw IllegalStateException("O Google Drive não devolveu o identificador do arquivo enviado ('$fileName').")
            } catch (e: ResumableSessionLostException) {
                lost = e
            }
        }
        throw IllegalStateException("O envio de '$fileName' foi interrompido e não pôde ser retomado. ${lost?.message.orEmpty()}".trim())
    }

    private fun startResumableSession(
        token: String,
        folderId: String,
        fileName: String,
        mimeType: String,
        size: Long,
        existing: RemoteFile?,
        session: SyncSessionSnapshot?
    ): String {
        val metadata = JSONObject().apply {
            put("name", fileName)
            if (existing == null) put("parents", JSONArray().put(folderId))
        }
        val body = metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        val builder = Request.Builder()
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Upload-Content-Type", mimeType)
            .addHeader("X-Upload-Content-Length", size.toString())
        val request = if (existing != null) {
            builder.url("$driveUploadUrl/files/${existing.id}?uploadType=resumable").patch(body).build()
        } else {
            builder.url("$driveUploadUrl/files?uploadType=resumable").post(body).build()
        }
        return executeHttpInternal(request, session) { it.header("Location") }
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("O Google Drive não devolveu o endereço para enviar '$fileName' em partes.")
    }

    /**
     * Sends the file in parts. When a part fails for a passing reason, Drive is asked how much it already
     * has and the upload goes on from there instead of from zero. Returns the final response body.
     */
    private fun sendResumableChunks(
        sessionUri: String,
        mimeType: String,
        payload: UploadPayload,
        session: SyncSessionSnapshot?
    ): String {
        val total = payload.size
        var offset = 0L
        var stalls = 0
        while (true) {
            if (!isOperationCurrent(session)) {
                throw IllegalStateException("A conta do Google mudou durante o envio de um arquivo.")
            }
            val length = minOf(resumableChunkBytes.toLong(), total - offset).toInt()
            val chunk = payload.readRange(offset, length)
            var outcome = executeChunkRequest(
                Request.Builder()
                    .url(sessionUri)
                    .put(chunk.toRequestBody(mimeType.toMediaType()))
                    .header("Content-Range", "bytes $offset-${offset + length - 1}/$total")
                    .build()
            )

            // After a passing failure never re-send blindly: ask Drive how much it has, until it answers.
            var retries = 0
            while (outcome is ChunkOutcome.Retry) {
                retries++
                if (retries > RESUMABLE_MAX_RETRIES_IN_A_ROW) {
                    throw IllegalStateException("O envio em partes falhou várias vezes seguidas.")
                }
                if (retries > 1) Thread.sleep(1000L * (retries - 1))
                outcome = executeChunkRequest(
                    Request.Builder()
                        .url(sessionUri)
                        .put(ByteArray(0).toRequestBody(null))
                        .header("Content-Range", "bytes */$total")
                        .build()
                )
            }

            when (val o = outcome) {
                is ChunkOutcome.Complete -> return o.body
                is ChunkOutcome.Incomplete -> {
                    if (o.nextOffset >= total) {
                        throw IllegalStateException("O Google Drive recebeu o arquivo inteiro mas não confirmou o envio.")
                    }
                    if (o.nextOffset > offset) {
                        stalls = 0
                    } else {
                        stalls++
                        if (stalls > RESUMABLE_MAX_RETRIES_IN_A_ROW) {
                            throw IllegalStateException("O Google Drive não aceitou a parte enviada do arquivo.")
                        }
                    }
                    offset = o.nextOffset
                }
                is ChunkOutcome.Lost -> throw ResumableSessionLostException("A sessão de envio expirou.")
                is ChunkOutcome.Fatal ->
                    throw DriveApiException(o.code, "Falha no envio em partes (${o.code}): ${extractErrorMessage(o.body)}", o.body)
                is ChunkOutcome.Retry -> throw IllegalStateException("Estado inesperado no envio em partes.")
            }
        }
    }

    private fun executeChunkRequest(request: Request): ChunkOutcome {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            return ChunkOutcome.Retry
        }
        return response.use { res ->
            val code = res.code
            when {
                code == 200 || code == 201 -> ChunkOutcome.Complete(res.body?.string().orEmpty())
                code == 308 -> {
                    // "Range: bytes=0-N" is the last byte Drive stored; no header means nothing yet.
                    val last = res.header("Range")?.substringAfter("bytes=", "")?.substringAfter('-', "")?.toLongOrNull()
                    ChunkOutcome.Incomplete(if (last == null) 0L else last + 1)
                }
                code == 404 || code == 410 -> ChunkOutcome.Lost
                code == 408 || code == 429 || code in 500..599 -> ChunkOutcome.Retry
                else -> ChunkOutcome.Fatal(code, res.body?.string().orEmpty())
            }
        }
    }

    private fun readUriBytes(uriStr: String): ByteArray? {
        return try {
            if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
            } else {
                val f = File(uriStr)
                if (f.exists()) f.readBytes() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read bytes from $uriStr: ${e.message}")
            null
        }
    }

    private fun buildCompleteVersionedBackup(
        profile: StellaProfile,
        glucoseList: List<GlucoseRecord>,
        medications: List<Medication>,
        administrations: List<MedicationAdministration>,
        appointments: List<VeterinaryAppointment>,
        documents: List<ExamDocument>,
        feeding: List<FeedingRecord>,
        weights: List<WeightRecord>,
        vaccines: List<VaccinePreventionRecord>,
        reminders: List<Reminder>,
        attachmentsManifest: JSONArray,
        recordAttachmentsManifest: JSONArray = JSONArray()
    ): String {
        val root = JSONObject()
        root.put("backupVersion", CURRENT_BACKUP_VERSION)
        root.put("appName", "STELLA")
        root.put("exportDate", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
        root.put("driveFolder", DRIVE_FOLDER_NAME)

        // Complete Profile
        root.put("profile", JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("photoUri", profile.photoUri ?: "")
            put("species", profile.species)
            put("breed", profile.breed)
            put("gender", profile.gender)
            put("birthDate", profile.birthDate)
            put("currentWeight", profile.currentWeight)
            put("color", profile.color)
            put("microchip", profile.microchip)
            put("vetRegistrationNumber", profile.vetRegistrationNumber)
            put("vetName", profile.vetName)
            put("clinicName", profile.clinicName)
            put("clinicPhone", profile.clinicPhone)
            put("notes", profile.notes)
            put("personality", profile.personality)
            put("curiosities", profile.curiosities)
        })

        // Glucose Records
        val gArray = JSONArray()
        glucoseList.forEach { g ->
            gArray.put(JSONObject().apply {
                put("id", g.id)
                put("dateTimeMillis", g.dateTimeMillis)
                put("dateString", g.dateString)
                put("timeString", g.timeString)
                put("value", g.value)
                put("unit", g.unit)
                put("mealRelation", g.mealRelation)
                put("medicationRelation", g.medicationRelation)
                put("notes", g.notes)
                put("symptoms", g.symptoms)
                put("createdAt", g.createdAt)
            })
        }
        root.put("glucoseRecords", gArray)

        // Medications
        val mArray = JSONArray()
        medications.forEach { m ->
            mArray.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("activeIngredient", m.activeIngredient)
                put("dosage", m.dosage)
                put("unit", m.unit)
                put("administrationRoute", m.administrationRoute)
                put("quantity", m.quantity)
                put("frequency", m.frequency)
                put("scheduledTimes", m.scheduledTimes)
                put("startDate", m.startDate)
                put("endDate", m.endDate)
                put("vetName", m.vetName)
                put("reason", m.reason)
                put("notes", m.notes)
                put("isVitaminOrSupplement", m.isVitaminOrSupplement)
                put("status", m.status)
                put("suspensionReason", m.suspensionReason)
                put("suspensionDate", m.suspensionDate)
                put("createdAt", m.createdAt)
            })
        }
        root.put("medications", mArray)

        // Administrations
        val aArray = JSONArray()
        administrations.forEach { a ->
            aArray.put(JSONObject().apply {
                put("id", a.id)
                put("medicationId", a.medicationId)
                put("medicationName", a.medicationName)
                put("dosage", a.dosage)
                put("timestamp", a.timestamp)
                put("dateString", a.dateString)
                put("timeString", a.timeString)
                put("registeredBy", a.registeredBy)
                put("notes", a.notes)
            })
        }
        root.put("administrations", aArray)

        // Appointments
        val aptArray = JSONArray()
        appointments.forEach { apt ->
            aptArray.put(JSONObject().apply {
                put("id", apt.id)
                put("dateString", apt.dateString)
                put("timeString", apt.timeString)
                put("timestamp", apt.timestamp)
                put("vetName", apt.vetName)
                put("clinicName", apt.clinicName)
                put("reason", apt.reason)
                if (apt.weightKg != null) put("weightKg", apt.weightKg)
                if (apt.temperatureC != null) put("temperatureC", apt.temperatureC)
                put("notes", apt.notes)
                put("symptomsReported", apt.symptomsReported)
                put("conductRecommended", apt.conductRecommended)
                put("medicationsPrescribed", apt.medicationsPrescribed)
                put("nextAppointmentDate", apt.nextAppointmentDate)
                put("attachedFileCount", apt.attachedFileCount)
                put("createdAt", apt.createdAt)
            })
        }
        root.put("appointments", aptArray)

        // Exam Documents
        val dArray = JSONArray()
        documents.forEach { d ->
            dArray.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("fileType", d.fileType)
                put("dateString", d.dateString)
                put("clinicName", d.clinicName)
                put("vetName", d.vetName)
                put("category", d.category)
                put("notes", d.notes)
                put("fileUriOrPath", d.fileUriOrPath ?: "")
                put("isArchived", d.isArchived)
                put("createdAt", d.createdAt)
            })
        }
        root.put("examDocuments", dArray)

        // Feeding Records
        val fArray = JSONArray()
        feeding.forEach { f ->
            fArray.put(JSONObject().apply {
                put("id", f.id)
                put("brand", f.brand)
                put("foodName", f.foodName)
                put("type", f.type)
                put("line", f.line)
                put("flavor", f.flavor)
                put("dailyAmount", f.dailyAmount)
                put("portionAmount", f.portionAmount)
                put("mealsPerDay", f.mealsPerDay)
                put("scheduleTimes", f.scheduleTimes)
                put("startDate", f.startDate)
                put("endDate", f.endDate)
                put("isCurrent", f.isCurrent)
                put("notes", f.notes)
                put("createdAt", f.createdAt)
            })
        }
        root.put("feedingRecords", fArray)

        // Weight Records
        val wArray = JSONArray()
        weights.forEach { w ->
            wArray.put(JSONObject().apply {
                put("id", w.id)
                put("timestamp", w.timestamp)
                put("dateString", w.dateString)
                put("weightKg", w.weightKg)
                put("notes", w.notes)
            })
        }
        root.put("weightRecords", wArray)

        // Vaccines
        val vArray = JSONArray()
        vaccines.forEach { v ->
            vArray.put(JSONObject().apply {
                put("id", v.id)
                put("name", v.name)
                put("type", v.type)
                put("dateAdministered", v.dateAdministered)
                put("dose", v.dose)
                put("nextDueDate", v.nextDueDate)
                put("vetName", v.vetName)
                put("clinicName", v.clinicName)
                put("notes", v.notes)
                put("createdAt", v.createdAt)
            })
        }
        root.put("vaccines", vArray)

        // Reminders
        val rArray = JSONArray()
        reminders.forEach { r ->
            rArray.put(JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("category", r.category)
                put("dateString", r.dateString)
                put("timeString", r.timeString)
                put("timestamp", r.timestamp)
                put("status", r.status)
                if (r.executionTimestamp != null) put("executionTimestamp", r.executionTimestamp)
                put("notes", r.notes)
            })
        }
        root.put("reminders", rArray)

        // Profile photo and legacy exam files (same layout as version 1)
        root.put("attachments", attachmentsManifest)

        // Version 2: the files attached to consultations and exams (several per record)
        root.put("recordAttachments", recordAttachmentsManifest)

        return root.toString(2)
    }

    private fun buildTextReport(
        profile: StellaProfile,
        glucoseList: List<GlucoseRecord>,
        medications: List<Medication>,
        appointments: List<VeterinaryAppointment>,
        documents: List<ExamDocument>,
        feeding: List<FeedingRecord>,
        weights: List<WeightRecord>
    ): String {
        val nowStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("==================================================")
            appendLine("🐾 PRONTUÁRIO DE SAÚDE DO PET - STELLA 🐾")
            appendLine("Registro feito pelo tutor. Não é laudo nem documento veterinário.")
            appendLine("Pasta no Google Drive: $DRIVE_FOLDER_NAME")
            appendLine("Sincronizado em: $nowStr")
            appendLine("==================================================")
            appendLine()
            appendLine("1. IDENTIFICAÇÃO")
            appendLine("• Nome: ${profile.name}")
            appendLine("• Espécie: ${profile.species}")
            appendLine("• Raça: ${profile.breed}")
            appendLine("• Sexo: ${profile.gender}")
            appendLine("• Data de Nascimento: ${if (profile.birthDate.isNotBlank()) profile.birthDate else "Não informada"}")
            appendLine("• Peso Atual: ${if (profile.currentWeight > 0.0) "${profile.currentWeight} kg" else "Não informado"}")
            appendLine("• Microchip: ${if (profile.microchip.isNotBlank()) profile.microchip else "Não informado"}")
            appendLine("• Veterinário Principal: ${if (profile.vetName.isNotBlank()) "${profile.vetName} (${profile.clinicName})" else "Não informado"}")
            appendLine("• Telefone da Clínica: ${if (profile.clinicPhone.isNotBlank()) profile.clinicPhone else "Não informado"}")
            appendLine()
            appendLine("2. ALIMENTAÇÃO ATUAL")
            val currentDiet = feeding.firstOrNull { it.isCurrent }
            if (currentDiet != null) {
                appendLine("• Ração: ${currentDiet.brand} ${currentDiet.foodName} (${currentDiet.line})")
                appendLine("• Qtd Diária: ${currentDiet.dailyAmount} em ${currentDiet.mealsPerDay} refeições (${currentDiet.scheduleTimes})")
            } else {
                appendLine("• Nenhuma ração marcada como em uso atual.")
            }
            appendLine()
            appendLine("3. GLICEMIA (TOTAL: ${glucoseList.size} MEDIÇÕES)")
            if (glucoseList.isNotEmpty()) {
                val avg = glucoseList.map { it.value }.average()
                appendLine("• Média Geral: ${String.format(Locale.getDefault(), "%.1f", avg)} mg/dL")
                appendLine("• Histórico recente:")
                glucoseList.take(10).forEach { g ->
                    appendLine("  - ${g.dateString} ${g.timeString}: ${g.value.toInt()} mg/dL (${g.mealRelation}) ${g.notes}")
                }
            } else {
                appendLine("• Nenhuma medição registrada.")
            }
            appendLine()
            appendLine("4. MEDICAMENTOS ATIVOS")
            val activeM = medications.filter { it.status == "ATIVO" }
            if (activeM.isNotEmpty()) {
                activeM.forEach { m ->
                    appendLine("• ${m.name} (${m.dosage}) - ${m.frequency} às ${m.scheduledTimes}")
                }
            } else {
                appendLine("• Nenhum medicamento ativo.")
            }
            appendLine()
            appendLine("5. CONSULTAS VETERINÁRIAS")
            if (appointments.isNotEmpty()) {
                appointments.take(5).forEach { a ->
                    appendLine("• ${a.dateString}: ${a.reason} - Dr(a). ${a.vetName} (${a.clinicName})")
                    if (a.conductRecommended.isNotBlank()) appendLine("  Conduta: ${a.conductRecommended}")
                }
            } else {
                appendLine("• Nenhuma consulta registrada.")
            }
            appendLine()
            appendLine("6. DOCUMENTOS E EXAMES")
            if (documents.isNotEmpty()) {
                documents.take(10).forEach { d ->
                    appendLine("• [${d.category}] ${d.name} (${d.dateString}) - ${d.notes}")
                }
            } else {
                appendLine("• Nenhum exame cadastrado.")
            }
            appendLine()
            appendLine("--------------------------------------------------")
            appendLine("Arquivo digital sincronizado na nuvem com o Google Drive.")
        }
    }
}
