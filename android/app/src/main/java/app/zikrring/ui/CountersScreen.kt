@file:OptIn(ExperimentalLayoutApi::class)

package app.zikrring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zikrring.Graph
import app.zikrring.data.AppData
import app.zikrring.data.Counter
import app.zikrring.data.Period
import app.zikrring.ring.RingState

@Composable
fun CountersScreen(d: AppData, s: RingState, now: Long) {
    val e = Graph.engine
    val act = rememberRunner()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Counter?>(null) }
    var manualFor by remember { mutableStateOf<Counter?>(null) }
    var deleting by remember { mutableStateOf<Counter?>(null) }

    val runningId = d.run?.counterId
    val ordered = d.counters.sortedWith(
        compareBy<Counter>({ it.id != runningId }, { e.remaining(d, it, now) == 0 }).thenByDescending { it.lastUsed },
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Compteurs", fontFamily = SerifNumbers, fontSize = 26.sp) }
            item {
                Muted(
                    "Chaque compteur retient ce qu'il reste à faire. Lancé sur la bague, il s'affiche en compte à rebours ; " +
                        "arrêtez quand vous voulez, la suite reprendra où vous en étiez.",
                )
            }
            if (d.counters.isEmpty()) {
                item {
                    SectionCard {
                        Text("Aucun compteur pour l'instant.", style = MaterialTheme.typography.titleMedium)
                        Muted("Exemples : « Astaghfirullah » 300 en une fois, ou « Salawat » 100 chaque jour.")
                        Button(onClick = { creating = true }) { Text("Créer un compteur") }
                    }
                }
            }
            items(ordered, key = { it.id }) { c ->
                CounterCard(
                    c, d, s, now,
                    onStart = { act { Graph.ring.startCounter(c.id) } },
                    onStop = { act { if (Graph.ring.stopRun()) null else "La bague n'a pas confirmé l'arrêt." } },
                    onEdit = { editing = c },
                    onManual = { manualFor = c },
                    onDelete = { deleting = c },
                )
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("Nouveau compteur") },
            icon = { Icon(Icons.Default.Add, null) },
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) {
        CounterDialog(null, onDismiss = { creating = false }) { name, target, period ->
            creating = false
            e.addCounter(name, target, period, System.currentTimeMillis())
        }
    }
    editing?.let { c ->
        CounterDialog(c, onDismiss = { editing = null }) { name, target, period ->
            editing = null
            e.updateCounter(c.id, name, target, period)
        }
    }
    manualFor?.let { c -> ManualDialog(d, c.id) { manualFor = null } }
    deleting?.let { c ->
        ConfirmDialog(
            title = "Supprimer « ${c.name} » ?",
            text = "Ce qui a déjà été compté reste dans le total général.",
            confirm = "Supprimer",
            onDismiss = { deleting = null },
        ) {
            if (!e.deleteCounter(c.id)) act { "Arrêtez-le d'abord sur la bague." }
        }
    }
}

@Composable
private fun CounterCard(
    c: Counter,
    d: AppData,
    s: RingState,
    now: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onManual: () -> Unit,
    onDelete: () -> Unit,
) {
    val e = Graph.engine
    val z = LocalZikr.current
    val done = e.progress(d, c, now)
    val left = (c.target - done).coerceAtLeast(0)
    val r = d.run
    val running = r != null && r.counterId == c.id
    var menu by remember { mutableStateOf(false) }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Muted(
                    (if (c.period == Period.DAILY) "Chaque jour" else "Une fois") + " · objectif ${fmtCount(c.target)}",
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Modifier") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Ajouter à la main") }, onClick = { menu = false; onManual() })
                    if (c.period == Period.ONCE && !running) {
                        DropdownMenuItem(
                            text = { Text("Recommencer à zéro") },
                            onClick = { menu = false; e.restartCounter(c.id, System.currentTimeMillis()) },
                        )
                    }
                    if (!running) {
                        DropdownMenuItem(text = { Text("Supprimer", color = z.bad) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }

        LinearProgressIndicator(
            progress = { (done.toFloat() / c.target.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = if (left == 0) z.ok else z.accent,
            trackColor = z.bead,
            strokeCap = StrokeCap.Round,
        )

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (left == 0) "Terminé" else fmtCount(left),
                fontFamily = SerifNumbers,
                fontSize = 30.sp,
                lineHeight = 32.sp,
                color = if (left == 0) z.ok else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Muted(
                when {
                    left == 0 && c.period == Period.DAILY -> "pour aujourd'hui"
                    left == 0 -> "${fmtCount(done)} faits"
                    else -> "restants · ${fmtCount(done)} faits" + if (c.period == Period.DAILY) " aujourd'hui" else ""
                },
                Modifier.padding(bottom = 4.dp),
            )
        }

        if (running && r != null) {
            Text(
                "En cours sur la bague : ${fmtCount(r.progress)} sur ${fmtCount(r.target)}" + if (!r.confirmed) " (en attente)" else "",
                color = z.accent,
                fontWeight = FontWeight.Bold,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                running -> Button(onClick = onStop, enabled = s.connected) {
                    Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Arrêter")
                }
                left > 0 -> Button(onClick = onStart, enabled = s.connected) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lancer sur la bague")
                }
                c.period == Period.ONCE -> OutlinedButton(onClick = { e.restartCounter(c.id, System.currentTimeMillis()) }) {
                    Text("Recommencer")
                }
            }
            OutlinedButton(onClick = onManual) { Text("À la main") }
        }
        if (!s.connected && left > 0 && !running) Muted("Connectez la bague pour lancer ce compteur.")
    }
}
