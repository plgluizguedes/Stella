package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Rescheduling alarms after $action")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    val reminders = db.stellaDao().getAllRemindersSync()
                    val notifManager = ReminderNotificationManager(context.applicationContext)
                    notifManager.rescheduleAllPendingReminders(reminders)
                    MedicationAlarmScheduler(context.applicationContext)
                        .scheduleAll(db.stellaDao().getAllMedicationsSync())
                    Log.d(TAG, "Successfully processed boot reminders rescheduling.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error rescheduling reminders after device boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
