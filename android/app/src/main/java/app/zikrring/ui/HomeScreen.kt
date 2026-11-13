package app.zikrring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zikrring.Graph
import app.zikrring.MainActivity
import app.zikrring.data.AppData
import app.zikrring.data.Period
import app.zikrring.ring.Conn
import app.zikrring.ring.RingState

@Composable
fun HomeScreen(d: AppData, s: RingState, now: Long, goTo: (String) -> Unit) {
    val e = Graph.engine
    val actions = LocalActions.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zikr Ring", fontFamily = SerifNumbers, fontSize = 26.sp)
                Spacer(Modifier.weight(1f))
                ConnChip(s)
            }
        }

        if (d.ring == null) {
            item { PairCard() }
        } else if (s.conn == Conn.BLUETOOTH_OFF) {
            item {
                Banner("Le Bluetooth est éteint.", "Activer") { actions.requestBluetoothOn() }
            }
        } else if (s.conn == Conn.NO_PERMISSION) {
            item {
                Banner("L'application a besoin de l'accès aux appareils à proximité pour parler à la bague.", "Autoriser") {
                    actions.requestConnectPermission()
                }
            }
        }

        item { DialSection(d, s, now) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Aujourd'hui", fmtCount(e.dayTotal(d, e.day(now))), Modifier.weight(1f))
                StatTile("Série", "${e.streak(d, now)} j", Modifier.weight(1f))
                StatTile("Batterie", fmtBattery(s.batteryMv), Modifier.weight(1f))
            }
        }

        if (d.inbox.isNotEmpty()) {
            item {
                SectionCard("À trier · ${d.inbox.size}") {
                    PendingSortRow(d.inbox.last(), d)
                    if (d.inbox.size > 1) {
                        TextButton(onClick = { goTo(MainActivity.TAB_SESSIONS) }) {
                            Text("Voir les ${d.inbox.size} séances à trier")
                        }
                    }
                }
            }
        }

        item { QuickCounters(d, s, now, goTo) }
    }
}

@Composable
private fun DialSection(d: AppData, s: RingState, now: Long) {
    val e = Graph.engine
    val run = rememberRunner()
    val r = d.run
    val f = d.free
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            r != null -> {
                val counter = r.counterId?.let { id -> d.counters.find { it.id == id } }
                val left = (r.target - r.progress).coerceAtLeast(0)
                val filled = if (r.target > 0) (r.progress * 33 / r.target).coerceIn(0, 33) else ((r.progress - 1) % 33) + 1
                BeadDial(filled, Modifier.fillMaxWidth(0.8f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BigNumber(if (r.target > 0) left else r.progress)
                        Muted(if (r.target > 0) "restants" else "comptés")
                    }
                }
                Text(counter?.name ?: "Compteur lancé depuis la bague", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Muted(
                    if (r.target > 0) "${fmtCount(r.progress)} faits sur ${fmtCount(r.target)}" + if (!r.confirmed) " · en attente de la bague" else ""
                    else "Il sera à trier quand il se terminera.",
                )
                if (r.counterId != null) {
                    OutlinedButton(onClick = { run { if (Graph.ring.stopRun()) null else "La bague n'a pas confirmé l'arrêt." } }, enabled = s.connected) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Arrêter (ce qui reste est gardé)")
                    }
                }
            }
            f != null -> {
                BeadDial(((f.count - 1) % 33) + 1, Modifier.fillMaxWidth(0.8f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BigNumber(f.count)
                        Muted("comptage libre")
                    }
                }
                Muted(
                    "Depuis ${fmtTime(f.start)} · se termine après ${d.settings.idleMinutes} min sans appui",
                    Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { e.endFreeSession() }) { Text("Terminer la séance") }
            }
            else -> {
                BeadDial(0, Modifier.fillMaxWidth(0.8f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BigNumber(e.dayTotal(d, e.day(now)))
                        Muted("aujourd'hui")
                    }
                }
                Muted(
                    if (s.connected) "Appuyez sur la bague pour compter librement, ou lancez un compteur ci-dessous."
                    else "Les appuis faits sans le téléphone sont récupérés à la prochaine connexion.",
                )
            }
        }
    }
}

@Composable
private fun BigNumber(n: Int) {
    Text(fmtCount(n), fontFamily = SerifNumbers, fontSize = 64.sp, lineHeight = 64.sp)
}

@Composable
private fun QuickCounters(d: AppData, s: RingState, now: Long, goTo: (String) -> Unit) {
    val e = Graph.engine
    val run = rememberRunner()
    SectionCard("Lancer sur la bague") {
        val counters = e.countersByUse(d)
        if (counters.isEmpty()) {
            Muted("Créez un compteur avec un objectif : la bague compte à rebours et l'application retient ce qu'il reste.")
            Button(onClick = { goTo(MainActivity.TAB_COUNTERS) }) { Text("Créer un compteur") }
            return@SectionCard
        }
        if (!s.connected) Muted("Connectez la bague pour lancer un compteur.")
        counters.take(6).forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(color = LocalZikr.current.line)
            val left = e.remaining(d, c, now)
            val running = d.run?.counterId == c.id
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.Bold)
                    Muted(
                        when {
                            running -> "En cours sur la bague"
                            left == 0 -> if (c.period == Period.DAILY) "Fait aujourd'hui" else "Terminé"
                            else -> "Reste ${fmtCount(left)} sur ${fmtCount(c.target)}" + if (c.period == Period.DAILY) " aujourd'hui" else ""
                        },
                    )
                }
                if (running) {
                    FilledTonalIconButton(onClick = { run { if (Graph.ring.stopRun()) null else "La bague n'a pas confirmé l'arrêt." } }, enabled = s.connected) {
                        Icon(Icons.Default.Close, "Arrêter")
                    }
                } else {
                    FilledTonalIconButton(onClick = { run { Graph.ring.startCounter(c.id) } }, enabled = s.connected && left > 0) {
                        Icon(Icons.Default.PlayArrow, "Lancer")
                    }
                }
            }
        }
        if (counters.size > 6) TextButton(onClick = { goTo(MainActivity.TAB_COUNTERS) }) { Text("Tous les compteurs") }
    }
}

@Composable
private fun PairCard() {
    val actions = LocalActions.current
    SectionCard("Associer la bague") {
        Text("1. Fermez l'application iQIBLA Life (ou désactivez son Bluetooth) : la bague ne parle qu'à un téléphone à la fois.")
        Text("2. Gardez la bague près du téléphone.")
        Text("3. Touchez « Chercher ma bague » et choisissez « Zikr Ring ».")
        Button(onClick = { actions.pairRing() }) { Text("Chercher ma bague") }
        TextButton(onClick = { actions.scanRings() }) { Text("Rien ne s'affiche ? Recherche manuelle") }
    }
}

@Composable
private fun Banner(text: String, button: String, onClick: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Warning, null, tint = LocalZikr.current.warn)
            Text(text, Modifier.weight(1f))
        }
        Button(onClick = onClick) { Text(button) }
    }
}
