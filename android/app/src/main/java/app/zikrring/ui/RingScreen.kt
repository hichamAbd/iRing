@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.zikrring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zikrring.BuildConfig
import app.zikrring.Graph
import app.zikrring.data.AppData
import app.zikrring.ring.PrayerSetting
import app.zikrring.ring.RingState
import kotlinx.coroutines.launch
import java.util.Locale

private val PRAYER_NAMES = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

@Composable
fun RingScreen(d: AppData, s: RingState) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bague", fontFamily = SerifNumbers, fontSize = 26.sp)
                Spacer(Modifier.weight(1f))
                ConnChip(s)
            }
        }
        item { StatusCard(d, s) }
        if (d.ring != null) {
            item { PrayerCard(d, s) }
            item { ReminderCard(d, s) }
            item { MilestoneCard(s) }
            item { ScreenCard(s) }
        }
        item { AppCard(d) }
        item { LogCard() }
        item {
            Muted(
                "Version ${BuildConfig.VERSION_NAME}. L'application ne parle qu'à la bague : pas de compte, pas de serveur. " +
                    "Seule la carte (si vous l'ouvrez) télécharge des images d'OpenStreetMap.",
            )
        }
    }
}

/** Runs ring actions one at a time and reports errors (or messages) in a snackbar. */
@Composable
private fun rememberBusyRunner(): Pair<Boolean, ((suspend () -> String?) -> Unit)> {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val runner: (suspend () -> String?) -> Unit = { block ->
        if (!busy) {
            busy = true
            scope.launch {
                val msg = try { block() } finally { busy = false }
                msg?.let { snack.showSnackbar(it) }
            }
        }
    }
    return busy to runner
}

@Composable
private fun StatusCard(d: AppData, s: RingState) {
    val actions = LocalActions.current
    val (busy, act) = rememberBusyRunner()
    val ring = d.ring
    SectionCard("Ma bague") {
        if (ring == null) {
            Muted("Aucune bague associée. Fermez l'application iQIBLA Life (la bague ne parle qu'à un téléphone à la fois), puis :")
            Button(onClick = { actions.pairRing() }) { Text("Chercher ma bague") }
            TextButton(onClick = { actions.scanRings() }) { Text("Recherche manuelle") }
            return@SectionCard
        }
        Text(ring.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val details = listOfNotNull(
            s.batteryMv?.let { "Batterie ${batteryPercent(it)} % ($it mV)" },
            s.firmware?.let { "logiciel $it" },
        )
        Muted(
            when {
                details.isNotEmpty() -> details.joinToString(" · ")
                s.connected -> "Lecture des informations…"
                else -> "Les informations s'afficheront à la prochaine connexion."
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { act { Graph.ring.findRing(!s.finding) } }, enabled = s.connected && !busy) {
                Text(if (s.finding) "Arrêter la vibration" else "Faire vibrer")
            }
            FilledTonalButton(onClick = { act { Graph.ring.flipDisplay() } }, enabled = s.connected && !busy) {
                Text("Retourner l'écran")
            }
            FilledTonalButton(
                onClick = { act { if (Graph.ring.setClockNow()) "Heure du téléphone envoyée." else "La bague n'a pas répondu." } },
                enabled = s.connected && !busy,
            ) { Text("Mettre à l'heure") }
        }
        if (!s.connected) Muted("La bague se connecte toute seule quand elle est à portée.")
    }
}

@Composable
private fun PrayerCard(d: AppData, s: RingState) {
    val (busy, act) = rememberBusyRunner()
    val saved = d.settings.prayers
    val known = List(5) { i -> s.prayers.getOrNull(i) ?: saved?.getOrNull(i) }
    val values = known.map { it ?: PrayerSetting(false) }
    var picking by remember { mutableStateOf(false) }
    val lat = d.settings.lat
    val lon = d.settings.lon

    SectionCard("Rappels de prière") {
        Muted("La bague vibre à l'heure des prières choisies. Elle calcule les horaires à partir du lieu ci-dessous.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, null, tint = LocalZikr.current.muted)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (lat != null && lon != null) String.format(Locale.FRANCE, "%.2f, %.2f", lat, lon) else "Aucun lieu choisi",
                    fontWeight = FontWeight.Bold,
                )
                Muted("Arrondi à environ 1 km, gardé sur ce téléphone et envoyé seulement à la bague.")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = true }) { Text(if (lat == null) "Choisir sur la carte" else "Changer de lieu") }
            if (lat != null) TextButton(onClick = { act { Graph.ring.saveLocation(null, null) } }) { Text("Retirer") }
        }
        HorizontalDivider(color = LocalZikr.current.line)
        PRAYER_NAMES.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name)
                    s.prayerTimes.getOrNull(i)?.let { Muted(it) }
                }
                Switch(
                    checked = values[i].on,
                    enabled = s.connected && !busy,
                    onCheckedChange = { on ->
                        val list = values.toMutableList().also { it[i] = it[i].copy(on = on) }
                        act { Graph.ring.savePrayers(list) }
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { act { Graph.ring.savePrayers(values.map { it.copy(on = true) }) } }, enabled = s.connected && !busy) {
                Text("Tout activer")
            }
            TextButton(onClick = { act { Graph.ring.savePrayers(values.map { it.copy(on = false) }) } }, enabled = s.connected && !busy) {
                Text("Tout désactiver")
            }
        }
        if (!s.connected) Muted("Connectez la bague pour modifier.")
        else if (known.any { it == null }) Muted("La bague n'a pas tout indiqué : les réglages seront enregistrés tels qu'affichés.")
    }

    if (picking) {
        MapPickerDialog(lat, lon, onDismiss = { picking = false }) { la, lo ->
            picking = false
            act { Graph.ring.saveLocation(la, lo) }
        }
    }
}

