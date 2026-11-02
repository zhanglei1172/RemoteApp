package com.remoteapp.host.domain

object InputValidation {
    fun isNormalized(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f

    fun durationMs(value: Long): Long = value.coerceIn(80, 10_000)
}
