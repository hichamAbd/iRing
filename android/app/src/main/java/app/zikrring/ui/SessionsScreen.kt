@file:OptIn(ExperimentalLayoutApi::class)

package app.zikrring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zikrring.Graph
import app.zikrring.Notifier
import app.zikrring.data.AppData
import app.zikrring.data.Entry
import app.zikrring.data.Pending
import app.zikrring.data.Source
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SessionsScreen(d: AppData, now: Long) {
    val e = Graph.engine
    var manual by remember { mutableStateOf(false) }
    var allInto by remember { mutableStateOf(false) }
    var shownDays by remember { mutableIntStateOf(7) }
    var moving by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }

    val byDay = remember(d.entries) { d.entries.sortedByDescending { it.end }.groupBy { e.day(it.end) } }
    val days = byDay.keys.toList()
    val runEntry = d.run?.entryId

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Séances", fontFamily = SerifNumbers, fontSize = 26.sp) }

            if (d.inbox.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle("À trier · ${d.inbox.size}")
                        Muted("Comptages libres faits sur la bague. Rangez chacun dans un compteur, au total général, ou ignorez-le.")
                    }
                }
                items(d.inbox.reversed(), key = { it.id }) { p -> SectionCard { PendingSortRow(p, d) } }
                if (d.inbox.size > 1) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { e.sortAll(null, System.currentTimeMillis()) }) { Text("Tout au total général") }
                            TextButton(onClick = { allInto = true }) { Text("Tout ranger dans…") }
                        }
                    }
                }
            }

            item { WeekChart(e.lastDays(d, now, 7), e.day(now)) }

            if (days.isEmpty()) {
                item {
                    Muted("Rien encore. Les comptes de la bague, les séances triées et les ajouts à la main s'afficheront ici.")
                }
            }
            days.take(shownDays).forEach { day ->
                val list = byDay[day].orEmpty()
                item(key = "day-$day") {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(fmtDay(day, e.day(now)))
                        Spacer(Modifier.weight(1f))
                        Text(fmtCount(list.sumOf { it.amount }), fontWeight = FontWeight.Bold)
                    }
                }
                item(key = "entries-$day") {
                    SectionCard {
                        list.forEachIndexed { i, en ->
                            if (i > 0) HorizontalDivider(color = LocalZikr.current.line)
                            EntryRow(en, d, locked = en.id == runEntry, onMove = { moving = en }, onDelete = { deleting = en })
                        }
                    }
                }
            }
            if (days.size > shownDays) {
                item { TextButton(onClick = { shownDays += 14 }) { Text("Afficher plus de jours") } }
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("Ajouter à la main") },
            icon = { Icon(Icons.Default.Add, null) },
            onClick = { manual = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (manual) ManualDialog(d, null) { manual = false }
    if (allInto) {
        DestinationDialog("Ranger les ${d.inbox.size} séances dans…", d, onDismiss = { allInto = false }) { id ->
            allInto = false
            e.sortAll(id, System.currentTimeMillis())
        }
    }
    moving?.let { en ->
        DestinationDialog("Déplacer ${fmtCount(en.amount)} vers…", d, onDismiss = { moving = null }) { id ->
            moving = null
            e.moveEntry(en.id, id, System.currentTimeMillis())
        }
    }
    deleting?.let { en ->
        ConfirmDialog(
            title = "Supprimer ${fmtCount(en.amount)} ?",
            text = "Ce compte sera retiré de l'historique et des objectifs.",
            confirm = "Supprimer",
            onDismiss = { deleting = null },
        ) { e.deleteEntry(en.id) }
    }
}

/** One waiting session with quick choices: recent counters, the general tally, other, ignore. */
@Composable
fun PendingSortRow(p: Pending, d: AppData) {
    val e = Graph.engine
    val ctx = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    fun done() = Notifier.cancel(ctx, Notifier.pendingId(p))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtCount(p.count), fontFamily = SerifNumbers, fontSize = 30.sp, lineHeight = 32.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtRange(p.start, p.end), style = MaterialTheme.typography.bodyLarge)
                val notes = listOfNotNull(p.note, if (p.offline) "en partie compté hors connexion" else null)
                if (notes.isNotEmpty()) Muted(notes.joinToString(" · "))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.countersByUse(d).take(3).forEach { c ->
                AssistChip(onClick = { e.sort(p.id, c.id, System.currentTimeMillis()); done() }, label = { Text(c.name) })
            }
            AssistChip(onClick = { e.sort(p.id, null, System.currentTimeMillis()); done() }, label = { Text("Total général") })
            AssistChip(onClick = { choosing = true }, label = { Text("Autre…") })
            AssistChip(
                onClick = { e.discard(p.id); done() },
                label = { Text("Ignorer") },
                leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) },
            )
        }
    }
    if (choosing) {
        DestinationDialog("Ranger ${fmtCount(p.count)} dans…", d, onDismiss = { choosing = false }) { id ->
            choosing = false
            e.sort(p.id, id, System.currentTimeMillis())
            done()
        }
    }
}

@Composable
private fun EntryRow(en: Entry, d: AppData, locked: Boolean, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val name = en.counterId?.let { id -> d.counters.find { it.id == id }?.name } ?: "Total général"
    val source = when (en.source) {
        Source.RING -> "bague"
        Source.SORTED -> "comptage libre"
        Source.MANUAL -> "à la main"
    }
    val time = if (en.end - en.start < 60_000) fmtTime(en.start) else "${fmtTime(en.start)}–${fmtTime(en.end)}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold)
            Muted("$time · $source" + if (locked) " · en cours" else "")
        }
        Text(fmtCount(en.amount), fontFamily = SerifNumbers, fontSize = 20.sp)
        if (locked) {
            Spacer(Modifier.width(48.dp))
        } else {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Déplacer vers…") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Supprimer", color = LocalZikr.current.bad) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun WeekChart(days: List<Pair<LocalDate, Int>>, today: LocalDate) {
    val z = LocalZikr.current
    val max = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    SectionCard("7 derniers jours") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { (date, n) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (n > 0) compact(n) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = z.muted,
                        maxLines = 1,
                    )
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.BottomCenter) {
                        val frac = n.toFloat() / max
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(if (n > 0) Modifier.fillMaxHeight(frac.coerceAtLeast(0.04f)) else Modifier.height(3.dp))
                                .background(
                                    when {
                                        n == 0 -> z.bead
                                        date == today -> z.accent
                                        else -> z.accent.copy(alpha = 0.45f)
                                    },
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                ),
                        )
                    }
                    Text(
                        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRANCE).trimEnd('.'),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (date == today) MaterialTheme.colorScheme.onSurface else z.muted,
                    )
                }
            }
        }
        Muted("Total sur 7 jours : ${fmtCount(days.sumOf { it.second })}")
    }
}

private fun compact(n: Int): String = if (n >= 10_000) "${n / 1000}k" else fmtCount(n)
