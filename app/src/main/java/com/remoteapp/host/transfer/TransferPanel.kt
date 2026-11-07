package com.remoteapp.host.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferPanel(busy: Boolean, message: String?, onImport: () -> Unit, onExport: (TransferFile) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<TransferFile>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            try { files = withContext(Dispatchers.IO) { PhoneTransfers.store(context).list().files } }
            catch (_: Exception) { error = "无法读取传输区，请检查存储空间。" }
            delay(2_000)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文件互传", style = MaterialTheme.typography.titleSmall)
            Text("选择手机文件放入传输区后，可从网页或 Agent 下载。收到的文件可另存到手机。单个文件最多 100 MiB，总计 512 MiB；移除只删除传输副本。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onImport, enabled = !busy) { Text(if (busy) "正在处理文件…" else "从手机选择文件") }
            (message ?: error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (files.isEmpty()) Text("传输区暂无文件", style = MaterialTheme.typography.bodySmall)
            files.forEach { file ->
                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("${file.size} 字节", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onExport(file) }, enabled = !busy) { Text("保存到手机") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                files = withContext(Dispatchers.IO) {
                                    PhoneTransfers.store(context).apply { remove(file.id) }.list().files
                                }
                            } catch (_: Exception) { error = "移除失败，请刷新后重试。" }
                        }
                    }, enabled = !busy) { Text("移除副本") }
                }
            }
        }
    }
}
