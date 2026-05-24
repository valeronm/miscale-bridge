package com.miscalebridge.app.ble

import java.time.Instant

/**
 * Decodes the S400-specific payload that lives inside a decrypted MiBeacon frame.
 *
 * The S400 product ID is 0x3BD5. The MiBeacon TLV wrapper is `type(2) length(1)`,
 * carrying a 9-byte value with object type 0x6e16. Layout (canonical, from
 * Bluetooth-Devices/xiaomi-ble `obj6e16`):
 *
 *   byte 0     — user profile id (1..N)
 *   bytes 1..4 — 32-bit LE bit-packed metrics word:
 *       bits 0..10  (11 bits) weight in 0.1 kg
 *       bits 11..17 (7 bits)  heart rate; `0 < raw < 127` ⇒ bpm = raw + 50;
 *                             0 and 127 are "not measured" sentinels
 *       bits 18..31 (14 bits) impedance in 0.1 Ω
 *   bytes 5..8 — 32-bit LE UNIX epoch seconds (device-local timestamp;
 *                the scale's RTC is often wrong — don't trust this without
 *                cross-checking the wall clock)
 *
 * The scale emits a sequence as the reading settles:
 *   weight only → +HR → +impedance (regular) → impedance-only (low frequency).
 * We flag `isLowFrequency` when impedance is present but weight is 0.
 */
object S400Parser {
    const val PRODUCT_ID_S400 = 0x3BD5

    fun bindkeyFromHex(hex: String): ByteArray {
        val clean = hex.trim().lowercase().removePrefix("0x")
        require(clean.length == 32) { "bindkey must be 32 hex chars" }
        return ByteArray(16) { i ->
            ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    fun parse(payload: ByteArray): S400Measurement? {
        // Strip TLV wrapper if present: [type(2)] [length(1)] [value(length)].
        val body = if (payload.size >= 12 && payload[2].toInt() and 0xFF == payload.size - 3) {
            payload.copyOfRange(3, payload.size)
        } else payload
        if (body.size < 9) return null

        val profileId = body[0].toInt() and 0xFF

        val packed = (body[1].toInt() and 0xFF).toLong() or
            ((body[2].toInt() and 0xFF).toLong() shl 8) or
            ((body[3].toInt() and 0xFF).toLong() shl 16) or
            ((body[4].toInt() and 0xFF).toLong() shl 24)

        val weightRaw = (packed and 0x7FFL).toInt()
        val hrRaw = ((packed ushr 11) and 0x7FL).toInt()
        val impRaw = ((packed ushr 18) and 0x3FFFL).toInt()

        val weightKg = weightRaw / 10.0
        val heartRate = if (hrRaw in 1..126) hrRaw + 50 else null
        val impedanceOhm = if (impRaw == 0) null else impRaw / 10.0
        val isLowFreq = weightRaw == 0 && impRaw != 0

        val epochLe = (body[5].toInt() and 0xFF).toLong() or
            ((body[6].toInt() and 0xFF).toLong() shl 8) or
            ((body[7].toInt() and 0xFF).toLong() shl 16) or
            ((body[8].toInt() and 0xFF).toLong() shl 24)
        val timestamp = Instant.ofEpochSecond(epochLe)

        return S400Measurement(
            weightKg = weightKg,
            heartRateBpm = heartRate,
            // On the low-freq frame the parsed impedance belongs to the low channel.
            impedanceOhm = if (isLowFreq) null else impedanceOhm,
            impedanceLowOhm = if (isLowFreq) impedanceOhm else null,
            isLowFrequency = isLowFreq,
            timestamp = timestamp,
            profileId = profileId,
            rawHex = body.joinToString("") { "%02x".format(it) },
        )
    }
}
