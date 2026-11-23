package com.ylprivate.agents.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.ylprivate.agents.data.AppSettings
import com.ylprivate.agents.data.SettingsRepository
import com.ylprivate.agents.R
import com.ylprivate.agents.webaccess.WebAccessManager

/**
 * Web Access settings: start the desktop-browser HTTP server, pick bind address/port,
 * show the per-run access code and the LAN addresses to open on the computer.
 *
 * Retargeted from YL-COS `ui/settings/services/SettingsWebAccessScreen.kt`.
 * License: AGPL-3.0 (YL-COS). Private use only while the structure is copied.
 */
@Composable
internal fun WebAccessSettings(
    settings: AppSettings,
    manager: WebAccessManager,
    repository: SettingsRepository,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val state = manager.state.value
    var port by remember { mutableStateOf(settings.webAccessPort.toString()) }
    var lan by remember { mutableStateOf(settings.webAccessHost == "0.0.0.0") }

    // Keep the local mirrors in sync with the settings flow.
    LaunchedEffect(settings.webAccessPort) { port = settings.webAccessPort.toString() }
    LaunchedEffect(settings.webAccessHost) { lan = settings.webAccessHost == "0.0.0.0" }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(context.getString(R.string.web_access_title), style = MaterialTheme.typography.titleLarge)
        Text(context.getString(R.string.web_access_desc), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(context.getString(R.string.web_access_lan))
            Switch(checked = lan, enabled = !state.running, onCheckedChange = { checked ->
                lan = checked
                scope.launch { repository.setWebAccessHost(if (checked) "0.0.0.0" else "127.0.0.1") }
            })
        }
        OutlinedTextField(value = port, onValueChange = { port = it.filter { it.isDigit() }.take(5) }, enabled = !state.running,
            label = { Text(context.getString(R.string.web_access_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (state.running) manager.stop() else {
                val value = port.toIntOrNull()
                if (value != null && value in 1024..65535) {
                    scope.launch { repository.setWebAccessPort(value) }
                    manager.start()
                }
            }
        }, enabled = state.running || port.toIntOrNull() in 1024..65535) {
            Text(context.getString(if (state.running) R.string.web_access_stop else R.string.web_access_start))
        }
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        Text(context.getString(R.string.web_access_storage_desc), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = Build.VERSION.SDK_INT >= 30, onClick = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
        }) { Text(context.getString(R.string.web_access_storage_permission)) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(context.getString(R.string.web_access_addresses), style = MaterialTheme.typography.bodyLarge)
        state.addresses.forEach { address ->
            Row(Modifier.fillMaxWidth()) {
                Text(address)
                TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Web Access", address)) }) { Text(context.getString(R.string.web_access_copy)) }
            }
        }
        Text(context.getString(R.string.web_access_token), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth()) {
            Text(state.accessToken, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Web Access", state.accessToken)) }) { Text(context.getString(R.string.web_access_copy)) }
            TextButton(onClick = { manager.stop(); port = java.util.UUID.randomUUID().toString().take(8) }) {
                Text(context.getString(R.string.web_access_reset_token))
            }
        }
    }
    Text(context.getString(R.string.web_access_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