@Composable
private fun ReminderCard(d: AppData, s: RingState) {
    val (busy, act) = rememberBusyRunner()
    val r = s.reminder
    val on = r?.on == true
    val base = if (r != null && on) r else d.settings.reminderSchedule
    var minutes by remember(base) { mutableIntStateOf(base.minutes.coerceIn(60, 180)) }
    var from by remember(base) { mutableStateOf(base.start) }
    var to by remember(base) { mutableStateOf(base.end) }

    SectionCard("Rappel régulier") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vibrer à intervalles réguliers")
                Muted(
                    when {
                        r == null -> if (s.connected) "Lecture…" else "État inconnu tant que la bague n'est pas connectée."
                        on -> "Toutes les ${hours(r.minutes)}, de ${r.start} à ${r.end}"
                        else -> "Désactivé"
                    },
                )
            }
            Switch(
                checked = on,
                enabled = s.connected && !busy && r != null,
                onCheckedChange = { want -> act { Graph.ring.saveReminder(if (want) minutes else 0, from, to) } },
            )
        }
        if (on || r == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 120, 180).forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("Toutes les ${hours(m)}") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HourPicker("De", from) { from = it }
                HourPicker("à", to) { to = it }
            }
            val changed = r == null || minutes != r.minutes || from != r.start || to != r.end
            Button(
                onClick = { act { Graph.ring.saveReminder(minutes, from, to) } },
                enabled = s.connected && !busy && changed,
            ) { Text("Enregistrer") }
        }
    }
}

private fun hours(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

@Composable
private fun HourPicker(label: String, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label $value") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { h ->
                val v = String.format(Locale.ROOT, "%02d:00", h)
                DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onPick(v) })
            }
        }
    }
}

@Composable
private fun MilestoneCard(s: RingState) {
    val (busy, act) = rememberBusyRunner()
    val m33Known = s.milestones.getOrNull(0)
    val m100Known = s.milestones.getOrNull(1)
    val m33 = m33Known == "1"
    val m100 = m100Known == "1"
    val custom = s.milestones.getOrNull(2)?.toIntOrNull()
    var text by remember(custom) { mutableStateOf(custom?.takeIf { it > 0 }?.toString() ?: "") }
    val typed = text.toIntOrNull() ?: 0

    SectionCard("Vibrations de palier") {
        Muted("La bague vibre quand le compte atteint un multiple de ces nombres.")
        SwitchRow("Tous les 33", m33, s.connected && !busy) { v -> act { Graph.ring.saveMilestones(v, m100, custom ?: 0) } }
        SwitchRow("Tous les 100", m100, s.connected && !busy) { v -> act { Graph.ring.saveMilestones(m33, v, custom ?: 0) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                label = { Text("Palier personnalisé") },
                placeholder = { Text("Ex. 500") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { act { Graph.ring.saveMilestones(m33, m100, typed) } },
                enabled = s.connected && !busy && typed != (custom ?: 0),
            ) { Text("Enregistrer") }
        }
        Muted(
            if (custom == null) "Laissez vide pour ne pas l'utiliser."
            else if (custom > 0) "Actuellement : tous les ${fmtCount(custom)}. Videz le champ pour l'arrêter."
            else "Actuellement : pas de palier personnalisé.",
        )
        if (s.connected && (m33Known == null || m100Known == null)) {
            Muted("La bague n'a pas tout indiqué : les réglages seront enregistrés tels qu'affichés.")
        }
    }
}

@Composable
private fun ScreenCard(s: RingState) {
    val (busy, act) = rememberBusyRunner()
    SectionCard("Écran allumé") {
        Muted("Combien de temps l'écran de la bague reste allumé après un appui.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (10..60 step 10).forEach { sec ->
                FilterChip(
                    selected = s.screenSeconds == sec,
                    onClick = { act { Graph.ring.saveScreenTime(sec) } },
                    enabled = s.connected && !busy,
                    label = { Text("$sec s") },
                )
            }
        }
    }
}

