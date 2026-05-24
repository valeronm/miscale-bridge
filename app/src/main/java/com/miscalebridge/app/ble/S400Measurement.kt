package com.miscalebridge.app.ble

import java.time.Instant

/**
 * One decoded measurement, possibly assembled from two frames: the main
 * reading (weight + HR + regular impedance) plus an optional follow-up
 * "low-frequency impedance" pulse the scale emits as you step off.
 */
data class S400Measurement(
    val weightKg: Double,
    val heartRateBpm: Int?,
    /** Regular-frequency impedance from the main reading. */
    val impedanceOhm: Double?,
    /** Low-frequency impedance from the follow-up frame, if it has arrived. */
    val impedanceLowOhm: Double?,
    /** True when this frame *was* a standalone low-frequency-only emission. */
    val isLowFrequency: Boolean,
    val timestamp: Instant,
    val profileId: Int,
    /** Hex of the raw decrypted inner payload — kept for debugging. */
    val rawHex: String,
)
