package com.miscalebridge.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class S400ParserTest {

    /** Helper: build the 9-byte body `[profile_id(1)] [packed(4)] [timestamp(4)]`. */
    private fun body(profileId: Int, packed: Long, epoch: Long): ByteArray =
        byteArrayOf(profileId.toByte()) + packed.toLeBytes(4) + epoch.toLeBytes(4)

    @Test fun `weight only frame`() {
        // weight = 773 (=77.3 kg), hr = 0 (sentinel), imp = 0
        val m = S400Parser.parse(body(profileId = 2, packed = 773L, epoch = 1700000000L))
        assertNotNull(m)
        assertEquals(77.3, m!!.weightKg, 0.0001)
        assertNull(m.heartRateBpm)
        assertNull(m.impedanceOhm)
        assertEquals(2, m.profileId)
        assertEquals(Instant.ofEpochSecond(1700000000L), m.timestamp)
    }

    @Test fun `weight plus hr plus impedance`() {
        // weight = 773, hr_raw = 25 (=> 75 bpm), imp = 5000 (500 Ω)
        val packed = 773L or (25L shl 11) or (5000L shl 18)
        val m = S400Parser.parse(body(profileId = 1, packed = packed, epoch = 1700000000L))!!
        assertEquals(77.3, m.weightKg, 0.0001)
        assertEquals(75, m.heartRateBpm)
        assertEquals(500.0, m.impedanceOhm!!, 0.0001)
        assertEquals(false, m.isLowFrequency)
        assertEquals(1, m.profileId)
    }

    @Test fun `low-frequency impedance frame`() {
        // weight = 0, hr = 0, imp = 6000 (600 Ω)
        val packed = (6000L shl 18)
        val m = S400Parser.parse(body(profileId = 1, packed = packed, epoch = 1700000000L))!!
        assertEquals(0.0, m.weightKg, 0.0001)
        assertNull(m.heartRateBpm)
        assertNull(m.impedanceOhm)                       // not the regular channel
        assertEquals(600.0, m.impedanceLowOhm!!, 0.0001) // belongs to low channel
        assertEquals(true, m.isLowFrequency)
    }

    @Test fun `real captured frame 71 round-trip`() {
        // From a real device capture: decrypted payload, TLV-wrapped 12 bytes.
        val raw = "166e090205fb0300f125126a".decodeHex()
        val m = S400Parser.parse(raw)!!
        assertEquals(2, m.profileId)
        assertEquals(77.3, m.weightKg, 0.0001)
        assertNull(m.heartRateBpm)             // hr_raw = 127 sentinel
        assertNull(m.impedanceOhm)              // imp_raw = 0
    }

    @Test fun `tlv wrapper is stripped`() {
        // 12-byte TLV-wrapped payload should be stripped to its 9-byte body.
        val wrapped = byteArrayOf(0x16, 0x6e, 0x09) +
            body(profileId = 5, packed = 1000L, epoch = 1700000000L)
        val m = S400Parser.parse(wrapped)!!
        assertEquals(5, m.profileId)
        assertEquals(100.0, m.weightKg, 0.0001)
    }

    private fun String.decodeHex(): ByteArray =
        ByteArray(length / 2) { i ->
            ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }

    @Test fun `bindkey hex parsing`() {
        val k = S400Parser.bindkeyFromHex("0728974d657a4b60964c1b1677f35f7c")
        assertEquals(16, k.size)
        assertEquals(0x07.toByte(), k[0])
        assertEquals(0x7c.toByte(), k[15])
    }

    private fun Long.toLeBytes(n: Int): ByteArray =
        ByteArray(n) { i -> ((this ushr (i * 8)) and 0xFF).toByte() }
}
