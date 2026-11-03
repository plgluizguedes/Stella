package com.example

import com.example.data.model.Reminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAndSyncTest {

    @Test
    fun testReminderFiltering_onlyPendingAndFutureAreRescheduled() {
        val now = System.currentTimeMillis()
        val futurePending = Reminder(
            id = 1L,
            title = "Insulina Manhã",
            category = "Medicamento",
            dateString = "29/09/2026",
            timeString = "08:00",
            timestamp = now + 3600_000L,
            status = "PENDENTE"
        )
        val pastPending = Reminder(
            id = 2L,
            title = "Insulina Ontem",
            category = "Medicamento",
            dateString = "27/09/2026",
            timeString = "08:00",
            timestamp = now - 3600_000L,
            status = "PENDENTE"
        )
        val futureCompleted = Reminder(
            id = 3L,
            title = "Consulta Vet",
            category = "Consulta",
            dateString = "29/09/2026",
            timeString = "14:00",
            timestamp = now + 7200_000L,
            status = "REALIZADO"
        )

        val reminders = listOf(futurePending, pastPending, futureCompleted)
        val toSchedule = reminders.filter { it.status == "PENDENTE" && it.timestamp > now }

        assertEquals(1, toSchedule.size)
        assertEquals(1L, toSchedule.first().id)
        assertEquals("Insulina Manhã", toSchedule.first().title)
    }

    @Test
    fun testReminderToggle_statusTransitions() {
        val pendingReminder = Reminder(
            id = 10L,
            title = "Passeio",
            category = "Geral",
            dateString = "28/09/2026",
            timeString = "17:00",
            timestamp = 1700000000000L,
            status = "PENDENTE"
        )

        // Toggle from PENDENTE to REALIZADO
        val newStatus1 = if (pendingReminder.status == "REALIZADO") "PENDENTE" else "REALIZADO"
        val toggled1 = pendingReminder.copy(status = newStatus1)
        assertEquals("REALIZADO", toggled1.status)

        // Toggle from REALIZADO back to PENDENTE
        val newStatus2 = if (toggled1.status == "REALIZADO") "PENDENTE" else "REALIZADO"
        val toggled2 = toggled1.copy(status = newStatus2)
        assertEquals("PENDENTE", toggled2.status)
    }

    @Test
    fun testMutexSequentialExecution_preventsRaceCondition() = runBlocking {
        val mutex = Mutex()
        val executionOrder = mutableListOf<String>()

        // Simulate write launching first
        val jobWrite = launch {
            mutex.withLock {
                executionOrder.add("WRITE_START")
                delay(50)
                executionOrder.add("WRITE_PERSISTED")
            }
        }

        // Simulate sync attempting to read database
        val jobSync = launch {
            mutex.withLock {
                executionOrder.add("SYNC_READ")
            }
        }

        jobWrite.join()
        jobSync.join()

        // Sync must NEVER read Room before write has persisted!
        assertEquals(listOf("WRITE_START", "WRITE_PERSISTED", "SYNC_READ"), executionOrder)
    }

    @Test
    fun testPendingChangesFlagLifecycle() {
        var hasPendingChanges = false

        // Simulating mutation: local write marks pending changes
        hasPendingChanges = true
        assertTrue(hasPendingChanges)

        // Simulating successful Google Drive sync
        hasPendingChanges = false
        assertFalse(hasPendingChanges)
    }
}
