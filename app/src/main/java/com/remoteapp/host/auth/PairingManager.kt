package com.remoteapp.host.auth

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class PairingManager(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "remoteapp_auth",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val random = SecureRandom()
    private val activeTokens = ConcurrentHashMap<String, String>()
    @Volatile private var pairingCode: String? = null
    @Volatile private var pairingExpiresAt: Long = 0L

    fun createPairingCode(): String {
        val code = (100000 + random.nextInt(900000)).toString()
        pairingCode = code
        pairingExpiresAt = System.currentTimeMillis() + PAIRING_WINDOW_MS
        return code
    }

    fun currentPairingCode(): String? = pairingCode?.takeIf { System.currentTimeMillis() < pairingExpiresAt }

    @Synchronized
    fun claim(code: String): String? {
        if (code != pairingCode || System.currentTimeMillis() >= pairingExpiresAt) return null
        pairingCode = null
        pairingExpiresAt = 0L
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.encodeToString(tokenBytes, Base64.NO_WRAP or Base64.URL_SAFE)
        activeTokens[hash(token)] = "paired"
        persist()
        return token
    }

    fun isAuthorized(token: String?): Boolean = token?.let { activeTokens.containsKey(hash(it)) } == true

    fun revoke(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val removed = activeTokens.remove(hash(token)) != null
        persist()
        return removed
    }

    fun revokeAll() {
        activeTokens.clear()
        persist()
    }

    private fun persist() = prefs.edit().putStringSet(KEY_TOKENS, activeTokens.keys.toSet()).apply()

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_TOKENS = "token_hashes"
        private const val PAIRING_WINDOW_MS = 5 * 60 * 1000L
    }

    init {
        prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty().forEach { activeTokens[it] = "persisted" }
    }
}
