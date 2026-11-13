package app.zikrring.data

import app.zikrring.ring.HourlyReminder
import app.zikrring.ring.PrayerSetting
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves everything as one JSON file in the app's private storage. */
class Storage(private val file: File) {
    fun load(): AppData = try {
        if (file.exists()) DataJson.decode(file.readText()) else AppData()
    } catch (e: Exception) {
        // Keep a copy of a file that can't be read instead of losing it
        runCatching { file.copyTo(File(file.parentFile, file.name + ".broken-" + System.currentTimeMillis()), overwrite = false) }
        AppData()
    }

    @Synchronized
    fun save(d: AppData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(DataJson.encode(d))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

object DataJson {
    private const val VERSION = 1

    fun encode(d: AppData): String = JSONObject().apply {
        put("version", VERSION)
        put("counters", JSONArray(d.counters.map {
            JSONObject().put("id", it.id).put("name", it.name).put("target", it.target).put("period", it.period.name)
                .put("createdAt", it.createdAt).put("resetAt", it.resetAt).put("lastUsed", it.lastUsed)
        }))
        put("entries", JSONArray(d.entries.map {
            JSONObject().put("id", it.id).put("counterId", it.counterId ?: JSONObject.NULL).put("amount", it.amount)
                .put("start", it.start).put("end", it.end).put("source", it.source.name).put("created", it.created)
        }))
        put("inbox", JSONArray(d.inbox.map {
            JSONObject().put("id", it.id).put("count", it.count).put("start", it.start).put("end", it.end)
                .put("offline", it.offline).put("note", it.note ?: JSONObject.NULL)
        }))
        d.run?.let {
            put("run", JSONObject().put("counterId", it.counterId ?: JSONObject.NULL).put("target", it.target)
                .put("progress", it.progress).put("entryId", it.entryId).put("startedAt", it.startedAt).put("confirmed", it.confirmed))
        }
        d.free?.let {
            put("free", JSONObject().put("ringSessionId", it.ringSessionId).put("fromCount", it.fromCount).put("toCount", it.toCount)
                .put("start", it.start).put("lastPress", it.lastPress).put("offline", it.offline))
        }
        put("accounted", JSONArray(d.accounted.map { (k, v) -> JSONArray().put(k).put(v) }))
        put("doneIds", JSONArray(d.doneIds))
        val s = d.settings
        put("settings", JSONObject().apply {
            put("keepConnected", s.keepConnected)
            put("idleMinutes", s.idleMinutes)
            s.lat?.let { put("lat", it) }
            s.lon?.let { put("lon", it) }
            s.prayers?.let { list ->
                put("prayers", JSONArray(list.map { JSONObject().put("on", it.on).put("before", it.before).put("after", it.after) }))
            }
            put("reminder", JSONObject().put("minutes", s.reminderSchedule.minutes)
                .put("start", s.reminderSchedule.start).put("end", s.reminderSchedule.end))
        })
        d.ring?.let { put("ring", JSONObject().put("address", it.address).put("name", it.name)) }
    }.toString()

    fun decode(text: String): AppData {
        val o = JSONObject(text)
        fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)
        fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        val counters = o.optJSONArray("counters")?.objects().orEmpty().map {
            Counter(
                it.getString("id"), it.getString("name"), it.getInt("target"),
                runCatching { Period.valueOf(it.getString("period")) }.getOrDefault(Period.ONCE),
                it.optLong("createdAt"), it.optLong("resetAt"), it.optLong("lastUsed"),
            )
        }
        val entries = o.optJSONArray("entries")?.objects().orEmpty().map {
            Entry(
                it.getString("id"), it.str("counterId"), it.getInt("amount"), it.optLong("start"), it.optLong("end"),
                runCatching { Source.valueOf(it.getString("source")) }.getOrDefault(Source.MANUAL), it.optLong("created"),
            )
        }
        val inbox = o.optJSONArray("inbox")?.objects().orEmpty().map {
            Pending(it.getString("id"), it.getInt("count"), it.optLong("start"), it.optLong("end"), it.optBoolean("offline"), it.str("note"))
        }
        val run = o.optJSONObject("run")?.let {
            Run(it.str("counterId"), it.getInt("target"), it.getInt("progress"), it.getString("entryId"), it.optLong("startedAt"), it.optBoolean("confirmed"))
        }
        val free = o.optJSONObject("free")?.let {
            FreeSession(it.getString("ringSessionId"), it.getInt("fromCount"), it.getInt("toCount"), it.optLong("start"), it.optLong("lastPress"), it.optBoolean("offline"))
        }
        val accounted = LinkedHashMap<String, Int>()
        o.optJSONArray("accounted")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { accounted[it.getString(0)] = it.getInt(1) } }
        val doneIds = o.optJSONArray("doneIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

        val so = o.optJSONObject("settings") ?: JSONObject()
        val defaults = AppSettings()
        val settings = AppSettings(
            keepConnected = so.optBoolean("keepConnected", defaults.keepConnected),
            idleMinutes = so.optInt("idleMinutes", defaults.idleMinutes),
            lat = if (so.has("lat")) so.getDouble("lat") else null,
            lon = if (so.has("lon")) so.getDouble("lon") else null,
            prayers = so.optJSONArray("prayers")?.objects()?.map {
                PrayerSetting(it.optBoolean("on"), it.optString("before", "+00"), it.optString("after", "+00"))
            },
            reminderSchedule = so.optJSONObject("reminder")?.let {
                HourlyReminder(it.optInt("minutes", 60), it.optString("start", "08:00"), it.optString("end", "20:00"))
            } ?: defaults.reminderSchedule,
        )
        val ring = o.optJSONObject("ring")?.let { PairedRing(it.getString("address"), it.optString("name", "Zikr Ring")) }
        return AppData(counters, entries, inbox, run, free, accounted, doneIds, settings, ring)
    }
}
