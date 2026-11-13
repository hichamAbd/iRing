package app.zikrring.ring

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The iQibla Zikr Ring speaks short ASCII text messages over Bluetooth LE.
 *
 *  - Service D0FF. The ring sends on D002 (notify), the phone writes commands to D004.
 *  - Phone -> ring: "Pnn,<local time yyyyMMddHHmmssSSS>,<args…>"
 *  - Ring -> phone: "Qnn,<fields…>" answers, plus "R01" right after a connection.
 *
 * Everything here was read from recordings of the official app's traffic.
 * The second vendor service (02F00000…FE00) looks like firmware update and is never used.
 */
object RingUuids {
    val SERVICE: UUID = UUID.fromString("0000d0ff-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000d002-0000-1000-8000-00805f9b34fb")
    val COMMAND: UUID = UUID.fromString("0000d004-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val NAME_PREFIX = "Zikr"
}

/** One command for the ring, plus the answer code that confirms it (if any). */
data class RingCommand(val text: String, val expect: String?) {
    val code: String get() = text.substringBefore(',')
}

object Commands {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS", Locale.ROOT)

    fun stamp(t: LocalDateTime): String = t.format(STAMP)

    private fun cmd(code: String, now: LocalDateTime, expect: String?, vararg args: String) =
        RingCommand((listOf(code, stamp(now)) + args).joinToString(","), expect)

    /** Time-zone offset in hours with one decimal, as the official app sends it ("1.0"). */
    fun tzHours(zone: ZoneId, now: LocalDateTime): String {
        val seconds = zone.rules.getOffset(now).totalSeconds
        return String.format(Locale.ROOT, "%.1f", seconds / 3600.0)
    }

    /** Coordinates the way the ring expects them (5 decimals); rounded to ~1 km before that. */
    fun coord(value: Double): String = String.format(Locale.ROOT, "%.5f", Math.round(value * 100) / 100.0)

    /** P01: set the clock. Latitude/longitude are optional (empty when no location is saved). */
    fun setClock(now: LocalDateTime, tz: String, lat: Double?, lon: Double?) =
        cmd("P01", now, "Q01", "0", "1", tz, lat?.let(::coord) ?: "", lon?.let(::coord) ?: "")

    /** P02: battery (millivolts) and firmware. */
    fun info(now: LocalDateTime) = cmd("P02", now, "Q02")

    /** P03: flip the display; the ring answers with the new orientation. */
    fun flipDisplay(now: LocalDateTime) = cmd("P03", now, "Q03")

    /** P04: start (true) or stop (false) the "find my ring" vibration. */
    fun find(now: LocalDateTime, on: Boolean) = cmd("P04", now, "Q04", if (on) "1" else "0")

    /** P06: confirm a saved session (Q06) so the ring can drop it. */
    fun ackSession(now: LocalDateTime, id: String) = cmd("P06", now, null, id)

    /** P07: confirm a count message (Q07). Without these confirmations the ring hangs up after ~10 s. */
    fun ackCount(now: LocalDateTime, id: String) = cmd("P07", now, null, id)

    /** P09: prayer-time calculation from a location. */
    fun prayerLocation(now: LocalDateTime, lat: Double, lon: Double, tz: String) =
        cmd("P09", now, "Q09", "1", "3", "0", coord(lat), coord(lon), tz)

    /** P11: hourly reminder. minutes = 0 turns it off; start/end are "HH:MM". */
    fun reminder(now: LocalDateTime, minutes: Int, start: String, end: String) =
        if (minutes <= 0) cmd("P11", now, "Q11", "0")
        else cmd("P11", now, "Q11", minutes.toString(), "$start#$end")

    /** P12: sync. The ring answers Q12 and then sends any saved sessions as Q06. */
    fun sync(now: LocalDateTime) = cmd("P12", now, "Q12")

    /** P13: status (hourly reminder, prayer times…). */
    fun status(now: LocalDateTime) = cmd("P13", now, "Q13")

