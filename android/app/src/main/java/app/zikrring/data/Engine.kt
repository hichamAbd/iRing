package app.zikrring.data

import app.zikrring.ring.RingEvent
import app.zikrring.ring.RingParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

interface EngineListener {
    fun inboxAdded(p: Pending) {}
    fun counterCompleted(c: Counter) {}
}

/**
 * All counting logic, independent of Bluetooth and screens.
 *
 * - Free counting on the ring becomes [FreeSession]s, closed after [AppSettings.idleMinutes]
 *   without a press, when the ring's count is reset, or by hand; closed sessions wait in the inbox.
 * - A counter "runs" on the ring as a custom tally of its remaining count; progress goes straight
 *   into that counter.
 * - Every ring session id remembers how many presses are already counted, so presses made while
 *   the app wasn't listening are picked up later and nothing is counted twice.
 */
class Engine(initial: AppData, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val state = MutableStateFlow(initial)
    val data: StateFlow<AppData> = state
    var listener: EngineListener? = null

    private val lock = Any()
    private var lastRunEnd = 0L

    private fun edit(block: (AppData, MutableList<() -> Unit>) -> AppData) {
        val effects = mutableListOf<() -> Unit>()
        synchronized(lock) { state.value = block(state.value, effects) }
        effects.forEach { it() }
    }

    private fun newId() = UUID.randomUUID().toString()

    // ---------------------------------------------------------------- queries

    fun day(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone()).toLocalDate()

    fun progress(d: AppData, c: Counter, now: Long): Int {
        val today = day(now)
        return d.entries.asSequence()
            .filter { it.counterId == c.id }
            .filter { if (c.period == Period.DAILY) day(it.end) == today else it.created >= c.resetAt }
            .sumOf { it.amount }
    }

    fun remaining(d: AppData, c: Counter, now: Long): Int = max(0, c.target - progress(d, c, now))

    fun dayTotal(d: AppData, date: LocalDate): Int =
        d.entries.asSequence().filter { day(it.end) == date }.sumOf { it.amount }

    /** Totals for the last [n] days, oldest first. */
    fun lastDays(d: AppData, now: Long, n: Int): List<Pair<LocalDate, Int>> {
        val today = day(now)
        val totals = d.entries.groupBy { day(it.end) }.mapValues { e -> e.value.sumOf { it.amount } }
        return (n - 1 downTo 0).map { today.minusDays(it.toLong()).let { date -> date to (totals[date] ?: 0) } }
    }

    /** Days in a row with something counted, ending today (or yesterday if nothing yet today). */
    fun streak(d: AppData, now: Long): Int {
        val totals = d.entries.groupBy { day(it.end) }.mapValues { e -> e.value.sumOf { it.amount } }
        var date = day(now)
        if ((totals[date] ?: 0) <= 0) date = date.minusDays(1)
        var n = 0
        while ((totals[date] ?: 0) > 0) { n++; date = date.minusDays(1) }
        return n
    }

    /** Counters with the most recently used first, for quick choices. */
    fun countersByUse(d: AppData): List<Counter> = d.counters.sortedByDescending { it.lastUsed }

    // ---------------------------------------------------------------- ring events

    fun onCount(ev: RingEvent.Count, now: Long) = edit { d, fx ->
        when (ev.mode) {
            2, 4 -> onCustomProgress(d, ev, now, fx)
            3 -> if (d.run != null) finishRun(d, now, fx) else d
            else -> onFreePress(d, ev, now, fx)
        }
    }

    fun onSavedSession(ev: RingEvent.SavedSession, now: Long) = edit { d, fx ->
        val sid = ev.id
        val known = d.accounted[sid] ?: 0
        val missed = max(0, ev.count - known)
        var out = d.copy(accounted = account(d.accounted, sid, max(ev.count, known)))
        val end = min(RingParser.parseTime(ev.end, zone()) ?: now, now)
        val f = out.free
        if (f != null && f.ringSessionId == sid) {
            out = out.copy(free = f.copy(toCount = f.toCount + missed, lastPress = max(f.lastPress, end), offline = f.offline || missed > 0))
            out = closeFree(out, fx)
        } else if (missed > 0) {
            val start = if (known == 0) RingParser.parseTime(ev.start, zone()) ?: end else end
            out = addPending(out, Pending(newId(), missed, min(start, end), end, offline = true), fx)
        }
        out
    }

    fun onCustomDone(ev: RingEvent.CustomDone, now: Long) = edit { d, fx ->
        if (ev.id in d.doneIds) return@edit d
        var out = d.copy(doneIds = (d.doneIds + ev.id).takeLast(50))
        val run = out.run
        if (run != null) {
            val delta = max(0, ev.target - run.progress)
            if (delta > 0) out = addToRun(out, run, delta, now, fx)
            out = finishRun(out.copy(run = out.run?.copy(progress = max(run.progress, ev.target))), now, fx)
        } else {
            val start = RingParser.parseTime(ev.start, zone()) ?: now
            val end = RingParser.parseTime(ev.end, zone()) ?: now
            out = addPending(out, Pending(newId(), ev.target, start, end, offline = true, note = "Compteur de la bague terminé"), fx)
        }
        out
    }

    /** Closes the free session if it has been idle long enough. Call regularly. */
    fun tick(now: Long) = edit { d, fx ->
        val f = d.free
        if (f != null && now - f.lastPress > idleMs(d)) closeFree(d, fx) else d
    }

    /** "Terminer la séance". */
    fun endFreeSession() = edit { d, fx -> closeFree(d, fx) }

    private fun idleMs(d: AppData) = d.settings.idleMinutes.coerceIn(1, 120) * 60_000L

    private fun account(map: Map<String, Int>, sid: String, value: Int): Map<String, Int> {
        val m = LinkedHashMap(map)
        m.remove(sid)
        m[sid] = value
        while (m.size > 200) m.remove(m.keys.first())
        return m
    }

    private fun onFreePress(d0: AppData, ev: RingEvent.Count, now: Long, fx: MutableList<() -> Unit>): AppData {
        var d = d0
        // Back to normal counting while a custom tally was running: it ended on the ring
        if (d.run?.confirmed == true) d = finishRun(d, now, fx)
        if (ev.count == 0) return closeFree(d, fx)   // count reset on the ring
        val sid = ev.sessionId
        val known = d.accounted[sid]
        val fresh = if (known == null) ev.count else ev.count - known
        if (fresh <= 0) return d
        d = d.copy(accounted = account(d.accounted, sid, ev.count))
        val f = d.free
        return if (f == null || f.ringSessionId != sid || now - f.lastPress > idleMs(d)) {
            closeFree(d, fx).copy(free = FreeSession(sid, ev.count - fresh, ev.count, now, now, offline = fresh > 1))
        } else {
            d.copy(free = f.copy(toCount = ev.count, lastPress = now, offline = f.offline || fresh > 1))
        }
    }

    private fun closeFree(d: AppData, fx: MutableList<() -> Unit>): AppData {
        val f = d.free ?: return d
        val out = d.copy(free = null)
        if (f.count <= 0) return out
        return addPending(out, Pending(newId(), f.count, f.start, f.lastPress, f.offline), fx)
    }

    private fun addPending(d: AppData, p: Pending, fx: MutableList<() -> Unit>): AppData {
        fx += { listener?.inboxAdded(p) }
        return d.copy(inbox = d.inbox + p)
    }

    // ---------------------------------------------------------------- counters on the ring

    /** Prepares a run; returns the target to send with P19, or null if it can't start. */
    fun beginRun(counterId: String, now: Long): Int? {
        var target: Int? = null
        edit { d, fx ->
            val c = d.counters.find { it.id == counterId } ?: return@edit d
            if (d.run != null) return@edit d
            val rem = remaining(d, c, now)
            if (rem <= 0) return@edit d
            target = rem
            closeFree(d, fx).copy(
                run = Run(counterId, rem, 0, newId(), now, confirmed = false),
                counters = d.counters.map { if (it.id == counterId) it.copy(lastUsed = now) else it },
            )
        }
        return target
    }

    /** The ring confirmed the custom tally (Q19). */
    fun confirmRun() = edit { d, _ -> d.run?.let { d.copy(run = it.copy(confirmed = true)) } ?: d }

    /** The ring never confirmed: forget the run. */
    fun abortRun() = edit { d, _ ->
        val r = d.run
        if (r != null && !r.confirmed && r.progress == 0) d.copy(run = null) else d
    }

    /** Stopped from the app (after the ring confirmed the stop) or otherwise ended. */
    fun endRun(now: Long) = edit { d, fx -> finishRun(d, now, fx) }

    private fun onCustomProgress(d0: AppData, ev: RingEvent.Count, now: Long, fx: MutableList<() -> Unit>): AppData {
        var d = d0
        var run = d.run
        if (run == null) {
            // Mode 4 right after a Q20, or a late message after a run ended: already handled
            if (ev.mode == 4 || now - lastRunEnd < 3_000) return d
            // A custom tally started outside this app: track it, sort it when it ends
            d = closeFree(d, fx)
            run = Run(null, 0, 0, newId(), now, confirmed = true)
        }
        if (!run.confirmed) run = run.copy(confirmed = true)
        if (ev.progress < run.progress) {
            // A new custom tally started on the ring
            d = finishRun(d.copy(run = run), now, fx)
            run = Run(null, 0, 0, newId(), now, confirmed = true)
        }
        val delta = ev.progress - run.progress
        if (delta > 0) d = addToRun(d, run, delta, now, fx)
        d = d.copy(run = run.copy(progress = max(run.progress, ev.progress)))
        if (ev.mode == 4) d = finishRun(d, now, fx)
        return d
    }

    private fun addToRun(d: AppData, run: Run, delta: Int, now: Long, fx: MutableList<() -> Unit>): AppData {
        val cid = run.counterId ?: return d
        return withCompletionCheck(d, cid, now, fx) { dd ->
            val i = dd.entries.indexOfFirst { it.id == run.entryId }
            val entries = if (i >= 0) {
                dd.entries.toMutableList().also { it[i] = it[i].copy(amount = it[i].amount + delta, end = now) }
            } else {
                dd.entries + Entry(run.entryId, cid, delta, run.startedAt, now, Source.RING, created = now)
            }
            dd.copy(entries = entries)
        }
    }

    private fun finishRun(d: AppData, now: Long, fx: MutableList<() -> Unit>): AppData {
        val run = d.run ?: return d
        lastRunEnd = now
        var out = d.copy(run = null)
        if (run.counterId == null && run.progress > 0) {
            out = addPending(out, Pending(newId(), run.progress, run.startedAt, now, offline = false, note = "Compteur de la bague"), fx)
        }
        return out
    }

    private fun withCompletionCheck(
        d: AppData, counterId: String?, now: Long, fx: MutableList<() -> Unit>, change: (AppData) -> AppData,
    ): AppData {
        val c = counterId?.let { id -> d.counters.find { it.id == id } } ?: return change(d)
        val before = progress(d, c, now)
        val out = change(d)
        if (before < c.target && progress(out, c, now) >= c.target) fx += { listener?.counterCompleted(c) }
        return out
    }

    // ---------------------------------------------------------------- inbox and entries

    /** Puts a waiting session into a counter, or into the general tally when [counterId] is null. */
    fun sort(pendingId: String, counterId: String?, now: Long) = edit { d, fx ->
        val p = d.inbox.find { it.id == pendingId } ?: return@edit d
        val out = d.copy(
            inbox = d.inbox.filterNot { it.id == pendingId },
            counters = if (counterId != null) d.counters.map { if (it.id == counterId) it.copy(lastUsed = now) else it } else d.counters,
        )
        withCompletionCheck(out, counterId, now, fx) {
            it.copy(entries = it.entries + Entry(newId(), counterId, p.count, p.start, p.end, Source.SORTED, created = now))
        }
    }

    fun sortAll(counterId: String?, now: Long) {
        data.value.inbox.map { it.id }.forEach { sort(it, counterId, now) }
    }

    fun discard(pendingId: String) = edit { d, _ -> d.copy(inbox = d.inbox.filterNot { it.id == pendingId }) }

    /** Counts done without the ring. */
    fun addManual(counterId: String?, amount: Int, time: Long, now: Long) = edit { d, fx ->
        if (amount == 0) return@edit d
        withCompletionCheck(d, counterId, now, fx) {
            it.copy(entries = it.entries + Entry(newId(), counterId, amount, time, time, Source.MANUAL, created = now))
        }
    }

    fun deleteEntry(id: String) = edit { d, _ ->
        if (d.run?.entryId == id) d else d.copy(entries = d.entries.filterNot { it.id == id })
    }

    fun moveEntry(id: String, counterId: String?, now: Long) = edit { d, fx ->
        if (d.run?.entryId == id) return@edit d
        withCompletionCheck(d, counterId, now, fx) { dd ->
            dd.copy(entries = dd.entries.map { if (it.id == id) it.copy(counterId = counterId, created = now) else it })
        }
    }

    // ---------------------------------------------------------------- counters

    fun addCounter(name: String, target: Int, period: Period, now: Long): String {
        val id = newId()
        edit { d, _ -> d.copy(counters = d.counters + Counter(id, name.trim(), target.coerceIn(1, 99_999), period, now, now, now)) }
        return id
    }

    fun updateCounter(id: String, name: String, target: Int, period: Period) = edit { d, _ ->
        d.copy(counters = d.counters.map {
            if (it.id == id) it.copy(name = name.trim(), target = target.coerceIn(1, 99_999), period = period) else it
        })
    }

    /** Starts a one-off counter over. */
    fun restartCounter(id: String, now: Long) = edit { d, _ ->
        if (d.run?.counterId == id) d else d.copy(counters = d.counters.map { if (it.id == id) it.copy(resetAt = now) else it })
    }

    /** Deletes a counter; its counts stay in the general tally. Not allowed while it runs on the ring. */
    fun deleteCounter(id: String): Boolean {
        var ok = false
        edit { d, _ ->
            if (d.run?.counterId == id) return@edit d
            ok = true
            d.copy(
                counters = d.counters.filterNot { it.id == id },
                entries = d.entries.map { if (it.counterId == id) it.copy(counterId = null) else it },
            )
        }
        return ok
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(change: (AppSettings) -> AppSettings) = edit { d, _ -> d.copy(settings = change(d.settings)) }

    fun setRing(ring: PairedRing?) = edit { d, _ -> d.copy(ring = ring) }
}
