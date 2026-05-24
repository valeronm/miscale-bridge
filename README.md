# MiScale Bridge

A personal-use Android app that listens for **Xiaomi Body Composition Scale
S400** (model `MJTZC01YM`, market name `yunmai.scales.ms103`/`ms104`) BLE
broadcasts, computes body composition locally, and writes the result to
**Android Health Connect**.

Everything runs on-device. Nothing is uploaded.

---

## Setup

### 1. Get your scale's bindkey

The S400 encrypts its BLE measurement broadcasts with a 16-byte key that's
generated when you first pair the scale to a Xiaomi account. You need to
extract this key once.

1. Pair the scale in **Mi Home** / **Xiaomi Home** / **Mi Fitness** so the
   cloud generates a bindkey.
2. Run one of the open-source [Xiaomi Cloud token extractors][extractor]
   against your Xiaomi account — every BLE device is listed with its MAC and
   bindkey.
3. Note the 32-hex-char bindkey and the MAC address (`AA:BB:CC:DD:EE:FF`).

Unbinding and rebinding the scale in the Xiaomi app rotates the key.

[extractor]: https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor

### 2. Build and install the app

Open the project in Android Studio (Koala / 2024.1+) and Run, or from the CLI:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK with `platforms;android-36` +
`build-tools;36.0.0`. The Gradle wrapper handles the rest.

### 3. Configure on the phone

1. **Open the app** — it opens to the **Now** tab and shows "Bluetooth
   permission needed" or "No profile yet".
2. **Settings tab** — paste scale MAC, paste bindkey, fill in height / age /
   sex / profile id (the slot you use on the scale, usually `1`), tap **Save**.
3. **Back on Now** — grant the Bluetooth permission when prompted. The
   scanner auto-starts; the top-right dot turns green (`ready`) once it sees
   the scale.
4. **Toggle Auto-write to Health Connect** to **ON**. This opens the Health
   Connect permission screen — enable all six toggles. The auto-write switch
   only stays on if every permission is granted.

That's it. No "Start scan" or "Save" buttons after this — the app is fully
hands-off from now on.

## Daily use

1. **Step on the scale.** The status dot turns blue (`measuring`) and a
   "Measuring…" badge appears in the app.
2. **Wait until the scale displays a final weight** (~15 s).
3. **Step off.** The badge clears, the Now tab updates with weight + BMI +
   body composition, and (with auto-write on) the record is pushed to Health
   Connect.

Open the Health Connect app to see your weight history accumulate. The
**History** tab in MiScale Bridge shows recent weigh-ins for the current
session.

## What gets written to Health Connect

Six records per weigh-in, all tagged with manufacturer "Xiaomi", model
"MJTZC01YM":

- **Weight** (kg)
- **Body Fat** (%)
- **Lean Body Mass** (kg)
- **Body Water Mass** (kg)
- **Bone Mass** (kg)
- **Basal Metabolic Rate** (kcal/day, stored as power)

Records are deduped by (profile id + scale timestamp), so the same weigh-in
can't accidentally be written twice.

## What's shown in the app but NOT written

The Now tab displays more than Health Connect can store. These extras stay in
the app:

- **Skeletal muscle mass** (Janssen 2000)
- **Protein** (Wang 1999)
- **Visceral fat rating** (Zepp empirical)
- **Water compartments** ECW / ICW / BCM / ECW-TBW ratio — derived from the
  dual-frequency impedance signal

Health Connect has no record type for any of these.

## Accuracy

Body fat, water, bone, and BMR typically land within ~1 percentage point of
what the Xiaomi Home app shows for the same weigh-in. Skeletal muscle and
visceral fat differ — Xiaomi applies proprietary corrections that haven't
been publicly reverse-engineered. The formulas are sourced from
[dckiller51/bodymiscale][bms] (Apache 2.0) and run entirely on-device.

[bms]: https://github.com/dckiller51/bodymiscale

## Distribution

Personal **side-load only**. The manifest is configured for the bare minimum
needed for Health Connect to work, but:

- **Google Play** distribution would additionally require the
  [Health Apps Declaration form][declare], a published privacy policy, and a
  filled-in Data Safety section.
- The protocol decoding is community reverse-engineering, not a vendor
  partnership; firmware updates to the S400 could change the payload layout.

[declare]: https://developer.android.com/health-and-fitness/guides/health-connect/publish/declare-access

## Disclaimer

This project is independent community work and is not affiliated with,
endorsed by, or sponsored by Xiaomi Inc. or Huami / Zepp. The Bluetooth
protocol decoding is interoperability research; *Xiaomi*, *Mi*, *Mi Fit*,
*Xiaomi Home*, *MJTZC01YM*, and other product or model names are trademarks
of their respective owners and are used here solely to identify the device
this app interoperates with.

Licensed under [Apache License 2.0](./LICENSE). Body-composition formulas
are ported (Apache 2.0) from
[dckiller51/bodymiscale](https://github.com/dckiller51/bodymiscale).

## For developers

Developer notes — architecture, BLE protocol, build setup, design decisions,
and conventions — live in **[CLAUDE.md](./CLAUDE.md)**.
