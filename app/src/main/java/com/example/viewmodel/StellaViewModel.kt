package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.attachments.AddAttachmentResult
import com.example.data.attachments.AttachmentLimits
import com.example.data.attachments.AttachmentManager
import com.example.data.attachments.AttachmentStorage
import com.example.data.attachments.ImportResult
import com.example.data.attachments.StageResult
import com.example.data.attachments.StagedAttachment
import com.example.data.db.AppDatabase
import com.example.data.erase.LocalDataEraser
import com.example.data.erase.LocalEraseResult
import com.example.data.model.Attachment
import com.example.data.model.AttachmentOwner
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
import com.example.data.drive.BackupManifestPreview
import com.example.data.drive.BackupVersion
import com.example.data.drive.CloudDeleteResult
import com.example.data.drive.CloudFilesCount
import com.example.data.drive.DriveConnectionStatus
import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.drive.SafetyCopyResult
import com.example.data.model.BackupSnapshot
import com.example.notifications.MedicationAlarmScheduler
import com.example.notifications.ReminderNotificationManager
import com.example.util.DateTimeUtils
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class DriveSyncDecisionState {
    object None : DriveSyncDecisionState()
    object Checking : DriveSyncDecisionState()
    data class DecisionPending(
        val accountEmail: String,
        val remotePreview: BackupManifestPreview,
        val isLocalEmpty: Boolean,
        val localRecordCount: Int
    ) : DriveSyncDecisionState()
    data class Error(val message: String) : DriveSyncDecisionState()
}

/** State of the "Versões do backup" list: the current backup and the dated copies on Drive. */
sealed class BackupVersionsState {
    object Idle : BackupVersionsState()
    object Loading : BackupVersionsState()

    /** Current backup first, then the copies, newest first. May be empty when there is no backup yet. */
    data class Loaded(val versions: List<BackupVersion>) : BackupVersionsState()
    data class Error(val message: String) : BackupVersionsState()
}

/** Result of looking inside one backup version before restoring it. */
sealed class BackupPreviewResult {
    data class Loaded(val preview: BackupManifestPreview) : BackupPreviewResult()
    data class Failed(val message: String) : BackupPreviewResult()
}

/** What a "apagar" button deletes. */
enum class DataDeletionKind {
    /** The files on Google Drive only. Afterwards the account is disconnected; this device keeps its data. */
    CLOUD,

    /** This device only (records, photos, exams, attachments, alarms). The account stays connected, but sync waits for a new decision. */
    DEVICE,

    /** The cloud first, then this device, then the account is disconnected. Complete only if both worked. */
    BOTH
}

/**
 * Progress and result of [StellaViewModel.deleteCloudData], [StellaViewModel.deleteDeviceData] and
 * [StellaViewModel.deleteDeviceAndCloudData]. [Done] is only ever reported when everything the button
 * promised is really gone. Messages are written for the user (Portuguese).
 */
sealed class DataDeletionState {
    object Idle : DataDeletionState()
    data class Running(val kind: DataDeletionKind, val message: String) : DataDeletionState()

    /** [warnings] did not touch the data (for example an alarm that could not be cancelled). */
    data class Done(
        val kind: DataDeletionKind,
        val message: String,
        val cloudFilesDeleted: Int = 0,
        val warnings: List<String> = emptyList()
    ) : DataDeletionState()

    /**
     * NOT complete. [offline]: there was no internet, repeat later. [cloudDeleted]: for [DataDeletionKind.BOTH],
     * the cloud step already worked and only this device is left (the account stays connected so it can be repeated).
     */
    data class Failed(
        val kind: DataDeletionKind,
        val message: String,
        val offline: Boolean = false,
        val cloudDeleted: Boolean = false
    ) : DataDeletionState()
}

/** The number of files on Drive, for the information screen before "apagar a nuvem". */
sealed class CloudFilesCountState {
    object Idle : CloudFilesCountState()
    object Loading : CloudFilesCountState()
    data class Loaded(val count: CloudFilesCount) : CloudFilesCountState()
    data class Error(val message: String) : CloudFilesCountState()
}

data class GlucoseStats(
    val averageToday: Double?,
    val average7Days: Double?,
    val average30Days: Double?,
    val minValue: Double?,
    val maxValue: Double?,
    val count: Int
)

data class TimelineItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val dateString: String,
    val timestamp: Long,
    val category: String, // GLICEMIA, MEDICAMENTO, CONSULTA, EXAME, VACINA, PESO, ALIMENTACAO, LEMBRETE
    val details: String = ""
)

data class SearchResult(
    val id: String,
    val title: String,
    val snippet: String,
    val section: String,
    val date: String
)

