package app.zikrring.data

import app.zikrring.ring.HourlyReminder
import app.zikrring.ring.PrayerSetting

enum class Period { ONCE, DAILY }

/** Where a count came from. */
enum class Source { RING, SORTED, MANUAL }

/**
 * A named goal, e.g. "Astaghfirullah, 300". DAILY goals start again every day; ONCE goals count
 * everything added since [resetAt] until they're done.
 */
data class Counter(
    val id: String,
    val name: String,
    val target: Int,
    val period: Period,
    val createdAt: Long,
    val resetAt: Long,
    val lastUsed: Long,
)

/**
 * Counts that are in the tally. [counterId] null means the general tally (no named counter).
 * [end] decides which day it belongs to; [created] is when it was added to the app.
 */
data class Entry(
    val id: String,
    val counterId: String?,
    val amount: Int,
    val start: Long,
    val end: Long,
    val source: Source,
    val created: Long,
)

/** A burst of free counting waiting to be sorted into a counter, the general tally, or ignored. */
data class Pending(
    val id: String,
    val count: Int,
    val start: Long,
    val end: Long,
    /** Some of these presses happened while the app wasn't listening. */
    val offline: Boolean,
    val note: String? = null,
)

/** A counter running on the ring as a custom tally. [counterId] null: started outside this app. */
data class Run(
    val counterId: String?,
    val target: Int,
    val progress: Int,
    val entryId: String,
    val startedAt: Long,
    val confirmed: Boolean,
)

/** The free counting in progress: ring presses [fromCount]+1 … [toCount] of ring session [ringSessionId]. */
data class FreeSession(
    val ringSessionId: String,
    val fromCount: Int,
    val toCount: Int,
    val start: Long,
    val lastPress: Long,
    val offline: Boolean,
) {
    val count: Int get() = toCount - fromCount
}

data class PairedRing(val address: String, val name: String)

data class AppSettings(
    val keepConnected: Boolean = true,
    /** A free session ends after this many minutes without a press. */
    val idleMinutes: Int = 5,
    /** Optional location for prayer times; stays on the phone and goes only to the ring. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Prayer reminders last saved from the app; re-sent on every connection like the official app. */
    val prayers: List<PrayerSetting>? = null,
    /** The last schedule used when the hourly reminder was on, to restore it when switched back on. */
    val reminderSchedule: HourlyReminder = HourlyReminder(60, "08:00", "20:00"),
)

data class AppData(
    val counters: List<Counter> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val inbox: List<Pending> = emptyList(),
    val run: Run? = null,
    val free: FreeSession? = null,
    /** Ring session id → how many of its presses are already counted (newest last). */
    val accounted: Map<String, Int> = emptyMap(),
    /** Finished custom tallies already handled (ids from Q20). */
    val doneIds: List<String> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val ring: PairedRing? = null,
)
