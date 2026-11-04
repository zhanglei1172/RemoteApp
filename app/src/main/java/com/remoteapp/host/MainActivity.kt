package com.remoteapp.host

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.NetworkInterface
import java.net.Inet4Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.remoteapp.host.accessibility.RemoteAccessibilityService
import com.remoteapp.host.server.HostPhase
import com.remoteapp.host.platform.PlatformCapabilities
import com.remoteapp.host.server.RemoteRuntime

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private var addresses by mutableStateOf("")
    private var hostBusy by mutableStateOf(false)
    private var hostMessage by mutableStateOf<String?>(null)
    private var serverRunning by mutableStateOf(false)
    private var lanEnabled by mutableStateOf(false)
    private var pairingCode by mutableStateOf<String?>(null)
    private var passwordConfigured by mutableStateOf(false)
    private var password by mutableStateOf("")
    private var passwordConfirmation by mutableStateOf("")
    private var passwordMessage by mutableStateOf<String?>(null)
    private var apiKeyConfigured by mutableStateOf(false)
    private var apiKey by mutableStateOf<String?>(null)
    private var savingPassword by mutableStateOf(false)
    private var generatingApiKey by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var pendingLanEnabled = false
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            RemoteRuntime.startWithProjection(this, pendingLanEnabled, result.resultCode, result.data!!)
        } else RemoteRuntime.updateState(HostPhase.IDLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLanEnabled = savedInstanceState?.getBoolean("pendingLan") ?: false
        lanEnabled = savedInstanceState?.getBoolean("lan") ?: false
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                RemoteAppScreen(
                    accessibilityEnabled = accessibilityEnabled,
                    serverRunning = serverRunning,
                    hostBusy = hostBusy,
                    hostMessage = hostMessage,
                    addresses = addresses,
                    lanEnabled = lanEnabled,
                    pairingCode = pairingCode,
                    passwordConfigured = passwordConfigured,
                    password = password,
                    passwordConfirmation = passwordConfirmation,
                    passwordMessage = passwordMessage,
                    apiKeyConfigured = apiKeyConfigured,
                    apiKey = apiKey,
                    savingPassword = savingPassword,
                    generatingApiKey = generatingApiKey,
                    onPasswordChanged = { password = it },
                    onPasswordConfirmationChanged = { passwordConfirmation = it },
                    onSetPassword = {
                        when {
                            password != passwordConfirmation -> passwordMessage = "两次输入的密码不一致。"
                            !com.remoteapp.host.auth.PairingManager.isValidPassword(password) -> passwordMessage = "密码需为 6-128 位，并至少包含字母或数字。"
                            else -> {
                                val newPassword = password
                                savingPassword = true
                                lifecycleScope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        runCatching { RemoteRuntime.setPassword(this@MainActivity, newPassword) }.getOrDefault(false)
                                    }
                                    savingPassword = false
                                    passwordMessage = if (saved) "密码已保存，旧会话已撤销。" else "密码保存失败，请重试。"
                                    if (saved) {
                                        passwordConfigured = true
                                        password = ""
                                        passwordConfirmation = ""
                                    }
                                }
                            }
                        }
                    },
                    onGenerateApiKey = {
                        generatingApiKey = true
                        lifecycleScope.launch {
                            val key = withContext(Dispatchers.IO) {
                                runCatching { RemoteRuntime.generateApiKey(this@MainActivity) }.getOrNull()
                            }
                            generatingApiKey = false
                            if (key != null) {
                                apiKey = key
                                apiKeyConfigured = true
                            } else Toast.makeText(this@MainActivity, "生成失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCopyApiKey = {
                        apiKey?.let { key ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("RemoteApp API Key", key).apply {
                                description.extras = PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                            })
                            Toast.makeText(this, "API Key 已复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onLanChanged = { lanEnabled = it },
                    onStart = {
                        if (isAccessibilityEnabled() && !hostBusy && !serverRunning) {
                            pairingCode = RemoteRuntime.createPairingCode(this)
                            pendingLanEnabled = lanEnabled
                            RemoteRuntime.awaitingConsent()
                            try {
                                projectionLauncher.launch(PlatformCapabilities.captureIntent(this))
                            } catch (_: Exception) {
                                RemoteRuntime.updateState(HostPhase.ERROR, "无法打开录屏授权，请检查系统设置。")
                            }
                        }
                    },
                    onStop = {
                        RemoteRuntime.stop(this)
                        pairingCode = null
                    }
                )
            }
            LaunchedEffect(serverRunning, lanEnabled) {
                while (isActive && serverRunning) {
                    addresses = if (lanEnabled) withContext(Dispatchers.IO) {
                        runCatching {
                            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                                .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                                .map { "http://${it.hostAddress}:8765" }.distinct().joinToString("\n")
                        }.getOrDefault("").ifBlank { "未找到局域网地址，请检查 Wi-Fi 或热点。" }
                    } else "http://127.0.0.1:8765（仅本机）"
                    delay(5_000)
                }
            }
            LaunchedEffect(Unit) {
                combine(RemoteRuntime.state, RemoteAccessibilityService.connection) { _, _ -> Unit }
                    .collect { refreshState() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingLan", pendingLanEnabled)
        outState.putBoolean("lan", lanEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        accessibilityEnabled = isAccessibilityEnabled()
        val state = RemoteRuntime.state.value
        serverRunning = state.phase == HostPhase.RUNNING
        hostBusy = state.busy
        hostMessage = state.message ?: when (state.phase) {
            HostPhase.AWAITING_CONSENT -> "请在系统弹窗中允许整屏共享。"
            HostPhase.STARTING -> "正在等待屏幕首帧…"
            HostPhase.STOPPING -> "正在停止服务…"
            else -> null
        }
        if (serverRunning) lanEnabled = RemoteRuntime.lanEnabled
        passwordConfigured = RemoteRuntime.isPasswordConfigured(this)
        pairingCode = RemoteRuntime.pairingManager.currentPairingCode()
        apiKeyConfigured = RemoteRuntime.hasApiKey(this)
        apiKey = RemoteRuntime.currentApiKey(this)
    }

    private fun isAccessibilityEnabled(): Boolean {
        return RemoteAccessibilityService.connected.get()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
private fun RemoteAppScreen(
    accessibilityEnabled: Boolean,
    serverRunning: Boolean,
    hostBusy: Boolean,
    hostMessage: String?,
    addresses: String,
    lanEnabled: Boolean,
    pairingCode: String?,
    passwordConfigured: Boolean,
    password: String,
    passwordConfirmation: String,
    passwordMessage: String?,
    apiKeyConfigured: Boolean,
    apiKey: String?,
    savingPassword: Boolean,
    generatingApiKey: Boolean,
    onPasswordChanged: (String) -> Unit,
    onPasswordConfirmationChanged: (String) -> Unit,
    onSetPassword: () -> Unit,
    onGenerateApiKey: () -> Unit,
    onCopyApiKey: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onLanChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("RemoteApp Host", style = MaterialTheme.typography.headlineSmall)
        Text("让这台手机在用户明确授权后接受局域网控制。", style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("访问密码", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (passwordConfigured) "已设置，可在网页中登录。更新密码会撤销旧会话，API Key 不受影响。"
                    else "设置 6-128 位密码，即可在网页中登录；也可使用一次性配对码。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChanged,
                    label = { Text("新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    enabled = !savingPassword,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = passwordConfirmation,
                    onValueChange = onPasswordConfirmationChanged,
                    label = { Text("确认密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    enabled = !savingPassword,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = onSetPassword,
                    enabled = !savingPassword && password.isNotEmpty()
                ) { Text(if (savingPassword) "保存中…" else if (passwordConfigured) "更新密码" else "保存密码") }
                passwordMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Agent API Key", style = MaterialTheme.typography.titleSmall)
                Text("永久有效，重开应用后仍可复制。重新生成会使旧 Key 失效。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onGenerateApiKey, enabled = !generatingApiKey) {
                        Text(if (generatingApiKey) "生成中…" else if (apiKeyConfigured) "重新生成" else "生成 API Key")
                    }
                    if (apiKey != null) OutlinedButton(onClick = onCopyApiKey, enabled = !generatingApiKey) { Text("复制") }
                }
                apiKey?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权限状态", style = MaterialTheme.typography.titleSmall)
                Text(if (accessibilityEnabled) "无障碍服务已连接" else "需要启用或重新连接无障碍服务", style = MaterialTheme.typography.bodySmall)
                Text("每次启动需要允许整屏录制；锁屏或关闭无障碍会停止控制。侧载应用若无法开启无障碍，请到应用详情允许受限设置。后台被中断时，请检查系统的电池和后台运行设置。", style = MaterialTheme.typography.bodySmall)
                if (!accessibilityEnabled) {
                    Button(onClick = onOpenAccessibility) { Text("打开系统设置") }
                }
            }
        }

        hostMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("允许局域网访问", style = MaterialTheme.typography.titleSmall)
                Text("当前版本使用 HTTP，请仅在可信网络开启。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = lanEnabled, onCheckedChange = onLanChanged, enabled = !serverRunning && !hostBusy)
        }

        Spacer(Modifier.height(8.dp))
        if (!serverRunning) {
            Button(onClick = onStart, enabled = accessibilityEnabled && !hostBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (hostBusy) "正在准备服务…" else "启动远程服务")
            }
        } else {
            Text("服务正在运行：端口 8765", style = MaterialTheme.typography.bodySmall)
            Text(addresses, style = MaterialTheme.typography.bodySmall)
            pairingCode?.let {
                Text("一次性配对码", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.headlineSmall)
                Text("在同一局域网的浏览器打开本机地址并输入此配对码。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止远程服务") }
        }
    }
}
