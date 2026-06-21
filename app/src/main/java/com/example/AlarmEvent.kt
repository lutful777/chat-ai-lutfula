package com.example

class AlarmEvent : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent?) {
        AppNotify.showReminder(context.applicationContext)
    }
}
