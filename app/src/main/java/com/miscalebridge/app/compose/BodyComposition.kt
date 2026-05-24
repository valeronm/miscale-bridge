package com.miscalebridge.app.compose

import android.util.Log
import com.miscalebridge.app.ble.S400Measurement
import com.miscalebridge.app.profile.Sex
import com.miscalebridge.app.profile.UserProfile
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Locale-stable double formatter — always uses '.' as decimal separator. */
private fun Double.f(decimals: Int = 1): String =
    String.format(Locale.US, "%.${decimals}f", this)

/**
 * Derived body-composition metrics computed locally.
 *
 * Formulas ported (Apache 2.0) from
 *   dckiller51/bodymiscale — `custom_components/bodymiscale/metrics/`,
 * specifically its **S400 dual-frequency mode**, which matches Mi Fit output
 * for the S400 considerably more closely than single-frequency equations
 * (Sun, etc.) — typically within ~1 pp on body fat / water / bone / BMR.
 *
 * Impedance naming note (from bodymiscale's docs):
 *   In multi-frequency BIA physics, the **50 kHz** (low-frequency) current
 *   cannot cross cell membranes, so it sees a higher resistance value than the
 *   **250 kHz** (high-frequency) current. The xiaomi-ble project labels the
 *   two BLE fields by *numeric value*, not by frequency — so its
 *   "impedance_low" is actually the **higher**-frequency 250 kHz channel.
 *   We therefore use `max(z1, z2)` as Z_lf and `min(z1, z2)` as Z_hf,
 *   matching bodymiscale's robust guard.
 */

private const val TAG = "BodyComposition"

data class DerivedComposition(
    val bmi: Double,
    val leanBodyMassKg: Double?,           // LBM (foot-to-foot empirical regression)
    val bodyFatPercent: Double?,           // (W − LBM) / W × 100
    val waterPercent: Double?,             // Pace 0.73 of fat-free fraction
    val bodyWaterKg: Double?,
    val boneMassKg: Double?,
    val skeletalMuscleMassKg: Double?,     // Janssen 2000
    val proteinPercent: Double?,           // Wang 1999
    val visceralFatRating: Double?,        // Zepp/Xiaomi empirical rating (1–30)
    val bmrKcal: Double?,                  // Katch-McArdle using actual LBM
    val ecwKg: Double?,                    // S400 dual-freq only
    val icwKg: Double?,
    val bcmKg: Double?,
    val ecwTbwRatioPct: Double?,
)

