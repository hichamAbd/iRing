package app.zikrring.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.zikrring.Graph
import app.zikrring.Notifier

/** Reconnects to the ring after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val d = Graph.engine.data.value
        if (d.ring != null && d.settings.keepConnected) Graph.startRing(context)
    }
}

/** Buttons on the "séance à trier" notification. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TOTAL = "app.zikrring.SORT_TOTAL"
        const val ACTION_DISCARD = "app.zikrring.DISCARD"
        const val EXTRA_PENDING = "pending"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_PENDING) ?: return
        val p = Graph.engine.data.value.inbox.find { it.id == id }
        when (intent.action) {
            ACTION_TOTAL -> Graph.engine.sort(id, null, System.currentTimeMillis())
            ACTION_DISCARD -> Graph.engine.discard(id)
        }
        if (p != null) Notifier.cancel(context, Notifier.pendingId(p))
    }
}
