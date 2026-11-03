package com.example.data.repository

import com.example.data.dao.StellaDao
import com.example.data.model.Attachment
import com.example.data.model.AttachmentOwner
import com.example.data.model.BackupSnapshot
import com.example.data.model.ExamDocument
import com.example.data.model.FeedingRecord
import com.example.data.model.GlucoseRecord
import com.example.data.model.Medication
import com.example.data.model.MedicationAdministration
import com.example.data.model.Reminder
import com.example.data.model.RestoreResult
import com.example.data.model.StellaProfile
import com.example.data.model.VaccinePreventionRecord
import com.example.data.model.VeterinaryAppointment
import com.example.data.model.WeightRecord
import kotlinx.coroutines.flow.Flow

class StellaRepository(private val dao: StellaDao) {

    // Profile
    val profile: Flow<StellaProfile?> = dao.getProfile()
    suspend fun saveProfile(profile: StellaProfile) = dao.saveProfile(profile)

    // Glucose
    val allGlucoseRecords: Flow<List<GlucoseRecord>> = dao.getAllGlucoseRecords()
    fun getGlucoseSince(minMillis: Long): Flow<List<GlucoseRecord>> = dao.getGlucoseRecordsSince(minMillis)
    fun getGlucoseForDate(date: String): Flow<List<GlucoseRecord>> = dao.getGlucoseRecordsForDate(date)
    suspend fun addGlucoseRecord(record: GlucoseRecord): Long = dao.insertGlucoseRecord(record)
    suspend fun deleteGlucoseRecord(id: Long) = dao.deleteGlucoseRecord(id)

    // Medications
    val allMedications: Flow<List<Medication>> = dao.getAllMedications()
    val activeMedications: Flow<List<Medication>> = dao.getActiveMedications()
    suspend fun addMedication(medication: Medication): Long = dao.insertMedication(medication)
    suspend fun updateMedication(medication: Medication) = dao.updateMedication(medication)
    suspend fun deleteMedication(id: Long) = dao.deleteMedication(id)

    // Administrations
    val allAdministrations: Flow<List<MedicationAdministration>> = dao.getAllAdministrations()
    fun getAdministrationsForMedication(medId: Long): Flow<List<MedicationAdministration>> =
        dao.getAdministrationsForMedication(medId)
    suspend fun addAdministration(admin: MedicationAdministration): Long = dao.insertAdministration(admin)
    suspend fun deleteAdministration(id: Long) = dao.deleteAdministration(id)

    // Appointments
    val allAppointments: Flow<List<VeterinaryAppointment>> = dao.getAllAppointments()
    suspend fun addAppointment(appointment: VeterinaryAppointment): Long = dao.insertAppointment(appointment)
    suspend fun updateAppointment(appointment: VeterinaryAppointment) = dao.updateAppointment(appointment)
    suspend fun deleteAppointment(id: Long) = dao.deleteAppointmentWithAttachments(id)

    // Exam Documents
    val allExamDocuments: Flow<List<ExamDocument>> = dao.getAllExamDocuments()
    suspend fun addExamDocument(document: ExamDocument): Long = dao.insertExamDocument(document)
    suspend fun updateExamDocument(document: ExamDocument) = dao.updateExamDocument(document)
    suspend fun deleteExamDocument(id: Long) = dao.deleteExamDocumentWithAttachments(id)

    // Attachments (several files per consultation or exam/document)
    val allAttachments: Flow<List<Attachment>> = dao.getAllAttachments()
    fun getAttachmentsFor(ownerType: String, ownerId: Long): Flow<List<Attachment>> =
        dao.getAttachmentsFor(ownerType, ownerId)
    suspend fun getAllAttachmentsSync(): List<Attachment> = dao.getAllAttachmentsSync()
    suspend fun getAttachmentsForSync(ownerType: String, ownerId: Long): List<Attachment> =
        dao.getAttachmentsForSync(ownerType, ownerId)
    suspend fun getAttachmentById(id: Long): Attachment? = dao.getAttachmentById(id)
    suspend fun countAttachmentsWithPath(localPath: String): Int = dao.countAttachmentsWithPath(localPath)
    suspend fun deleteAttachment(id: Long) = dao.deleteAttachment(id)
    suspend fun ownerExists(ownerType: String, ownerId: Long): Boolean = when (ownerType) {
        AttachmentOwner.APPOINTMENT -> dao.countAppointmentsWithId(ownerId) > 0
        AttachmentOwner.EXAM_DOCUMENT -> dao.countExamDocumentsWithId(ownerId) > 0
        else -> false
    }
    suspend fun insertAttachmentsRespectingLimit(
        ownerType: String,
        ownerId: Long,
        items: List<Attachment>,
        maxPerRecord: Int
    ): List<Attachment> = dao.insertAttachmentsRespectingLimit(ownerType, ownerId, items, maxPerRecord)

