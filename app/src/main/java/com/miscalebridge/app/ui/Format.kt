package com.miscalebridge.app.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale-stable double formatter for UI strings. */
fun Double.f(decimals: Int = 1): String =
    String.format(Locale.US, "%.${decimals}f", this)

fun Int.f(): String = String.format(Locale.US, "%d", this)

private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d  HH:mm").withZone(ZoneId.systemDefault())

/** Render a measurement timestamp as "just now" / "5 min ago" / "May 24  12:08". */
fun Instant.relative(now: Instant = Instant.now()): String {
    val delta = Duration.between(this, now)
    val s = delta.seconds
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> DATE_FMT.format(this)
    }
}
