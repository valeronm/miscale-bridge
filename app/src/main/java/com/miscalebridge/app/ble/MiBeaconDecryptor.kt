package com.miscalebridge.app.ble

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * MiBeacon v5 (service UUID 0xFE95) AES-CCM decryptor.
 *
 * Frame layout (encrypted) — bytes are positional offsets into the FE95 service data:
 *   [0..1]  frame control (LE)
 *   [2..3]  product id (LE)
 *   [4]     frame counter
 *   [5..10] MAC reversed                 (present iff FC bit 4)
 *   [11]    capability                   (present iff FC bit 5)
 *   [12..]  I/O capability (2 bytes)     (present iff capability bit 5)
 *   [...]   ciphertext (the inner TLV)
 *   [-7..-4] 3-byte counter extension ("random")
 *   [-4..]   4-byte AES-CCM MIC
 *
 * Nonce = MAC(reversed) ‖ productId(LE) ‖ frameCounter ‖ counterExt
 * AAD   = 0x11
 *
 * References:
 *  - https://home-is-where-you-hang-your-hack.github.io/ble_monitor/MiBeacon_protocol
 *  - https://github.com/pvvx/ATC_MiThermometer/blob/master/InfoMijiaBLE/Mijia%20BLE%20MiBeacon%20protocol%20v5.md
 *  - Bluetooth-Devices/xiaomi-ble (canonical Python implementation)
 */
object MiBeaconDecryptor {

    private const val MIC_LEN = 4
    private const val EXT_CNT_LEN = 3
    private const val AAD: Byte = 0x11

    data class Parsed(
        val productId: Int,
        val frameCounter: Int,
        val macReversed: ByteArray,
        val ciphertextWithMic: ByteArray,
        val encrypted: Boolean,
        val headerEndOffset: Int,
    )

    /** Split a raw FE95 payload into its structural pieces without decrypting. */
    fun parseFrame(serviceData: ByteArray): Parsed? {
        if (serviceData.size < 5) return null
        val fc = (serviceData[0].toInt() and 0xFF) or
            ((serviceData[1].toInt() and 0xFF) shl 8)
        val encrypted = (fc and 0x08) != 0
        val hasMac = (fc and 0x10) != 0
        val hasCap = (fc and 0x20) != 0

        val productId = (serviceData[2].toInt() and 0xFF) or
            ((serviceData[3].toInt() and 0xFF) shl 8)
        val frameCounter = serviceData[4].toInt() and 0xFF

        var off = 5
        val macReversed: ByteArray = if (hasMac) {
            if (serviceData.size < off + 6) return null
            serviceData.copyOfRange(off, off + 6).also { off += 6 }
        } else ByteArray(6)

        if (hasCap) {
            if (serviceData.size < off + 1) return null
            val cap = serviceData[off].toInt() and 0xFF
            off += 1
            if ((cap and 0x20) != 0) off += 2  // I/O capability
        }

        return Parsed(
            productId = productId,
            frameCounter = frameCounter,
            macReversed = macReversed,
            ciphertextWithMic = serviceData.copyOfRange(off, serviceData.size),
            encrypted = encrypted,
            headerEndOffset = off,
        )
    }

    /**
     * Decrypt an encrypted frame. Returns the inner plaintext (typically a TLV
     * object: 2-byte type, 1-byte length, value). Throws if the MIC fails.
     *
     * @param deviceMacReversed the BLE source MAC of the broadcaster, in
     *   reversed byte order. The MiBeacon CCM nonce always uses the actual
     *   device MAC — the in-frame MAC field (when present) is only a sanity
     *   check; encrypted frames typically omit it.
     */
    fun decrypt(parsed: Parsed, bindkey: ByteArray, deviceMacReversed: ByteArray): ByteArray {
        require(parsed.encrypted) { "frame is not encrypted" }
        require(bindkey.size == 16) { "bindkey must be 16 bytes" }
        require(deviceMacReversed.size == 6) { "deviceMacReversed must be 6 bytes" }

        val blob = parsed.ciphertextWithMic
        require(blob.size >= EXT_CNT_LEN + MIC_LEN) { "ciphertext too short for CCM trailer" }

        val extCnt = blob.copyOfRange(blob.size - EXT_CNT_LEN - MIC_LEN, blob.size - MIC_LEN)
        val mic = blob.copyOfRange(blob.size - MIC_LEN, blob.size)
        val ciphertext = blob.copyOfRange(0, blob.size - EXT_CNT_LEN - MIC_LEN)

        val nonce = ByteArray(12).apply {
            System.arraycopy(deviceMacReversed, 0, this, 0, 6)
            this[6] = ((parsed.productId) and 0xFF).toByte()
            this[7] = ((parsed.productId ushr 8) and 0xFF).toByte()
            this[8] = parsed.frameCounter.toByte()
            System.arraycopy(extCnt, 0, this, 9, EXT_CNT_LEN)
        }

        // Bouncy Castle's lightweight CCM API expects [ciphertext || tag].
        val input = ciphertext + mic
        val cipher = CCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(
            /* forEncryption = */ false,
            AEADParameters(KeyParameter(bindkey), MIC_LEN * 8, nonce, byteArrayOf(AAD)),
        )
        val out = ByteArray(cipher.getOutputSize(input.size))
        val len = cipher.processBytes(input, 0, input.size, out, 0)
        val total = len + cipher.doFinal(out, len)
        return out.copyOf(total)
    }
}
