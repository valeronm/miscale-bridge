package com.miscalebridge.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Passively scans for Xiaomi MiBeacon (FE95) advertisements, filters to the S400
 * product id, decrypts with the user's bindkey, and exposes the latest parsed
 * measurement as a StateFlow.
 *
 * Dedups by (productId, frameCounter) — the scale broadcasts each measurement
 * many times per second while the value is settling.
 */
class ScaleScanner(private val context: Context) {

    enum class Status {
        IDLE,              // scanner not started
        SEARCHING,         // scanning but no recent frames from our scale
        READY,             // idle pings observed — scale is nearby and awake
        MEASURING,         // encrypted measurement frame just arrived
        ERROR_NO_PERMISSION,
        ERROR_BT_OFF,
    }

    private val _latest = MutableStateFlow<S400Measurement?>(null)
    val latestMeasurement: StateFlow<S400Measurement?> = _latest

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private var adapter: BluetoothAdapter? = null
    private var bindkey: ByteArray? = null
    private var expectedMac: String? = null
    private var lastFrameCounter: Int = -1
    /** Most recent main (weight-bearing) reading, used to attach a follow-up
     *  low-frequency impedance frame to the same weigh-in. */
    private var lastMain: S400Measurement? = null

    /** Wall-clock timestamps used to age status back from MEASURING → READY
     *  and from READY → SEARCHING when frames stop arriving. */
    private var lastFrameAtMs: Long = 0L
    private var lastEncryptedAtMs: Long = 0L
    /** Last time the burst detector saw a fast burst of idle pings (i.e. the
     *  scale was being weighed on). Combined with [lastEncryptedAtMs] to decide
     *  when MEASURING demotes back to READY. */
    private var lastBurstAtMs: Long = 0L
    /** Timestamps of the most recent N frames — used to detect a "fast burst"
     *  of idle pings that signals the scale is being weighed on. Pre-measurement
     *  the scale broadcasts at ~1–3 Hz; while someone stands on it the rate
     *  jumps to ~5–12 Hz, and that rate change is observable several seconds
     *  before the encrypted result frame is emitted. */
    private val recentFrameTimes: ArrayDeque<Long> = ArrayDeque(FAST_BURST_FRAMES)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val raw = result.scanRecord?.getServiceData(FE95_UUID) ?: return
            handleFrame(raw, result.device.address)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _status.value = Status.ERROR_NO_PERMISSION
        }
    }

    @SuppressLint("MissingPermission")
    fun start(bindkey: ByteArray, macAddress: String) {
        if (!hasPermissions()) { _status.value = Status.ERROR_NO_PERMISSION; return }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val a = mgr.adapter ?: run { _status.value = Status.ERROR_BT_OFF; return }
        if (!a.isEnabled) { _status.value = Status.ERROR_BT_OFF; return }
        adapter = a
        this.bindkey = bindkey
        this.expectedMac = macAddress.uppercase()
        this.lastFrameCounter = -1
        this.lastMain = null
        this.lastFrameAtMs = 0L
        this.lastEncryptedAtMs = 0L
        this.lastBurstAtMs = 0L
        recentFrameTimes.clear()

        val filter = ScanFilter.Builder()
            .setServiceData(FE95_UUID, byteArrayOf(), byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        a.bluetoothLeScanner?.startScan(listOf(filter), settings, callback)
        _status.value = Status.SEARCHING
        startTickJob()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasPermissions()) return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        tickJob?.cancel(); tickJob = null
        _status.value = Status.IDLE
    }

    private fun startTickJob() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val sinceFrame = now - lastFrameAtMs
                // MEASURING is only set by the step-on burst; demote it when
                // the burst dies out (user stepped off without a result, e.g.
                // mis-step). Encrypted frames demote MEASURING immediately in
                // handleFrame, so we don't need to consider them here.
                val sinceBurst = now - lastBurstAtMs
                val current = _status.value
                val next = when {
                    current == Status.MEASURING && sinceBurst > MEASURING_DWELL_MS ->
                        if (sinceFrame > READY_DWELL_MS) Status.SEARCHING else Status.READY
                    current == Status.READY && sinceFrame > READY_DWELL_MS ->
                        Status.SEARCHING
                    else -> current
                }
                if (next != current) {
                    Log.i(TAG, "status (tick): $current → $next " +
                        "(sinceFrame=${sinceFrame}ms sinceBurst=${sinceBurst}ms)")
                    _status.value = next
                }
            }
        }
    }

    private fun handleFrame(serviceData: ByteArray, deviceMac: String) {
        // Cheap rejections first — most ads are dupes or idle pings.
        if (expectedMac != null && !deviceMac.equals(expectedMac, ignoreCase = true)) return
        val parsed = MiBeaconDecryptor.parseFrame(serviceData) ?: return
        if (parsed.productId != S400Parser.PRODUCT_ID_S400) return

        // Status: encrypted frames are the result; idle pings carry a usable
        // signal in their *rate* — a fast burst means the scale is being
        // weighed on right now.
        //
        // Burst detection runs only on idle pings. Encrypted frames are
        // intentionally NOT added to the timestamp deque (they burst even
        // faster than a step-on and would otherwise leave a false-positive
        // afterglow that fires on the very next idle ping post-result).
        val now = System.currentTimeMillis()
        lastFrameAtMs = now
        val fastBurst: Boolean
        if (parsed.encrypted) {
            recentFrameTimes.clear()
            fastBurst = false
        } else {
            recentFrameTimes.addLast(now)
            while (recentFrameTimes.size > FAST_BURST_FRAMES) recentFrameTimes.removeFirst()
            fastBurst = recentFrameTimes.size == FAST_BURST_FRAMES &&
                (now - recentFrameTimes.first()) < FAST_BURST_WINDOW_MS
        }

        // Stepping *off* also produces a fast burst (the scale's load-change
        // reaction) for a couple of seconds after the encrypted result. Ignore
        // burst detection for a short cooldown window after each encrypted
        // frame so step-off doesn't spuriously re-enter MEASURING.
        val inPostResultCooldown = lastEncryptedAtMs > 0 &&
            (now - lastEncryptedAtMs) < POST_RESULT_COOLDOWN_MS

        val oldStatus = _status.value
        val newStatus = when {
            // Encrypted frame = the result has arrived. Measurement is over.
            parsed.encrypted -> {
                lastEncryptedAtMs = now
                Status.READY
            }
            // Fast burst of idle pings = scale is actively being weighed on
            // (unless we just finished a measurement, in which case it's
            // probably the step-off load-change burst).
            fastBurst && !inPostResultCooldown -> {
                lastBurstAtMs = now
                Status.MEASURING
            }
            oldStatus != Status.MEASURING -> Status.READY
            else -> oldStatus            // stay MEASURING until tick demotes
        }
        if (newStatus != oldStatus) {
            Log.i(TAG, "status: $oldStatus → $newStatus " +
                "(cnt=${parsed.frameCounter} enc=${parsed.encrypted} burst=$fastBurst)")
            _status.value = newStatus
        }

        if (parsed.frameCounter == lastFrameCounter) return
        if (!parsed.encrypted) return  // S400 only encrypts the measurement frames

        val key = bindkey ?: return
        val macBytes = parseMac(deviceMac) ?: run {
            Log.w(TAG, "unparseable MAC $deviceMac"); return
        }

        val plain = try {
            MiBeaconDecryptor.decrypt(parsed, key, macBytes.reversedArray())
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed (cnt=${parsed.frameCounter}): ${t.message}")
            return
        }
        val measurement = S400Parser.parse(plain) ?: run {
            Log.w(TAG, "parser returned null for ${plain.joinToString("") { "%02x".format(it) }}")
            return
        }
        lastFrameCounter = parsed.frameCounter

        if (measurement.isLowFrequency) {
            // Follow-up frame: attach low-freq impedance to the most recent
            // weight-bearing reading rather than wiping the displayed weight to 0.
            val main = lastMain
            if (main != null) {
                _latest.value = main.copy(impedanceLowOhm = measurement.impedanceLowOhm)
                Log.i(TAG, "merged low-freq imp=${measurement.impedanceLowOhm} into prior")
            }
        } else {
            lastMain = measurement
            _latest.value = measurement
            Log.i(TAG, "measurement: $measurement")
        }
    }

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun parseMac(s: String): ByteArray? {
        val hex = s.replace(":", "").replace("-", "")
        if (hex.length != 12) return null
        return try {
            ByteArray(6) { i ->
                ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
            }
        } catch (_: IllegalArgumentException) { null }
    }

    companion object {
        private const val TAG = "ScaleScanner"
        private val FE95_UUID =
            ParcelUuid(UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb"))

        /** After this much time without an encrypted frame, MEASURING → READY. */
        private const val MEASURING_DWELL_MS = 5_000L
        /** After this much time without any frame, READY → SEARCHING. */
        private const val READY_DWELL_MS = 30_000L
        /** Burst detection: this many frames within FAST_BURST_WINDOW_MS implies
         *  "someone stepped on the scale". Tuned from observed traffic: idle
         *  ~1–3 Hz, active ~5–12 Hz. */
        private const val FAST_BURST_FRAMES = 5
        private const val FAST_BURST_WINDOW_MS = 1000L
        /** After an encrypted result, suppress burst-triggered MEASURING for
         *  this long — stepping off also bursts and would otherwise re-arm. */
        private const val POST_RESULT_COOLDOWN_MS = 8_000L
    }
}
