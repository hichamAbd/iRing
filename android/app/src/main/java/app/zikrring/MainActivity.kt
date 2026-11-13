package app.zikrring

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.zikrring.data.PairedRing
import app.zikrring.ring.RingService
import app.zikrring.ui.AppActions
import app.zikrring.ui.LocalActions
import app.zikrring.ui.ScanUi
import app.zikrring.ui.ZikrAppUi
import app.zikrring.ui.ZikrTheme

class MainActivity : ComponentActivity(), AppActions {
    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = "home"
        const val TAB_COUNTERS = "counters"
        const val TAB_SESSIONS = "sessions"
        const val TAB_RING = "ring"
    }

    private val tabRequest: MutableState<String?> = mutableStateOf(null)
    private val scanState = mutableStateOf<ScanUi?>(null)
    override val scan: State<ScanUi?> get() = scanState
    private var scanner: RingScanner? = null
    private var afterPermission: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val next = afterPermission
        afterPermission = null
        if (result.values.all { it }) {
            next?.invoke()
        } else if (next != null) {
            toast("Autorisation refusée. Vous pouvez l'accorder dans les réglages de l'application.")
        }
        // The connection waits for the Bluetooth permission on Android 12+
        if (Graph.ring.hasConnectPermission()) Graph.startRing(this)
    }

    private val chooser = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val ring = CompanionPairing.parseResult(res.data)
        if (ring == null) toast("Bague non reconnue, essayez la recherche manuelle.") else usePairedRing(ring)
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Graph.startRing(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tabRequest.value = intent?.getStringExtra(EXTRA_TAB)
        setContent {
            ZikrTheme {
                CompositionLocalProvider(LocalActions provides this) {
                    ZikrAppUi(tabRequest)
                }
            }
        }
        if (savedInstanceState == null) askStartupPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tabRequest.value = it }
    }

    override fun onStart() {
        super.onStart()
        Graph.foreground = true
        Graph.startRing(this)
    }

    override fun onStop() {
        super.onStop()
        Graph.foreground = false
        closeScan()
        if (!RingService.isWanted(this)) Graph.stopRing(this)
    }

    private fun askStartupPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31 && Graph.engine.data.value.ring != null) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    /** Asks for what Bluetooth needs on this Android version, then runs [then]. */
    private fun withBluetoothPermissions(manualScan: Boolean, then: () -> Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (manualScan) add(Manifest.permission.BLUETOOTH_SCAN)
            } else if (manualScan) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        // Notifications are nice to have; don't block pairing on them
        val required = needed.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (required.isEmpty()) {
            then()
            if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        } else {
            afterPermission = then
            permissions.launch(needed.toTypedArray())
        }
    }

    // ------------------------------------------------------------------ AppActions

    override fun pairRing() = withBluetoothPermissions(manualScan = false) {
        if (!CompanionPairing.isAvailable(this)) {
            scanRings()
            return@withBluetoothPermissions
        }
        CompanionPairing.request(
            this,
            onChooser = { sender ->
                runOnUiThread {
                    try {
                        chooser.launch(IntentSenderRequest.Builder(sender).build())
                    } catch (e: Exception) {
                        scanRings()
                    }
                }
            },
            onFail = { runOnUiThread { scanRings() } },
        )
    }

    override fun scanRings() = withBluetoothPermissions(manualScan = true) {
        scanner?.stop()
        scanner = RingScanner(this) { scanState.value = it }.also { it.start() }
    }

    override fun chooseScanned(address: String, name: String) {
        closeScan()
        usePairedRing(PairedRing(address.uppercase(), name))
    }

    override fun closeScan() {
        scanner?.stop()
        scanner = null
        scanState.value = null
    }

    private fun usePairedRing(ring: PairedRing) {
        val old = Graph.engine.data.value.ring
        if (old != null && !old.address.equals(ring.address, ignoreCase = true)) CompanionPairing.forget(this, old.address)
        Graph.engine.setRing(ring)
        Graph.ring.restart()
        Graph.startRing(this)
        tabRequest.value = TAB_HOME
        toast("Bague associée : ${ring.name}")
    }

    override fun forgetRing() {
        val ring = Graph.engine.data.value.ring ?: return
        CompanionPairing.forget(this, ring.address)
        Graph.engine.setRing(null)
        Graph.stopRing(this)
        Graph.ring.restart()
    }

    override fun requestBluetoothOn() = withBluetoothPermissions(manualScan = false) {
        try {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
        }
    }

    override fun requestConnectPermission() {
        val missing = Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        if (missing && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) && askedBefore()) {
            // Android won't ask again: open the app's settings instead
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                toast("Autorisez « Appareils à proximité ».")
            } catch (_: Exception) {}
            return
        }
        markAsked()
        withBluetoothPermissions(manualScan = false) { Graph.startRing(this) }
    }

    private fun askedBefore() = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("askedConnect", false)
    private fun markAsked() = getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("askedConnect", true).apply()

    override fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    override fun isIgnoringBattery(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try { startActivity(Intent.createChooser(send, "Partager")) } catch (_: Exception) {}
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
