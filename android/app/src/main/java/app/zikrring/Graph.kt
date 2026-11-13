package app.zikrring

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import app.zikrring.data.Engine
import app.zikrring.data.Storage
import app.zikrring.ring.RingManager
import app.zikrring.ring.RingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

class ZikrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** The app's single instances: counting engine, ring connection, storage. */
object Graph {
    lateinit var app: Application
        private set
    lateinit var engine: Engine
        private set
    lateinit var ring: RingManager
        private set
    private lateinit var storage: Storage
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while the app's screen is visible (inbox notifications are skipped then). */
    @Volatile var foreground = false

    @OptIn(FlowPreview::class)
    fun init(app: Application) {
        this.app = app
        storage = Storage(File(app.filesDir, "data.json"))
        engine = Engine(storage.load())
        ring = RingManager(app, engine)
        engine.listener = Notifier(app)
        Notifier.ensureChannels(app)
        io.launch { engine.data.drop(1).debounce(400).collect { storage.save(it) } }
    }

    /** Starts the background connection if a ring is paired. */
    fun startRing(ctx: Context) {
        if (engine.data.value.ring == null) return
        val intent = Intent(ctx, RingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        } catch (e: Exception) {
            ring.log("Service impossible à démarrer : ${e.message}")
        }
    }

    fun stopRing(ctx: Context) {
        ctx.stopService(Intent(ctx, RingService::class.java))
    }
}
