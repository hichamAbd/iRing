package app.zikrring.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zikrring.ring.Conn
import app.zikrring.ring.RingState
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Things only the activity can do (system dialogs, permissions, sharing). */
interface AppActions {
    fun pairRing()
    /** Plain Bluetooth search, for when the system picker shows nothing. */
    fun scanRings()
    fun requestBluetoothOn()
    fun requestConnectPermission()
    fun openBatterySettings()
    fun isIgnoringBattery(): Boolean
    fun shareText(text: String)
    fun forgetRing()
    val scan: State<ScanUi?>
    fun chooseScanned(address: String, name: String)
    fun closeScan()
}

data class FoundRing(val address: String, val name: String, val rssi: Int)
data class ScanUi(val searching: Boolean, val found: List<FoundRing>, val message: String? = null)

val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions missing") }
val LocalSnack = staticCompositionLocalOf<SnackbarHostState> { error("Snackbar missing") }

/** Runs a ring action in the background and shows its error, if any, in a snackbar. */
@Composable
fun rememberRunner(): (suspend () -> String?) -> Unit {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    return remember(scope, snack) {
        { block -> scope.launch { block()?.let { snack.showSnackbar(it) } } }
    }
}

/** The 33-bead ring: [filled] beads lit, the last one outlined. */
@Composable
fun BeadDial(filled: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val z = LocalZikr.current
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val r = size.minDimension / 2f * 0.82f
            val c = Offset(size.width / 2f, size.height / 2f)
            val bead = size.minDimension * 0.027f
            for (i in 0 until 34) {
                val a = Math.toRadians(-90.0 + i * 360.0 / 34)
                val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                if (i == 0) {
                    drawCircle(surface, bead * 1.35f, p)
                    drawCircle(z.accent, bead * 1.35f, p, style = Stroke(width = bead * 0.4f))
                } else {
                    val on = i <= filled
                    drawCircle(if (on) z.accent else z.bead, bead, p)
                    if (i == filled) drawCircle(ink, bead * 1.05f, p, style = Stroke(width = bead * 0.3f))
                }
            }
        }
        content()
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) SectionTitle(title)
            content()
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = LocalZikr.current.muted,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = LocalZikr.current.muted)
}

@Composable
fun ConnChip(s: RingState, modifier: Modifier = Modifier) {
    val z = LocalZikr.current
    val (label, color) = when (s.conn) {
        Conn.NONE -> "Aucune bague" to z.muted
        Conn.IDLE -> "Déconnectée" to z.muted
        Conn.BLUETOOTH_OFF -> "Bluetooth éteint" to z.bad
        Conn.NO_PERMISSION -> "Autorisation requise" to z.bad
        Conn.CONNECTING -> "Connexion…" to z.warn
        Conn.WAITING -> "Hors de portée" to z.warn
        Conn.CONNECTED -> (if (s.synced) "Connectée" else "Synchronisation…") to z.ok
    }
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label.uppercase(), style = MaterialTheme.typography.labelSmall,
                color = LocalZikr.current.muted, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            )
            Text(
                value, fontFamily = SerifNumbers, fontSize = 22.sp,
                color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1,
            )
        }
    }
}
