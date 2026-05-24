# Working on MiScale Bridge

Project-specific context for development. The user-facing intro lives in
[`README.md`](./README.md); this file is the developer / future-Claude briefing.

---

## What this app does

Listens for **Xiaomi Body Composition Scale S400** (`MJTZC01YM`,
`yunmai.scales.ms103`/`ms104`, BLE product id `0x3BD5`) MiBeacon broadcasts,
decrypts them locally with the user's bindkey, computes body-composition metrics
from the dual-frequency impedance, and writes the result to Android Health
Connect. Personal-use POC, never intended for Play Store distribution.

## Repository layout

```
app/src/main/java/com/miscalebridge/app/
  MiScaleApp.kt              Application; wires DataStore + scanner + HC writer
                             + history. Owns the *one* derivation coroutine
                             that runs computeDerived and exposes latestDerived
                             — see "Single derivation point" below.
  MainActivity.kt            Compose entry.
  ble/
    ScaleScanner.kt          BluetoothLeScanner wrapper, FE95 filter, dedup,
                             status state machine. Tracks recent frame
                             timestamps for the burst detector.
    MiBeaconDecryptor.kt     FE95 frame split + AES-CCM via BouncyCastle.
    S400Parser.kt            obj6e16 payload decoder + bindkeyFromHex().
    S400Measurement.kt       Value type, includes both regular and low-freq
                             impedance fields.
  compose/
    BodyComposition.kt       All derived metrics (LBM, BF, water, bone, SMM,
                             protein, visceral, BMR, ECW/ICW/BCM). Formulas
                             ported from dckiller51/bodymiscale S400 mode.
  health/
    HealthConnectPermissions.kt  Set of 6 HC permissions we declare.
    HealthConnectWriter.kt       buildRecords + insertRecords + dedup set.
  history/MeasurementHistory.kt  In-memory ring (50 entries, lost on restart).
  profile/
    ProfileStore.kt          DataStore-backed UserProfile + autoWrite flag.
    UserProfile.kt           mac, bindkey, heightCm, age, sex, profileId.
  ui/
    MainScreen.kt            Scaffold + NavigationBar (Now/History/Settings)
                             + StatusDot in the TopAppBar.
    Tabs.kt                  enum.
    Format.kt                Locale.US double formatters, relative-time helper.
    screens/NowScreen.kt     Hero card, sections, auto-write toggle, dialog.
    screens/HistoryScreen.kt LazyColumn of past weigh-ins.
    screens/SettingsScreen.kt MAC/bindkey/profile form with masking.
    theme/Theme.kt           Default Material 3 light/dark scheme.
```

The build is single-module; no Compose Navigation, no Hilt/DI, no Room — just
DataStore + StateFlow + plain object construction in `MiScaleApp.onCreate()`.

## BLE protocol cheat sheet

Everything below is community-reverse-engineered (see References). Verified
against our captures; firmware updates could change it.

| Field | Where | Notes |
|---|---|---|
| Service UUID | `0x FE95` | Xiaomi MiBeacon |
| Product ID | bytes [2..3] LE of service data | `0x3BD5` for S400 |
| Frame counter | byte 4 | Used for dedup; advances on state change |
| Frame Control bit 3 | `0x08` of byte 0 | Encrypted flag |
| Frame Control bit 4 | `0x10` of byte 0 | MAC included in frame |
| Frame Control bit 5 | `0x20` of byte 0 | Capability byte present |

**Encrypted frames** (the result-delivery ones) on the S400 omit the in-frame
MAC. The AES-CCM nonce **must** use the actual BLE source address (reversed),
not the in-frame MAC field — see `MiBeaconDecryptor.decrypt(parsed, bindkey,
deviceMacReversed)`. Using the (zero-filled) in-frame MAC produces
`mac check in CCM failed`.

**AES-CCM** parameters:
- key = 16-byte bindkey
- nonce (12 B) = `MAC reversed (6)` ‖ `productId LE (2)` ‖ `frameCounter (1)` ‖ `random ext counter (3, from tail before MIC)`
- AAD = `0x11`
- MIC = trailing 4 bytes

