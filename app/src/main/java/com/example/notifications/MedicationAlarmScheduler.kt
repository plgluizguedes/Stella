package com.example.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.model.Medication

/**
 * Agenda os alarmes diários dos medicamentos a partir dos horários cadastrados.
 *
 * Cada horário de cada medicamento tem um alarme próprio. Quando um alarme dispara, o
 * [MedicationAlarmReceiver] mostra o aviso e agenda o mesmo horário para o dia seguinte, então a
 * repetição acontece enquanto o medicamento estiver ATIVO e dentro do período de uso.
 *
 * O aviso é só um lembrete: ele NUNCA registra a dose como dada. Quem registra é o tutor, no app.
 */
class MedicationAlarmScheduler(private val context: Context) {

    /** Agenda (ou reagenda) todos os horários do medicamento. Sem horários válidos, fica sem alarme. */
    fun scheduleFor(medication: Medication, nowMillis: Long = System.currentTimeMillis()) {
        cancelFor(medication.id)
        if (!MedicationSchedule.isActive(medication)) return

        val times = MedicationSchedule.parseTimes(medication.scheduledTimes)
        val start = MedicationSchedule.startOfPeriod(medication)
        val end = MedicationSchedule.endOfPeriod(medication)
        times.forEachIndexed { slot, minuteOfDay ->
            val trigger = MedicationSchedule.nextTrigger(minuteOfDay, nowMillis, start, end) ?: return@forEachIndexed
            setAlarm(medication.id, slot, trigger)
        }
    }

    fun scheduleAll(medications: List<Medication>, nowMillis: Long = System.currentTimeMillis()) {
        medications.forEach { scheduleFor(it, nowMillis) }
    }

    /** Cancela todos os alarmes do medicamento (inclusive de horários que já foram removidos). */
    fun cancelFor(medicationId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (slot in 0 until MedicationSchedule.MAX_TIMES_PER_DAY) {
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(medicationId, slot),
                Intent(context, MedicationAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private fun setAlarm(medicationId: Long, slot: Int, triggerMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, MedicationAlarmReceiver::class.java).apply {
            putExtra(MedicationAlarmReceiver.EXTRA_MEDICATION_ID, medicationId)
            putExtra(MedicationAlarmReceiver.EXTRA_SLOT, slot)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(medicationId, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ->
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
                canBeExact ->
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
                else ->
                    // Sem a permissão de alarme exato o Android pode atrasar o aviso.
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível agendar o alarme do medicamento #$medicationId (horário $slot)", e)
        }
    }

    companion object {
        private const val TAG = "MedicationAlarms"

        // Faixa própria de códigos, para nunca colidir com os alarmes dos lembretes (que usam o id do lembrete).
        private const val REQUEST_CODE_BASE = 0x40000000

        fun requestCode(medicationId: Long, slot: Int): Int =
            REQUEST_CODE_BASE + (medicationId.toInt() and 0x00FFFFFF) * 16 + slot
    }
}
