package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.di.AppContainer
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    saveNotificationOpenSession(intent)
    AppNotify.createChannels(this)
    requestNotificationPermissionIfNeeded()
    startMessageObserver()
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        AppNavigation()
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    saveNotificationOpenSession(intent)
  }

  override fun onStart() {
    super.onStart()
    AppVisibility.isAppInForeground = true
  }

  override fun onStop() {
    AppVisibility.isAppInForeground = false
    super.onStop()
  }

  private fun saveNotificationOpenSession(sourceIntent: Intent?) {
    val sessionId = sourceIntent?.getLongExtra(AppNotify.EXTRA_OPEN_SESSION_ID, -1L) ?: -1L
    if (sessionId > 0L) {
      applicationContext
        .getSharedPreferences("notification_open", Context.MODE_PRIVATE)
        .edit()
        .putLong("pending_session_id", sessionId)
        .apply()
    }
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  private fun startMessageObserver() {
    val appContext = applicationContext
    val prefs = appContext.getSharedPreferences("message_observer", Context.MODE_PRIVATE)
    val repository = AppContainer.getChatRepository(appContext)

    lifecycleScope.launch(Dispatchers.IO) {
      repository.observeLatestMessage().collectLatest { message ->
        if (message == null) return@collectLatest
        val lastId = prefs.getInt("last_message_id", 0)
        if (lastId == 0) {
          prefs.edit().putInt("last_message_id", message.id).apply()
          return@collectLatest
        }
        if (message.id <= lastId) return@collectLatest
        prefs.edit().putInt("last_message_id", message.id).apply()

        if (message.role == "assistant") {
          AppNotify.showAnswerReadyIfBackground(appContext, message.sessionId)
        } else if (message.role == "user") {
          ReminderScheduler.scheduleFromTextIfPossible(appContext, message.content)
        }
      }
    }
  }
}
