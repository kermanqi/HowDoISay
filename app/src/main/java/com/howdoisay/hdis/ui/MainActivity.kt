package com.howdoisay.hdis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.howdoisay.hdis.data.ArkEnglishExpressionService
import com.howdoisay.hdis.data.ExpressionException
import com.howdoisay.hdis.data.SecureCredentialStore
import com.howdoisay.hdis.data.toExpressionError
import com.howdoisay.hdis.domain.ProviderCredentials
import com.howdoisay.hdis.domain.userMessage
import com.howdoisay.hdis.overlay.OverlayForegroundService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var store: SecureCredentialStore
    private val permissionVersion = mutableStateOf(0)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionVersion.value += 1 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureCredentialStore(this)
        setContent {
            HdisTheme {
                permissionVersion.value
                var credentials by remember { mutableStateOf(store.read()) }
                var testMessage by remember { mutableStateOf<String?>(null) }
                SettingsScreen(
                    credentials = credentials,
                    microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                    notificationsGranted = Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                    overlayGranted = Settings.canDrawOverlays(this),
                    onCredentialsChanged = { credentials = it },
                    onSave = {
                        store.save(credentials)
                        toast("Saved")
                    },
                    onRequestMicrophone = { requestRuntimePermissions() },
                    onRequestNotifications = { requestRuntimePermissions() },
                    onRequestOverlay = { openOverlaySettings() },
                    onBatterySettings = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    onStart = {
                        store.save(credentials)
                        when {
                            !credentials.isReady() -> toast("Check API settings")
                            !hasPermission(Manifest.permission.RECORD_AUDIO) -> toast("Allow microphone access first")
                            !Settings.canDrawOverlays(this) -> toast("Allow display over other apps first")
                            else -> OverlayForegroundService.start(this)
                        }
                    },
                    onStop = { OverlayForegroundService.stop(this) },
                    onTest = {
                        store.save(credentials)
                        testMessage = "Testing…"
                        lifecycleScope.launch {
                            val arkCredentials = credentials.arkCredentials()
                            if (arkCredentials == null) {
                                testMessage = "Check API settings"
                                return@launch
                            }
                            val result = ArkEnglishExpressionService().testConnection(arkCredentials)
                            testMessage = result.fold(
                                onSuccess = { "Connection works" },
                                onFailure = { error ->
                                    ((error as? ExpressionException)?.error ?: error.toExpressionError()).userMessage()
                                }
                            )
                        }
                    },
                    testMessage = testMessage
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionVersion.value += 1
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun openOverlaySettings() {
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

@Composable
private fun HdisTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF9CB6FF),
        background = Color(0xFF101114),
        surface = Color(0xFF1A1C20),
        onBackground = Color(0xFFF2F2F5),
        onSurface = Color(0xFFF2F2F5)
    )
    MaterialTheme(colorScheme = dark, content = content)
}

@Composable
private fun SettingsScreen(
    credentials: ProviderCredentials,
    microphoneGranted: Boolean,
    notificationsGranted: Boolean,
    overlayGranted: Boolean,
    onCredentialsChanged: (ProviderCredentials) -> Unit,
    onSave: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestOverlay: () -> Unit,
    onBatterySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
    testMessage: String?
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("HDIS", style = MaterialTheme.typography.displaySmall)
        Text("Think in Chinese. Speak in English.", color = MaterialTheme.colorScheme.primary)
        Text("A floating English expression assistant — not a chatbot.", style = MaterialTheme.typography.bodyMedium)

        SettingsSection("Bubble") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start Bubble") }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2427))
                ) { Text("Stop Bubble") }
            }
        }

        SettingsSection("Permissions") {
            PermissionRow("Microphone", microphoneGranted, onRequestMicrophone)
            PermissionRow("Display over other apps", overlayGranted, onRequestOverlay)
            PermissionRow("Notifications", notificationsGranted, onRequestNotifications)
            TextButton(onClick = onBatterySettings) { Text("Open Battery Optimization") }
        }

        SettingsSection("Doubao-Seed-2.0-mini") {
            CredentialField("API Key", credentials.arkApiKey, true) {
                onCredentialsChanged(credentials.copy(arkApiKey = it))
            }
            CredentialField("Model or Endpoint ID", credentials.arkEndpointId, false) {
                onCredentialsChanged(credentials.copy(arkEndpointId = it))
            }
            TextButton(onClick = onTest) { Text("Test Ark Connection") }
            testMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save Settings") }
        Text("HDIS stores your credentials only on this device. It never writes them to source control.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBFC2CB))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(color = Color(0xFF3B3E46))
        content()
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(if (granted) "Allowed" else "Required", style = MaterialTheme.typography.bodySmall, color = if (granted) Color(0xFF8DD9A5) else Color(0xFFFFB4AB))
        }
        TextButton(onClick = onClick) { Text(if (granted) "Open" else "Allow") }
    }
}

@Composable
private fun CredentialField(label: String, value: String, secret: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text)
    )
}
