package com.remoteapp.host.auth

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PairingManagerTest {
    private val prefs = MemoryPreferences()
    private var time = 1_000_000L
    private fun manager() = PairingManager(prefs) { time }

    @Test
    fun passwordFreezePersistsAndBlockedAttemptsDoNotExtendItOrInvalidateCredentials() {
        var auth = manager()
        auth.setPassword("existing-password")
        val session = auth.login("existing-password")!!
        val key = auth.generateApiKey()
        for (seconds in listOf(1L, 2L, 4L, 8L, 300L)) {
            val failure = auth.attemptLogin("incorrect-password")
            assertNull(failure.token)
            assertEquals(seconds, failure.retryAfterSeconds)
            assertEquals(seconds == 300L, failure.rateLimited)
            auth = manager() // Process/service restart cannot clear the failed-attempt count.
            if (seconds != 300L) time += seconds * 1_000
        }
        repeat(20) { assertEquals(300L, auth.attemptLogin("existing-password").retryAfterSeconds) }
        assertTrue(auth.isAuthorized(session))
        assertTrue(auth.isApiKeyAuthorized(key))
        time += 299_001
        assertEquals(1L, auth.attemptLogin("existing-password").retryAfterSeconds)
        time += 999
        assertNotNull(auth.attemptLogin("existing-password").token)
        assertEquals(1L, auth.attemptLogin("incorrect-password").retryAfterSeconds)
    }

    @Test
    fun pairingFreezeCannotBeBypassedByNewCodeAndDoesNotBlockPasswordLogin() {
        val auth = manager()
        auth.setPassword("existing-password")
        auth.createPairingCode()
        for (seconds in listOf(1L, 2L, 4L, 8L, 300L)) {
            assertEquals(seconds, auth.attemptClaim("000000").retryAfterSeconds)
            if (seconds != 300L) time += seconds * 1_000
        }
        val fresh = auth.createPairingCode()
        assertEquals(300L, auth.attemptClaim(fresh).retryAfterSeconds)
        assertNotNull(auth.attemptLogin("existing-password").token)
        time += 300_000
        // The old code expires during the freeze; phone-side generation grants no reset.
        val newCode = auth.createPairingCode()
        assertNotNull(auth.attemptClaim(newCode).token)
        assertNull(auth.attemptClaim(newCode).token)
    }

    @Test
    fun quietWindowAndLocalPasswordReplacementResetOnlyPasswordFailures() {
        val auth = manager()
        auth.setPassword("existing-password")
        assertEquals(1L, auth.attemptLogin("bad").retryAfterSeconds)
        time += 15 * 60_000L
        assertEquals(1L, auth.attemptLogin("bad").retryAfterSeconds)
        auth.attemptClaim("000000")
        assertTrue(auth.setPassword("replacement-password"))
        assertNotNull(auth.attemptLogin("replacement-password").token)
        assertTrue(auth.attemptClaim("000000").rateLimited)
    }

    @Test
    fun parallelPairingGuessesShareOneCooldown() {
        val auth = manager()
        auth.createPairingCode()
        val results = java.util.Collections.synchronizedList(mutableListOf<AuthenticationResult>())
        val workers = List(12) { Thread { results.add(auth.attemptClaim("000000")) } }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        assertEquals(1, results.count { !it.rateLimited })
        assertTrue(results.all { it.token == null && it.retryAfterSeconds == 1L })
        time += 1_000
        assertEquals(2L, auth.attemptClaim("000000").retryAfterSeconds)
    }

    @Test
    fun passwordLoginPersistsAndRejectsIncorrectOrOversizedPasswords() {
        val auth = manager()
        assertNull(auth.login("test-password"))
        assertFalse(auth.setPassword("short"))
        assertTrue(auth.setPassword("test-password"))
        assertNull(auth.login("wrong-password"))
        assertNull(auth.login("test-password")) // Backoff applies even to a correct retry.
        time += 2_000
        assertNotNull(auth.login("test-password"))
        assertNotNull(manager().login("test-password"))
        assertNull(auth.login("x".repeat(129)))
        assertFalse(prefs.all.values.contains("test-password"))
        assertTrue((prefs.all["password_hash"] as String).startsWith("pbkdf2-sha256:100000:"))
    }

    @Test
    fun earlierPasswordFormatsAreUpgradedWithoutChangingApiKey() {
        val salt = ByteArray(16) { it.toByte() }
        val password = "existing-password"
        val auth = manager()
        val key = auth.generateApiKey()
        val legacy = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray())
        )
        val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
        val earlierPbkdf = "pbkdf2-sha256:600000:" + Base64.getEncoder().encodeToString(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        )
        spec.clearPassword()
        for (storedHash in listOf(legacy, earlierPbkdf)) {
            prefs.edit().putString("password_salt", Base64.getEncoder().encodeToString(salt))
                .putString("password_hash", storedHash).commit()
            assertNotNull(auth.login(password))
            assertTrue((prefs.all["password_hash"] as String).startsWith("pbkdf2-sha256:100000:"))
            assertTrue(auth.isApiKeyAuthorized(key))
        }
    }

    @Test
    fun apiKeySurvivesProcessRestartAndTimePassingAndCanBeCopiedAgain() {
        val key = manager().generateApiKey()
        time += 3650L * 24 * 60 * 60 * 1_000
        val restored = manager()
        assertEquals(key, restored.currentApiKey())
        assertTrue(restored.isApiKeyAuthorized(key))
        assertFalse(restored.isApiKeyAuthorized(null))
        assertFalse(restored.isApiKeyAuthorized("invalid"))
    }

    @Test
    fun replacingPasswordRevokesSessionsButPreservesApiKey() {
        val auth = manager()
        val key = auth.generateApiKey()
        assertTrue(auth.setPassword("before-password"))
        val session = auth.login("before-password")!!
        val ticket = auth.issueStreamTicket(Credential(session, false))
        assertTrue(auth.setPassword("after-password"))
        assertFalse(auth.isAuthorized(session))
        assertNull(auth.consumeStreamTicket(ticket))
        assertTrue(auth.isApiKeyAuthorized(key))
        assertNull(auth.login("before-password"))
        time += 2_000
        assertNotNull(auth.login("after-password"))
    }

    @Test
    fun rotatedKeyInvalidatesTicketsAndActiveStreamCredentials() {
        val auth = manager()
        val oldKey = auth.generateApiKey()
        val credential = auth.credential(null, oldKey)!!
        val ticket = auth.issueStreamTicket(credential)
        val newKey = auth.generateApiKey()
        assertNotEquals(oldKey, newKey)
        assertFalse(auth.isValid(credential))
        assertNull(auth.consumeStreamTicket(ticket))
        assertTrue(manager().isApiKeyAuthorized(newKey))
        assertFalse(manager().isApiKeyAuthorized(oldKey))
    }

    @Test
    fun apiKeyHeadersAreExplicitAndSessionsAreNotMistakenForKeys() {
        val auth = manager()
        val key = auth.generateApiKey()
        assertNotNull(auth.credential(null, key))
        assertNotNull(auth.credential("ApiKey $key", null))
        assertNotNull(auth.credential("bearer $key", null))
        assertNull(auth.credential("Basic $key", null))
        assertNull(auth.credential(key, null))
        val session = auth.claim(auth.createPairingCode())!!
        assertNotNull(auth.credential("Bearer $session", null))
        assertNull(auth.credential("ApiKey $session", null))
        auth.revokeAll()
        assertNull(auth.credential("Bearer $session", null))
        assertNotNull(auth.credential(null, key))
    }

    @Test
    fun streamTicketsAreSingleUseAndExpire() {
        val auth = manager()
        val credential = Credential(auth.generateApiKey(), true)
        val first = auth.issueStreamTicket(credential)
        assertEquals(credential, auth.consumeStreamTicket(first))
        assertNull(auth.consumeStreamTicket(first))
        val second = auth.issueStreamTicket(credential)
        time += 30_001
        assertNull(auth.consumeStreamTicket(second))
    }

    @Test
    fun pairingCodeIsSingleUseAndExpires() {
        val auth = manager()
        val code = auth.createPairingCode()
        assertNotNull(auth.claim(code))
        assertNull(auth.claim(code))
        val expired = auth.createPairingCode()
        time += 300_001
        assertNull(auth.claim(expired))
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): Map<String, *> = values.toMap()
    override fun contains(key: String) = values.containsKey(key)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = (values[key] as? Set<String>)?.toSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() { commit() }
    }
}
