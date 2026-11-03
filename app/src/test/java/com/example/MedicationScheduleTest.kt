package com.example

import com.example.data.model.Medication
import com.example.notifications.MedicationAlarmScheduler
import com.example.notifications.MedicationSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MedicationScheduleTest {

    private val zone = TimeZone.getTimeZone("America/Sao_Paulo")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun leHorariosSeparadosPorVirgulaPontoEVirgulaOuEspaco() {
        assertEquals(listOf(8 * 60, 20 * 60), MedicationSchedule.parseTimes("08:00, 20:00"))
        assertEquals(listOf(8 * 60, 20 * 60 + 30), MedicationSchedule.parseTimes("8h; 20h30"))
        assertEquals(listOf(6 * 60, 18 * 60), MedicationSchedule.parseTimes("18:00 06:00"))
    }

    @Test
    fun ignoraTextoQueNaoEHorarioEHorariosRepetidos() {
        assertEquals(emptyList<Int>(), MedicationSchedule.parseTimes("Livre"))
        assertEquals(emptyList<Int>(), MedicationSchedule.parseTimes("a cada 8 horas"))
        assertEquals(emptyList<Int>(), MedicationSchedule.parseTimes(""))
        assertEquals(listOf(8 * 60), MedicationSchedule.parseTimes("08:00, 8h, 08:00"))
        assertEquals(emptyList<Int>(), MedicationSchedule.parseTimes("25:00, 10:75"))
    }

    @Test
    fun limitaQuantidadeDeHorariosPorDia() {
        val many = (0 until 20).joinToString(", ") { "%02d:00".format(it) }
        assertEquals(MedicationSchedule.MAX_TIMES_PER_DAY, MedicationSchedule.parseTimes(many).size)
    }

    @Test
    fun proximoDisparoEHojeSeOHorarioAindaNaoPassou() {
        val now = millis(2026, 11, 3, 7, 0)
        assertEquals(millis(2026, 11, 3, 8, 0), MedicationSchedule.nextTrigger(8 * 60, now, zone = zone))
    }

    @Test
    fun proximoDisparoEAmanhaSeOHorarioJaPassouOuEAgora() {
        val now = millis(2026, 11, 3, 8, 0)
        assertEquals(millis(2026, 11, 4, 8, 0), MedicationSchedule.nextTrigger(8 * 60, now, zone = zone))
        assertEquals(millis(2026, 11, 4, 8, 0), MedicationSchedule.nextTrigger(8 * 60, now + 1, zone = zone))
    }

    @Test
    fun respeitaDataDeInicioNoFuturo() {
        val now = millis(2026, 11, 3, 7, 0)
        val start = millis(2026, 11, 10, 0, 0)
        assertEquals(millis(2026, 11, 10, 8, 0), MedicationSchedule.nextTrigger(8 * 60, now, startMillis = start, endMillis = null, zone = zone))
    }

    @Test
    fun naoAgendaDepoisDoFimDoTratamento() {
        val now = millis(2026, 11, 3, 9, 0)
        val end = millis(2026, 11, 3, 23, 59)
        assertNull(MedicationSchedule.nextTrigger(8 * 60, now, endMillis = end, zone = zone))
        assertEquals(millis(2026, 11, 3, 20, 0), MedicationSchedule.nextTrigger(20 * 60, now, endMillis = end, zone = zone))
    }

    @Test
    fun soMedicamentoAtivoTemAlarme() {
        val med = Medication(name = "X", dosage = "1", frequency = "1x", scheduledTimes = "08:00", startDate = "01/01/2026")
        assertTrue(MedicationSchedule.isActive(med))
        assertFalse(MedicationSchedule.isActive(med.copy(status = "ENCERRADO")))
    }

    @Test
    fun codigosDeAlarmeNaoColidemEntreHorariosNemComLembretes() {
        val a = MedicationAlarmScheduler.requestCode(1, 0)
        val b = MedicationAlarmScheduler.requestCode(1, 1)
        val c = MedicationAlarmScheduler.requestCode(2, 0)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertTrue("fora da faixa dos ids de lembrete", a > 1_000_000)
    }
}