    // Feeding
    val allFeedingRecords: Flow<List<FeedingRecord>> = dao.getAllFeedingRecords()
    suspend fun addFeedingRecord(record: FeedingRecord): Long = dao.insertFeedingRecord(record)
    suspend fun updateFeedingRecord(record: FeedingRecord) = dao.updateFeedingRecord(record)
    suspend fun deleteFeedingRecord(id: Long) = dao.deleteFeedingRecord(id)

    // Weight
    val allWeightRecords: Flow<List<WeightRecord>> = dao.getAllWeightRecords()
    val weightEvolution: Flow<List<WeightRecord>> = dao.getWeightEvolution()
    suspend fun addWeightRecord(record: WeightRecord): Long = dao.insertWeightRecord(record)
    suspend fun deleteWeightRecord(id: Long) = dao.deleteWeightRecord(id)

    // Vaccines & Prevention
    val allVaccines: Flow<List<VaccinePreventionRecord>> = dao.getAllVaccines()
    suspend fun addVaccine(record: VaccinePreventionRecord): Long = dao.insertVaccine(record)
    suspend fun updateVaccine(record: VaccinePreventionRecord) = dao.updateVaccine(record)
    suspend fun deleteVaccine(id: Long) = dao.deleteVaccine(id)

    // Reminders
    val allReminders: Flow<List<Reminder>> = dao.getAllReminders()
    fun getRemindersForDate(date: String): Flow<List<Reminder>> = dao.getRemindersForDate(date)
    suspend fun addReminder(reminder: Reminder): Long = dao.insertReminder(reminder)
    suspend fun updateReminder(reminder: Reminder) = dao.updateReminder(reminder)
    suspend fun deleteReminder(id: Long) = dao.deleteReminder(id)

    // Synchronous reads for Atomic Backup
    suspend fun getProfileSync(): StellaProfile? = dao.getProfileSync()
    suspend fun getAllGlucoseRecordsSync(): List<GlucoseRecord> = dao.getAllGlucoseRecordsSync()
    suspend fun getAllMedicationsSync(): List<Medication> = dao.getAllMedicationsSync()
    suspend fun getAllAdministrationsSync(): List<MedicationAdministration> = dao.getAllAdministrationsSync()
    suspend fun getAllAppointmentsSync(): List<VeterinaryAppointment> = dao.getAllAppointmentsSync()
    suspend fun getAllExamDocumentsSync(): List<ExamDocument> = dao.getAllExamDocumentsSync()
    suspend fun getAllFeedingRecordsSync(): List<FeedingRecord> = dao.getAllFeedingRecordsSync()
    suspend fun getAllWeightRecordsSync(): List<WeightRecord> = dao.getAllWeightRecordsSync()
    suspend fun getAllVaccinesSync(): List<VaccinePreventionRecord> = dao.getAllVaccinesSync()
    suspend fun getAllRemindersSync(): List<Reminder> = dao.getAllRemindersSync()

    // Synchronous snapshot
    suspend fun getBackupSnapshot(): BackupSnapshot = dao.getBackupSnapshot()

    /** Empties every table in one transaction and puts the default profile back ("apagar tudo"). */
    suspend fun clearAllUserData() = dao.clearAllUserData()

    // Bulk Restore (Atomic Transaction)
    suspend fun restoreDatabase(
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
        replaceExisting: Boolean,
        attachments: List<Attachment> = emptyList()
    ): RestoreResult {
        return dao.executeRestoreTransaction(
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
            attachments = attachments,
            replaceExisting = replaceExisting
        )
    }
}