class StellaViewModel @JvmOverloads constructor(
    application: Application,
    customDriveSyncManager: GoogleDriveSyncManager? = null,
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
    // Replaceable only so tests can make a step of "apagar tudo neste aparelho" fail.
    customLocalDataEraser: LocalDataEraser? = null
) : AndroidViewModel(application) {

    companion object {
        private const val FOREGROUND_VERIFY_MIN_INTERVAL_MS = 5 * 60 * 1000L

        fun isLocalDatabaseEmpty(snapshot: BackupSnapshot): Boolean = snapshot.hasNoUserData()

        fun countLocalRecords(snapshot: BackupSnapshot): Int = snapshot.recordCount()

        /** The word the user types to confirm a deletion (second of the three confirmations). */
        const val DELETE_CONFIRMATION_WORD = "APAGAR"

        /** True when [typed] is the confirmation word. Spaces around it and upper/lower case do not matter. */
        fun isDeleteConfirmationText(typed: String): Boolean =
            typed.trim().equals(DELETE_CONFIRMATION_WORD, ignoreCase = true)
    }

    private val repository: StellaRepository
    val driveSyncManager: GoogleDriveSyncManager = customDriveSyncManager ?: GoogleDriveSyncManager(application)
    val reminderNotificationManager = ReminderNotificationManager(application)
    val medicationAlarms = MedicationAlarmScheduler(application)
    val driveSyncState = driveSyncManager.syncState
    val driveConnectionStatus: StateFlow<DriveConnectionStatus> = driveSyncManager.connectionStatus
    val driveAccountEmail = driveSyncManager.connectedAccountEmail
    val lastSyncTimestamp = driveSyncManager.lastSyncTimestamp
    val hasPendingChanges = driveSyncManager.hasPendingChanges

    private val _syncDecisionState = MutableStateFlow<DriveSyncDecisionState>(DriveSyncDecisionState.None)
    val syncDecisionState: StateFlow<DriveSyncDecisionState> = _syncDecisionState.asStateFlow()
    val driveSyncDecisionState: StateFlow<DriveSyncDecisionState> = _syncDecisionState.asStateFlow()

    private val _uiErrorMessage = MutableStateFlow<String?>(null)
    val uiErrorMessage: StateFlow<String?> = _uiErrorMessage.asStateFlow()

    fun clearUiErrorMessage() {
        _uiErrorMessage.value = null
    }

    private val dbMutex = Mutex()
    private var debouncedSyncJob: Job? = null
    private var activeSyncJob: Job? = null
    private var activeVerifyJob: Job? = null
    private var decisionCheckJob: Job? = null

    private val _backupVersions = MutableStateFlow<BackupVersionsState>(BackupVersionsState.Idle)
    val backupVersions: StateFlow<BackupVersionsState> = _backupVersions.asStateFlow()
    private var backupVersionsJob: Job? = null

    // True while "Restaurar da nuvem" or "Substituir a nuvem" runs, including the safety copy that comes
    // first. The screen should disable both buttons meanwhile; a second tap is ignored anyway.
    private val decisionActionGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _decisionActionInProgress = MutableStateFlow(false)
    val decisionActionInProgress: StateFlow<Boolean> = _decisionActionInProgress.asStateFlow()
    private var lastForegroundVerifyTimestampMs: Long? = null

    // ---- Deleting data (cloud, device or both) ----
    private val dataDeletionGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _dataDeletion = MutableStateFlow<DataDeletionState>(DataDeletionState.Idle)

    /** Progress and result of the three "apagar" actions. Call [dismissDataDeletionResult] when the screen has shown it. */
    val dataDeletion: StateFlow<DataDeletionState> = _dataDeletion.asStateFlow()

    private val _cloudFilesCount = MutableStateFlow<CloudFilesCountState>(CloudFilesCountState.Idle)
    val cloudFilesCount: StateFlow<CloudFilesCountState> = _cloudFilesCount.asStateFlow()
    private var cloudFilesCountJob: Job? = null

    private val localDataEraser: LocalDataEraser by lazy {
        customLocalDataEraser
            ?: LocalDataEraser(repository, AttachmentStorage(getApplication<Application>()), reminderNotificationManager, medicationAlarms = medicationAlarms)
    }

    private fun isBusyWithDriveOrDeletion(sync: DriveSyncState, deleting: DataDeletionState, decisionRunning: Boolean): Boolean =
        sync is DriveSyncState.Syncing || sync is DriveSyncState.Restoring ||
            deleting is DataDeletionState.Running || decisionRunning

    /**
     * Whether the cloud buttons ("apagar a nuvem" and "apagar ambos") may be tapped: an account is connected
     * and nothing else is running (sync, restore, safety copy, another deletion).
     */
    val canDeleteCloud: StateFlow<Boolean> = combine(
        driveAccountEmail, driveSyncState, _dataDeletion, _decisionActionInProgress
    ) { email, sync, deleting, decisionRunning ->
        !email.isNullOrBlank() && !isBusyWithDriveOrDeletion(sync, deleting, decisionRunning)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether "apagar tudo neste aparelho" may be tapped: nothing else is running. Works without an account. */
    val canDeleteDevice: StateFlow<Boolean> = combine(
        driveSyncState, _dataDeletion, _decisionActionInProgress
    ) { sync, deleting, decisionRunning ->
        !isBusyWithDriveOrDeletion(sync, deleting, decisionRunning)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        val dao = AppDatabase.getDatabase(application).stellaDao()
        repository = StellaRepository(dao)
        // Ensure initial profile exists
        viewModelScope.launch {
            repository.profile.collect { existing ->
                if (existing == null) {
                    repository.saveProfile(StellaProfile())
                }
            }
        }
        // Remote verification / configuration of Google Drive connection
        activeVerifyJob = viewModelScope.launch {
            when (driveSyncManager.connectionStatus.value) {
                DriveConnectionStatus.VERIFYING -> {
                    val status = driveSyncManager.verifyDriveConnection()
                    if (status == DriveConnectionStatus.CONFIGURED && !driveSyncManager.isSyncApprovedForAccount(driveAccountEmail.value)) {
                        checkSyncApprovalOrPromptDecision(driveAccountEmail.value)
                    }
                }
                DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE -> {
                    val configured = driveSyncManager.ensureFolderConfigured()
                    if (configured && !driveSyncManager.isSyncApprovedForAccount(driveAccountEmail.value)) {
                        checkSyncApprovalOrPromptDecision(driveAccountEmail.value)
                    }
                }
                DriveConnectionStatus.CONFIGURED -> {
                    if (!driveSyncManager.isSyncApprovedForAccount(driveAccountEmail.value)) {
                        checkSyncApprovalOrPromptDecision(driveAccountEmail.value)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun scheduleDebouncedSync(delayMillis: Long = 10_000L) {
        val email = driveAccountEmail.value
        if (driveConnectionStatus.value != DriveConnectionStatus.CONFIGURED ||
            email == null ||
            !driveSyncManager.isSyncApprovedForAccount(email)
        ) {
            debouncedSyncJob?.cancel()
            return
        }
        debouncedSyncJob?.cancel()
        debouncedSyncJob = viewModelScope.launch {
            delay(delayMillis)
            if (driveConnectionStatus.value == DriveConnectionStatus.CONFIGURED &&
                driveAccountEmail.value != null &&
                driveSyncManager.isSyncApprovedForAccount(driveAccountEmail.value)
            ) {
                syncToGoogleDrive()
            }
        }
    }

    val profile: StateFlow<StellaProfile> = repository.profile
        .combine(MutableStateFlow(StellaProfile())) { dbProfile, defaultProfile ->
            dbProfile ?: defaultProfile
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StellaProfile())

    val glucoseList: StateFlow<List<GlucoseRecord>> = repository.allGlucoseRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val medicationsList: StateFlow<List<Medication>> = repository.allMedications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val administrationsList: StateFlow<List<MedicationAdministration>> = repository.allAdministrations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appointmentsList: StateFlow<List<VeterinaryAppointment>> = repository.allAppointments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val examDocumentsList: StateFlow<List<ExamDocument>> = repository.allExamDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val feedingList: StateFlow<List<FeedingRecord>> = repository.allFeedingRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weightList: StateFlow<List<WeightRecord>> = repository.allWeightRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vaccinesList: StateFlow<List<VaccinePreventionRecord>> = repository.allVaccines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val remindersList: StateFlow<List<Reminder>> = repository.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search query state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // Profile actions
    fun updateProfile(updated: StellaProfile) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.saveProfile(updated)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // Glucose actions
    fun addGlucose(
        dateString: String,
        timeString: String,
        value: Double,
        unit: String,
        mealRelation: String,
        medicationRelation: String,
        notes: String,
        symptoms: String
    ): Boolean {
        val millis = parseDateTimeToMillis(dateString, timeString)
        if (millis == null) {
            _uiErrorMessage.value = "Data ou horário inválido para a medição de glicemia (use DD/MM/AAAA e HH:MM)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                repository.addGlucoseRecord(
                    GlucoseRecord(
                        dateTimeMillis = millis,
                        dateString = dateString,
                        timeString = timeString,
                        value = value,
                        unit = unit,
                        mealRelation = mealRelation,
                        medicationRelation = medicationRelation,
                        notes = notes,
                        symptoms = symptoms
                    )
                )
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun deleteGlucose(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.deleteGlucoseRecord(id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // Medication actions
    fun addMedication(
        name: String,
        activeIngredient: String,
        dosage: String,
        unit: String,
        route: String,
        quantity: String,
        frequency: String,
        scheduledTimes: String,
        startDate: String,
        endDate: String,
        vetName: String,
        reason: String,
        notes: String,
        isVitamin: Boolean
    ) {
        viewModelScope.launch {
            dbMutex.withLock {
                val newMedication = Medication(
                        name = name,
                        activeIngredient = activeIngredient,
                        dosage = dosage,
                        unit = unit,
                        administrationRoute = route,
                        quantity = quantity,
                        frequency = frequency,
                        scheduledTimes = scheduledTimes,
                        startDate = startDate,
                        endDate = endDate,
                        vetName = vetName,
                        reason = reason,
                        notes = notes,
                        isVitaminOrSupplement = isVitamin,
                        status = "ATIVO"
                    )
                val newId = repository.addMedication(newMedication)
                medicationAlarms.scheduleFor(newMedication.copy(id = newId))
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun suspendMedication(medication: Medication, reason: String) {
        viewModelScope.launch {
            dbMutex.withLock {
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                val todayStr = sdf.format(Date())
                repository.updateMedication(
                    medication.copy(
                        status = "ENCERRADO",
                        suspensionReason = reason,
                        suspensionDate = todayStr
                    )
                )
                medicationAlarms.cancelFor(medication.id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun reactivateMedication(medication: Medication) {
        viewModelScope.launch {
            dbMutex.withLock {
                val reactivated = medication.copy(
                    status = "ATIVO",
                    suspensionReason = "",
                    suspensionDate = ""
                )
                repository.updateMedication(reactivated)
                medicationAlarms.scheduleFor(reactivated)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun deleteMedication(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.deleteMedication(id)
                medicationAlarms.cancelFor(id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun recordMedicationAdministration(
        medicationId: Long,
        medicationName: String,
        dosage: String,
        dateString: String,
        timeString: String,
        registeredBy: String,
        notes: String
    ): Boolean {
        val millis = parseDateTimeToMillis(dateString, timeString)
        if (millis == null) {
            _uiErrorMessage.value = "Data ou horário inválido para o registro da medicação (use DD/MM/AAAA e HH:MM)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                repository.addAdministration(
                    MedicationAdministration(
                        medicationId = medicationId,
                        medicationName = medicationName,
                        dosage = dosage,
                        timestamp = millis,
                        dateString = dateString,
                        timeString = timeString,
                        registeredBy = registeredBy,
                        notes = notes
                    )
                )
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun deleteAdministration(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.deleteAdministration(id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // Appointment actions
    fun addAppointment(
        dateString: String,
        timeString: String,
        vetName: String,
        clinicName: String,
        reason: String,
        weightKg: Double?,
        temperatureC: Double?,
        notes: String,
        symptomsReported: String,
        conductRecommended: String,
        medicationsPrescribed: String,
        nextAppointmentDate: String,
        attachments: List<StagedAttachment> = emptyList()
    ): Boolean {
        val millis = parseDateTimeToMillis(dateString, timeString)
        if (millis == null) {
            _uiErrorMessage.value = "Data ou horário inválido para a consulta (use DD/MM/AAAA e HH:MM)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                val appointmentId = repository.addAppointment(
                    VeterinaryAppointment(
                        dateString = dateString,
                        timeString = timeString,
                        timestamp = millis,
                        vetName = vetName,
                        clinicName = clinicName,
                        reason = reason,
                        weightKg = weightKg,
                        temperatureC = temperatureC,
                        notes = notes,
                        symptomsReported = symptomsReported,
                        conductRecommended = conductRecommended,
                        medicationsPrescribed = medicationsPrescribed,
                        nextAppointmentDate = nextAppointmentDate
                    )
                )
                if (weightKg != null && weightKg > 0.0) {
                    repository.addWeightRecord(
                        WeightRecord(
                            dateString = dateString,
                            weightKg = weightKg,
                            notes = "Aferido em consulta ($vetName)"
                        )
                    )
                }
                commitStagedLocked(AttachmentOwner.APPOINTMENT, appointmentId, attachments)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun deleteAppointment(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                val filePaths = repository.getAttachmentsForSync(AttachmentOwner.APPOINTMENT, id).map { it.localPath }
                repository.deleteAppointment(id)
                attachmentManager.deleteFilesIfUnreferenced(filePaths)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // Exam Document actions
    fun addExamDocument(
        name: String,
        fileType: String,
        dateString: String,
        clinicName: String,
        vetName: String,
        category: String,
        notes: String,
        fileUri: String?,
        attachments: List<StagedAttachment> = emptyList()
    ) {
        viewModelScope.launch {
            dbMutex.withLock {
                val documentId = repository.addExamDocument(
                    ExamDocument(
                        name = name,
                        fileType = fileType,
                        dateString = dateString,
                        clinicName = clinicName,
                        vetName = vetName,
                        category = category,
                        notes = notes,
                        fileUriOrPath = fileUri
                    )
                )
                commitStagedLocked(AttachmentOwner.EXAM_DOCUMENT, documentId, attachments)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun updateExamDocument(document: ExamDocument) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.updateExamDocument(document)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun deleteExamDocument(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                val filePaths = repository.getAttachmentsForSync(AttachmentOwner.EXAM_DOCUMENT, id).map { it.localPath }
                repository.deleteExamDocument(id)
                attachmentManager.deleteFilesIfUnreferenced(filePaths)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Attachments: several PDFs / images per consultation or exam-document
    // ---------------------------------------------------------------------------------------

    private val attachmentManager: AttachmentManager by lazy {
        AttachmentManager(repository, AttachmentStorage(getApplication<Application>()))
    }

    /** All attachments, for screens that only need a count or an icon per record. */
    val allAttachments: StateFlow<List<Attachment>> = repository.allAttachments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _brokenAttachments = MutableStateFlow<List<Attachment>>(emptyList())

    /** Attachments whose file is missing or damaged. Filled by [refreshBrokenAttachments]. */
    val brokenAttachments: StateFlow<List<Attachment>> = _brokenAttachments.asStateFlow()

    init {
        // Old files nobody uses (a dialog abandoned long ago). Skipped when the folder is empty.
        viewModelScope.launch {
            try {
                attachmentManager.cleanupUnreferenced()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("StellaViewModel", "Limpeza de anexos sem uso falhou", e)
            }
        }
    }

    fun attachmentsFor(ownerType: String, ownerId: Long): Flow<List<Attachment>> =
        attachmentManager.attachmentsFor(ownerType, ownerId)

    /**
     * Step 1 for a record that is still being filled in: copies the picked file into private storage.
     * Pass the staged files to [addAppointment] or [addExamDocument] when the user saves, or give each
     * one back to [discardStagedAttachment] if the dialog is closed without saving.
     */
    fun stageAttachment(
        uri: String,
        label: String = AttachmentLimits.DEFAULT_LABEL,
        onResult: (StageResult) -> Unit
    ) {
        viewModelScope.launch { onResult(attachmentManager.stage(uri, label)) }
    }

    fun discardStagedAttachment(staged: StagedAttachment) {
        viewModelScope.launch { attachmentManager.discardStaged(staged) }
    }

    /** For a record that already exists: picks, copies and attaches in one go. */
    fun addAttachment(
        ownerType: String,
        ownerId: Long,
        uri: String,
        label: String = AttachmentLimits.DEFAULT_LABEL,
        onResult: (AddAttachmentResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            when (val staged = attachmentManager.stage(uri, label)) {
                is StageResult.Rejected -> onResult(AddAttachmentResult.Rejected(staged.reason, staged.message))
                is StageResult.Staged -> {
                    val commit = dbMutex.withLock {
                        attachmentManager.commit(ownerType, ownerId, listOf(staged.attachment)).also {
                            if (it.added.isNotEmpty()) driveSyncManager.markPendingChanges()
                        }
                    }
                    val added = commit.added.firstOrNull()
                    if (added != null) {
                        scheduleDebouncedSync()
                        onResult(AddAttachmentResult.Added(added))
                    } else {
                        onResult(
                            AddAttachmentResult.Rejected(
                                commit.reason ?: com.example.data.attachments.AttachmentRejectReason.UNREADABLE,
                                commit.message ?: "Não foi possível anexar o arquivo."
                            )
                        )
                    }
                }
            }
        }
    }

    fun removeAttachment(attachmentId: Long, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val removed = dbMutex.withLock {
                attachmentManager.remove(attachmentId).also {
                    if (it) driveSyncManager.markPendingChanges()
                }
            }
            if (removed) scheduleDebouncedSync()
            onDone(removed)
        }
    }

    /** Intent to show the file in another app; null when the file is missing. Start it from the screen. */
    fun openAttachmentIntent(attachment: Attachment): android.content.Intent? =
        attachmentManager.openIntent(attachment)

    fun refreshBrokenAttachments() {
        viewModelScope.launch { _brokenAttachments.value = attachmentManager.findBroken() }
    }

    /**
     * Profile photo: copies the picked image into private storage (the picker's own access is temporary)
     * and saves its path in the profile. Use this instead of storing the picker's address.
     */
    fun setProfilePhoto(uri: String, onResult: (ImportResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                attachmentManager.storage.importFromUri(uri, AttachmentLimits.IMAGE_MIME_TYPES)
            }
            if (result is ImportResult.Imported) {
                dbMutex.withLock {
                    val current = repository.getProfileSync() ?: StellaProfile()
                    val previousPhoto = current.photoUri
                    repository.saveProfile(current.copy(photoUri = result.file.localPath))
                    previousPhoto?.takeIf { it.isNotBlank() }?.let {
                        attachmentManager.deleteFilesIfUnreferenced(listOf(it))
                    }
                    driveSyncManager.markPendingChanges()
                }
                scheduleDebouncedSync()
            }
            onResult(result)
        }
    }

    /** Runs inside the database lock (the callers already hold it). */
    private suspend fun commitStagedLocked(ownerType: String, ownerId: Long, staged: List<StagedAttachment>) {
        if (staged.isEmpty()) return
        attachmentManager.commit(ownerType, ownerId, staged).message?.let { _uiErrorMessage.value = it }
    }

    // Feeding actions
    fun addFeedingRecord(
        brand: String,
        foodName: String,
        type: String,
        line: String,
        flavor: String,
        dailyAmount: String,
        portionAmount: String,
        mealsPerDay: Int,
        scheduleTimes: String,
        startDate: String,
        notes: String
    ) {
        viewModelScope.launch {
            dbMutex.withLock {
                val all = feedingList.value
                all.forEach {
                    if (it.isCurrent) {
                        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        repository.updateFeedingRecord(it.copy(isCurrent = false, endDate = sdf.format(Date())))
                    }
                }
                repository.addFeedingRecord(
                    FeedingRecord(
                        brand = brand,
                        foodName = foodName,
                        type = type,
                        line = line,
                        flavor = flavor,
                        dailyAmount = dailyAmount,
                        portionAmount = portionAmount,
                        mealsPerDay = mealsPerDay,
                        scheduleTimes = scheduleTimes,
                        startDate = startDate,
                        isCurrent = true,
                        notes = notes
                    )
                )
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun deleteFeedingRecord(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.deleteFeedingRecord(id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // Weight actions
    fun addWeightRecord(dateString: String, weightKg: Double, notes: String): Boolean {
        val millis = parseDateTimeToMillis(dateString, "12:00")
        if (millis == null) {
            _uiErrorMessage.value = "Data inválida para o registro de peso (use DD/MM/AAAA)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                repository.addWeightRecord(
                    WeightRecord(
                        timestamp = millis,
                        dateString = dateString,
                        weightKg = weightKg,
                        notes = notes
                    )
                )
                val p = profile.value
                repository.saveProfile(p.copy(currentWeight = weightKg))
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun deleteWeightRecord(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.deleteWeightRecord(id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // Vaccine & Prevention actions
    fun addVaccine(
        name: String,
        type: String,
        dateAdministered: String,
        dose: String,
        nextDueDate: String,
        vetName: String,
        clinicName: String,
        notes: String,
        scheduleReminder: Boolean = false
    ): Boolean {
        if (!DateTimeUtils.isValidDate(dateAdministered)) {
            _uiErrorMessage.value = "Data de aplicação da vacina inválida (use DD/MM/AAAA)."
            return false
        }
        if (nextDueDate.isNotBlank() && !DateTimeUtils.isValidDate(nextDueDate)) {
            _uiErrorMessage.value = "Data do próximo reforço inválida (use DD/MM/AAAA)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                val record = VaccinePreventionRecord(
                    name = name,
                    type = type,
                    dateAdministered = dateAdministered,
                    dose = dose,
                    nextDueDate = nextDueDate,
                    vetName = vetName,
                    clinicName = clinicName,
                    notes = notes
                )
                repository.addVaccine(record)

                if (scheduleReminder && nextDueDate.isNotBlank()) {
                    val reminderMillis = parseDateTimeToMillis(nextDueDate, "09:00")
                    if (reminderMillis != null) {
                        val reminder = Reminder(
                            title = "Reforço de $name ($type)",
                            category = "Vacina",
                            dateString = nextDueDate,
                            timeString = "09:00",
                            timestamp = reminderMillis,
                            status = "PENDENTE",
                            notes = "Reforço programado para Stella. Dose: $dose"
                        )
                        val reminderId = repository.addReminder(reminder)
                        reminderNotificationManager.scheduleNotification(reminder.copy(id = reminderId))
                    }
                }

                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun updateVaccine(
        record: VaccinePreventionRecord,
        scheduleReminder: Boolean = false
    ): Boolean {
        if (!DateTimeUtils.isValidDate(record.dateAdministered)) {
            _uiErrorMessage.value = "Data de aplicação da vacina inválida (use DD/MM/AAAA)."
            return false
        }
        if (record.nextDueDate.isNotBlank() && !DateTimeUtils.isValidDate(record.nextDueDate)) {
            _uiErrorMessage.value = "Data do próximo reforço inválida (use DD/MM/AAAA)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                repository.updateVaccine(record)

                if (scheduleReminder && record.nextDueDate.isNotBlank()) {
                    val reminderMillis = parseDateTimeToMillis(record.nextDueDate, "09:00")
                    if (reminderMillis != null) {
                        val reminder = Reminder(
                            title = "Reforço de ${record.name} (${record.type})",
                            category = "Vacina",
                            dateString = record.nextDueDate,
                            timeString = "09:00",
                            timestamp = reminderMillis,
                            status = "PENDENTE",
                            notes = "Reforço programado para Stella. Dose: ${record.dose}"
                        )
                        val reminderId = repository.addReminder(reminder)
                        reminderNotificationManager.scheduleNotification(reminder.copy(id = reminderId))
                    }
                }

                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun deleteVaccine(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.deleteVaccine(id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    // Reminder actions
    fun addReminder(
        title: String,
        category: String,
        dateString: String,
        timeString: String,
        notes: String
    ): Boolean {
        val millis = parseDateTimeToMillis(dateString, timeString)
        if (millis == null) {
            _uiErrorMessage.value = "Data ou horário inválido para o lembrete (use DD/MM/AAAA e HH:MM)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                val reminder = Reminder(
                    title = title,
                    category = category,
                    dateString = dateString,
                    timeString = timeString,
                    timestamp = millis,
                    status = "PENDENTE",
                    notes = notes
                )
                val newId = repository.addReminder(reminder)
                val scheduledReminder = reminder.copy(id = newId)
                reminderNotificationManager.scheduleNotification(scheduledReminder)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun updateReminder(reminder: Reminder): Boolean {
        val millis = parseDateTimeToMillis(reminder.dateString, reminder.timeString)
        if (millis == null) {
            _uiErrorMessage.value = "Data ou horário inválido para o lembrete (use DD/MM/AAAA e HH:MM)."
            return false
        }
        viewModelScope.launch {
            dbMutex.withLock {
                val validReminder = reminder.copy(timestamp = millis)
                repository.updateReminder(validReminder)
                if (validReminder.status == "PENDENTE") {
                    reminderNotificationManager.scheduleNotification(validReminder)
                } else {
                    reminderNotificationManager.cancelNotification(validReminder.id)
                }
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
        return true
    }

    fun toggleReminderStatus(reminder: Reminder) {
        viewModelScope.launch {
            dbMutex.withLock {
                val newStatus = if (reminder.status == "REALIZADO") "PENDENTE" else "REALIZADO"
                val execTime = if (newStatus == "REALIZADO") System.currentTimeMillis() else null
                val updated = reminder.copy(status = newStatus, executionTimestamp = execTime)
                repository.updateReminder(updated)
                if (newStatus == "PENDENTE") {
                    reminderNotificationManager.scheduleNotification(updated)
                } else {
                    reminderNotificationManager.cancelNotification(reminder.id)
                }
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch {
            dbMutex.withLock {
                repository.deleteReminder(id)
                reminderNotificationManager.cancelNotification(id)
                driveSyncManager.markPendingChanges()
            }
            scheduleDebouncedSync()
        }
    }

    fun syncToGoogleDrive() {
        debouncedSyncJob?.cancel()
        // Nothing goes up to Drive while the cloud or this device is being erased.
        if (dataDeletionGuard.get()) {
            android.util.Log.w("StellaViewModel", "syncToGoogleDrive abortado: apagamento de dados em andamento")
            return
        }
        val email = driveAccountEmail.value
        if (!driveSyncManager.isSyncApprovedForAccount(email)) {
            android.util.Log.w("StellaViewModel", "syncToGoogleDrive abortado: sincronização ainda não liberada para a conta atual")
            return
        }
        activeSyncJob = viewModelScope.launch {
            val captured = dbMutex.withLock {
                driveSyncManager.captureBackup(repository)
            }
            driveSyncManager.syncToDrive(captured)
        }
    }

    fun handleDriveSignIn(account: GoogleSignInAccount) {
        viewModelScope.launch {
            val status = driveSyncManager.handleSignInSuccess(account)
            if (status == DriveConnectionStatus.CONFIGURED) {
                val email = account.email
                if (driveSyncManager.isSyncApprovedForAccount(email)) {
                    syncToGoogleDrive()
                } else {
                    checkSyncApprovalOrPromptDecision(email)
                }
            }
        }
    }

    fun configureDriveFolder() {
        viewModelScope.launch {
            val configured = driveSyncManager.ensureFolderConfigured()
            if (configured) {
                val email = driveAccountEmail.value
                if (driveSyncManager.isSyncApprovedForAccount(email)) {
                    syncToGoogleDrive()
                } else {
                    checkSyncApprovalOrPromptDecision(email)
                }
            }
        }
    }

    fun verifyDriveConnection() {
        activeVerifyJob = viewModelScope.launch {
            driveSyncManager.verifyDriveConnection()
        }
    }

    fun onDriveAppResumed() {
        if (driveAccountEmail.value.isNullOrBlank()) {
            return
        }

        if (driveSyncManager.recoverableAuthIntent.value != null) {
            return
        }

        val isEligibleStatus = when (driveConnectionStatus.value) {
            DriveConnectionStatus.CONFIGURED,
            DriveConnectionStatus.UNVERIFIED,
            DriveConnectionStatus.PERMISSION_MISSING,
            DriveConnectionStatus.ERROR -> true
            DriveConnectionStatus.NOT_CONNECTED,
            DriveConnectionStatus.CONNECTING,
            DriveConnectionStatus.VERIFYING,
            DriveConnectionStatus.ACCOUNT_CONNECTED_NO_DRIVE -> false
        }
        if (!isEligibleStatus) {
            return
        }

        val currentSyncState = driveSyncState.value
        if (currentSyncState is DriveSyncState.Syncing ||
            currentSyncState is DriveSyncState.Restoring ||
            activeSyncJob?.isActive == true ||
            activeVerifyJob?.isActive == true
        ) {
            return
        }

        val now = currentTimeMillisProvider()
        val lastCheck = lastForegroundVerifyTimestampMs
        if (lastCheck != null && now - lastCheck < FOREGROUND_VERIFY_MIN_INTERVAL_MS) {
            return
        }

        if (!driveSyncManager.operationMutex.tryLock()) {
            return
        }

        val syncStateUnderLock = driveSyncState.value
        if (syncStateUnderLock is DriveSyncState.Syncing ||
            syncStateUnderLock is DriveSyncState.Restoring ||
            activeSyncJob?.isActive == true ||
            activeVerifyJob?.isActive == true
        ) {
            driveSyncManager.operationMutex.unlock()
            return
        }

        lastForegroundVerifyTimestampMs = now

        val unlocked = java.util.concurrent.atomic.AtomicBoolean(false)
        val unlockOnce = {
            if (unlocked.compareAndSet(false, true)) {
                driveSyncManager.operationMutex.unlock()
            }
        }

        val job = viewModelScope.launch {
            val verifiedStatus = try {
                driveSyncManager.verifyDriveConnection()
            } finally {
                unlockOnce()
            }
            if (verifiedStatus == DriveConnectionStatus.CONFIGURED &&
                hasPendingChanges.value &&
                driveSyncManager.isSyncApprovedForAccount(driveAccountEmail.value)
            ) {
                syncToGoogleDrive()
            }
        }
        activeVerifyJob = job
        job.invokeOnCompletion {
            unlockOnce()
        }
    }

    fun checkSyncApprovalOrPromptDecision(email: String? = driveAccountEmail.value) {
        val targetEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return

        if (driveSyncManager.isSyncApprovedForAccount(targetEmail)) {
            _syncDecisionState.value = DriveSyncDecisionState.None
            return
        }

        if (decisionCheckJob?.isActive == true || decisionActionGuard.get()) {
            return
        }

        decisionCheckJob = viewModelScope.launch {
            _syncDecisionState.value = DriveSyncDecisionState.Checking
            try {
                val exists = driveSyncManager.checkRemoteBackupExists(targetEmail)
                if (!exists) {
                    driveSyncManager.setSyncApprovedForAccount(targetEmail, true)
                    _syncDecisionState.value = DriveSyncDecisionState.None
                    syncToGoogleDrive()
                } else {
                    val remotePreview = driveSyncManager.getBackupPreviewFromDrive(targetEmail)
                    val snapshot = dbMutex.withLock { repository.getBackupSnapshot() }
                    val isLocalEmpty = isLocalDatabaseEmpty(snapshot)
                    val localCount = countLocalRecords(snapshot)

                    _syncDecisionState.value = DriveSyncDecisionState.DecisionPending(
                        accountEmail = targetEmail,
                        remotePreview = remotePreview,
                        isLocalEmpty = isLocalEmpty,
                        localRecordCount = localCount
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StellaViewModel", "Erro ao verificar backup existente no Drive", e)
                _syncDecisionState.value = DriveSyncDecisionState.Error(
                    e.localizedMessage ?: "Falha ao verificar backup existente no Google Drive"
                )
            }
        }
    }

    private fun tryBeginDecisionAction(): Boolean {
        // A restore or an overwrite must not start while something is being erased.
        if (dataDeletionGuard.get()) return false
        if (!decisionActionGuard.compareAndSet(false, true)) return false
        _decisionActionInProgress.value = true
        return true
    }

    private fun endDecisionAction() {
        decisionActionGuard.set(false)
        _decisionActionInProgress.value = false
    }

    /**
     * The user may dismiss the decision, disconnect or switch account while a (slow) action runs.
     * Approving sync afterwards would open the lock for an account nobody decided about.
     */
    private fun isDecisionStillValid(startedFrom: DriveSyncDecisionState.DecisionPending?, email: String): Boolean =
        if (startedFrom != null) _syncDecisionState.value === startedFrom
        else driveAccountEmail.value == email

    /**
     * Replaces the data on this device with the cloud backup. When the device already holds data of its
     * own, a dated copy of it is uploaded to the Drive folder first; if that fails nothing is restored,
     * the decision stays pending and [onComplete] receives false.
     */
    fun confirmRestoreFromDrive(replaceExisting: Boolean = true, onComplete: ((Boolean) -> Unit)? = null) {
        val pending = _syncDecisionState.value as? DriveSyncDecisionState.DecisionPending
        val email = pending?.accountEmail ?: driveAccountEmail.value
        if (email.isNullOrBlank()) {
            onComplete?.invoke(false)
            return
        }
        // Double tap: the first one is still running and will report through its own onComplete.
        if (!tryBeginDecisionAction()) return

        viewModelScope.launch {
            try {
                debouncedSyncJob?.cancel()
                val success = driveSyncManager.restoreBackupFromDrive(
                    repository,
                    replaceExisting,
                    protectLocalData = true
                )
                if (success && isDecisionStillValid(pending, email)) {
                    driveSyncManager.setSyncApprovedForAccount(email, true)
                    driveSyncManager.clearPendingChanges()
                    _syncDecisionState.value = DriveSyncDecisionState.None
                }
                onComplete?.invoke(success)
            } finally {
                endDecisionAction()
            }
        }
    }

    /**
     * Replaces the cloud backup with the data on this device. Before anything is sent, Drive makes a dated
     * copy of the backup that is there now. If the copy fails nothing is replaced, the decision stays
     * pending and the reason is published in [uiErrorMessage].
     */
    fun confirmOverwriteDriveBackup() {
        val pending = _syncDecisionState.value as? DriveSyncDecisionState.DecisionPending
        val email = pending?.accountEmail ?: driveAccountEmail.value
        if (email.isNullOrBlank()) return
        // Double tap: ignored, the first one is already making the copy.
        if (!tryBeginDecisionAction()) return

        viewModelScope.launch {
            try {
                // Dismissed or disconnected before it even started: do nothing, not even the copy.
                if (!isDecisionStillValid(pending, email)) return@launch
                debouncedSyncJob?.cancel()
                when (val copy = driveSyncManager.copyExistingBackupForSafety(email)) {
                    is SafetyCopyResult.Failed -> {
                        _uiErrorMessage.value = copy.message
                        return@launch
                    }
                    is SafetyCopyResult.Created, SafetyCopyResult.NothingToCopy -> Unit
                }
                if (!isDecisionStillValid(pending, email)) return@launch
                driveSyncManager.setSyncApprovedForAccount(email, true)
                _syncDecisionState.value = DriveSyncDecisionState.None
                syncToGoogleDrive()
            } finally {
                endDecisionAction()
            }
        }
    }

    /**
     * Reads the list of backup versions on Drive into [backupVersions]. Only names and dates are read, so it
     * is quick; use [previewBackupVersion] to see what is inside one. Call it again after a restore or a
     * sync, because those can create new dated copies.
     */
    fun loadBackupVersions() {
        val email = driveAccountEmail.value?.trim()?.takeIf { it.isNotEmpty() }
        if (email == null) {
            _backupVersions.value = BackupVersionsState.Error("Conecte uma conta do Google Drive para ver as versões do backup.")
            return
        }
        backupVersionsJob?.cancel()
        _backupVersions.value = BackupVersionsState.Loading
        backupVersionsJob = viewModelScope.launch {
            _backupVersions.value = try {
                BackupVersionsState.Loaded(driveSyncManager.listBackupVersions(email))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StellaViewModel", "Erro ao listar versões do backup", e)
                BackupVersionsState.Error(e.localizedMessage ?: "Não foi possível listar as versões do backup no Google Drive")
            }
        }
    }

    /** What is inside the backup version [fileId] (one of the ids in [backupVersions]). Changes nothing. */
    fun previewBackupVersion(fileId: String, onResult: (BackupPreviewResult) -> Unit) {
        val email = driveAccountEmail.value?.trim()?.takeIf { it.isNotEmpty() }
        if (email == null) {
            onResult(BackupPreviewResult.Failed("Conecte uma conta do Google Drive para ver esta versão."))
            return
        }
        viewModelScope.launch {
            val result = try {
                BackupPreviewResult.Loaded(driveSyncManager.getBackupPreviewFromDrive(email, versionFileId = fileId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StellaViewModel", "Erro ao ler a versão do backup", e)
                BackupPreviewResult.Failed(e.localizedMessage ?: "Não foi possível ler esta versão do backup")
            }
            onResult(result)
        }
    }

    /**
     * Goes back to the backup version [fileId]. Before anything changes on this device, a dated copy of its
     * data is uploaded when it holds data of its own; and when the version is not the current backup, the
     * current backup is copied aside too (the restored data replaces it at the next sync). If either copy
     * fails nothing is restored and [onComplete] receives false, with the reason in the Drive sync state.
     *
     * [replaceExisting] true replaces this device's data; false adds the backup's records to it.
     * A second tap while one restore runs is ignored (see [decisionActionInProgress]).
     */
    fun restoreBackupVersion(fileId: String, replaceExisting: Boolean = true, onComplete: ((Boolean) -> Unit)? = null) {
        if (driveAccountEmail.value.isNullOrBlank()) {
            onComplete?.invoke(false)
            return
        }
        if (!tryBeginDecisionAction()) return

        viewModelScope.launch {
            try {
                debouncedSyncJob?.cancel()
                val success = driveSyncManager.restoreBackupFromDrive(
                    repository,
                    replaceExisting,
                    protectLocalData = true,
                    versionFileId = fileId
                )
                onComplete?.invoke(success)
            } finally {
                endDecisionAction()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Deleting data: the cloud, this device, or both. The screens ask for three confirmations
    // (information with the numbers and "irreversível", typing [DELETE_CONFIRMATION_WORD], a last button)
    // and enable the buttons from [canDeleteCloud] / [canDeleteDevice]; the functions below do the work.
    // ---------------------------------------------------------------------------------------

    /**
     * Counts what is on Drive for the information screen (see [cloudFilesCount]). Only reads names, never
     * changes anything.
     */
    fun loadCloudFilesCount() {
        val email = driveAccountEmail.value?.trim()?.takeIf { it.isNotEmpty() }
        if (email == null) {
            _cloudFilesCount.value = CloudFilesCountState.Error("Conecte uma conta do Google Drive para ver quantos arquivos há na nuvem.")
            return
        }
        cloudFilesCountJob?.cancel()
        _cloudFilesCount.value = CloudFilesCountState.Loading
        cloudFilesCountJob = viewModelScope.launch {
            _cloudFilesCount.value = try {
                CloudFilesCountState.Loaded(driveSyncManager.countCloudFiles(email))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StellaViewModel", "Erro ao contar os arquivos da nuvem", e)
                CloudFilesCountState.Error(e.localizedMessage ?: "Não foi possível contar os arquivos no Google Drive")
            }
        }
    }

    /**
     * "Apagar a nuvem": permanently deletes every file the app has on Drive (the folder stays, empty) and,
     * only if that fully worked, disconnects the account. The data on this device is NOT touched.
     * Without internet (or any failure) nothing is reported as done and the account stays connected: see
     * [dataDeletion], and repeat later. [onComplete] receives true only when everything was deleted.
     */
    fun deleteCloudData(onComplete: ((Boolean) -> Unit)? = null) = runDataDeletion(DataDeletionKind.CLOUD, onComplete)

    /**
     * "Apagar tudo neste aparelho": records, photos, exams, attachments and reminder alarms. The Drive
     * account stays connected and the backup on Drive is NOT touched, but sync stays locked until the user
     * decides again what to do with it (the decision screen appears), so the next edit cannot overwrite
     * the cloud backup with an almost empty device.
     */
    fun deleteDeviceData(onComplete: ((Boolean) -> Unit)? = null) = runDataDeletion(DataDeletionKind.DEVICE, onComplete)

    /**
     * "Apagar ambos": the cloud first (if that fails, nothing is deleted on this device), then this device,
     * then the account is disconnected. Complete only when both are really gone. If the cloud was deleted
     * but this device could not be fully erased, the account stays connected so it can be repeated.
     */
    fun deleteDeviceAndCloudData(onComplete: ((Boolean) -> Unit)? = null) = runDataDeletion(DataDeletionKind.BOTH, onComplete)

    /** The screen has shown the result of a deletion. Does nothing while one is running. */
    fun dismissDataDeletionResult() {
        if (_dataDeletion.value !is DataDeletionState.Running) {
            _dataDeletion.value = DataDeletionState.Idle
        }
    }

    private fun runDataDeletion(kind: DataDeletionKind, onComplete: ((Boolean) -> Unit)?) {
        // Double tap: a deletion is already running; it reports through its own onComplete and its own state.
        if (dataDeletionGuard.get()) return
        val email = driveAccountEmail.value?.trim()?.takeIf { it.isNotEmpty() }
        if (kind != DataDeletionKind.DEVICE && email == null) {
            _dataDeletion.value = DataDeletionState.Failed(kind, "Conecte uma conta do Google Drive para apagar a nuvem.")
            onComplete?.invoke(false)
            return
        }
        val busy = driveSyncState.value.let { it is DriveSyncState.Syncing || it is DriveSyncState.Restoring } ||
            decisionActionGuard.get() || activeSyncJob?.isActive == true
        if (busy) {
            _dataDeletion.value = DataDeletionState.Failed(
                kind, "Há uma sincronização ou restauração em andamento. Espere terminar e tente de novo."
            )
            onComplete?.invoke(false)
            return
        }
        if (!dataDeletionGuard.compareAndSet(false, true)) return
        _dataDeletion.value = DataDeletionState.Running(
            kind,
            if (kind == DataDeletionKind.DEVICE) "Apagando os dados deste aparelho..." else "Apagando os arquivos do Google Drive..."
        )
        debouncedSyncJob?.cancel()
        decisionCheckJob?.cancel()
        _syncDecisionState.value = DriveSyncDecisionState.None

        viewModelScope.launch {
            var result: DataDeletionState = DataDeletionState.Failed(kind, "A exclusão foi interrompida antes de terminar.")
            try {
                result = when (kind) {
                    DataDeletionKind.CLOUD -> deleteCloudStep()
                    DataDeletionKind.DEVICE -> deleteDeviceStep(email)
                    DataDeletionKind.BOTH -> deleteBothStep()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StellaViewModel", "Erro inesperado ao apagar dados ($kind)", e)
                result = DataDeletionState.Failed(
                    kind, "Não foi possível terminar de apagar. (${e.localizedMessage ?: e.message ?: "erro desconhecido"})"
                )
            } finally {
                _dataDeletion.value = result
                dataDeletionGuard.set(false)
            }
            // This device was emptied but the account is still connected: ask what to do with the backup
            // on Drive instead of leaving sync locked without saying why.
            if (kind == DataDeletionKind.DEVICE && result is DataDeletionState.Done && email != null) {
                checkSyncApprovalOrPromptDecision(email)
            }
            onComplete?.invoke(result is DataDeletionState.Done)
        }
    }

    private suspend fun deleteCloudStep(): DataDeletionState =
        when (val cloud = driveSyncManager.deleteAllCloudFiles()) {
            is CloudDeleteResult.Failed -> DataDeletionState.Failed(DataDeletionKind.CLOUD, cloud.message, offline = cloud.offline)
            is CloudDeleteResult.Deleted -> {
                driveSyncManager.disconnect()
                DataDeletionState.Done(
                    DataDeletionKind.CLOUD,
                    "Os arquivos do Stella no Google Drive foram apagados e a conta foi desconectada. " +
                        "Os dados deste aparelho foram mantidos.",
                    cloudFilesDeleted = cloud.filesDeleted
                )
            }
        }

    private suspend fun deleteDeviceStep(email: String?): DataDeletionState {
        // The sync/restore lock first, then the database lock: a sync or restore cannot run in the middle.
        val local = driveSyncManager.operationMutex.withLock { dbMutex.withLock { localDataEraser.eraseAll() } }
        // Whatever happened, what is left on the device must never overwrite the backup on Drive unasked.
        driveSyncManager.blockSyncUntilDecision(email)
        return when (local) {
            is LocalEraseResult.Failed -> DataDeletionState.Failed(DataDeletionKind.DEVICE, local.message)
            is LocalEraseResult.Erased -> {
                _brokenAttachments.value = emptyList()
                driveSyncManager.clearPendingChanges()
                DataDeletionState.Done(
                    DataDeletionKind.DEVICE,
                    if (email != null) {
                        "Os dados deste aparelho foram apagados. A sincronização com o Google Drive ficou em pausa " +
                            "até você escolher o que fazer com o backup da nuvem."
                    } else {
                        "Os dados deste aparelho foram apagados."
                    },
                    warnings = local.warnings
                )
            }
        }
    }

    private suspend fun deleteBothStep(): DataDeletionState {
        val email = driveAccountEmail.value
        val cloud = driveSyncManager.deleteAllCloudFiles()
        if (cloud is CloudDeleteResult.Failed) {
            return DataDeletionState.Failed(
                DataDeletionKind.BOTH,
                cloud.message + " Nada foi apagado neste aparelho.",
                offline = cloud.offline
            )
        }
        val cloudFilesDeleted = (cloud as CloudDeleteResult.Deleted).filesDeleted

        _dataDeletion.value = DataDeletionState.Running(DataDeletionKind.BOTH, "Apagando os dados deste aparelho...")
        val local = driveSyncManager.operationMutex.withLock { dbMutex.withLock { localDataEraser.eraseAll() } }
        return when (local) {
            is LocalEraseResult.Failed -> {
                // The account stays connected so the user can repeat; until then nothing may be uploaded again.
                driveSyncManager.blockSyncUntilDecision(email)
                DataDeletionState.Failed(
                    DataDeletionKind.BOTH,
                    "Os arquivos da nuvem foram apagados, mas não foi possível terminar de apagar este aparelho. " +
                        "${local.message} A conta continua conectada: repita \"Apagar ambos\" para concluir.",
                    cloudDeleted = true
                )
            }
            is LocalEraseResult.Erased -> {
                _brokenAttachments.value = emptyList()
                driveSyncManager.clearPendingChanges()
                driveSyncManager.disconnect()
                DataDeletionState.Done(
                    DataDeletionKind.BOTH,
                    "Os arquivos da nuvem e os dados deste aparelho foram apagados e a conta foi desconectada.",
                    cloudFilesDeleted = cloudFilesDeleted,
                    warnings = local.warnings
                )
            }
        }
    }

    fun dismissSyncDecision() {
        val pending = _syncDecisionState.value as? DriveSyncDecisionState.DecisionPending
        val email = pending?.accountEmail ?: driveAccountEmail.value
        if (!email.isNullOrBlank()) {
            driveSyncManager.setSyncApprovedForAccount(email, false)
        }
        _syncDecisionState.value = DriveSyncDecisionState.None
    }

    fun setDriveSignInError(errorMessage: String) {
        driveSyncManager.setSignInError(errorMessage)
    }

    fun prepareForDriveAccountSwitch(onReadyToSignIn: () -> Unit) {
        debouncedSyncJob?.cancel()
        decisionCheckJob?.cancel()
        _syncDecisionState.value = DriveSyncDecisionState.None
        driveSyncManager.prepareForAccountSwitch(onReadyToSignIn)
    }

    fun disconnectGoogleDrive(onComplete: () -> Unit = {}) {
        debouncedSyncJob?.cancel()
        decisionCheckJob?.cancel()
        _syncDecisionState.value = DriveSyncDecisionState.None
        driveSyncManager.signOut(onComplete)
    }

    fun parseDateTimeToMillis(dateStr: String, timeStr: String? = null): Long? {
        return com.example.util.DateTimeUtils.parseDateTimeToMillis(dateStr, timeStr)
    }
}
