package com.example

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object AppNotify {
    const val EXTRA_OPEN_SESSION_ID = "open_session_id"
    private const val CHANNEL_ID = "ai_chat_status"
    private const val CHANNEL_NAME = "Ai Chat"
    private const val ANSWER_ID = 4101

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun showAnswerReadyIfBackground(context: Context, sessionId: Long) {
        if (AppVisibility.isAppInForeground) return
        show(context, ANSWER_ID, "Ai Chat", "Answer is ready", sessionId)
    }

    fun showReminder(context: Context) {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        show(context, id, "Ai Chat Reminder", "It's time for your reminder.", null)
    }

    @SuppressLint("MissingPermission")
    private fun show(context: Context, id: Int, title: String, message: String, sessionId: Long?) {
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (sessionId != null) putExtra(EXTRA_OPEN_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val item = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(id, item)
    }
}
