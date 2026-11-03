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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.remoteapp.host.accessibility.RemoteAccessibilityService
import com.remoteapp.host.server.RemoteRuntime

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
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
            LaunchedEffect(Unit) {
                RemoteRuntime.running.collect { refreshState() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        accessibilityEnabled = isAccessibilityEnabled()
        serverRunning = RemoteRuntime.server != null
        if (serverRunning) lanEnabled = RemoteRuntime.lanEnabled
        passwordConfigured = RemoteRuntime.isPasswordConfigured(this)
        pairingCode = RemoteRuntime.pairingManager.currentPairingCode()
        apiKeyConfigured = RemoteRuntime.hasApiKey(this)
        apiKey = RemoteRuntime.currentApiKey(this)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGenerateApiKey, enabled = !generatingApiKey, modifier = Modifier.weight(1f)) {
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
                Text(if (accessibilityEnabled) "无障碍服务已启用" else "需要启用无障碍服务", style = MaterialTheme.typography.bodySmall)
                if (!accessibilityEnabled) {
                    Button(onClick = onOpenAccessibility) { Text("打开系统设置") }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("允许局域网访问", style = MaterialTheme.typography.titleSmall)
                Text("当前版本使用 HTTP，请仅在可信网络开启。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = lanEnabled, onCheckedChange = onLanChanged, enabled = !serverRunning)
        }

        Spacer(Modifier.height(8.dp))
        if (!serverRunning) {
            Button(onClick = onStart, enabled = accessibilityEnabled, modifier = Modifier.fillMaxWidth()) {
                Text("启动远程服务")
            }
        } else {
            Text("服务正在运行：端口 8765", style = MaterialTheme.typography.bodySmall)
            pairingCode?.let {
                Text("一次性配对码", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.headlineSmall)
                Text("在同一局域网的浏览器打开本机地址并输入此配对码。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止远程服务") }
        }
    }
}
