package app.zikrring.ring

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.zikrring.data.Engine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Conn {
    /** No ring paired yet. */
    NONE,
    /** Not trying to connect. */
    IDLE,
    BLUETOOTH_OFF,
    NO_PERMISSION,
    CONNECTING,
    /** Out of range: Android waits for the ring to come back. */
    WAITING,
    CONNECTED,
}

/** What the ring has told us during this connection (null = not reported yet). */
data class RingState(
    val conn: Conn = Conn.IDLE,
    val synced: Boolean = false,
    val batteryMv: Int? = null,
    val firmware: String? = null,
    val flipped: Boolean? = null,
    val screenSeconds: Int? = null,
    val prayers: List<PrayerSetting?> = List(5) { null },
    val milestones: List<String?> = listOf(null, null, null),
    val reminder: HourlyReminder? = null,
    val prayerTimes: List<String> = emptyList(),
    val finding: Boolean = false,
    val mtu: Int = 23,
) {
    val connected: Boolean get() = conn == Conn.CONNECTED
}

/**
 * Keeps the connection to the ring, sends commands and hands what the ring says to the [Engine].
 * Everything runs on one background thread, one Bluetooth operation at a time.
 */
@SuppressLint("MissingPermission")
class RingManager(private val ctx: Context, private val engine: Engine) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ble = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ble)

    private val _state = MutableStateFlow(RingState(conn = if (engine.data.value.ring == null) Conn.NONE else Conn.IDLE))
    val state: StateFlow<RingState> = _state
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var active = false
    private var loopJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var maxChunk = 20

    private var connected: CompletableDeferred<Boolean>? = null
    private var disconnected: CompletableDeferred<Unit>? = null
    private var servicesDone: CompletableDeferred<Boolean>? = null
    private var mtuDone: CompletableDeferred<Int>? = null
    private var descDone: CompletableDeferred<Boolean>? = null
    private var writeDone: CompletableDeferred<Boolean>? = null
    private val writeMutex = Mutex()
    private val waiters = mutableListOf<Pair<String, CompletableDeferred<String>>>()

    private val reassembler = Reassembler { handle(it) }
    private var flushJob: Job? = null
    private var helloJob: Job? = null
    private var syncJob: Job? = null
    private var findJob: Job? = null

    // ------------------------------------------------------------------ log

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(line: String) {
        val stamped = "[${LocalDateTime.now().format(time)}] $line"
        _log.update { (it + stamped).takeLast(600) }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Connect (and keep reconnecting) to the paired ring. */
    fun start() = scope.launch {
        active = true
        if (loopJob?.isActive != true) loopJob = scope.launch { connectionLoop() }
    }

    fun stop() = scope.launch {
        active = false
        loopJob?.cancel()
        loopJob = null
        closeGatt()
        _state.update { it.copy(conn = if (engine.data.value.ring == null) Conn.NONE else Conn.IDLE, synced = false, finding = false) }
    }

    /** Called after the user picked another ring or forgot it. */
    fun restart() = scope.launch {
        loopJob?.cancel()
        loopJob = null
        closeGatt()
        _state.value = RingState(conn = if (engine.data.value.ring == null) Conn.NONE else Conn.IDLE)
        if (active && engine.data.value.ring != null) loopJob = scope.launch { connectionLoop() }
    }

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private suspend fun connectionLoop() {
        var failures = 0
        while (scope.isActive && active) {
            val ring = engine.data.value.ring
            if (ring == null) { _state.update { it.copy(conn = Conn.NONE) }; return }
            if (!hasConnectPermission()) { _state.update { it.copy(conn = Conn.NO_PERMISSION) }; return }
            val a = adapter
            if (a == null || !a.isEnabled) {
                _state.update { it.copy(conn = Conn.BLUETOOTH_OFF) }
                delay(3_000)
                continue
            }
            val device = try {
                a.getRemoteDevice(ring.address)
            } catch (e: IllegalArgumentException) {
                log("Adresse de bague invalide : ${ring.address}")
                _state.update { it.copy(conn = Conn.NONE) }
                return
            }
            // After a few quick failures, let Android wait for the ring in the background
            val auto = failures >= 3
            _state.update { it.copy(conn = if (auto) Conn.WAITING else Conn.CONNECTING) }
            val ok = runSession(device, auto)
            if (!active) break
            failures = if (ok) 0 else failures + 1
            delay(if (ok) 1_000 else minOf(1_000L * (1 shl minOf(failures, 4)), 15_000))
        }
    }

    /** One connection attempt; returns true if it connected (even if it dropped later). */
    private suspend fun runSession(device: BluetoothDevice, auto: Boolean): Boolean {
        val conn = CompletableDeferred<Boolean>().also { connected = it }
        val disc = CompletableDeferred<Unit>().also { disconnected = it }
        log(if (auto) "En attente de la bague…" else "Connexion à ${device.address}…")
        val g = try {
            device.connectGatt(ctx, auto, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _state.update { it.copy(conn = Conn.NO_PERMISSION) }
            null
        } ?: return false
        gatt = g
        try {
            val ok = if (auto) conn.await() else (withTimeoutOrNull(20_000) { conn.await() } ?: false)
            if (!ok) return false
            log("Connectée.")

            delay(600)   // Samsung phones like a short pause before service discovery
            val services = CompletableDeferred<Boolean>().also { servicesDone = it }
            if (!g.discoverServices()) { log("Découverte des services impossible."); return true }
            if (withTimeoutOrNull(15_000) { services.await() } != true) { log("Services introuvables."); return true }
            val svc = g.getService(RingUuids.SERVICE) ?: run { log("Service D0FF absent : est-ce bien la bague ?"); return true }
            val notify = svc.getCharacteristic(RingUuids.NOTIFY)
            val cmd = svc.getCharacteristic(RingUuids.COMMAND)
            if (notify == null || cmd == null) { log("Canaux D002/D004 absents."); return true }

            // Bigger packets so long messages arrive whole (the official app uses 247)
            val mtu = CompletableDeferred<Int>().also { mtuDone = it }
            if (g.requestMtu(247)) {
                val m = withTimeoutOrNull(3_000) { mtu.await() }
                if (m != null && m > 23) maxChunk = m - 3
            }
            log("Taille des paquets : ${maxChunk + 3}")

            g.setCharacteristicNotification(notify, true)
            notify.getDescriptor(RingUuids.CCCD)?.let { d ->
                val done = CompletableDeferred<Boolean>().also { descDone = it }
                if (writeDescriptorCompat(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    withTimeoutOrNull(3_000) { done.await() }
                }
            }
            cmdChar = cmd
            _state.update { it.copy(conn = Conn.CONNECTED, mtu = maxChunk + 3) }
            // The ring says "R01" when ready; sync anyway if it doesn't
            helloJob = scope.launch { delay(1_500); syncRing() }
            disc.await()
            return true
        } finally {
            cmdChar = null
            helloJob?.cancel(); syncJob?.cancel(); findJob?.cancel()
            waiters.forEach { it.second.cancel() }
            waiters.clear()
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
            if (gatt === g) gatt = null
            maxChunk = 20
            _state.update {
                it.copy(conn = if (active) Conn.CONNECTING else Conn.IDLE, synced = false, finding = false)
            }
            log("Déconnectée.")
        }
    }

    private fun closeGatt() {
        cmdChar = null
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        connected?.complete(false)
        disconnected?.complete(Unit)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (g !== gatt) return@launch
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected?.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) log("Connexion interrompue (code $status).")
                    connected?.complete(false)
                    disconnected?.complete(Unit)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch { servicesDone?.complete(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch { mtuDone?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            scope.launch { descDone?.complete(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            scope.launch { writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS) }
        }

        @Deprecated("Used before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            val copy = v.copyOf()
            scope.launch { onBytes(copy) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            scope.launch { onBytes(copy) }
        }
    }

    // ------------------------------------------------------------------ messages

    private fun onBytes(bytes: ByteArray) {
        val held = reassembler.accept(bytes, maxChunk)
        flushJob?.cancel()
        if (held) flushJob = scope.launch { delay(150); reassembler.flush() }
    }

    private fun handle(text: String) {
        log("bague : $text")
        val it = waiters.iterator()
        while (it.hasNext()) {
            val (code, d) = it.next()
            if (text == code || text.startsWith("$code,")) { d.complete(text); it.remove() }
        }
        val now = System.currentTimeMillis()
        when (val ev = RingParser.parse(text)) {
            RingEvent.Hello -> { helloJob?.cancel(); syncRing() }
            is RingEvent.Count -> {
                fire(Commands.ackCount(ldt(), ev.sessionId))   // confirm like the official app
                engine.onCount(ev, now)
            }
            is RingEvent.SavedSession -> {
                fire(Commands.ackSession(ldt(), ev.id))
                engine.onSavedSession(ev, now)
            }
            is RingEvent.CustomDone -> {
                fire(Commands.ackCustomDone(ldt(), ev.id))
                engine.onCustomDone(ev, now)
            }
            is RingEvent.Info -> _state.update { it.copy(batteryMv = ev.batteryMv ?: it.batteryMv, firmware = ev.firmware ?: it.firmware) }
            is RingEvent.Flip -> _state.update { it.copy(flipped = ev.flipped) }
            is RingEvent.Status -> _state.update {
                it.copy(reminder = ev.reminder ?: it.reminder, prayerTimes = ev.prayerTimes.ifEmpty { it.prayerTimes })
            }
            is RingEvent.Settings -> _state.update { s ->
                s.copy(
                    screenSeconds = ev.screenSeconds ?: s.screenSeconds,
                    prayers = List(5) { i -> ev.prayers.getOrNull(i) ?: s.prayers.getOrNull(i) },
                    milestones = List(3) { i -> ev.milestones.getOrNull(i) ?: s.milestones.getOrNull(i) },
                )
            }
            else -> Unit
        }
    }

    private fun fire(cmd: RingCommand) {
        scope.launch { send(cmd) }
    }

    private fun ldt(): LocalDateTime = LocalDateTime.now()
    private fun tz(): String = Commands.tzHours(ZoneId.systemDefault(), LocalDateTime.now())

    /** Sends a command; returns the ring's answer, "" if none is expected, or null on failure/timeout. */
    suspend fun send(cmd: RingCommand, timeoutMs: Long = 2_500): String? = withContext(ble) {
        val w = cmd.expect?.let { it to CompletableDeferred<String>() }
        w?.let { waiters += it }
        val ok = write(cmd.text.toByteArray(Charsets.US_ASCII))
        if (!ok) {
            w?.let { waiters.remove(it) }
            log("Envoi impossible : ${cmd.code}")
            return@withContext null
        }
        log("appli : ${cmd.text}")
        if (w == null) return@withContext ""
        val r = withTimeoutOrNull(timeoutMs) { w.second.await() }
        waiters.remove(w)
        if (r == null) log("Pas de réponse à ${cmd.code}.")
        r
    }

    private suspend fun write(bytes: ByteArray): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val c = cmdChar ?: return false
        // Short commands go without response like the official app; anything longer than a packet uses a long write
        val type = if (bytes.size <= maxChunk) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val done = CompletableDeferred<Boolean>().also { writeDone = it }
        val started = try {
            writeCharacteristicCompat(g, c, bytes, type)
        } catch (e: SecurityException) {
            false
        }
        if (!started) return false
        withTimeoutOrNull(2_000) { done.await() } ?: true
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = bytes
            g.writeCharacteristic(c)
        }

    /** What the official app does after connecting, minus anything personal. */
    private fun syncRing() {
        if (syncJob?.isActive == true || _state.value.synced || cmdChar == null) return
        syncJob = scope.launch {
            val s = engine.data.value.settings
            setClockNow()
            send(Commands.info(ldt()))
            send(Commands.status(ldt()))
            val lat = s.lat
            val lon = s.lon
            if (lat != null && lon != null) send(Commands.prayerLocation(ldt(), lat, lon, tz()))
            // Re-send the prayer reminders saved here, as the official app does on every connection
            s.prayers?.takeIf { it.size == 5 }?.let { list ->
                if (send(Commands.prayerReminders(ldt(), list)) != null) _state.update { it.copy(prayers = list) }
            }
            send(Commands.settings(ldt()))
            send(Commands.sync(ldt()), 3_000)
            _state.update { it.copy(synced = true) }
            log("Synchronisée.")
        }
    }

    // ------------------------------------------------------------------ actions for the screens

    private fun ensureConnected(): String? = if (_state.value.connected) null else "La bague n'est pas connectée."

    suspend fun setClockNow(): Boolean {
        val s = engine.data.value.settings
        return send(Commands.setClock(ldt(), tz(), s.lat, s.lon)) != null
    }

    suspend fun flipDisplay(): String? = ensureConnected() ?: (if (send(Commands.flipDisplay(ldt())) != null) null else "La bague n'a pas répondu.")

    suspend fun findRing(on: Boolean): String? {
        ensureConnected()?.let { return it }
        findJob?.cancel()
        send(Commands.find(ldt(), on)) ?: return "La bague n'a pas répondu."
        _state.update { it.copy(finding = on) }
        if (on) findJob = scope.launch {
            delay(20_000)
            if (send(Commands.find(ldt(), false)) != null) _state.update { it.copy(finding = false) }
        }
        return null
    }

    /** Starts a counter as a custom tally on the ring; returns an error message or null. */
    suspend fun startCounter(counterId: String): String? {
        ensureConnected()?.let { return it }
        if (engine.data.value.run != null && !stopRun()) return "Impossible d'arrêter le compteur en cours."
        val target = engine.beginRun(counterId, System.currentTimeMillis()) ?: return "Ce compteur est déjà terminé."
        return if (send(Commands.customTally(ldt(), true, target)) != null) {
            engine.confirmRun(); null
        } else {
            engine.abortRun(); "La bague n'a pas confirmé."
        }
    }

    /** Stops the counter running on the ring; what's left is remembered for next time. */
    suspend fun stopRun(): Boolean {
        val run = engine.data.value.run ?: return true
        if (!_state.value.connected) return false
        val ok = send(Commands.customTally(ldt(), false, run.target)) != null
        if (ok) engine.endRun(System.currentTimeMillis())
        return ok
    }

    suspend fun saveScreenTime(seconds: Int): String? {
        ensureConnected()?.let { return it }
        return if (send(Commands.screenTime(ldt(), seconds)) != null) {
            _state.update { it.copy(screenSeconds = seconds) }; null
        } else "La bague n'a pas confirmé."
    }

    suspend fun saveReminder(minutes: Int, start: String, end: String): String? {
        ensureConnected()?.let { return it }
        if (send(Commands.reminder(ldt(), minutes, start, end)) == null) return "La bague n'a pas confirmé."
        if (minutes > 0) engine.updateSettings { it.copy(reminderSchedule = HourlyReminder(minutes, start, end)) }
        send(Commands.status(ldt()))
        val r = _state.value.reminder
        val matches = r != null && if (minutes > 0) r.minutes == minutes && r.start == start && r.end == end else !r.on
        return if (matches) null else "Envoyé, mais la bague indique autre chose."
    }

    suspend fun savePrayers(list: List<PrayerSetting>): String? {
        ensureConnected()?.let { return it }
        if (send(Commands.prayerReminders(ldt(), list)) == null) return "La bague n'a pas confirmé."
        engine.updateSettings { it.copy(prayers = list) }
        _state.update { it.copy(prayers = list) }
        send(Commands.settings(ldt()))
        return null
    }

    suspend fun saveMilestones(every33: Boolean, every100: Boolean, custom: Int): String? {
        ensureConnected()?.let { return it }
        if (send(Commands.milestones(ldt(), every33, every100, custom)) == null) return "La bague n'a pas confirmé."
        _state.update { it.copy(milestones = listOf(if (every33) "1" else "0", if (every100) "1" else "0", custom.toString())) }
        send(Commands.settings(ldt()))
        return null
    }

    /** Saves (or clears) the prayer location; sends it to the ring when connected. */
    suspend fun saveLocation(lat: Double?, lon: Double?): String? {
        engine.updateSettings { it.copy(lat = lat, lon = lon) }
        if (!_state.value.connected) return null
        setClockNow()
        if (lat != null && lon != null) send(Commands.prayerLocation(ldt(), lat, lon, tz()))
        send(Commands.status(ldt()))
        return null
    }
}
