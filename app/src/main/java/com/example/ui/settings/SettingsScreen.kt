package com.example.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Chat API", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.textPath,
                onValueChange = viewModel::updateTextPath,
                label = { Text("Path") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::updateApiKey,
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.modelName,
                onValueChange = viewModel::updateModelName,
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save) { Text("Save") }
                OutlinedButton(onClick = viewModel::testConnection) { Text("Test Connection") }
            }

            uiState.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            uiState.testError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            uiState.testResult?.let { Text(it, color = Color(0xFF2E7D32)) }

            HorizontalDivider()

            Text("Microsoft Outlook", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = uiState.microsoftClientId,
                onValueChange = viewModel::updateMicrosoftClientId,
                label = { Text("Microsoft Client ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.microsoftTenant,
                onValueChange = viewModel::updateMicrosoftTenant,
                label = { Text("Tenant, default common") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Azure redirect URI harus sama dengan build.gradle dan AndroidManifest: msauth://com.aistudio.aichatmobile.xmqpr/<encoded-signature-hash>. Tenant common tetap mendukung personal + organizational.",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.saveMicrosoftConfig(context) }) { Text("Save Microsoft") }
                OutlinedButton(
                    enabled = activity != null && !uiState.isTesting,
                    onClick = { activity?.let { viewModel.signInMicrosoft(it, context) } }
                ) { Text(if (uiState.isTesting) "Connecting..." else "Connect Outlook") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = viewModel::testMicrosoftProfile) { Text("Test Profile") }
                OutlinedButton(onClick = viewModel::testMicrosoftInbox) { Text("Check Inbox") }
            }
            uiState.microsoftAccount?.let { account ->
                Text("Connected: ${account.username ?: account.id}", color = Color(0xFF2E7D32))
                OutlinedButton(onClick = { viewModel.signOutMicrosoft(context) }) { Text("Disconnect Outlook") }
            } ?: Text("No Microsoft account connected", color = Color.Gray)

            HorizontalDivider()

            Text("Firecrawl", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = uiState.firecrawlApiKey,
                onValueChange = viewModel::updateFirecrawlApiKey,
                label = { Text("Firecrawl API Key") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::saveFirecrawlKey) { Text("Save Firecrawl") }
                OutlinedButton(onClick = viewModel::removeFirecrawlKey) { Text("Remove") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}