    /** P19: start (true) or stop (false) a custom tally that counts up to [target]. */
    fun customTally(now: LocalDateTime, start: Boolean, target: Int) =
        cmd("P19", now, "Q19", if (start) "1" else "0", target.toString(), "1", "2", "0", "0", "0")

    /** P20: confirm a finished custom tally (Q20). */
    fun ackCustomDone(now: LocalDateTime, id: String) = cmd("P20", now, null, id)

    /** P22: how long the screen stays on, in seconds (10–60). */
    fun screenTime(now: LocalDateTime, seconds: Int) = cmd("P22", now, "Q22", seconds.toString())

    /** P23: the five prayer reminders (Fajr…Isha). */
    fun prayerReminders(now: LocalDateTime, prayers: List<PrayerSetting>) =
        cmd("P23", now, "Q23", prayers.joinToString(":") { "${if (it.on) 1 else 0}#${it.before}#${it.after}" })

    /** P24: vibrate every 33, every 100, and every [custom] presses (0 = off). */
    fun milestones(now: LocalDateTime, every33: Boolean, every100: Boolean, custom: Int) =
        cmd("P24", now, "Q24", "${if (every33) 1 else 0}#${if (every100) 1 else 0}#$custom")

    /** P25: settings (screen time, prayer reminders, milestones). */
    fun settings(now: LocalDateTime) = cmd("P25", now, "Q25")
}

data class PrayerSetting(val on: Boolean, val before: String = "+00", val after: String = "+00")

data class HourlyReminder(val minutes: Int, val start: String, val end: String) {
    val on: Boolean get() = minutes > 0
}

/** Everything the ring can tell the phone. */
sealed interface RingEvent {
    data object Hello : RingEvent

    /**
     * Q07: sent on every press. [count] is the ring's running total for [sessionId].
     * [mode]: 0 normal, 2 custom tally running, 3 custom tally stopped, 4 custom target reached;
     * [progress] is the custom tally's progress.
     */
    data class Count(val count: Int, val sessionId: String, val mode: Int, val progress: Int) : RingEvent

    /** Q06: a counting session the ring saved (it ends when the count is reset on the ring). */
    data class SavedSession(val start: String, val end: String, val count: Int, val id: String) : RingEvent

    /** Q20: a custom tally reached its target. */
    data class CustomDone(val target: Int, val start: String, val end: String, val id: String) : RingEvent

    /** Q02: battery in millivolts and firmware version. */
    data class Info(val batteryMv: Int?, val firmware: String?) : RingEvent

    /** Q03: display orientation after a flip. */
    data class Flip(val flipped: Boolean) : RingEvent

    /** Q12: answer to a sync; saved sessions follow as Q06. */
    data class Sync(val pending: Int, val count: Int) : RingEvent

    /** Q13: status. */
    data class Status(val reminder: HourlyReminder?, val prayerTimes: List<String>) : RingEvent

    /**
     * Q25: settings. The ring sometimes cuts this answer short, so every part may be missing:
     * a null prayer means "not reported", and [milestones] may hold fewer than 3 values.
     */
    data class Settings(
        val screenSeconds: Int?,
        val prayers: List<PrayerSetting?>,
        val milestones: List<String>,
    ) : RingEvent

    /** Plain confirmations: Q01, Q04, Q09, Q11, Q19, Q22, Q23, Q24. */
    data class Ack(val code: String) : RingEvent

    data class Unknown(val text: String) : RingEvent
}

object RingParser {
    private val HEADER = Regex("^[QR]\\d{2}(,|$)")

    fun isMessageStart(text: String): Boolean = HEADER.containsMatchIn(text)

