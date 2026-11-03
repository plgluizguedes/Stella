package com.example.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.data.model.Reminder

class ReminderBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
        const val EXTRA_REMINDER_CATEGORY = "extra_reminder_category"
        const val EXTRA_REMINDER_TIME = "extra_reminder_time"
        private const val TAG = "ReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: "Lembrete da Stella"
        val category = intent.getStringExtra(EXTRA_REMINDER_CATEGORY) ?: "Lembrete"
        val time = intent.getStringExtra(EXTRA_REMINDER_TIME) ?: ""

        Log.d(TAG, "Notification trigger fired for reminder #$reminderId")

        // Notification Open Intent
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingTapIntent = PendingIntent.getActivity(
            context,
            reminderId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build system notification
        val builder = NotificationCompat.Builder(context, ReminderNotificationManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Stella: $title")
            .setContentText("$category • Horário: $time")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingTapIntent)

        val notificationManager = NotificationManagerCompat.from(context)
        try {
            notificationManager.notify(reminderId.toInt(), builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing: ${e.message}")
        }
    }
}

class ReminderNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "stella_health_reminders"
        private const val CHANNEL_NAME = "Lembretes da Stella"
        private const val TAG = "ReminderNotifManager"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Notificações de medicamentos, consultas e cuidados diários da Stella"
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun scheduleNotification(reminder: Reminder) {
        val triggerTime = reminder.timestamp
        val now = System.currentTimeMillis()

        if (reminder.status != "PENDENTE" || triggerTime <= now) {
            Log.d(TAG, "Skipping schedule for reminder #${reminder.id} (not pending or in past)")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, reminder.id)
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_TITLE, reminder.title)
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_CATEGORY, reminder.category)
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_TIME, reminder.timeString)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled alarm for reminder #${reminder.id} at $triggerTime")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm for reminder #${reminder.id}", e)
        }
    }

    fun rescheduleAllPendingReminders(reminders: List<Reminder>) {
        val now = System.currentTimeMillis()
        var scheduledCount = 0
        for (reminder in reminders) {
            if (reminder.status == "PENDENTE" && reminder.timestamp > now) {
                scheduleNotification(reminder)
                scheduledCount++
            }
        }
        Log.d(TAG, "rescheduleAllPendingReminders: scheduled $scheduledCount reminders")
    }

    fun cancelNotification(reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(reminderId.toInt())
    }
}
