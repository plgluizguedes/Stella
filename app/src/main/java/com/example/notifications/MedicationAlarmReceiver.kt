package com.example.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Recebe o alarme diário de um medicamento, avisa o tutor e agenda o próximo dia. */
class MedicationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val medicationId = intent.getLongExtra(EXTRA_MEDICATION_ID, -1L)
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        if (medicationId < 0 || slot < 0) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = MedicationAlarmScheduler(appContext)
                val medication = AppDatabase.getDatabase(appContext).stellaDao()
                    .getAllMedicationsSync()
                    .firstOrNull { it.id == medicationId }

                // Remédio apagado ou encerrado: não toca e não agenda mais.
                if (medication == null || !MedicationSchedule.isActive(medication)) {
                    scheduler.cancelFor(medicationId)
                    return@launch
                }

                val now = System.currentTimeMillis()
                val end = MedicationSchedule.endOfPeriod(medication)
                if (end != null && now > end) {
                    scheduler.cancelFor(medicationId)
                    return@launch
                }

                showNotification(appContext, medicationId, slot, medication.name, medication.dosage, medication.unit)

                // Reagenda todos os horários; o de agora passa para amanhã.
                scheduler.scheduleFor(medication, now + RESCHEDULE_MARGIN_MILLIS)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao tratar o alarme do medicamento #$medicationId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(
        context: Context,
        medicationId: Long,
        slot: Int,
        name: String,
        dosage: String,
        unit: String
    ) {
        ReminderNotificationManager(context) // garante que o canal de notificação existe

        val code = MedicationAlarmScheduler.requestCode(medicationId, slot)
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            code,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dose = listOf(dosage, unit).filter { it.isNotBlank() }.joinToString(" ")
        val notification = NotificationCompat.Builder(context, ReminderNotificationManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Hora do remédio: $name")
            .setContentText(
                if (dose.isBlank()) "Registre no app depois de administrar."
                else "Dose: $dose. Registre no app depois de administrar."
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(code, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permissão de notificação ausente: o aviso do medicamento não foi exibido")
        }
    }

    companion object {
        const val EXTRA_MEDICATION_ID = "extra_medication_id"
        const val EXTRA_SLOT = "extra_slot"
        private const val TAG = "MedicationAlarmReceiver"
        private const val RESCHEDULE_MARGIN_MILLIS = 30_000L
    }
}
