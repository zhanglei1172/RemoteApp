package com.remoteapp.host.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class Credential(val value: String, val apiKey: Boolean)

class PairingManager internal constructor(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(EncryptedSharedPreferences.create(
        context,
        "remoteapp_auth",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ))

    private val random = SecureRandom()
    private val activeTokens = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty())
    }
    private val tickets = ConcurrentHashMap<String, Pair<Credential, Long>>()
    @Volatile private var pairingCode: String? = null
    @Volatile private var pairingExpiresAt = 0L
    private val passwordThrottle = AuthThrottle(prefs, "password_attempt", now)
    private val pairingThrottle = AuthThrottle(prefs, "pairing_attempt", now)

    @Synchronized
    fun createPairingCode(): String {
        val code = (100000 + random.nextInt(900000)).toString()
        pairingCode = code
        pairingExpiresAt = now() + PAIRING_WINDOW_MS
        return code
    }

    fun currentPairingCode(): String? = pairingCode?.takeIf { now() < pairingExpiresAt }

    fun isPasswordConfigured(): Boolean = prefs.contains(KEY_PASSWORD_HASH) && prefs.contains(KEY_PASSWORD_SALT)

    @Synchronized
    fun setPassword(password: String): Boolean {
        if (!isValidPassword(password)) return false
        val salt = ByteArray(16).also(random::nextBytes)
        val saved = prefs.edit()
            .putString(KEY_PASSWORD_SALT, Base64.getEncoder().encodeToString(salt))
            .putString(KEY_PASSWORD_HASH, hashPassword(password, salt))
            .putStringSet(KEY_TOKENS, emptySet())
            .commit()
        if (!saved) return false
        activeTokens.clear()
        tickets.clear()
        passwordThrottle.clear()
        return true
    }

    fun login(password: String): String? = attemptLogin(password).token

    @Synchronized
    internal fun attemptLogin(password: String): AuthenticationResult {
        val remaining = passwordThrottle.remainingSeconds()
        if (remaining > 0) return AuthenticationResult(retryAfterSeconds = remaining, rateLimited = true)
        if (!isPasswordConfigured()) return AuthenticationResult()
        if (!verifyPassword(password)) return passwordThrottle.failed()
        passwordThrottle.clear()
        return AuthenticationResult(token = createToken())
    }

    private fun verifyPassword(password: String): Boolean {
        if (!isValidPassword(password)) return false
        val salt = prefs.getString(KEY_PASSWORD_SALT, null)?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        } ?: return false
        val expected = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        // Upgrade passwords saved by the earlier debug build after successful verification.
        val legacy = !expected.startsWith(PASSWORD_PREFIX)
        val iterations = if (legacy) PASSWORD_ITERATIONS else expected.split(':').getOrNull(1)
            ?.toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: return false
        val actual = if (legacy) Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8))
        ) else hashPassword(password, salt, iterations)
        if (!constantTimeEquals(expected, actual)) return false
        return !(legacy || iterations != PASSWORD_ITERATIONS) || setPassword(password)
    }

    @Synchronized
    fun generateApiKey(): String {
        val key = "ra_" + randomSecret()
        check(prefs.edit().putString(KEY_API_KEY_HASH, hash(key))
            .putString(KEY_API_KEY_VALUE, key).commit()) { "Unable to save API key" }
        return key
    }

    fun hasApiKey(): Boolean = !prefs.getString(KEY_API_KEY_HASH, null).isNullOrBlank()
    fun currentApiKey(): String? = prefs.getString(KEY_API_KEY_VALUE, null)

    fun isApiKeyAuthorized(key: String?): Boolean {
        if (key.isNullOrBlank() || key.length > 128) return false
        val expected = prefs.getString(KEY_API_KEY_HASH, null) ?: return false
        return constantTimeEquals(expected, hash(key))
    }

    fun claim(code: String): String? = attemptClaim(code).token

    @Synchronized
    internal fun attemptClaim(code: String): AuthenticationResult {
        val remaining = pairingThrottle.remainingSeconds()
        if (remaining > 0) return AuthenticationResult(retryAfterSeconds = remaining, rateLimited = true)
        val expected = pairingCode
        if (expected == null || code.length != 6 || !constantTimeEquals(code, expected) || now() >= pairingExpiresAt) {
            return pairingThrottle.failed()
        }
        pairingCode = null
        pairingExpiresAt = 0L
        pairingThrottle.clear()
        return AuthenticationResult(token = createToken())
    }

    fun isAuthorized(token: String?): Boolean =
        !token.isNullOrBlank() && token.length <= 128 && activeTokens.contains(hash(token))

    internal fun credential(authorization: String?, apiKey: String?): Credential? {
        if (isApiKeyAuthorized(apiKey)) return Credential(apiKey!!, true)
        val parts = authorization?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
        if (parts.size != 2) return null
        val value = parts[1]
        if (parts[0].equals("Bearer", true) && isAuthorized(value)) return Credential(value, false)
        if ((parts[0].equals("Bearer", true) || parts[0].equals("ApiKey", true)) && isApiKeyAuthorized(value)) {
            return Credential(value, true)
        }
        return null
    }

    internal fun isValid(credential: Credential): Boolean =
        if (credential.apiKey) isApiKeyAuthorized(credential.value) else isAuthorized(credential.value)

    internal fun issueStreamTicket(credential: Credential): String {
        check(isValid(credential))
        tickets.entries.removeIf { it.value.second <= now() || !isValid(it.value.first) }
        check(tickets.size < 128) { "Too many pending stream tickets" }
        val ticket = randomSecret()
        tickets[ticket] = credential to (now() + 30_000)
        return ticket
    }

    internal fun consumeStreamTicket(ticket: String?): Credential? {
        val entry = ticket?.let(tickets::remove) ?: return null
        return entry.first.takeIf { now() < entry.second && isValid(it) }
    }

    @Synchronized
    fun revoke(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val removed = activeTokens.remove(hash(token))
        persist()
        return removed
    }

    @Synchronized
    fun revokeAll() {
        activeTokens.clear()
        persist()
    }

    private fun persist() = prefs.edit().putStringSet(KEY_TOKENS, activeTokens.toSet()).apply()

    private fun createToken(): String {
        val token = randomSecret()
        activeTokens.add(hash(token))
        persist()
        return token
    }

    private fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    private fun hashPassword(password: String, salt: ByteArray, iterations: Int = PASSWORD_ITERATIONS): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            "$PASSWORD_PREFIX$iterations:" + Base64.getEncoder().encodeToString(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        private const val KEY_TOKENS = "token_hashes"
        private const val KEY_PASSWORD_SALT = "password_salt"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_API_KEY_HASH = "api_key_hash"
        private const val KEY_API_KEY_VALUE = "api_key_value"
        private const val PASSWORD_PREFIX = "pbkdf2-sha256:"
        private const val PASSWORD_ITERATIONS = 100_000
        private const val PAIRING_WINDOW_MS = 5 * 60 * 1000L

        fun isValidPassword(password: String): Boolean =
            password.length in 6..128 && password.any(Char::isLetterOrDigit)
    }
}
