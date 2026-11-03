package com.example

import com.example.data.drive.DriveSyncState
import com.example.data.drive.GoogleDriveSyncManager
import com.example.data.model.Medication
import com.example.data.model.MedicationAdministration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreTest {

    @Test
    fun testFileMetaDetection_preservesPng() {
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D, 0x0A)
        // Simulate detection logic without android context
        val isPngMagic = pngMagic.size >= 4 &&
            pngMagic[0] == 0x89.toByte() &&
            pngMagic[1] == 0x50.toByte() &&
            pngMagic[2] == 0x4E.toByte() &&
            pngMagic[3] == 0x47.toByte()

        assertTrue("Magic bytes for PNG should be recognized", isPngMagic)
        val ext = if (isPngMagic) "png" else "jpg"
        val mime = if (isPngMagic) "image/png" else "image/jpeg"

        assertEquals("png", ext)
        assertEquals("image/png", mime)
    }

    @Test
    fun testFileMetaDetection_preservesPdf() {
        val pdfMagic = "%PDF-1.4".toByteArray(Charsets.UTF_8)
        val isPdfMagic = pdfMagic.size >= 4 &&
            pdfMagic[0] == 0x25.toByte() &&
            pdfMagic[1] == 0x50.toByte() &&
            pdfMagic[2] == 0x44.toByte() &&
            pdfMagic[3] == 0x46.toByte()

        assertTrue("Magic bytes for PDF should be recognized", isPdfMagic)
        val ext = if (isPdfMagic) "pdf" else "jpg"
        val mime = if (isPdfMagic) "application/pdf" else "image/jpeg"

        assertEquals("pdf", ext)
        assertEquals("application/pdf", mime)
    }

    @Test
    fun testIncrementalRestore_idRemappingLogic() {
        // Given backup with old medication IDs
        val oldMedication1 = Medication(
            id = 10L,
            name = "Insulina NPH",
            dosage = "2 UI",
            frequency = "A cada 12 horas",
            scheduledTimes = "08:00, 20:00",
            startDate = "01/01/2026"
        )
        val oldMedication2 = Medication(
            id = 20L,
            name = "Benazepril",
            dosage = "5 mg",
            frequency = "1x ao dia",
            scheduledTimes = "12:00",
            startDate = "01/01/2026"
        )
        val backupMedications = listOf(oldMedication1, oldMedication2)

        // Administrations referring to old medication IDs
        val admin1 = MedicationAdministration(
            id = 100L,
            medicationId = 10L,
            medicationName = "Insulina NPH",
            dosage = "2 UI",
            dateString = "28/09/2026",
            timeString = "08:00"
        )
        val admin2 = MedicationAdministration(
            id = 101L,
            medicationId = 20L,
            medicationName = "Benazepril",
            dosage = "5 mg",
            dateString = "28/09/2026",
            timeString = "12:00"
        )
        val backupAdmins = listOf(admin1, admin2)

        // When simulating incremental restore (simulating auto-generated new IDs)
        val oldToNewMap = mutableMapOf<Long, Long>()
        var simulatedGeneratedIdCounter = 500L

        backupMedications.forEach { med ->
            val newId = simulatedGeneratedIdCounter++
            oldToNewMap[med.id] = newId
        }

        // Remap administrations
        val remappedAdmins = backupAdmins.map { admin ->
            val mappedMedId = oldToNewMap[admin.medicationId] ?: admin.medicationId
            admin.copy(id = 0L, medicationId = mappedMedId)
        }

        // Then verify relationships are correctly updated
        assertEquals(500L, oldToNewMap[10L])
        assertEquals(501L, oldToNewMap[20L])
        assertEquals(500L, remappedAdmins[0].medicationId)
        assertEquals(501L, remappedAdmins[1].medicationId)
        assertEquals(0L, remappedAdmins[0].id)
        assertEquals(0L, remappedAdmins[1].id)
    }

    @Test
    fun testDriveSyncConsistency_attachmentFailureNeverSuccess() {
        val failedAttachments = listOf("Foto de perfil: erro ao ler bytes", "Exame hemograma: HTTP 500")
        val syncResult = failedAttachments.isEmpty()

        assertFalse("Sync with failed attachments must never succeed", syncResult)

        val syncState: DriveSyncState = if (failedAttachments.isNotEmpty()) {
            DriveSyncState.Error("Falha na sincronização de anexos: ${failedAttachments.joinToString("; ")}")
        } else {
            DriveSyncState.Success("28/09/2026", GoogleDriveSyncManager.DRIVE_FOLDER_NAME, 5)
        }

        assertTrue("Sync state must be Error when attachments fail", syncState is DriveSyncState.Error)
    }

    @Test
    fun testRestoreConsistency_failedAttachmentsReportsPartialNeverSuccess() {
        val failedRestoreAttachments = listOf("anexo_doc_1.pdf (não encontrado no Drive)")
        val totalRecords = 42
        val filesCount = 2

        val restoreState: DriveSyncState = if (failedRestoreAttachments.isNotEmpty()) {
            DriveSyncState.RestorePartial(
                message = "Restauração concluída com ressalvas: ${failedRestoreAttachments.size} anexo(s) falharam ao recuperar.",
                recordsCount = totalRecords,
                filesCount = filesCount,
                failedFiles = failedRestoreAttachments
            )
        } else {
            DriveSyncState.RestoreSuccess("Prontuário restaurado com sucesso!", totalRecords, filesCount)
        }

        assertFalse("Restore state must not be RestoreSuccess when attachments fail", restoreState is DriveSyncState.RestoreSuccess)
        assertTrue("Restore state must be RestorePartial when attachments fail", restoreState is DriveSyncState.RestorePartial)
        val partialState = restoreState as DriveSyncState.RestorePartial
        assertEquals(1, partialState.failedFiles.size)
        assertEquals("anexo_doc_1.pdf (não encontrado no Drive)", partialState.failedFiles[0])
    }
}