    fun parse(text: String): RingEvent {
        val f = text.trim().split(',')
        fun int(i: Int): Int? = f.getOrNull(i)?.trim()?.toIntOrNull()
        return when (f[0]) {
            "R01" -> RingEvent.Hello
            "Q07" -> if (f.size >= 2) RingEvent.Count(
                count = int(1) ?: 0,
                sessionId = f.getOrNull(2) ?: "0",
                mode = int(3) ?: 0,
                progress = int(4) ?: 0,
            ) else RingEvent.Unknown(text)
            "Q06" -> if (f.size >= 5) RingEvent.SavedSession(f[1], f[2], int(3) ?: 0, f[4]) else RingEvent.Unknown(text)
            "Q20" -> if (f.size >= 6) RingEvent.CustomDone(int(1) ?: 0, f[3], f[4], f[5]) else RingEvent.Unknown(text)
            "Q02" -> RingEvent.Info(int(5), f.getOrNull(6)?.takeIf { it.isNotBlank() })
            "Q03" -> RingEvent.Flip(f.getOrNull(1) == "1")
            "Q12" -> RingEvent.Sync(int(1) ?: 0, int(2) ?: 0)
            "Q13" -> parseStatus(f)
            "Q25" -> parseSettings(f)
            "Q01", "Q04", "Q09", "Q11", "Q19", "Q22", "Q23", "Q24" -> RingEvent.Ack(f[0])
            else -> RingEvent.Unknown(text)
        }
    }

    // Q13,<flip?>,<1#time>,<minutes#start#end>,…,<5 prayer times "HH:MM#…">,<?>
    private fun parseStatus(f: List<String>): RingEvent.Status {
        val rem = f.getOrNull(3)?.split('#')
        val reminder = if (rem != null && rem.size >= 3) {
            HourlyReminder(rem[0].toIntOrNull() ?: 0, rem[1], rem[2])
        } else null
        val times = f.getOrNull(10)?.split('#')?.filter { Regex("^\\d{1,2}:\\d{2}$").matches(it) }.orEmpty()
        return RingEvent.Status(reminder, if (times.size >= 5) times.take(5) else emptyList())
    }

    // Q25,<screen seconds>,<on#before#after : ×5>,<33#100#custom>
    private fun parseSettings(f: List<String>): RingEvent.Settings {
        val entries = f.getOrNull(2)?.split(':').orEmpty()
        val prayers = (0 until 5).map { i ->
            val e = entries.getOrNull(i)?.split('#')
            when (e?.getOrNull(0)) {
                "0", "1" -> PrayerSetting(e[0] == "1", e.getOrNull(1)?.ifBlank { null } ?: "+00", e.getOrNull(2)?.ifBlank { null } ?: "+00")
                else -> null
            }
        }
        val milestones = if (f.size >= 4) f[3].split('#').filter { it.isNotBlank() } else emptyList()
        return RingEvent.Settings(f.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }, prayers, milestones)
    }

    /** Ring timestamps are local "yyyyMMddHHmmss". */
    fun parseTime(s: String, zone: ZoneId): Long? {
        if (s.length < 12 || !s.take(12).all { it.isDigit() }) return null
        return try {
            LocalDateTime.of(
                s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(),
                if (s.length >= 14) s.substring(12, 14).toInt() else 0,
            ).atZone(zone).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Rebuilds messages that arrive in pieces. With a small Bluetooth packet size the ring splits long
 * answers into chunks of (MTU − 3) bytes; anything that doesn't start like a new message is glued
 * onto the previous one. A chunk that fills the packet may have more coming, so it's held until
 * [flush] (the caller calls it after a short delay) or until the next message starts.
 */
class Reassembler(private val emit: (String) -> Unit) {
    private val pending = StringBuilder()

    /** Returns true when a partial message is being held and the caller should schedule [flush]. */
    fun accept(bytes: ByteArray, maxChunk: Int): Boolean {
        val text = String(bytes, Charsets.US_ASCII).map { if (it.code in 0x20..0x7e) it else '·' }.joinToString("")
        if (pending.isNotEmpty() && !RingParser.isMessageStart(text)) {
            pending.append(text)
        } else {
            flush()
            pending.append(text)
        }
        return if (bytes.size >= maxChunk) true else {
            flush(); false
        }
    }

    fun flush() {
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending.clear()
        emit(text)
    }
}
