package app.zikrring.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.zikrring.Graph
import app.zikrring.MainActivity
import app.zikrring.Notifier
import app.zikrring.R
import app.zikrring.ui.fmtCount
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keeps the app listening to the ring while the screen is off, with a quiet notification. */
class RingService : Service() {
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_SERVICE, build("Connexion à la bague…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Android 14+ refuses without the Bluetooth permission
            Graph.ring.log("Arrière-plan refusé : ${e.message}")
            stopSelf()
            return
        }
        Graph.ring.start()
        scope.launch {
            combine(Graph.ring.state, Graph.engine.data) { s, d ->
                val run = d.run
                val counter = run?.counterId?.let { id -> d.counters.find { it.id == id } }
                when {
                    !s.connected -> when (s.conn) {
                        Conn.BLUETOOTH_OFF -> "Bluetooth désactivé"
                        Conn.NO_PERMISSION -> "Autorisation Bluetooth manquante"
                        Conn.WAITING -> "En attente de la bague (hors de portée ?)"
                        else -> "Connexion à la bague…"
                    }
                    run != null && counter != null -> "${counter.name} : reste ${fmtCount(run.target - run.progress)}"
                    d.free != null -> "Comptage libre : ${fmtCount(d.free.count)}"
                    else -> "Connectée · aujourd'hui ${fmtCount(Graph.engine.dayTotal(d, Graph.engine.day(System.currentTimeMillis())))}"
                }
            }.distinctUntilChanged().collect { text ->
                Notifier.notify(this@RingService, Notifier.ID_SERVICE, build(text))
            }
        }
        // Close free sessions that went quiet
        scope.launch {
            while (true) {
                delay(30_000)
                Graph.engine.tick(System.currentTimeMillis())
            }
        }
    }

    private fun build(text: String) = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Zikr Ring")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentIntent(Notifier.openApp(this))
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Graph.ring.start()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Graph.ring.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun isWanted(ctx: Context): Boolean {
            val d = Graph.engine.data.value
            return d.ring != null && (d.settings.keepConnected || Graph.foreground)
        }
    }
}
