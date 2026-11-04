package com.remoteapp.host.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.remoteapp.host.domain.ClipboardResponse

/** No background clipboard cache: every read reflects a current platform read. */
object PhoneClipboard {
    const val MAX_TEXT_LENGTH = 65_536
    @Volatile var windowFocused = false

    fun read(context: Context): ClipboardResponse {
        // Android 10+ silently returns null for background reads. Do not report that as empty.
        check(Build.VERSION.SDK_INT < 29 || windowFocused) { "CLIPBOARD_FOREGROUND_REQUIRED" }
        val manager = context.getSystemService(ClipboardManager::class.java)
        val clip = manager.primaryClip ?: return ClipboardResponse("", false)
        val text = if (clip.itemCount > 0) clip.getItemAt(0).text else null
        check(text != null) { "CLIPBOARD_NOT_TEXT" }
        require(text.length <= MAX_TEXT_LENGTH) { "CLIPBOARD_TOO_LARGE" }
        return ClipboardResponse(text.toString(), true)
    }

    fun write(context: Context, text: String) {
        require(text.length <= MAX_TEXT_LENGTH) { "CLIPBOARD_TOO_LARGE" }
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("RemoteApp", text))
    }
}
