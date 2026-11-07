package com.remoteapp.host

import android.app.admin.DevicePolicyManager
import com.remoteapp.host.platform.ScreenLock
import com.remoteapp.host.transfer.PhoneTransfers
import com.remoteapp.host.transfer.TransferPanel
import android.provider.OpenableColumns
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
    private var lockPermissionRequired by mutableStateOf(false)
    private var captureConsentRequired by mutableStateOf(false)
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

    private var transferBusy by mutableStateOf(false)
    private var transferMessage by mutableStateOf<String?>(null)
    private var pendingExportId: String? = null
    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch {
            transferBusy = true
            var imported = 0
            try {
                withContext(Dispatchers.IO) {
                    val store = PhoneTransfers.store(this@MainActivity)
                    for (uri in uris) {
                        coroutineContext.ensureActive()
                        try {
                            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "file"
                            contentResolver.openInputStream(uri)?.use { input ->
                                val job = coroutineContext
                                store.import(name, input) { job.ensureActive() }
                                imported++
                            }
                        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                        catch (_: Exception) { /* Continue independent selections; report counts below. */ }
                    }
                }
                transferMessage = "已放入传输区 $imported 个文件" + if (imported < uris.size) "，${uris.size - imported} 个失败（请检查大小、权限或空间）。" else "，可从网页下载。"
            } finally { transferBusy = false }
        }
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) lifecycleScope.launch {
            transferBusy = true
            try {
                withContext(Dispatchers.IO) {
                    PhoneTransfers.store(this@MainActivity).open(id).use { input ->
                        val output = contentResolver.openOutputStream(uri) ?: error("No output stream")
                        output.use {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                it.write(buffer, 0, count)
                            }
                        }
                    }
                }
                transferMessage = "已保存到所选位置。"
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { transferMessage = "保存失败，请检查文件是否仍在、目标权限和剩余空间。" }
            finally { transferBusy = false }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var pendingLanEnabled = false
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            RemoteRuntime.startWithProjection(this, pendingLanEnabled, result.resultCode, result.data!!)
        } else RemoteRuntime.updateState(if (RemoteRuntime.server != null) HostPhase.RUNNING else HostPhase.IDLE, "屏幕共享未获授权，请重试。")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.remoteapp.host.platform.PhoneClipboard.windowFocused = hasFocus
    }

    override fun onStop() {
        com.remoteapp.host.platform.PhoneClipboard.windowFocused = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportId = savedInstanceState?.getString("exportId")
        pendingLanEnabled = savedInstanceState?.getBoolean("pendingLan") ?: false
        lanEnabled = savedInstanceState?.getBoolean("lan") ?: false
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                RemoteAppScreen(
                    transferPanel = {
                        TransferPanel(transferBusy, transferMessage,
                            onImport = { importFiles.launch(arrayOf("*/*")) },
                            onExport = { file -> pendingExportId = file.id; exportFile.launch(file.name) },
                        )
                    },
                    accessibilityEnabled = accessibilityEnabled,
                    serverRunning = serverRunning,
                    captureConsentRequired = captureConsentRequired,
                    lockPermissionRequired = lockPermissionRequired,
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
                    onGrantLockPermission = {
                        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ScreenLock.adminComponent(this@MainActivity))
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "仅用于响应远程锁屏请求，不修改密码、不清除数据。Android 8 锁屏后可能需要输入系统密码。")
                        })
                    },
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onLanChanged = { lanEnabled = it },
                    onStart = {
                        if (isAccessibilityEnabled() && !hostBusy && (!serverRunning || captureConsentRequired)) {
                            if (!serverRunning) pairingCode = RemoteRuntime.createPairingCode(this)
                            pendingLanEnabled = if (serverRunning) RemoteRuntime.lanEnabled else lanEnabled
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
        outState.putString("exportId", pendingExportId)
        outState.putBoolean("pendingLan", pendingLanEnabled)
        outState.putBoolean("lan", lanEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        lockPermissionRequired = ScreenLock.permissionRequired(this)
        accessibilityEnabled = isAccessibilityEnabled()
        val state = RemoteRuntime.state.value
        serverRunning = RemoteRuntime.running.value
        captureConsentRequired = !RemoteRuntime.captureManager.hasSession()
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
    transferPanel: @androidx.compose.runtime.Composable () -> Unit,
    accessibilityEnabled: Boolean,
    serverRunning: Boolean,
    captureConsentRequired: Boolean,
    lockPermissionRequired: Boolean,
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
    onGrantLockPermission: () -> Unit,
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
                Text("每次启动需要允许整屏录制；熄屏后保留连接，可从网页唤醒。系统若撤销录屏授权，需重新允许共享；安全锁屏仍需正常解锁，关闭无障碍会停止控制。侧载应用若无法开启无障碍，请到应用详情允许受限设置。后台被中断时，请检查系统的电池和后台运行设置。", style = MaterialTheme.typography.bodySmall)
                if (lockPermissionRequired) {
                    Text("Android 8 远程锁屏需要一次设备管理授权，仅申请锁屏能力；锁屏后可能需输入系统密码。可随时在系统设备管理设置中停用。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onGrantLockPermission) { Text("启用锁屏权限（Android 8）") }
                }
                if (!accessibilityEnabled) {
                    Button(onClick = onOpenAccessibility) { Text("打开系统设置") }
                }
            }
        }

        transferPanel()

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
            if (captureConsentRequired) {
                Button(onClick = onStart, enabled = accessibilityEnabled && !hostBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hostBusy) "正在恢复屏幕共享…" else "重新授权屏幕共享")
                }
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止远程服务") }
        }
    }
}