@Composable
private fun AppCard(d: AppData) {
    val e = Graph.engine
    val ctx = LocalContext.current
    val actions = LocalActions.current
    var ignoring by remember { mutableStateOf(actions.isIgnoringBattery()) }
    LifecycleResumeEffect(Unit) {
        ignoring = actions.isIgnoringBattery()
        onPauseOrDispose { }
    }
    var forgetting by remember { mutableStateOf(false) }

    SectionCard("Application") {
        SwitchRow(
            "Rester connectée en arrière-plan",
            d.settings.keepConnected,
            enabled = d.ring != null,
            hint = "Compte même écran éteint ; une notification discrète reste affichée.",
        ) { v ->
            e.updateSettings { it.copy(keepConnected = v) }
            if (v) Graph.startRing(ctx) else if (!Graph.foreground) Graph.stopRing(ctx)
        }
        HorizontalDivider(color = LocalZikr.current.line)
        Text("Fin d'une séance libre")
        Muted("Le comptage libre devient une séance à trier après ce temps sans appui (ou quand vous remettez la bague à zéro).")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 5, 10, 15, 30).forEach { m ->
                FilterChip(
                    selected = d.settings.idleMinutes == m,
                    onClick = { e.updateSettings { it.copy(idleMinutes = m) } },
                    label = { Text("$m min") },
                )
            }
        }
        HorizontalDivider(color = LocalZikr.current.line)
        if (ignoring) {
            Muted("Optimisation de la batterie : désactivée pour l'application. Bien.")
        } else {
            Text("Économie de batterie")
            Muted("Samsung peut endormir l'application et couper la bague. Autorisez-la à fonctionner en arrière-plan.")
            OutlinedButton(onClick = { actions.openBatterySettings() }) { Text("Ne pas optimiser la batterie") }
        }
        Muted("Sur Samsung, vérifiez aussi que l'application n'est pas dans Paramètres › Batterie › « Applications en veille ».")
        if (d.ring != null) {
            HorizontalDivider(color = LocalZikr.current.line)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.pairRing() }) { Text("Changer de bague") }
                TextButton(onClick = { forgetting = true }) { Text("Oublier la bague", color = LocalZikr.current.bad) }
            }
        }
    }
    if (forgetting) {
        ConfirmDialog(
            title = "Oublier la bague ?",
            text = "L'application arrêtera de s'y connecter. Vos compteurs et votre historique sont gardés.",
            confirm = "Oublier",
            onDismiss = { forgetting = false },
        ) { actions.forgetRing() }
    }
}

@Composable
private fun LogCard() {
    val lines by Graph.ring.logLines.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val clip = LocalClipboardManager.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    SectionCard("Journal Bluetooth") {
        Muted("Les messages échangés avec la bague. Utile pour comprendre un souci.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Masquer" else "Afficher (${lines.size})") }
            TextButton(onClick = {
                clip.setText(AnnotatedString(lines.joinToString("\n")))
                scope.launch { snack.showSnackbar("Journal copié.") }
            }) { Text("Copier") }
            TextButton(onClick = { actions.shareText(lines.joinToString("\n")) }) { Text("Partager") }
        }
        if (open) {
            Text(
                lines.takeLast(200).reversed().joinToString("\n").ifEmpty { "Rien pour l'instant." },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let { Muted(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
