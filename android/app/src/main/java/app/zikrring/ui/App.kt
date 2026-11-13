package app.zikrring.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import app.zikrring.Graph
import app.zikrring.MainActivity
import kotlinx.coroutines.delay

@Composable
fun ZikrAppUi(tabRequest: MutableState<String?>) {
    val data by Graph.engine.data.collectAsStateWithLifecycle()
    val ring by Graph.ring.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MainActivity.TAB_HOME) }
    LaunchedEffect(tabRequest.value) {
        tabRequest.value?.let { tab = it; tabRequest.value = null }
    }
    // Ticks so "today" and idle sessions stay current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            Graph.engine.tick(System.currentTimeMillis())
            value = System.currentTimeMillis()
        }
    }
    val nowMs = maxOf(now, System.currentTimeMillis())
    val snack = remember { SnackbarHostState() }

    CompositionLocalProvider(LocalSnack provides snack) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_HOME, onClick = { tab = MainActivity.TAB_HOME },
                        icon = { Icon(Icons.Default.Home, null) }, label = { Text("Accueil") },
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_COUNTERS, onClick = { tab = MainActivity.TAB_COUNTERS },
                        icon = { Icon(Icons.Default.Star, null) }, label = { Text("Compteurs") },
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_SESSIONS, onClick = { tab = MainActivity.TAB_SESSIONS },
                        icon = {
                            BadgedBox(badge = { if (data.inbox.isNotEmpty()) Badge { Text(data.inbox.size.toString()) } }) {
                                Icon(Icons.AutoMirrored.Filled.List, null)
                            }
                        },
                        label = { Text("Séances") },
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_RING, onClick = { tab = MainActivity.TAB_RING },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Bague") },
                    )
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                when (tab) {
                    MainActivity.TAB_COUNTERS -> CountersScreen(data, ring, nowMs)
                    MainActivity.TAB_SESSIONS -> SessionsScreen(data, nowMs)
                    MainActivity.TAB_RING -> RingScreen(data, ring)
                    else -> HomeScreen(data, ring, nowMs) { tab = it }
                }
            }
        }
        ScanDialog()
    }
}

/** Shown only when the system ring picker isn't available: a plain Bluetooth search. */
@Composable
private fun ScanDialog() {
    val actions = LocalActions.current
    val scan = actions.scan.value ?: return
    AlertDialog(
        onDismissRequest = { actions.closeScan() },
        title = { Text("Choisir la bague") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                scan.message?.let { Muted(it) }
                if (scan.searching && scan.found.isEmpty()) {
                    CircularProgressIndicator()
                    Muted("Recherche… Gardez la bague près du téléphone, et fermez l'application iQIBLA Life.")
                }
                if (!scan.searching && scan.found.isEmpty() && scan.message == null) {
                    Muted("Aucune bague trouvée. Vérifiez qu'aucun autre téléphone n'y est connecté, puis réessayez.")
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(scan.found, key = { it.address }) { r ->
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text("${r.address} · signal ${r.rssi} dBm") },
                            modifier = Modifier.clickable { actions.chooseScanned(r.address, r.name) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { actions.closeScan() }) { Text("Fermer") } },
    )
}
