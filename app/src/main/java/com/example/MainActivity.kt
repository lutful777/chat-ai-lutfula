package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppNotify.createChannels(this)
    requestNotificationPermissionIfNeeded()
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        AppNavigation()
      }
    }
  }

  override fun onStart() {
    super.onStart()
    AppVisibility.isAppInForeground = true
  }

  override fun onStop() {
    AppVisibility.isAppInForeground = false
    super.onStop()
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}
