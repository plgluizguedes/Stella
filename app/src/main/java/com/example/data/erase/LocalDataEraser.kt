package com.example.data.erase

import android.util.Log
import com.example.data.attachments.AttachmentStorage
import com.example.data.repository.StellaRepository
import com.example.notifications.MedicationAlarmScheduler
import com.example.notifications.ReminderNotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of erasing everything the app keeps on this device. */
sealed class LocalEraseResult {
    /**
     * The records and the files are gone. [warnings] are things that did not touch the data (for
     * example an alarm that could not be cancelled), written for the user in Portuguese.
     */
    data class Erased(val warnings: List<String> = emptyList()) : LocalEraseResult()

    /** Something could NOT be deleted: data may still be on this device. Calling it again is safe. */
    data class Failed(val message: String) : LocalEraseResult()
}

/**
 * "Apagar tudo neste aparelho": every record (all tables, attachments included), the files in the app's
 * private attachment folder (photos, exams, documents) and the alarms of the reminders. The profile goes
 * back to the default one, as on a first run.
 *
 * It only touches this device. The Drive account and the backup on Drive are left alone: the caller
 * decides what happens to them (see the ViewModel). The order matters so that a failed run can be repeated:
 * alarms are cancelled first (they are found through the reminders), then the database, then the files.
 */
class LocalDataEraser(
    private val repository: StellaRepository,
    private val storage: AttachmentStorage,
    private val reminders: ReminderNotificationManager,
    // Replaceable only so tests can make a step fail; the app always uses the defaults.
    private val clearDatabase: suspend () -> Unit = { repository.clearAllUserData() },
    private val deleteFiles: () -> Boolean = { storage.deleteAllFiles() },
    private val medicationAlarms: MedicationAlarmScheduler? = null
) {

    suspend fun eraseAll(): LocalEraseResult = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()

        // 1. Alarms and notifications still on screen. Without the reminders there is nothing to find them
        //    by, so this has to come before the database is emptied.
        try {
            var notCancelled = 0
            for (reminder in repository.getAllRemindersSync()) {
                try {
                    reminders.cancelNotification(reminder.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Alarme do lembrete #${reminder.id} não pôde ser cancelado: ${e.message}")
                    notCancelled++
                }
            }
            // Alarmes diários dos remédios: também são achados pelos cadastros, que ainda existem aqui.
            try {
                repository.getAllMedicationsSync().forEach { medicationAlarms?.cancelFor(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Alarmes de medicamentos não puderam ser cancelados: ${e.message}")
                notCancelled++
            }
            if (notCancelled > 0) {
                warnings.add("$notCancelled alarme(s) de lembrete não puderam ser cancelados e podem tocar uma vez.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível ler os lembretes para cancelar os alarmes: ${e.message}")
            warnings.add("Os alarmes de lembrete não puderam ser conferidos e podem tocar uma vez.")
        }

        // 2. The records, in one transaction: all of them or none.
        try {
            clearDatabase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao apagar o banco de dados", e)
            return@withContext LocalEraseResult.Failed(
                "Não foi possível apagar os registros deste aparelho; nada foi apagado do banco. " +
                    "(${e.localizedMessage ?: e.message ?: "erro desconhecido"})"
            )
        }

        // 3. The files.
        val filesGone = try {
            deleteFiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao apagar os arquivos do aparelho", e)
            false
        }
        if (!filesGone) {
            return@withContext LocalEraseResult.Failed(
                "Os registros foram apagados, mas alguns arquivos (fotos, exames ou anexos) não puderam ser " +
                    "apagados deste aparelho. Tente de novo."
            )
        }

        LocalEraseResult.Erased(warnings)
    }

    private companion object {
        const val TAG = "LocalDataEraser"
    }
}