**Inner payload** (after decryption) is a MiBeacon TLV: `[type(2)] [len(1)] [value(len)]`.
For the S400 the type is `0x6e16` and value is 9 bytes (see `S400Parser.kt`
docstring for bit layout — don't restate here).

**Two-frame measurement pattern**:
1. Main encrypted frame: weight + HR + impedance (regular channel)
2. Follow-up encrypted frame ~1 s later: `weight = 0`, `hr = 0`, impedance only
   (low-frequency channel)
Scanner merges (2) into (1) via the `impedanceLowOhm` field instead of
overwriting. See `lastMain` in `ScaleScanner.kt`.

**Idle vs active broadcast rate** — observable from non-encrypted ("idle ping")
frames:
- Idle (scale awake but no one on it): ~1–3 Hz
- Active (someone is being weighed): ~5–12 Hz
- After encrypted result: brief 1–2 s of step-off burst at high rate again

This rate change is what drives the `MEASURING` status — see "Status state
machine" below.

## Status state machine (ScaleScanner)

```
IDLE ──start()──► SEARCHING
                     │ any FE95 from configured MAC
                     ▼
                  READY ◄────────────────────┐
                     │ 5 idle pings in 1 s   │
                     │ (and not in cooldown) │
                     ▼                       │ encrypted frame arrives
                  MEASURING ─────────────────┤
                     │                       │
                     │ 5 s since last burst  │
                     └───────────────────────┘
```

Detected via:
- **Burst**: timestamps of last 5 *non-encrypted* frames within 1000 ms.
  Encrypted frames are deliberately excluded — they themselves arrive in a
  faster burst that would otherwise cause MEASURING to re-fire on the next
  idle ping.
- **Post-result cooldown** (`POST_RESULT_COOLDOWN_MS = 8 s`): suppresses
  burst-triggered MEASURING for 8 s after each encrypted frame. Stepping off
  produces a symmetric load-change burst that would otherwise re-arm MEASURING.
- **Tick coroutine** (1 Hz): demotes MEASURING → READY 5 s after last burst,
  READY → SEARCHING 30 s after last frame.

Encrypted frame → MEASURING ends immediately (result arrived = measurement
over).

The scanner runs `BluetoothLeScanner.SCAN_MODE_LOW_LATENCY` because the
encrypted result is a brief single-burst window and lower scan modes risk
missing it. Battery cost is bounded — the scanner only runs while the
`MainActivity` is foregrounded.

## Body composition

All formulas in `compose/BodyComposition.kt`. **Don't rewrite them blindly** —
the openScale-style "Mi Scale 2" constants we tried first produced LBM ≈ 255 kg
because the multiplicative chain blows up. The current set is ported from
[dckiller51/bodymiscale](https://github.com/dckiller51/bodymiscale)'s S400 mode
(Apache 2.0; attribute in file header). Validated on real S400 data:

| Metric | Sun ref → ours vs Xiaomi Home |
|---|---|
| BMI | 27.5 / 27.5 ✓ |
| Body fat % | 25.4 / 24.6 (Δ +0.8 pp) ✓ |
| Water | 42.3 kg / 41.8 kg ✓ |
| Bone | 2.91 kg / 3 kg ✓ |
| BMR | 1621 / 1634 kcal ✓ |
| SMM | 35.1 / 30.1 (Δ +5 kg — Janssen overshoots foot-to-foot) ✗ |
| Visceral | 13 / 10 (different Zepp generation) ✗ |
| Protein | 11.25 / 12.9 kg (different formula) ✗ |

The three failing metrics are **only shown in the UI**, never written to HC
(see "Health Connect" below). Don't add corrections unless the user explicitly
asks for empirical calibration.

**Impedance naming inversion** — xiaomi-ble's "impedance_low" is actually the
*higher*-frequency 250 kHz channel (BIA physics: lower frequency = higher
impedance, because current can't cross cell membranes at low freq). We
defensively sort: `Z_lf = max(z1, z2)`, `Z_hf = min(z1, z2)`. See
`BodyComposition.kt` top-of-file comment.

## Health Connect

We write **exactly 6 records per weigh-in**:

```
WeightRecord, BodyFatRecord, LeanBodyMassRecord,
BodyWaterMassRecord, BoneMassRecord, BasalMetabolicRateRecord
```

**Principle:** the app only writes what the scale **measured, calculated, or
estimated**. Inputs the user typed in Settings are not data the app is
authoritative for, and don't belong in Health Connect.

Deliberately NOT written:
- `HeightRecord` — height is a profile *input* (typed by the user), not a
  scale measurement. Writing it would mean the app is claiming authorship of
  the user's height value.
- `HeartRateRecord` — scoped out for now. A single instantaneous HR sample
  taken while standing on a scale, with no context (resting? post-exercise?
  stressed?), adds little to a health record. Reconsider if we ever classify
  the sample (e.g. tag as resting when paired with morning weigh-ins).
- SMM / visceral / protein / ECW / ICW / BCM — no HC record types exist.
- BMI — derivable from Weight + Height; no record type.

Dedup is keyed on `(profileId, scaleTimestamp)` in
`HealthConnectWriter.written`. The main + low-freq follow-up frames share a
scale timestamp, so they coalesce into one HC entry. Re-tapping a manual write
also no-ops.

Auto-write toggle:
- OFF → no automatic writes; flow still computes derived for the UI
- Toggle ON with HC perms missing → launches HC permission request; toggle
  flips to ON only after the user grants *all* declared permissions
- Toggle ON with a measurement already on screen → confirmation dialog asks
  before saving that (possibly stale) reading
- Auto-write decision is captured at the moment `MiScaleApp`'s combine fires
  for a *new* (m, p) pair. Toggling alone doesn't re-fire the coroutine —
  that's intentional; the dialog handles "save the current one too".

## Single derivation point

`computeDerived` is **expensive and logs**. It is called from exactly **one
place**: the `appScope.launch { ... }` block in `MiScaleApp.onCreate()`. The
result is published to `latestDerived: StateFlow<Pair<S400Measurement,
DerivedComposition>?>`. The UI subscribes to this flow instead of recomputing.

If you find yourself calling `computeDerived` from a `@Composable`, that's a
regression — push it back into the flow.

The `distinctUntilChangedBy` key in that coroutine is
`(measurement.weightKg + timestamp, impedanceOhm + impedanceLowOhm, profileId)`.
Adding new inputs (e.g. a calibration knob) requires extending the key or the
flow won't re-fire.

## Build & deploy

### Prerequisites

| Tool | Minimum | Why |
|---|---|---|
| **JDK** | 17 | AGP 8.9 requires it. Any distro works (Temurin, Microsoft, Zulu, Corretto). |
| **Android SDK** | `platforms;android-36`, `build-tools;36.0.0` | Health Connect 1.1.0‑rc03 mandates `compileSdk = 36`. |
| **Android SDK** | `platform-tools` | for `adb`. |
| **Gradle** | 8.11.1+ | AGP 8.9.2 requires it. The wrapper auto-fetches; no system Gradle needed if you have a JDK. |
| **Android device** | API 28+ with Health Connect installed | The S400 itself is unsupported on emulators (no real BLE). |
| Bindkey + scale MAC | extracted from a Xiaomi account once | See README for the extraction procedure. |

Standard Android Studio (Koala / 2024.1+) covers all of the above out of the
box. CI builds also work with just JDK 17 + the SDK packages.

### Version coupling

Three deps move together; bumping one in isolation breaks the chain:

- **Health Connect** `connect-client` 1.1.0-rc03 needs `compileSdk = 36`
  and AGP 8.9+. The `Metadata.autoRecorded(device)` factory used in
  `HealthConnectWriter` is also a 1.1.0+ API — earlier versions need the
  long `Metadata(...)` constructor with explicit `recordingMethod`.
- **AGP** 8.9.x needs **Gradle** 8.11.1+.
- **`targetSdk`** is pinned to 36 to match `compileSdk`.

If you upgrade any of these, bump the others in the same change.

### Env

The build needs `ANDROID_HOME` (or `ANDROID_SDK_ROOT`, or `local.properties`'s
`sdk.dir=…`) and a JDK that the wrapper can find via `JAVA_HOME` or the
default shell `java`. Android Studio sets all three automatically. From the
CLI:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`local.properties` is gitignored — never commit it.

### Build commands

```bash
./gradlew :app:assembleDebug                  # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest              # runs the parser tests
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

### Deploy to a phone

USB or wireless ADB, doesn't matter. Once `adb devices` shows the phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.miscalebridge.app
adb shell am start -n com.miscalebridge.app/.MainActivity
```

If multiple devices are attached, target a specific one with
`adb -s <serial-or-ip:port>`.

### Wireless ADB pairing

Android 11+ supports it under *Settings → System → Developer options →
Wireless debugging*. The pairing screen and the main connect screen show two
**different** ports — pairing uses a one-shot port and code, connection uses
the stable port.

```bash
adb pair <ip>:<pair-port> <6-digit-code>   # one-time per host
adb connect <ip>:<connect-port>            # after each reboot / IP change
```

### Watching logs

The app uses three log tags. A filtered logcat covers everything interesting:

```bash
adb logcat -s ScaleScanner:I BodyComposition:I HealthConnectWriter:I AndroidRuntime:E
```

- `ScaleScanner` — state transitions (`status: READY → MEASURING`) and
  measurement decoding (`measurement: S400Measurement(…)`, `merged low-freq imp=…`).
- `BodyComposition` — derivation inputs and outputs (one block per change;
  see *Single derivation point*).
- `HealthConnectWriter` — `writing N record(s) at …` per Health Connect insert
  and `skip duplicate write for …` per dedup.

## Non-goals (deliberately out of scope)

- **Foreground service.** Scanning only runs while `MainActivity` is alive;
  backgrounding the app stops capture. The intended UX is "open app, step on
  scale, watch result" — not unattended capture.
- **Multi-user profiles.** App is single-profile; the scale's `profile_id`
  field is stored on each measurement but not used to route to different
  Health Connect identities or settings.

## What's NOT done yet (open work)

- **Persistent history.** `MeasurementHistory` is in-memory and lost on
  process death. Health Connect is the durable store today; a local Room (or
  DataStore) cache would let the History tab survive app restarts.
- **Calibration to match Mi Fit exactly.** Body fat, water, bone, and BMR
  typically land within ~1 pp of Mi Fit. SMM, visceral, and protein diverge
  because Xiaomi applies proprietary corrections we haven't reproduced. An
  empirical fit over N weigh-ins (per user) would close the gap.
- **Play Store distribution.** Manifest does the minimum for Health Connect
  to function locally; Play Console's Health Apps Declaration form, the
  privacy policy plumbing, and the Data Safety section are all unfilled.
- **Icons.** Launcher icon is the system `ic_menu_compass` placeholder.

## Comments

Add a comment only when it tells the reader something the code itself can't:

- **Intent** — the *why* behind a choice, not the *what* (code shows what).
- **Non-obvious context** — protocol byte layouts, formula attribution,
  cross-references to distant call sites, pitfalls.

Don't add:

- Summaries of the next few lines of code.
- Enumerations of file/class contents — they drift the moment someone adds
  or removes a thing.
- Section banner dividers (`// ----- Hero -----`); let the code structure
  speak.
- Anecdotes about past incidents ("we hit this bug…", "the user reported…").
- KDoc that just restates a function or field name.

When uncertain, delete. The class name, function name, and surrounding code
are usually enough.

## Commits

Audience: future developers (you, six months from now) bisecting history or
hunting regressions, plus tools like `git log`, `git bisect`, and changelog
generators. Same principle as comments: **explain why, not what** — the diff
shows what changed.

A useful commit message carries:

- A **subject in the imperative mood** ("Add", "Fix", "Refactor" — not
  "Added" / "Adding").
- The **motivation** — the problem, constraint, or observation that drove
  this change.
- Any **non-obvious tradeoffs** or decisions made along the way.
- References to **tickets / issues** it resolves, if any.

Leave out:

- Restatements of the diff (the diff is right there).
- Process narratives ("first I tried X then Y then Z").
- Vague subjects ("Updates", "Various fixes", "WIP").

## References

- [Bluetooth-Devices/xiaomi-ble](https://github.com/Bluetooth-Devices/xiaomi-ble)
  — canonical Python MiBeacon + S400 parser
- [dckiller51/bodymiscale](https://github.com/dckiller51/bodymiscale)
  — body-composition formulas (Apache 2.0, ported here)
- [esphome PR #8524](https://github.com/esphome/esphome/pull/8524) by zry98
  — original S400 ESPHome implementation
- [openScale wiki — Xiaomi Bluetooth Mi Scale](https://github.com/oliexdev/openScale/wiki/Xiaomi-Bluetooth-Mi-Scale)
  — protocol background
- [MiBeacon v5 spec (pvvx)](https://github.com/pvvx/ATC_MiThermometer/blob/master/InfoMijiaBLE/Mijia%20BLE%20MiBeacon%20protocol%20v5.md)
- [ble_monitor — Reverse engineering MiBeacon](https://home-is-where-you-hang-your-hack.github.io/ble_monitor/MiBeacon_protocol)
- [Health Connect — Write data](https://developer.android.com/health-and-fitness/health-connect/write-data)

