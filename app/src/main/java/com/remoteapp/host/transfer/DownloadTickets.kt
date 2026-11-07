package com.remoteapp.host.transfer

import com.remoteapp.host.auth.Credential
import java.security.SecureRandom
import java.util.Base64

internal data class DownloadGrant(val fileId: String, val credential: Credential, val expiresAt: Long)

internal class DownloadTickets(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = mutableMapOf<String, DownloadGrant>()
    @Synchronized fun issue(fileId: String, credential: Credential): String {
        entries.entries.removeAll { it.value.expiresAt <= now() }
        if (entries.size >= 128) throw TransferException("TRANSFER_BUSY")
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        entries[token] = DownloadGrant(fileId, credential, now() + 30_000)
        return token
    }
    @Synchronized fun consume(token: String): DownloadGrant? = entries.remove(token)?.takeIf { it.expiresAt > now() }
}
