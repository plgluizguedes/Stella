package com.example

import com.example.util.DateTimeUtils
import com.example.util.InputLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLimitsTest {

    @Test
    fun glicemiaDentroDaFaixaEAceita() {
        assertTrue(InputLimits.isValidGlucose(10.0))
        assertTrue(InputLimits.isValidGlucose(120.0))
        assertTrue(InputLimits.isValidGlucose(800.0))
    }

    @Test
    fun glicemiaForaDaFaixaOuInvalidaERejeitada() {
        assertFalse(InputLimits.isValidGlucose(0.0))
        assertFalse(InputLimits.isValidGlucose(-5.0))
        assertFalse(InputLimits.isValidGlucose(1000.0))
        assertFalse(InputLimits.isValidGlucose(Double.NaN))
        assertFalse(InputLimits.isValidGlucose(Double.POSITIVE_INFINITY))
    }

    @Test
    fun pesoDentroDaFaixaEAceito() {
        assertTrue(InputLimits.isValidWeight(0.05))
        assertTrue(InputLimits.isValidWeight(8.5))
        assertTrue(InputLimits.isValidWeight(150.0))
    }

    @Test
    fun pesoForaDaFaixaOuInvalidoERejeitado() {
        assertFalse(InputLimits.isValidWeight(0.0))
        assertFalse(InputLimits.isValidWeight(-1.0))
        assertFalse(InputLimits.isValidWeight(151.0))
        assertFalse(InputLimits.isValidWeight(Double.NaN))
    }

    @Test
    fun anoAbsurdoERejeitadoNasDatas() {
        assertFalse(DateTimeUtils.isValidDate("10/10/2626"))
        assertFalse(DateTimeUtils.isValidDate("10/10/1026"))
        assertNull(DateTimeUtils.parseDateTimeToMillis("10/10/2626", "10:00"))
    }

    @Test
    fun dataNormalContinuaValida() {
        assertTrue(DateTimeUtils.isValidDate("29/02/2024"))
        assertNotNull(DateTimeUtils.parseDateTimeToMillis("03/11/2026", "08:30"))
        assertFalse(DateTimeUtils.isValidDate("31/04/2026"))
    }
}
