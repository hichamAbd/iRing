package app.zikrring.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zikrring.BuildConfig
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File
import java.util.Locale

private fun round2(v: Double) = Math.round(v * 100) / 100.0

/**
 * Pick the prayer-time location by moving a map under a cross; nothing reads the phone's position.
 * Map pictures come from OpenStreetMap while this is open.
 */
@Composable
fun MapPickerDialog(lat: Double?, lon: Double?, onDismiss: () -> Unit, onPick: (Double, Double) -> Unit) {
    val ctx = LocalContext.current
    val start = GeoPoint(lat ?: 30.0, lon ?: 10.0)
    var center by remember { mutableStateOf(start) }
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = "ZikrRing/${BuildConfig.VERSION_NAME} (app.zikrring)"
            val base = File(ctx.cacheDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 14.0
            controller.setZoom(if (lat != null) 9.0 else 3.0)
            controller.setCenter(start)
            val view = this
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    center = GeoPoint(view.mapCenter.latitude, view.mapCenter.longitude)
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    center = GeoPoint(view.mapCenter.latitude, view.mapCenter.longitude)
                    return false
                }
            })
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Fermer") }
                    Spacer(Modifier.width(4.dp))
                    Text("Lieu des horaires de prière", style = MaterialTheme.typography.titleMedium)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.align(Alignment.Center).size(44.dp)) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = size.minDimension / 2f
                        for ((color, w) in listOf(Color.White to 7.dp.toPx(), Color(0xFFB03636) to 3.dp.toPx())) {
                            drawLine(color, Offset(c.x - r, c.y), Offset(c.x - r * 0.3f, c.y), w, StrokeCap.Round)
                            drawLine(color, Offset(c.x + r * 0.3f, c.y), Offset(c.x + r, c.y), w, StrokeCap.Round)
                            drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.3f), w, StrokeCap.Round)
                            drawLine(color, Offset(c.x, c.y + r * 0.3f), Offset(c.x, c.y + r), w, StrokeCap.Round)
                            drawCircle(color, r * 0.12f, c, style = Stroke(w * 0.6f))
                        }
                    }
                }
                Column(
                    Modifier.padding(16.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        String.format(Locale.FRANCE, "%.2f, %.2f", round2(center.latitude), round2(center.longitude)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Muted(
                        "Déplacez la carte pour mettre la croix sur votre ville. La position est arrondie (environ 1 km), " +
                            "reste sur ce téléphone et n'est envoyée qu'à la bague. Carte © OpenStreetMap.",
                    )
                    Button(
                        onClick = { onPick(round2(center.latitude), round2(center.longitude)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Utiliser ce lieu") }
                }
            }
        }
    }
}
