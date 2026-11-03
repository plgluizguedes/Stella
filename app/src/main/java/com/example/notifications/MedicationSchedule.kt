package com.example.notifications

import com.example.data.model.Medication
import com.example.util.DateTimeUtils
import java.util.Calendar
import java.util.TimeZone

/**
 * Regras (sem Android) que transformam os horários digitados de um medicamento em alarmes diários.
 * Ficam separadas do AlarmManager para poderem ser testadas na JVM.
 */
object MedicationSchedule {

    /** Máximo de horários por dia por medicamento; o que passar disso é ignorado. */
    const val MAX_TIMES_PER_DAY = 12

    private val TIME_TOKEN = Regex("^(\\d{1,2})(?::|h|H)(\\d{1,2})?$")

    /**
     * Lê textos como "08:00, 20:00", "8h; 20h30" ou "08:00 20:00" e devolve os horários como minutos
     * desde a meia-noite, sem repetição e em ordem. Trechos que não são horário (como "Livre") são
     * ignorados: o remédio fica sem alarme em vez de receber um horário inventado.
     */
    fun parseTimes(raw: String): List<Int> {
        val minutes = sortedSetOf<Int>()
        for (token in raw.split(Regex("[,;\\s]+"))) {
            val match = TIME_TOKEN.matchEntire(token.trim()) ?: continue
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour in 0..23 && minute in 0..59) minutes.add(hour * 60 + minute)
        }
        return minutes.take(MAX_TIMES_PER_DAY)
    }

    /** Início do dia de [startDate] (DD/MM/AAAA) ou null se em branco/inválida. */
    fun startOfPeriod(medication: Medication): Long? =
        DateTimeUtils.parseDateTimeToMillis(medication.startDate, "00:00")?.takeIf { medication.startDate.isNotBlank() }

    /** Último instante do dia de [endDate] (DD/MM/AAAA) ou null se em branco/inválida. */
    fun endOfPeriod(medication: Medication): Long? =
        DateTimeUtils.parseDateTimeToMillis(medication.endDate, "23:59")?.takeIf { medication.endDate.isNotBlank() }

    /**
     * Próximo disparo de um horário diário depois de [afterMillis], respeitando o início e o fim do
     * tratamento. Devolve null quando o tratamento já terminou.
     */
    fun nextTrigger(
        minuteOfDay: Int,
        afterMillis: Long,
        startMillis: Long? = null,
        endMillis: Long? = null,
        zone: TimeZone = TimeZone.getDefault()
    ): Long? {
        val from = if (startMillis != null && startMillis - 1 > afterMillis) startMillis - 1 else afterMillis
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= from) calendar.add(Calendar.DAY_OF_MONTH, 1)
        val trigger = calendar.timeInMillis
        if (endMillis != null && trigger > endMillis) return null
        return trigger
    }

    /** Um medicamento só tem alarmes enquanto estiver em uso. */
    fun isActive(medication: Medication): Boolean = medication.status == "ATIVO"
}
