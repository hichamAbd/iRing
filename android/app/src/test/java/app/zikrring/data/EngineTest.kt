package app.zikrring.data

import app.zikrring.ring.HourlyReminder
import app.zikrring.ring.PrayerSetting
import app.zikrring.ring.RingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EngineTest {
    private val zone = ZoneId.of("Europe/Paris")
    private lateinit var engine: Engine
    private val inboxed = mutableListOf<Pending>()
    private val completed = mutableListOf<String>()

    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 8) =
        LocalDateTime.of(2026, 11, day, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun press(count: Int, sid: String, t: Long, mode: Int = 0, progress: Int = 0) =
        engine.onCount(RingEvent.Count(count, sid, mode, progress), t)

    private val d get() = engine.data.value

    @Before fun setUp() {
        engine = Engine(AppData()) { zone }
        engine.listener = object : EngineListener {
            override fun inboxAdded(p: Pending) { inboxed += p }
            override fun counterCompleted(c: Counter) { completed += c.name }
        }
    }

    @Test fun freePressesBecomeOneSessionUntilIdle() {
        for (i in 1..5) press(i, "A", at(10, 0, i))
        assertEquals(5, d.free!!.count)
        assertTrue(d.inbox.isEmpty())
        // 6 minutes later: the first session is closed and a new one starts
        press(6, "A", at(10, 6, 30))
        assertEquals(1, d.inbox.size)
        assertEquals(5, d.inbox[0].count)
        assertEquals(1, d.free!!.count)
        engine.tick(at(10, 20))
        assertNull(d.free)
        assertEquals(listOf(5, 1), d.inbox.map { it.count })
        assertEquals(2, inboxed.size)
    }

    @Test fun duplicateCountsAreIgnoredAndResetClosesTheSession() {
        press(1, "A", at(10, 0, 1)); press(2, "A", at(10, 0, 2)); press(2, "A", at(10, 0, 3))
        assertEquals(2, d.free!!.count)
        press(0, "A", at(10, 0, 4))
        assertNull(d.free)
        assertEquals(2, d.inbox.single().count)
    }

    @Test fun firstSightOfARingSessionCountsEverythingAsMissed() {
        press(40, "A", at(9, 0))
        assertEquals(40, d.free!!.count)
        assertTrue(d.free!!.offline)
    }

    @Test fun savedSessionAddsPressesMadeWhileNotListening() {
        for (i in 1..50) press(i, "B", at(11, 0, i))
        engine.onSavedSession(RingEvent.SavedSession("20261108110000", "20261108111500", 66, "B"), at(11, 20))
        assertNull(d.free)
        assertEquals(66, d.inbox.single().count)
        assertTrue(d.inbox.single().offline)
        // The same saved session again changes nothing
        engine.onSavedSession(RingEvent.SavedSession("20261108110000", "20261108111500", 66, "B"), at(11, 21))
        assertEquals(1, d.inbox.size)
    }

    @Test fun savedSessionNeverSeenGoesToTheInbox() {
        engine.onSavedSession(RingEvent.SavedSession("20261108070000", "20261108071000", 12, "C"), at(12, 0))
        val p = d.inbox.single()
        assertEquals(12, p.count)
        assertEquals(at(7, 0), p.start)
        assertEquals(at(7, 10), p.end)
    }

    @Test fun counterRunsOnTheRingAndRemembersWhatsLeft() {
        val id = engine.addCounter("Potatoes", 300, Period.ONCE, at(8, 0))
        for (i in 1..3) press(i, "A", at(9, 0, i))
        assertEquals(300, engine.beginRun(id, at(9, 1)))
        assertEquals(3, d.inbox.single().count)   // the free session was closed first
        engine.confirmRun()
        for (p in 1..120) press(3, "A", at(9, 2, p % 60), mode = 2, progress = p)
        val c = d.counters.single()
        assertEquals(120, engine.progress(d, c, at(9, 5)))
        // Stopped from the app
        engine.endRun(at(9, 5))
        assertNull(d.run)
        assertEquals(180, engine.remaining(d, c, at(9, 6)))
        // Next time it continues with what's left
        assertEquals(180, engine.beginRun(id, at(10, 0)))
        assertEquals(1, d.entries.size)
    }

    @Test fun completionFromCustomDoneIsCountedOnce() {
        val id = engine.addCounter("Tomatoes", 5, Period.ONCE, at(8, 0))
        assertEquals(5, engine.beginRun(id, at(18, 46)))
        engine.confirmRun()
        for (p in 1..4) press(40, "S", at(18, 46, 48), mode = 2, progress = p)
        engine.onCustomDone(RingEvent.CustomDone(5, "20261108184649", "20261108184651", "X1"), at(18, 46, 49))
        press(40, "S", at(18, 46, 49), mode = 4, progress = 5)   // arrives after Q20 in real traffic
        engine.onCustomDone(RingEvent.CustomDone(5, "20261108184649", "20261108184651", "X1"), at(18, 46, 50))
        assertNull(d.run)
        assertEquals(listOf("Tomatoes"), completed)
        assertEquals(5, engine.progress(d, d.counters.single(), at(18, 47)))
        assertTrue(d.inbox.isEmpty())
        assertNull(engine.beginRun(id, at(18, 50)))   // already done
        // Back to free counting on the same ring session
        press(41, "S", at(18, 47, 10))
        assertEquals(41, d.free!!.count)   // first sight of session S in free mode: all 41 presses
    }

    @Test fun dailyCounterStartsAgainEachDay() {
        val id = engine.addCounter("Salawat", 100, Period.DAILY, at(8, 0))
        engine.addManual(id, 100, at(9, 0), at(9, 0))
        val c = d.counters.single()
        assertEquals(0, engine.remaining(d, c, at(20, 0)))
        assertEquals(100, engine.remaining(d, c, at(8, 0, day = 9)))
        assertEquals(listOf("Salawat"), completed)
    }

    @Test fun sortingIntoCountersAndTheGeneralTally() {
        val id = engine.addCounter("Istighfar", 300, Period.ONCE, at(8, 0))
        for (i in 1..30) press(i, "A", at(10, 0, i))
        engine.endFreeSession()
        for (i in 31..40) press(i, "A", at(10, 30, i % 60))
        engine.endFreeSession()
        for (i in 41..45) press(i, "A", at(11, 0, i % 60))
        engine.endFreeSession()
        val (a, b, c) = d.inbox
        engine.sort(a.id, id, at(12, 0))
        engine.sort(b.id, null, at(12, 0))
        engine.discard(c.id)
        assertTrue(d.inbox.isEmpty())
        assertEquals(30, engine.progress(d, d.counters.single(), at(12, 0)))
        assertEquals(40, engine.dayTotal(d, engine.day(at(12, 0))))
    }

    @Test fun sessionSortedLaterStillCountsForAOneOffCounterCreatedAfterIt() {
        for (i in 1..20) press(i, "A", at(7, 0, i))
        engine.endFreeSession()
        val id = engine.addCounter("Goal", 50, Period.ONCE, at(9, 0))
        engine.sort(d.inbox.single().id, id, at(9, 1))
        assertEquals(20, engine.progress(d, d.counters.single(), at(9, 2)))
    }

    @Test fun customTallyStartedElsewhereEndsInTheInbox() {
        for (p in 1..7) press(10, "A", at(13, 0, p), mode = 2, progress = p)
        press(10, "A", at(13, 0, 9), mode = 3, progress = 0)
        assertEquals(7, d.inbox.single().count)
        assertNull(d.run)
    }

    @Test fun deletingACounterKeepsItsCountsInTheTotal() {
        val id = engine.addCounter("X", 10, Period.ONCE, at(8, 0))
        engine.addManual(id, 4, at(9, 0), at(9, 0))
        assertTrue(engine.deleteCounter(id))
        assertEquals(4, engine.dayTotal(d, engine.day(at(9, 0))))
        assertNull(d.entries.single().counterId)
    }

    @Test fun streakCountsDaysInARow() {
        engine.addManual(null, 10, at(9, 0, day = 6), at(9, 0, day = 6))
        engine.addManual(null, 10, at(9, 0, day = 7), at(9, 0, day = 7))
        assertEquals(2, engine.streak(d, at(8, 0, day = 8)))   // nothing yet today: still 2
        engine.addManual(null, 1, at(9, 0, day = 8), at(9, 0, day = 8))
        assertEquals(3, engine.streak(d, at(10, 0, day = 8)))
    }

    @Test fun jsonRoundTrip() {
        val id = engine.addCounter("Astaghfirullah", 300, Period.DAILY, at(8, 0))
        for (i in 1..3) press(i, "A", at(10, 0, i))
        engine.endFreeSession()
        press(4, "A", at(10, 1))
        assertNotNull(engine.beginRun(id, at(10, 2)))
        engine.updateSettings {
            it.copy(lat = 10.12, lon = -20.46, prayers = List(5) { i -> PrayerSetting(i % 2 == 0) },
                reminderSchedule = HourlyReminder(120, "02:00", "10:00"))
        }
        engine.setRing(PairedRing("34:DD:7E:00:00:00", "Zikr Ring"))
        engine.addManual(null, 7, at(11, 0), at(11, 0))
        val back = DataJson.decode(DataJson.encode(d))
        assertEquals(d, back)
    }
}
