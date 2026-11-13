package app.zikrring.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FR = Locale.FRANCE
private val HM = DateTimeFormatter.ofPattern("HH:mm", FR)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", FR)
private val DAY_HM = DateTimeFormatter.ofPattern("d MMM HH:mm", FR)

fun fmtCount(n: Int): String = NumberFormat.getIntegerInstance(FR).format(n)

private fun zoned(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())

fun fmtTime(t: Long): String = zoned(t).format(HM)

/** "18:21–18:27", or with the date when it isn't today. */
fun fmtRange(a: Long, b: Long): String {
    val za = zoned(a)
    val zb = zoned(b)
    val today = LocalDate.now()
    val prefix = if (za.toLocalDate() != today) za.format(DAY_HM) else za.format(HM)
    if (b - a < 60_000) return prefix
    return "$prefix–${zb.format(HM)}"
}

fun fmtDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Aujourd'hui"
    today.minusDays(1) -> "Hier"
    else -> date.format(DAY).replaceFirstChar { it.uppercase(FR) }
}

fun fmtBattery(mv: Int?): String = mv?.let { "${batteryPercent(it)} %" } ?: "–"

/** Rough curve for a small lithium cell; the ring only reports millivolts. */
fun batteryPercent(mv: Int): Int {
    val pts = listOf(3500 to 0, 3600 to 8, 3700 to 25, 3800 to 45, 3900 to 62, 4000 to 78, 4100 to 92, 4180 to 100)
    if (mv <= pts.first().first) return 0
    for (i in 1 until pts.size) {
        val (x1, y1) = pts[i]
        if (mv <= x1) {
            val (x0, y0) = pts[i - 1]
            return y0 + (y1 - y0) * (mv - x0) / (x1 - x0)
        }
    }
    return 100
}

fun plural(n: Int, one: String, many: String) = if (n <= 1) one else many
