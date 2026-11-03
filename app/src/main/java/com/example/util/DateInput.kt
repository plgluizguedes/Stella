package com.example.util

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Entende datas e horários digitados de forma curta e devolve o formato do app
 * (DD/MM/AAAA e HH:MM). Se não entender, devolve o texto como foi digitado, para que a
 * validação de sempre (DateTimeUtils) continue dizendo que está inválido.
 */
object DateInput {

    private val NON_DIGITS = Regex("[^0-9]+")

    /**
     * "11/10" -> 11/10/<ano atual>; "11/10/26" -> 11/10/2026; "1110" -> 11/10/<ano atual>;
     * "111026" -> 11/10/2026; "11102026" -> 11/10/2026; "5-3-26" -> 05/03/2026.
     * Ano de 2 dígitos: até 10 anos à frente do ano atual é 20xx, acima disso é 19xx
     * (assim "99" numa data de nascimento vira 1999, e "30" vira 2030).
     * Texto em branco volta como está (campos opcionais).
     */
    fun normalizeDate(raw: String, today: Calendar = Calendar.getInstance()): String {
        val text = raw.trim()
        if (text.isEmpty()) return raw
        val hasSeparator = text.any { !it.isDigit() }

        val day: String
        val month: String
        val year: String?
        if (hasSeparator) {
            val parts = text.split(NON_DIGITS).filter { it.isNotEmpty() }
            if (parts.size !in 2..3) return raw
            day = parts[0]; month = parts[1]; year = parts.getOrNull(2)
        } else {
            when (text.length) {
                4 -> { day = text.substring(0, 2); month = text.substring(2, 4); year = null }
                6 -> { day = text.substring(0, 2); month = text.substring(2, 4); year = text.substring(4, 6) }
                8 -> { day = text.substring(0, 2); month = text.substring(2, 4); year = text.substring(4, 8) }
                else -> return raw // 3, 5 ou 7 dígitos seriam ambíguos
            }
        }
        if (day.length !in 1..2 || month.length !in 1..2) return raw

        val currentYear = today.get(Calendar.YEAR)
        val fullYear = when {
            year == null -> currentYear
            year.length == 4 -> year.toInt()
            year.length in 1..2 -> {
                val yy = year.toInt()
                val pivot = (currentYear % 100) + 10
                if (yy <= pivot) 2000 + yy else 1900 + yy
            }
            else -> return raw
        }
        val d = day.toInt()
        val m = month.toInt()
        if (!isRealDate(d, m, fullYear)) return raw
        return "%02d/%02d/%04d".format(d, m, fullYear)
    }

    /** "9" -> 09:00; "930" -> 09:30; "0930" -> 09:30; "9:5" -> 09:05; "9h30" -> 09:30. */
    fun normalizeTime(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return raw
        val hour: String
        val minute: String
        if (text.any { !it.isDigit() }) {
            val parts = text.split(NON_DIGITS).filter { it.isNotEmpty() }
            if (parts.size !in 1..2) return raw
            hour = parts[0]; minute = parts.getOrNull(1) ?: "0"
        } else {
            when (text.length) {
                1, 2 -> { hour = text; minute = "0" }
                3 -> { hour = text.substring(0, 1); minute = text.substring(1, 3) }
                4 -> { hour = text.substring(0, 2); minute = text.substring(2, 4) }
                else -> return raw
            }
        }
        if (hour.length !in 1..2 || minute.length !in 1..2) return raw
        val h = hour.toInt()
        val min = minute.toInt()
        if (h > 23 || min > 59) return raw
        return "%02d:%02d".format(h, min)
    }

    private fun isRealDate(day: Int, month: Int, year: Int): Boolean {
        if (year < 1000 || month !in 1..12 || day < 1) return false
        val cal = GregorianCalendar(year, month - 1, 1)
        return day <= cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
}
