package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "stella_profile")
data class StellaProfile(
    @PrimaryKey val id: Int = 1,
    val name: String = "Meu pet",
    val photoUri: String? = null,
    val species: String = "Cachorro",
    val breed: String = "SRD (Sem Raça Definida)",
    val gender: String = "Não informado",
    val birthDate: String = "",
    val currentWeight: Double = 0.0,
    val color: String = "",
    val microchip: String = "",
    val vetRegistrationNumber: String = "",
    val vetName: String = "",
    val clinicName: String = "",
    val clinicPhone: String = "",
    val notes: String = "",
    val curiosities: String = "",
    val personality: String = ""
)

@Entity(tableName = "glucose_records")
data class GlucoseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateTimeMillis: Long = System.currentTimeMillis(),
    val dateString: String,
    val timeString: String,
    val value: Double,
    val unit: String = "mg/dL",
    val mealRelation: String, // Antes da alimentação, Depois da alimentação, Em jejum, Não relacionado
    val medicationRelation: String = "", // Antes da medicação, Após medicação, Sem relação
    val notes: String = "",
    val symptoms: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val activeIngredient: String = "",
    val dosage: String,
    val unit: String = "comprimido",
    val administrationRoute: String = "Oral",
    val quantity: String = "1",
    val frequency: String, // A cada 12 horas, 1x ao dia, etc.
    val scheduledTimes: String, // Ex: "08:00, 20:00"
    val startDate: String,
    val endDate: String = "",
    val vetName: String = "",
    val reason: String = "",
    val notes: String = "",
    val isVitaminOrSupplement: Boolean = false, // false = Medicamento, true = Vitamina / Suplemento
    val status: String = "ATIVO", // ATIVO, ENCERRADO
    val suspensionReason: String = "",
    val suspensionDate: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "medication_administrations")
data class MedicationAdministration(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val medicationName: String,
    val dosage: String,
    val timestamp: Long = System.currentTimeMillis(),
    val dateString: String,
    val timeString: String,
    val registeredBy: String = "Tutor",
    val notes: String = ""
)

@Entity(tableName = "veterinary_appointments")
data class VeterinaryAppointment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateString: String,
    val timeString: String,
    val timestamp: Long = System.currentTimeMillis(),
    val vetName: String,
    val clinicName: String,
    val reason: String,
    val weightKg: Double? = null,
    val temperatureC: Double? = null,
    val notes: String = "",
    val symptomsReported: String = "",
    val conductRecommended: String = "",
    val medicationsPrescribed: String = "",
    val nextAppointmentDate: String = "",
    val attachedFileCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "exam_documents")
data class ExamDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fileType: String = "Documento", // PDF, JPG, PNG, Documento
    val dateString: String,
    val clinicName: String = "",
    val vetName: String = "",
    val category: String, // Exame de sangue, Glicemia, Ultrassom, Tomografia, PCR, Receita, Consulta, Vacina, Documento, Outros
    val notes: String = "",
    val fileUriOrPath: String? = null,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "feeding_records")
data class FeedingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String,
    val foodName: String,
    val type: String = "Seca", // Seca, Úmida, Natural, Medicamentosa
    val line: String = "Super Premium",
    val flavor: String = "",
    val dailyAmount: String = "", // Ex: 180g
    val portionAmount: String = "", // Ex: 90g
    val mealsPerDay: Int = 2,
    val scheduleTimes: String = "08:00, 19:30",
    val startDate: String,
    val endDate: String = "",
    val isCurrent: Boolean = true,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "weight_records")
data class WeightRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val dateString: String,
    val weightKg: Double,
    val notes: String = ""
)

@Entity(tableName = "vaccine_prevention_records")
data class VaccinePreventionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String = "Vacina", // Vacina, Vermífugo, Antiparasitário, Outro
    val dateAdministered: String,
    val dose: String = "Dose Única",
    val nextDueDate: String = "",
    val vetName: String = "",
    val clinicName: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String, // Medicamento, Vitamina, Consulta, Exame, Vacina, Alimentação, Outro
    val dateString: String,
    val timeString: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDENTE", // PENDENTE, REALIZADO, ATRASADO
    val executionTimestamp: Long? = null,
    val notes: String = ""
)

/** Kinds of record a file can be attached to. Stored as text in [Attachment.ownerType]. */
object AttachmentOwner {
    const val APPOINTMENT = "APPOINTMENT"
    const val EXAM_DOCUMENT = "EXAM_DOCUMENT"
    val ALL = setOf(APPOINTMENT, EXAM_DOCUMENT)
}

/**
 * One file (PDF or image) attached to a consultation or to an exam/document. A record can have several.
 *
 * The file lives in the app's private storage ([localPath], named after its SHA-256 so the same content is
 * stored once). There is no foreign key because the owner can be of two different kinds; the rows of an
 * owner are removed together with it by the DAO.
 */
@Entity(
    tableName = "attachments",
    indices = [Index(value = ["ownerType", "ownerId"])]
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: String,
    val ownerId: Long,
    val label: String = "Outro", // Receituário, Orientações, Exame, Laudo, Vacina, Outro
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val localPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class BackupSnapshot(
    val profile: StellaProfile,
    val glucoseList: List<GlucoseRecord>,
    val medications: List<Medication>,
    val administrations: List<MedicationAdministration>,
    val appointments: List<VeterinaryAppointment>,
    val documents: List<ExamDocument>,
    val feeding: List<FeedingRecord>,
    val weights: List<WeightRecord>,
    val vaccines: List<VaccinePreventionRecord>,
    val reminders: List<Reminder>,
    val attachments: List<Attachment> = emptyList()
) {
    /** Number of records the user entered. The profile itself is not counted. */
    fun recordCount(): Int =
        glucoseList.size + medications.size + administrations.size + appointments.size +
            documents.size + feeding.size + weights.size + vaccines.size + reminders.size

    /** True when the device holds nothing the user entered: no records and an untouched default profile. */
    fun hasNoUserData(): Boolean = recordCount() == 0 && profile == StellaProfile()
}

data class RestoreResult(
    val restoredAdministrations: Int = 0,
    val skippedAdministrations: List<MedicationAdministration> = emptyList(),
    val restoredAttachments: Int = 0,
    /** Attachments whose consultation or exam is not in the backup: they are not restored. */
    val skippedAttachments: List<Attachment> = emptyList()
)
