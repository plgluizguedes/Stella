package com.example

import com.example.util.DateInput
import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Test

class DateInputTest {
    private val today = GregorianCalendar(2026, 9, 19) // 19/10/2026

    private fun date(s: String) = DateInput.normalizeDate(s, today)

    @Test fun diaEMes_usaOAnoAtual() {
        assertEquals("11/10/2026", date("11/10"))
        assertEquals("11/10/2026", date("1110"))
        assertEquals("05/03/2026", date("5/3"))
    }

    @Test fun anoComDoisDigitos_viraSeculoCerto() {
        assertEquals("11/10/2026", date("11/10/26"))
        assertEquals("11/10/2026", date("111026"))
        assertEquals("05/03/2026", date("5-3-26"))
        assertEquals("15/03/2036", date("15/03/36"))
        assertEquals("15/03/1999", date("15/03/99"))
    }

    @Test fun anoCompleto_ficaComoEsta() {
        assertEquals("11/10/2026", date("11/10/2026"))
        assertEquals("11/10/2026", date("11102026"))
        assertEquals("11/10/2026", date("  11.10.2026 "))
    }

    @Test fun invalidoOuAmbiguo_voltaComoDigitado() {
        assertEquals("31/02", date("31/02"))
        assertEquals("32/01/26", date("32/01/26"))
        assertEquals("111", date("111"))
        assertEquals("11/10/202", date("11/10/202"))
        assertEquals("abc", date("abc"))
    }

    @Test fun branco_voltaComoEstava() {
        assertEquals("", date(""))
        assertEquals("  ", date("  "))
    }

    @Test fun anoBissexto() {
        assertEquals("29/02/2028", date("29/02/28"))
        assertEquals("30/02", date("30/02"))
        assertEquals("29/02", date("29/02")) // 2026 não é bissexto
    }

    @Test fun horario() {
        assertEquals("09:00", DateInput.normalizeTime("9"))
        assertEquals("09:30", DateInput.normalizeTime("930"))
        assertEquals("09:30", DateInput.normalizeTime("0930"))
        assertEquals("09:05", DateInput.normalizeTime("9:5"))
        assertEquals("09:30", DateInput.normalizeTime("9h30"))
        assertEquals("14:45", DateInput.normalizeTime("14:45"))
        assertEquals("25:00", DateInput.normalizeTime("25:00"))
        assertEquals("12:60", DateInput.normalizeTime("12:60"))
        assertEquals("", DateInput.normalizeTime(""))
    }
}