fun computeDerived(m: S400Measurement, p: UserProfile): DerivedComposition {
    val w = m.weightKg
    val hCm = p.heightCm.toDouble()
    val a = p.age
    val sex = p.sex
    val bmi = if (hCm > 0) w / (hCm / 100.0).let { it * it } else 0.0
    val sexFlag = if (sex == Sex.MALE) 1 else 0

    // Sort the two impedance values so Z_lf > Z_hf regardless of which field
    // each arrived in (xiaomi-ble's labels are inverted vs BIA convention).
    val z1 = m.impedanceOhm ?: 0.0
    val z2 = m.impedanceLowOhm ?: 0.0
    val zLf = if (z1 > 0 && z2 > 0) max(z1, z2) else z1
    val zHf = if (z1 > 0 && z2 > 0) min(z1, z2) else 0.0

    Log.i(TAG, "inputs: W=${w.f(1)} kg, H=${hCm.f(0)} cm, A=$a, sex=$sex, " +
        "z_lf=${zLf.f(1)} Ω, z_hf=${zHf.f(1)} Ω, profile=${p.profileId}")
    Log.i(TAG, "  bmi=${bmi.f(2)}")

    if (w < 20 || w > 250 || hCm < 100 || a <= 0) {
        Log.w(TAG, "  inputs out of range — only BMI returned")
        return empty(bmi)
    }
    if (zLf <= 0) {
        Log.i(TAG, "  no impedance — only BMI/BMR returned")
        val bmrFallback = mifflinStJeor(w, hCm, a, sex)
        Log.i(TAG, "  bmr_mifflin=${bmrFallback.f(0)} kcal/day")
        return empty(bmi).copy(bmrKcal = bmrFallback)
    }

    // LBM — bodymiscale's "all modes" formula tuned to foot-to-foot Xiaomi hardware.
    val lbmRaw = (hCm * 9.058 / 100.0) * (hCm / 100.0) +
        w * 0.32 + 12.226 -
        zLf * 0.0068 - a * 0.0542
    val lbm = min(lbmRaw, w * 0.98).coerceAtLeast(0.0)
    val bf = ((w - lbm) / w * 100.0).coerceIn(5.0, 75.0)
    val waterPct = ((100.0 - bf) * 0.73).coerceIn(35.0, 75.0)
    val waterKg = waterPct * w / 100.0
    val bone = bonemass(lbm, sex)
    val smm = (hCm * hCm / zLf) * 0.401 + sexFlag * 3.825 + a * -0.071 + 5.102
    val proteinPct = (lbm * 0.195 / w) * 100.0
    val visceral = visceralFat(w, hCm, a.toDouble(), sex)
    val bmr = (370.0 + 21.6 * lbm).coerceIn(500.0, 5000.0)

    Log.i(TAG, "  S400: LBM=${lbm.f(2)} kg, " +
        "BF=${bf.f(1)} %, water=${waterPct.f(1)} % (${waterKg.f(1)} kg), " +
        "bone=${bone.f(2)} kg, SMM=${smm.f(2)} kg, protein=${proteinPct.f(1)} %, " +
        "visceral=${visceral.f(1)}, BMR=${bmr.f(0)} kcal/day")

    // Dual-frequency compartments — only if we actually have both channels.
    var ecw: Double? = null
    var icw: Double? = null
    var bcm: Double? = null
    var ecwTbwPct: Double? = null
    if (zHf > 0) {
        val tbw = waterKg
        val zRatio = zHf / zLf
        ecw = tbw * (0.32 + 0.08 * zRatio)
        icw = tbw - ecw
        bcm = icw / 0.73
        ecwTbwPct = ecw / tbw * 100.0
        Log.i(TAG, "  dual-freq: ECW=${ecw.f(2)} L, ICW=${icw.f(2)} L, " +
            "BCM=${bcm.f(2)} kg, ECW/TBW=${ecwTbwPct.f(1)} %")
    }

    return DerivedComposition(
        bmi = bmi,
        leanBodyMassKg = lbm.takeIf { it in (w * 0.40)..(w * 0.95) },
        bodyFatPercent = bf,
        waterPercent = waterPct,
        bodyWaterKg = waterKg,
        boneMassKg = bone,
        skeletalMuscleMassKg = smm.takeIf { it in 5.0..80.0 },
        proteinPercent = proteinPct.takeIf { it in 5.0..40.0 },
        visceralFatRating = visceral,
        bmrKcal = bmr,
        ecwKg = ecw,
        icwKg = icw,
        bcmKg = bcm,
        ecwTbwRatioPct = ecwTbwPct,
    )
}

/** Empirical bone-mass formula (gender-specific base, linear in LBM). */
private fun bonemass(lbm: Double, sex: Sex): Double {
    val base = if (sex == Sex.FEMALE) 0.245691014 else 0.18016894
    var bone = -(base - lbm * 0.05158)   // = lbm·0.05158 − base
    bone += if (bone > 2.2) 0.1 else -0.1
    val cap = if (sex == Sex.FEMALE) 5.1 else 5.2
    if (bone > cap) bone = 8.0
    return bone.coerceIn(0.5, 8.0)
}

/** Zepp Life empirical visceral-fat rating (1–30 scale). */
private fun visceralFat(w: Double, hCm: Double, a: Double, sex: Sex): Double {
    val raw = if (sex == Sex.MALE) {
        if (hCm < w * 1.6 + 63.0) {
            a * 0.15 +
                (w * 305.0) / ((hCm * 0.0826 * hCm - hCm * 0.4) + 48.0) - 2.9
        } else {
            a * 0.15 + (w * (hCm * -0.0015 + 0.765) - hCm * 0.143) - 5.0
        }
    } else {
        if (w <= hCm * 0.5 - 13.0) {
            a * 0.07 + (w * (hCm * -0.0024 + 0.691) - hCm * 0.027) - 10.5
        } else {
            a * 0.07 +
                (w * 500.0) / ((hCm * 1.45 + hCm * 0.1158 * hCm) - 120.0) - 6.0
        }
    }
    return raw.coerceIn(1.0, 50.0)
}

/** Mifflin–St Jeor fallback used only when impedance is unavailable. */
private fun mifflinStJeor(w: Double, hCm: Double, a: Int, sex: Sex): Double {
    val sexAdj = if (sex == Sex.MALE) 5.0 else -161.0
    return 10.0 * w + 6.25 * hCm - 5.0 * a + sexAdj
}

private fun empty(bmi: Double): DerivedComposition = DerivedComposition(
    bmi = bmi, leanBodyMassKg = null, bodyFatPercent = null,
    waterPercent = null, bodyWaterKg = null, boneMassKg = null,
    skeletalMuscleMassKg = null, proteinPercent = null,
    visceralFatRating = null, bmrKcal = null,
    ecwKg = null, icwKg = null, bcmKg = null, ecwTbwRatioPct = null,
)
