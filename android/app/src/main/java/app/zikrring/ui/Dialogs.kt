@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.zikrring.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.zikrring.Graph
import app.zikrring.data.AppData
import app.zikrring.data.Counter
import app.zikrring.data.Period

/** Ideas for a new counter (the name and target can be changed). */
private val PRESETS = listOf(
    "Subhan Allah" to 33,
    "Alhamdulillah" to 33,
    "Allahu akbar" to 34,
    "Astaghfirullah" to 100,
    "La ilaha illa Allah" to 100,
    "Salat 'ala n-Nabi" to 100,
)

@Composable
fun CounterDialog(
    initial: Counter?,
    onDismiss: () -> Unit,
    onSave: (name: String, target: Int, period: Period) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var target by remember { mutableStateOf(initial?.target?.toString() ?: "") }
    var period by remember { mutableStateOf(initial?.period ?: Period.ONCE) }
    val t = target.toIntOrNull()
    val valid = name.isNotBlank() && t != null && t in 1..99_999
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nouveau compteur" else "Modifier le compteur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Nom") },
                    placeholder = { Text("Ex. Astaghfirullah") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { v -> target = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Objectif") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = period == Period.ONCE, onClick = { period = Period.ONCE }, label = { Text("Une fois") })
                    FilterChip(selected = period == Period.DAILY, onClick = { period = Period.DAILY }, label = { Text("Chaque jour") })
                }
                Muted(
                    if (period == Period.DAILY) "Repart à zéro chaque jour à minuit."
                    else "Compte jusqu'à l'objectif, sur autant de jours qu'il faut. Vous pourrez le recommencer ensuite.",
                )
                if (initial == null) {
                    SectionTitle("Idées")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESETS.forEach { (n, v) ->
                            AssistChip(onClick = { name = n; target = v.toString() }, label = { Text("$n · $v") })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (t != null) onSave(name.trim(), t, period) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Picks where counts go: a counter, the general tally, or a new counter made on the spot. */
@Composable
fun DestinationDialog(
    title: String,
    d: AppData,
    onDismiss: () -> Unit,
    onPick: (counterId: String?) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        CounterDialog(null, onDismiss = { creating = false }) { name, target, period ->
            creating = false
            onPick(Graph.engine.addCounter(name, target, period, System.currentTimeMillis()))
        }
        return
    }
    val e = Graph.engine
    val now = System.currentTimeMillis()
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = { Text("Total général") },
                    supportingContent = { Text("Sans compteur particulier") },
                    colors = clear,
                    modifier = Modifier.clickable { onPick(null) },
                )
                e.countersByUse(d).forEach { c ->
                    HorizontalDivider(color = LocalZikr.current.line)
                    val left = e.remaining(d, c, now)
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text(if (left == 0) "Objectif atteint" else "Reste ${fmtCount(left)}") },
                        colors = clear,
                        modifier = Modifier.clickable { onPick(c.id) },
                    )
                }
                HorizontalDivider(color = LocalZikr.current.line)
                ListItem(
                    headlineContent = { Text("Nouveau compteur…", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = clear,
                    modifier = Modifier.clickable { creating = true },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Counts done without the ring (or with another tasbih). */
@Composable
fun ManualDialog(d: AppData, initialCounter: String?, onDismiss: () -> Unit) {
    val e = Graph.engine
    var amount by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf(initialCounter) }
    var yesterday by remember { mutableStateOf(false) }
    val n = amount.toIntOrNull()
    val counters = e.countersByUse(d)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à la main") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { v -> amount = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Combien") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionTitle("Où")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = dest == null, onClick = { dest = null }, label = { Text("Total général") })
                    counters.forEach { c ->
                        FilterChip(selected = dest == c.id, onClick = { dest = c.id }, label = { Text(c.name) })
                    }
                }
                SectionTitle("Quand")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !yesterday, onClick = { yesterday = false }, label = { Text("Aujourd'hui") })
                    FilterChip(selected = yesterday, onClick = { yesterday = true }, label = { Text("Hier") })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = n != null && n > 0,
                onClick = {
                    val now = System.currentTimeMillis()
                    if (n != null && n > 0) e.addManual(dest, n, if (yesterday) now - 86_400_000L else now, now)
                    onDismiss()
                },
            ) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = LocalZikr.current.bad) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
