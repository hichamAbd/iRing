package app.zikrring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.zikrring.data.Counter
import app.zikrring.data.EngineListener
import app.zikrring.data.Pending
import app.zikrring.ring.ActionReceiver
import app.zikrring.ui.fmtCount
import app.zikrring.ui.fmtRange

class Notifier(private val ctx: Context) : EngineListener {
    companion object {
        const val CH_SERVICE = "connexion"
        const val CH_SESSIONS = "seances"
        const val CH_GOALS = "objectifs"
        const val ID_SERVICE = 1
        private const val ID_GOAL = 2

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Connexion à la bague", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Reste affichée tant que l'application écoute la bague."
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_SESSIONS, "Séances à trier", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Quand une séance de comptage libre se termine."
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_GOALS, "Objectifs atteints", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        fun openApp(ctx: Context, tab: String? = null, request: Int = 0): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                tab?.let { putExtra(MainActivity.EXTRA_TAB, it) }
            }
            return PendingIntent.getActivity(ctx, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun notify(ctx: Context, id: Int, n: android.app.Notification) {
            val nm = NotificationManagerCompat.from(ctx)
            if (!nm.areNotificationsEnabled()) return
            try {
                nm.notify(id, n)
            } catch (_: SecurityException) {
            }
        }

        fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

        fun pendingId(p: Pending) = 1000 + (p.id.hashCode() and 0x0fffffff)
    }

    override fun inboxAdded(p: Pending) {
        if (Graph.foreground) return
        val id = pendingId(p)
        fun action(action: String, req: Int) = PendingIntent.getBroadcast(
            ctx, id * 4 + req,
            Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(ActionReceiver.EXTRA_PENDING, p.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "${fmtCount(p.count)} · ${fmtRange(p.start, p.end)}" + if (p.offline) " · en partie hors connexion" else ""
        val n = NotificationCompat.Builder(ctx, CH_SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Séance à trier : ${fmtCount(p.count)}")
            .setContentText(text)
            .setContentIntent(openApp(ctx, MainActivity.TAB_SESSIONS, id))
            .setAutoCancel(true)
            .addAction(0, "Au total", action(ActionReceiver.ACTION_TOTAL, 1))
            .addAction(0, "Ignorer", action(ActionReceiver.ACTION_DISCARD, 2))
            .build()
        notify(ctx, id, n)
    }

    override fun counterCompleted(c: Counter) {
        val n = NotificationCompat.Builder(ctx, CH_GOALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${c.name} : objectif atteint")
            .setContentText("${fmtCount(c.target)} sur ${fmtCount(c.target)}. Qu'Allah l'accepte.")
            .setContentIntent(openApp(ctx, MainActivity.TAB_COUNTERS, ID_GOAL))
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_GOAL, n)
    }
}
