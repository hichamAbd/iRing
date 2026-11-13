package app.zikrring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Messages below are copied from real ring traffic (locations replaced). */
class ProtocolTest {
    private val now = LocalDateTime.of(2026, 11, 8, 18, 46, 31, 793_000_000)

    @Test fun commandsMatchTheOfficialApp() {
        assertEquals("P02,20261108184631793", Commands.info(now).text)
        assertEquals("P07,20261108184631793,525006", Commands.ackCount(now, "525006").text)
        assertEquals("P19,20261108184631793,1,5,1,2,0,0,0", Commands.customTally(now, true, 5).text)
        assertEquals("P19,20261108184631793,0,7,1,2,0,0,0", Commands.customTally(now, false, 7).text)
        assertEquals("P11,20261108184631793,120,02:00#10:00", Commands.reminder(now, 120, "02:00", "10:00").text)
        assertEquals("P11,20261108184631793,0", Commands.reminder(now, 0, "", "").text)
        assertEquals("P22,20261108184631793,30", Commands.screenTime(now, 30).text)
        assertEquals("P24,20261108184631793,1#1#90", Commands.milestones(now, true, true, 90).text)
        assertEquals(
            "P23,20261108184631793,1#+00#+00:0#+00#+00:1#+00#+00:1#+00#+00:1#+00#+00",
            Commands.prayerReminders(now, listOf(true, false, true, true, true).map { PrayerSetting(it) }).text,
        )
        assertEquals("P04,20261108184631793,1", Commands.find(now, true).text)
        assertEquals("Q19", Commands.customTally(now, true, 5).expect)
        assertNull(Commands.ackCount(now, "1").expect)
    }

    @Test fun clockAndLocation() {
        assertEquals("P01,20261108184631793,0,1,1.0,,", Commands.setClock(now, "1.0", null, null).text)
        assertEquals("P01,20261108184631793,0,1,1.0,10.12000,-20.46000", Commands.setClock(now, "1.0", 10.123, -20.456).text)
        assertEquals("P09,20261108184631793,1,3,0,10.12000,20.46000,1.0", Commands.prayerLocation(now, 10.1234, 20.4567, "1.0").text)
        assertEquals("1.0", Commands.tzHours(ZoneId.of("Europe/Paris"), now))
        assertEquals("5.5", Commands.tzHours(ZoneId.of("Asia/Kolkata"), now))
        assertEquals("-5.0", Commands.tzHours(ZoneId.of("America/New_York"), now))
    }

    @Test fun counts() {
        assertEquals(RingEvent.Count(37, "525006", 0, 0), RingParser.parse("Q07,0037,525006,0,0"))
        assertEquals(RingEvent.Count(40, "525006", 2, 3), RingParser.parse("Q07,0040,525006,2,3"))
        assertEquals(RingEvent.Count(40, "525006", 4, 5), RingParser.parse("Q07,0040,525006,4,5"))
        assertEquals(RingEvent.Count(1, "0", 0, 0), RingParser.parse("Q07,0001,0,0,0"))
        assertEquals(RingEvent.Hello, RingParser.parse("R01"))
    }

    @Test fun sessionsAndCustomDone() {
        assertEquals(
            RingEvent.SavedSession("20261108182100", "20261108182705", 66, "161416"),
            RingParser.parse("Q06,20261108182100,20261108182705,0066,161416,0"),
        )
        assertEquals(
            RingEvent.CustomDone(5, "20261108184649", "20261108184651", "1710558"),
            RingParser.parse("Q20,5,1,20261108184649,20261108184651,1710558"),
        )
        assertEquals(RingEvent.Sync(0, 36), RingParser.parse("Q12,0,36,0"))
    }

    @Test fun info() {
        assertEquals(RingEvent.Info(3861, "0.18.2"), RingParser.parse("Q02,0,3,307,2023/11/25 11:23,3861,0.18.2"))
        assertEquals(RingEvent.Flip(true), RingParser.parse("Q03,1"))
        assertEquals(RingEvent.Flip(false), RingParser.parse("Q03,0"))
        assertEquals(RingEvent.Ack("Q19"), RingParser.parse("Q19"))
    }

    @Test fun status() {
        val s = RingParser.parse("Q13,0,1#20261108184617,120#02:00#10:00,0,0,1,0,5,1,05:55#12:31#15:01#17:25#19:01#,231101") as RingEvent.Status
        assertEquals(HourlyReminder(120, "02:00", "10:00"), s.reminder)
        assertEquals(listOf("05:55", "12:31", "15:01", "17:25", "19:01"), s.prayerTimes)
        val off = RingParser.parse("Q13,0,1#20261108184723,0#0#0,0,0,1,0,5,1,05:55#12:31#15:01#17:25#19:01#,231101") as RingEvent.Status
        assertFalse(off.reminder!!.on)
    }

    @Test fun settingsFullAndCutShort() {
        val full = RingParser.parse("Q25,10,1#+00#+00:0#+00#+00:1#+00#+00:1#+00#+00:1#+00#+00,1#1#90") as RingEvent.Settings
        assertEquals(10, full.screenSeconds)
        assertEquals(listOf(true, false, true, true, true), full.prayers.map { it!!.on })
        assertEquals(listOf("1", "1", "90"), full.milestones)

        // The ring sometimes stops here
        val cut = RingParser.parse("Q25,10,0#+00#") as RingEvent.Settings
        assertEquals(10, cut.screenSeconds)
        assertEquals(false, cut.prayers[0]!!.on)
        assertTrue(cut.prayers.drop(1).all { it == null })
        assertTrue(cut.milestones.isEmpty())
    }

    @Test fun ringTimes() {
        val zone = ZoneId.of("Europe/Paris")
        val t = RingParser.parseTime("20261108182705", zone)!!
        assertEquals(LocalDateTime.of(2026, 11, 8, 18, 27, 5).atZone(zone).toInstant().toEpochMilli(), t)
        assertNull(RingParser.parseTime("garbage", zone))
    }

    @Test fun reassemblesTwentyBytePieces() {
        val out = mutableListOf<String>()
        val r = Reassembler { out += it }
        val msg = "Q13,0,1#20261108184617,120#02:00#10:00,0,0,1,0,5,1,05:55#12:31#15:01#17:25#19:01#,231101"
        val bytes = msg.toByteArray()
        var held = false
        for (i in bytes.indices step 20) held = r.accept(bytes.copyOfRange(i, minOf(i + 20, bytes.size)), 20)
        if (held) r.flush()
        assertEquals(listOf(msg), out)

        out.clear()
        assertFalse(r.accept("Q07,0001,5,0,0".toByteArray(), 20))
        assertFalse(r.accept("R01".toByteArray(), 20))
        assertEquals(listOf("Q07,0001,5,0,0", "R01"), out)

        // With a large packet size every message is whole
        out.clear()
        assertFalse(r.accept(msg.toByteArray(), 244))
        assertEquals(listOf(msg), out)
    }
}
