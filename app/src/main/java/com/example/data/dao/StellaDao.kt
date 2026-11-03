package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

@Dao
interface StellaDao {

    // --- Profile ---
    @Query("SELECT * FROM stella_profile WHERE id = 1 LIMIT 1")
    fun getProfile(): Flow<StellaProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: StellaProfile)

    // --- Glucose ---
    @Query("SELECT * FROM glucose_records ORDER BY dateTimeMillis DESC")
    fun getAllGlucoseRecords(): Flow<List<GlucoseRecord>>

    @Query("SELECT * FROM glucose_records WHERE dateTimeMillis >= :minMillis ORDER BY dateTimeMillis ASC")
    fun getGlucoseRecordsSince(minMillis: Long): Flow<List<GlucoseRecord>>

    @Query("SELECT * FROM glucose_records WHERE dateString = :date ORDER BY dateTimeMillis ASC")
    fun getGlucoseRecordsForDate(date: String): Flow<List<GlucoseRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGlucoseRecord(record: GlucoseRecord): Long

    @Query("DELETE FROM glucose_records WHERE id = :id")
    suspend fun deleteGlucoseRecord(id: Long)

    // --- Medications ---
    @Query("SELECT * FROM medications ORDER BY createdAt DESC")
    fun getAllMedications(): Flow<List<Medication>>

    @Query("SELECT * FROM medications WHERE status = 'ATIVO' ORDER BY name ASC")
    fun getActiveMedications(): Flow<List<Medication>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: Medication): Long

    @Update
    suspend fun updateMedication(medication: Medication)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMedication(id: Long)

    // --- Administrations ---
    @Query("SELECT * FROM medication_administrations ORDER BY timestamp DESC")
    fun getAllAdministrations(): Flow<List<MedicationAdministration>>

    @Query("SELECT * FROM medication_administrations WHERE medicationId = :medicationId ORDER BY timestamp DESC")
    fun getAdministrationsForMedication(medicationId: Long): Flow<List<MedicationAdministration>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdministration(administration: MedicationAdministration): Long

    @Query("DELETE FROM medication_administrations WHERE id = :id")
    suspend fun deleteAdministration(id: Long)

    // --- Appointments ---
    @Query("SELECT * FROM veterinary_appointments ORDER BY timestamp DESC")
    fun getAllAppointments(): Flow<List<VeterinaryAppointment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppointment(appointment: VeterinaryAppointment): Long

    @Update
    suspend fun updateAppointment(appointment: VeterinaryAppointment)

    @Query("DELETE FROM veterinary_appointments WHERE id = :id")
    suspend fun deleteAppointment(id: Long)

    // --- Exam Documents ---
    @Query("SELECT * FROM exam_documents ORDER BY createdAt DESC")
    fun getAllExamDocuments(): Flow<List<ExamDocument>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExamDocument(document: ExamDocument): Long

    @Update
    suspend fun updateExamDocument(document: ExamDocument)

    @Query("DELETE FROM exam_documents WHERE id = :id")
    suspend fun deleteExamDocument(id: Long)

    // --- Attachments ---
    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt ASC, id ASC")
    fun getAttachmentsFor(ownerType: String, ownerId: Long): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments ORDER BY id ASC")
    fun getAllAttachments(): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments ORDER BY id ASC")
    suspend fun getAllAttachmentsSync(): List<Attachment>

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun getAttachmentById(id: Long): Attachment?

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt ASC, id ASC")
    suspend fun getAttachmentsForSync(ownerType: String, ownerId: Long): List<Attachment>

    @Query("SELECT COUNT(*) FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun countAttachmentsFor(ownerType: String, ownerId: Long): Int

    @Query("SELECT COUNT(*) FROM attachments WHERE localPath = :localPath")
    suspend fun countAttachmentsWithPath(localPath: String): Int

    @Query("SELECT COUNT(*) FROM veterinary_appointments WHERE id = :id")
    suspend fun countAppointmentsWithId(id: Long): Int

    @Query("SELECT COUNT(*) FROM exam_documents WHERE id = :id")
    suspend fun countExamDocumentsWithId(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: Attachment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAttachments(attachments: List<Attachment>)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    @Query("DELETE FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteAttachmentsFor(ownerType: String, ownerId: Long)

    @Query("DELETE FROM attachments")
    suspend fun clearAttachments()

    /**
     * Adds as many of [items] as still fit under [maxPerRecord] for that owner and returns the ones that
     * were stored (with their new ids). The count and the inserts happen in ONE transaction, so two
     * taps cannot exceed the limit.
     */
    @androidx.room.Transaction
    suspend fun insertAttachmentsRespectingLimit(
        ownerType: String,
        ownerId: Long,
        items: List<Attachment>,
        maxPerRecord: Int
    ): List<Attachment> {
        val room = (maxPerRecord - countAttachmentsFor(ownerType, ownerId)).coerceAtLeast(0)
        return items.take(room).map { item ->
            val prepared = item.copy(id = 0L, ownerType = ownerType, ownerId = ownerId)
            prepared.copy(id = insertAttachment(prepared))
        }
    }

    /** Deletes the consultation and its attachment rows together (the files are removed by the caller). */
    @androidx.room.Transaction
    suspend fun deleteAppointmentWithAttachments(id: Long) {
        deleteAttachmentsFor(AttachmentOwner.APPOINTMENT, id)
        deleteAppointment(id)
    }

    /** Deletes the exam/document and its attachment rows together (the files are removed by the caller). */
    @androidx.room.Transaction
    suspend fun deleteExamDocumentWithAttachments(id: Long) {
        deleteAttachmentsFor(AttachmentOwner.EXAM_DOCUMENT, id)
        deleteExamDocument(id)
    }

    // --- Feeding ---
    @Query("SELECT * FROM feeding_records ORDER BY isCurrent DESC, createdAt DESC")
    fun getAllFeedingRecords(): Flow<List<FeedingRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedingRecord(record: FeedingRecord): Long

    @Update
    suspend fun updateFeedingRecord(record: FeedingRecord)

    @Query("DELETE FROM feeding_records WHERE id = :id")
    suspend fun deleteFeedingRecord(id: Long)

    // --- Weight ---
    @Query("SELECT * FROM weight_records ORDER BY timestamp DESC")
    fun getAllWeightRecords(): Flow<List<WeightRecord>>

    @Query("SELECT * FROM weight_records ORDER BY timestamp ASC")
    fun getWeightEvolution(): Flow<List<WeightRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeightRecord(record: WeightRecord): Long

    @Query("DELETE FROM weight_records WHERE id = :id")
    suspend fun deleteWeightRecord(id: Long)

    // --- Vaccines & Prevention ---
    @Query("SELECT * FROM vaccine_prevention_records ORDER BY createdAt DESC")
    fun getAllVaccines(): Flow<List<VaccinePreventionRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVaccine(record: VaccinePreventionRecord): Long

    @Update
    suspend fun updateVaccine(record: VaccinePreventionRecord)

    @Query("DELETE FROM vaccine_prevention_records WHERE id = :id")
    suspend fun deleteVaccine(id: Long)

    // --- Reminders ---
    @Query("SELECT * FROM reminders ORDER BY timestamp ASC")
    fun getAllReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE dateString = :date ORDER BY timestamp ASC")
    fun getRemindersForDate(date: String): Flow<List<Reminder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: Reminder): Long

    @Update
    suspend fun updateReminder(reminder: Reminder)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    // --- Synchronous Getters for Atomic Backup ---
    @Query("SELECT * FROM stella_profile WHERE id = 1 LIMIT 1")
    suspend fun getProfileSync(): StellaProfile?

    @Query("SELECT * FROM glucose_records ORDER BY dateTimeMillis DESC")
    suspend fun getAllGlucoseRecordsSync(): List<GlucoseRecord>

    @Query("SELECT * FROM medications ORDER BY createdAt DESC")
    suspend fun getAllMedicationsSync(): List<Medication>

    @Query("SELECT * FROM medication_administrations ORDER BY timestamp DESC")
    suspend fun getAllAdministrationsSync(): List<MedicationAdministration>

    @Query("SELECT * FROM veterinary_appointments ORDER BY timestamp DESC")
    suspend fun getAllAppointmentsSync(): List<VeterinaryAppointment>

    @Query("SELECT * FROM exam_documents ORDER BY createdAt DESC")
    suspend fun getAllExamDocumentsSync(): List<ExamDocument>

    @Query("SELECT * FROM feeding_records ORDER BY isCurrent DESC, createdAt DESC")
    suspend fun getAllFeedingRecordsSync(): List<FeedingRecord>

    @Query("SELECT * FROM weight_records ORDER BY timestamp DESC")
    suspend fun getAllWeightRecordsSync(): List<WeightRecord>

    @Query("SELECT * FROM vaccine_prevention_records ORDER BY createdAt DESC")
    suspend fun getAllVaccinesSync(): List<VaccinePreventionRecord>

    @Query("SELECT * FROM reminders ORDER BY timestamp ASC")
    suspend fun getAllRemindersSync(): List<Reminder>

    @androidx.room.Transaction
    suspend fun getBackupSnapshot(): BackupSnapshot {
        return BackupSnapshot(
            profile = getProfileSync() ?: StellaProfile(),
            glucoseList = getAllGlucoseRecordsSync(),
            medications = getAllMedicationsSync(),
            administrations = getAllAdministrationsSync(),
            appointments = getAllAppointmentsSync(),
            documents = getAllExamDocumentsSync(),
            feeding = getAllFeedingRecordsSync(),
            weights = getAllWeightRecordsSync(),
            vaccines = getAllVaccinesSync(),
            reminders = getAllRemindersSync(),
            attachments = getAllAttachmentsSync()
        )
    }

    // --- Bulk Insert for Restoration ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllGlucose(records: List<GlucoseRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMedications(records: List<Medication>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAdministrations(records: List<MedicationAdministration>)

    /** Returns the row id of each record, in the same order as [records]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAppointments(records: List<VeterinaryAppointment>): List<Long>

    /** Returns the row id of each record, in the same order as [records]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllExamDocuments(records: List<ExamDocument>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllFeeding(records: List<FeedingRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllWeights(records: List<WeightRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllVaccines(records: List<VaccinePreventionRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReminders(records: List<Reminder>)

    // --- Clear Methods for Full Replacement Restore ---
    @Query("DELETE FROM glucose_records")
    suspend fun clearGlucose()

    @Query("DELETE FROM medications")
    suspend fun clearMedications()

    @Query("DELETE FROM medication_administrations")
    suspend fun clearAdministrations()

    @Query("DELETE FROM veterinary_appointments")
    suspend fun clearAppointments()

    @Query("DELETE FROM exam_documents")
    suspend fun clearExamDocuments()

    @Query("DELETE FROM feeding_records")
    suspend fun clearFeeding()

    @Query("DELETE FROM weight_records")
    suspend fun clearWeights()

    @Query("DELETE FROM vaccine_prevention_records")
    suspend fun clearVaccines()

    @Query("DELETE FROM reminders")
    suspend fun clearReminders()

    /**
     * Empties every table in ONE transaction (all or nothing) and puts the default profile back, so the
     * app starts again as on a first run. The profile row is replaced, not removed.
     */
    @androidx.room.Transaction
    suspend fun clearAllUserData() {
        clearAttachments()
        clearAdministrations()
        clearMedications()
        clearGlucose()
        clearAppointments()
        clearExamDocuments()
        clearFeeding()
        clearWeights()
        clearVaccines()
        clearReminders()
        saveProfile(StellaProfile())
    }

    // --- Atomic Transaction for Restoration ---
    @androidx.room.Transaction
    suspend fun executeRestoreTransaction(
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
        attachments: List<Attachment>,
        replaceExisting: Boolean
    ): RestoreResult {
        if (replaceExisting) {
            clearGlucose()
            clearMedications()
            clearAdministrations()
            clearAppointments()
            clearExamDocuments()
            clearFeeding()
            clearWeights()
            clearVaccines()
            clearReminders()
            // Owner ids come back from the backup; leftover rows would attach to the wrong records.
            clearAttachments()

            saveProfile(profile)
            if (glucoseList.isNotEmpty()) insertAllGlucose(glucoseList)
            if (medications.isNotEmpty()) insertAllMedications(medications)

            val validMedIds = medications.map { it.id }.toSet()
            val validAdmins = mutableListOf<MedicationAdministration>()
            val skippedAdmins = mutableListOf<MedicationAdministration>()

            administrations.forEach { admin ->
                if (validMedIds.contains(admin.medicationId)) {
                    validAdmins.add(admin)
                } else {
                    skippedAdmins.add(admin)
                }
            }

            if (validAdmins.isNotEmpty()) insertAllAdministrations(validAdmins)
            if (appointments.isNotEmpty()) insertAllAppointments(appointments)
            if (documents.isNotEmpty()) insertAllExamDocuments(documents)
            if (feeding.isNotEmpty()) insertAllFeeding(feeding)
            if (weights.isNotEmpty()) insertAllWeights(weights)
            if (vaccines.isNotEmpty()) insertAllVaccines(vaccines)
            if (reminders.isNotEmpty()) insertAllReminders(reminders)

            // Records keep their ids here, so an attachment keeps pointing at the same record. The
            // attachment row ids are not referenced anywhere: they are generated again.
            val appointmentIds = appointments.map { it.id }.filter { it > 0L }.toSet()
            val documentIds = documents.map { it.id }.filter { it > 0L }.toSet()
            val validAttachments = mutableListOf<Attachment>()
            val skippedAttachments = mutableListOf<Attachment>()
            attachments.forEach { att ->
                val ownerExists = when (att.ownerType) {
                    AttachmentOwner.APPOINTMENT -> att.ownerId in appointmentIds
                    AttachmentOwner.EXAM_DOCUMENT -> att.ownerId in documentIds
                    else -> false
                }
                if (ownerExists) validAttachments.add(att.copy(id = 0L)) else skippedAttachments.add(att)
            }
            if (validAttachments.isNotEmpty()) insertAllAttachments(validAttachments)

            return RestoreResult(
                restoredAdministrations = validAdmins.size,
                skippedAdministrations = skippedAdmins,
                restoredAttachments = validAttachments.size,
                skippedAttachments = skippedAttachments
            )
        } else {
            // Incremental restore: preserve existing records, insert new entities with new IDs,
            // and remap foreign keys (especially MedicationAdministration.medicationId)
            saveProfile(profile)

            val oldToNewMedicationIdMap = mutableMapOf<Long, Long>()
            medications.forEach { med ->
                val oldId = med.id
                val newId = insertMedication(med.copy(id = 0L))
                if (oldId > 0L) {
                    oldToNewMedicationIdMap[oldId] = newId
                }
            }

            val validAdmins = mutableListOf<MedicationAdministration>()
            val skippedAdmins = mutableListOf<MedicationAdministration>()

            administrations.forEach { admin ->
                val newMedId = oldToNewMedicationIdMap[admin.medicationId]
                if (newMedId != null) {
                    validAdmins.add(admin.copy(id = 0L, medicationId = newMedId))
                } else {
                    skippedAdmins.add(admin)
                }
            }

            if (validAdmins.isNotEmpty()) {
                insertAllAdministrations(validAdmins)
            }

            if (glucoseList.isNotEmpty()) insertAllGlucose(glucoseList.map { it.copy(id = 0L) })

            // Appointments and documents get new ids, so their attachments are re-pointed at the new ids.
            val oldToNewAppointmentIds = mutableMapOf<Long, Long>()
            if (appointments.isNotEmpty()) {
                val newIds = insertAllAppointments(appointments.map { it.copy(id = 0L) })
                appointments.forEachIndexed { index, apt ->
                    if (apt.id > 0L) oldToNewAppointmentIds[apt.id] = newIds[index]
                }
            }
            val oldToNewDocumentIds = mutableMapOf<Long, Long>()
            if (documents.isNotEmpty()) {
                val newIds = insertAllExamDocuments(documents.map { it.copy(id = 0L) })
                documents.forEachIndexed { index, doc ->
                    if (doc.id > 0L) oldToNewDocumentIds[doc.id] = newIds[index]
                }
            }
            if (feeding.isNotEmpty()) insertAllFeeding(feeding.map { it.copy(id = 0L) })
            if (weights.isNotEmpty()) insertAllWeights(weights.map { it.copy(id = 0L) })
            if (vaccines.isNotEmpty()) insertAllVaccines(vaccines.map { it.copy(id = 0L) })
            if (reminders.isNotEmpty()) insertAllReminders(reminders.map { it.copy(id = 0L) })

            val validAttachments = mutableListOf<Attachment>()
            val skippedAttachments = mutableListOf<Attachment>()
            attachments.forEach { att ->
                val newOwnerId = when (att.ownerType) {
                    AttachmentOwner.APPOINTMENT -> oldToNewAppointmentIds[att.ownerId]
                    AttachmentOwner.EXAM_DOCUMENT -> oldToNewDocumentIds[att.ownerId]
                    else -> null
                }
                if (newOwnerId != null) {
                    validAttachments.add(att.copy(id = 0L, ownerId = newOwnerId))
                } else {
                    skippedAttachments.add(att)
                }
            }
            if (validAttachments.isNotEmpty()) insertAllAttachments(validAttachments)

            return RestoreResult(
                restoredAdministrations = validAdmins.size,
                skippedAdministrations = skippedAdmins,
                restoredAttachments = validAttachments.size,
                skippedAttachments = skippedAttachments
            )
        }
    }
}
