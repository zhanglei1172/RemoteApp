package com.remoteapp.host

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.remoteapp.host.accessibility.RemoteAccessibilityService
import com.remoteapp.host.server.RemoteRuntime

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private var serverRunning by mutableStateOf(false)
    private var lanEnabled by mutableStateOf(false)
    private var pairingCode by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var pendingLanEnabled = false
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            RemoteRuntime.startWithProjection(this, pendingLanEnabled, result.resultCode, result.data!!)
            serverRunning = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                RemoteAppScreen(
                    accessibilityEnabled = accessibilityEnabled,
                    serverRunning = serverRunning,
                    lanEnabled = lanEnabled,
                    pairingCode = pairingCode,
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onLanChanged = { lanEnabled = it },
                    onStart = {
                        if (isAccessibilityEnabled()) {
                            pairingCode = RemoteRuntime.createPairingCode(this)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                pendingLanEnabled = lanEnabled
                                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
                            } else {
                                RemoteRuntime.start(this, lanEnabled)
                                serverRunning = true
                            }
                        }
                    },
                    onStop = {
                        RemoteRuntime.stop(this)
                        serverRunning = false
                        pairingCode = null
                    }
                )
            }
            LaunchedEffect(Unit) { refreshState() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        accessibilityEnabled = isAccessibilityEnabled()
        serverRunning = RemoteRuntime.server != null
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val expected = "$packageName/${RemoteAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@androidx.compose.runtime.Composable
private fun RemoteAppScreen(
    accessibilityEnabled: Boolean,
    serverRunning: Boolean,
    lanEnabled: Boolean,
    pairingCode: String?,
    onOpenAccessibility: () -> Unit,
    onLanChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("RemoteApp Host", style = MaterialTheme.typography.headlineMedium)
        Text("让这台手机在用户明确授权后接受局域网控制。")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权限状态", style = MaterialTheme.typography.titleMedium)
                Text(if (accessibilityEnabled) "无障碍服务已启用" else "需要启用无障碍服务")
                if (!accessibilityEnabled) {
                    Button(onClick = onOpenAccessibility) { Text("打开系统设置") }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("允许局域网访问", style = MaterialTheme.typography.titleMedium)
                Text("当前版本使用 HTTP，请仅在可信网络开启。")
            }
            Switch(checked = lanEnabled, onCheckedChange = onLanChanged)
        }

        Spacer(Modifier.height(8.dp))
        if (!serverRunning) {
            Button(onClick = onStart, enabled = accessibilityEnabled, modifier = Modifier.fillMaxWidth()) {
                Text("启动远程服务")
            }
        } else {
            Text("服务正在运行：端口 8765")
            pairingCode?.let {
                Text("一次性配对码", style = MaterialTheme.typography.titleMedium)
                Text(it, style = MaterialTheme.typography.displaySmall)
                Text("在同一局域网的浏览器打开本机地址并输入此配对码。")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止远程服务") }
        }
    }
}
