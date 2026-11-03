package com.example.util

import java.text.SimpleDateFormat
import java.util.Locale

object DateTimeUtils {

    /** Anos aceitos nas datas digitadas: barra erros de digitação como 2626 ou 1026. */
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2100

    /** Confere o ano de um texto DD/MM/AAAA já validado pelo formato. */
    private fun hasAcceptableYear(dateStr: String): Boolean {
        val year = dateStr.trim().substringAfterLast('/').toIntOrNull() ?: return false
        return year in MIN_YEAR..MAX_YEAR
    }

    /**
     * Parses a date (DD/MM/YYYY) and optional time (HH:MM) into epoch milliseconds.
     * Strictly validates the date and time.
     * Returns null if the format or values are invalid (e.g. 32/01/2026, 25:00).
     * NEVER returns System.currentTimeMillis() as fallback for invalid input.
     */
    fun parseDateTimeToMillis(dateStr: String, timeStr: String? = null): Long? {
        val cleanDate = dateStr.trim()
        if (cleanDate.isBlank()) return null
        val cleanTime = if (timeStr.isNullOrBlank()) "08:00" else timeStr.trim()
        if (!hasAcceptableYear(cleanDate)) return null

        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).apply {
                isLenient = false
            }
            val date = sdf.parse("$cleanDate $cleanTime") ?: return null
            date.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strictly validates if dateStr is in DD/MM/YYYY format and is a valid calendar date.
     */
    fun isValidDate(dateStr: String): Boolean {
        val cleanDate = dateStr.trim()
        if (cleanDate.isBlank()) return false
        if (!hasAcceptableYear(cleanDate)) return false
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply {
                isLenient = false
            }
            sdf.parse(cleanDate) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Strictly validates if timeStr is in HH:MM format (00:00 to 23:59).
     */
    fun isValidTime(timeStr: String): Boolean {
        val cleanTime = timeStr.trim()
        if (cleanTime.isBlank()) return false
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                isLenient = false
            }
            sdf.parse(cleanTime) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates both date and time together.
     */
    fun isValidDateTime(dateStr: String, timeStr: String? = null): Boolean {
        return parseDateTimeToMillis(dateStr, timeStr) != null
    }
}
