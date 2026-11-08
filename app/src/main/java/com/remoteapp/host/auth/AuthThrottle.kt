package com.remoteapp.host.auth

import android.content.SharedPreferences

/** Accessed only under PairingManager's monitor, across all clients of this phone. */
internal class AuthThrottle(
    private val prefs: SharedPreferences,
    private val prefix: String,
    private val now: () -> Long,
) {
    fun remainingSeconds(): Long {
        val remaining = prefs.getLong("${prefix}_until", 0) - now()
        return if (remaining > 0) (remaining + 999) / 1_000 else 0
    }

    fun failed(): AuthenticationResult {
        val time = now()
        val oldCount = prefs.getInt("${prefix}_failures", 0)
        val resetAt = prefs.getLong("${prefix}_reset", 0)
        val count = (if (time >= resetAt) 1 else oldCount + 1).coerceIn(1, 5)
        val delay = if (count == 5) 300_000L else 1_000L shl (count - 1)
        check(prefs.edit().putInt("${prefix}_failures", count)
            .putLong("${prefix}_until", time + delay)
            .putLong("${prefix}_reset", time + if (count == 5) delay else 15 * 60_000L)
            .commit()) { "Unable to persist authentication cooldown" }
        return AuthenticationResult(retryAfterSeconds = delay / 1_000, rateLimited = count == 5)
    }

    fun clear() {
        check(prefs.edit().remove("${prefix}_failures").remove("${prefix}_until")
            .remove("${prefix}_reset").commit()) { "Unable to clear authentication cooldown" }
    }
}

internal data class AuthenticationResult(
    val token: String? = null,
    val retryAfterSeconds: Long = 0,
    val rateLimited: Boolean = false,
)
