package app.zikrring

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import app.zikrring.data.PairedRing
import app.zikrring.ring.RingUuids
import app.zikrring.ui.FoundRing
import app.zikrring.ui.ScanUi
import java.util.regex.Pattern

/**
 * Pairs the ring through Android's own device picker. The app never scans itself this way,
 * so it needs no location permission.
 */
@SuppressLint("MissingPermission")
object CompanionPairing {
    fun isAvailable(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    fun request(activity: Activity, onChooser: (IntentSender) -> Unit, onFail: (String) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) { onFail("Sélecteur d'appareils indisponible."); return }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("^${RingUuids.NAME_PREFIX}.*", Pattern.CASE_INSENSITIVE))
            .build()
        val req = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        try {
            cdm.associate(req, object : CompanionDeviceManager.Callback() {
                @Deprecated("Android 12 and older")
                override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)

                override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

                override fun onFailure(error: CharSequence?) = onFail(error?.toString() ?: "")
            }, null)
        } catch (e: Exception) {
            onFail(e.message ?: "")
        }
    }

    /** Reads the chosen ring from the picker's answer. */
    @Suppress("DEPRECATION")
    fun parseResult(data: Intent?): PairedRing? {
        if (data == null) return null
        val extra: Parcelable? = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        val device: BluetoothDevice? = when (extra) {
            is ScanResult -> extra.device
            is BluetoothDevice -> extra
            else -> null
        }
        var address: String? = device?.address
        var name: String? = try {
            (extra as? ScanResult)?.scanRecord?.deviceName ?: device?.name
        } catch (_: SecurityException) {
            null
        }
        if (address == null && Build.VERSION.SDK_INT >= 33) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            address = info?.deviceMacAddress?.toString()
            if (name == null) name = info?.displayName?.toString()
        }
        return address?.let { PairedRing(it.uppercase(), name?.takeIf { n -> n.isNotBlank() } ?: "Zikr Ring") }
    }

    /** Removes the system association so Android stops treating the ring as ours. */
    @Suppress("DEPRECATION")
    fun forget(ctx: Context, address: String) {
        if (!isAvailable(ctx)) return
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations
                    .filter { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
                    .forEach { cdm.disassociate(it.id) }
            } else {
                cdm.associations.filter { it.equals(address, ignoreCase = true) }.forEach { cdm.disassociate(it) }
            }
        } catch (_: Exception) {
        }
    }
}

/** Plain Bluetooth search, used when the system picker isn't there or finds nothing. */
@SuppressLint("MissingPermission")
class RingScanner(private val ctx: Context, private val onUpdate: (ScanUi) -> Unit) {
    private val found = LinkedHashMap<String, FoundRing>()
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it) }

        override fun onScanFailed(errorCode: Int) {
            stop()
            onUpdate(ScanUi(false, list(), "La recherche a échoué (code $errorCode). Éteignez et rallumez le Bluetooth, puis réessayez."))
        }
    }

    fun start() {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onUpdate(ScanUi(false, emptyList(), "Activez le Bluetooth, puis réessayez."))
            return
        }
        if (Build.VERSION.SDK_INT in 28..30) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            if (lm != null && !lm.isLocationEnabled) {
                onUpdate(
                    ScanUi(
                        false, emptyList(),
                        "Sur cette version d'Android, la recherche Bluetooth ne marche que si la localisation est activée. " +
                            "Activez-la le temps de la recherche (l'application ne lit pas votre position), " +
                            "ou utilisez « Chercher ma bague ».",
                    ),
                )
                return
            }
        }
        val s = adapter.bluetoothLeScanner
        if (s == null) {
            onUpdate(ScanUi(false, emptyList(), "Recherche Bluetooth indisponible."))
            return
        }
        stop()
        found.clear()
        try {
            s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (e: SecurityException) {
            onUpdate(ScanUi(false, emptyList(), "Autorisation Bluetooth refusée."))
            return
        }
        scanner = s
        onUpdate(ScanUi(true, emptyList()))
        handler.postDelayed({
            stop()
            onUpdate(ScanUi(false, list()))
        }, 15_000)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        scanner = null
    }

    private fun add(r: ScanResult) {
        val name = try {
            r.scanRecord?.deviceName ?: r.device.name
        } catch (_: SecurityException) {
            null
        }
        if (name.isNullOrBlank()) return
        found[r.device.address] = FoundRing(r.device.address, name, r.rssi)
        if (scanner != null) onUpdate(ScanUi(true, list()))
    }

    private fun list(): List<FoundRing> = found.values.sortedWith(
        compareByDescending<FoundRing> { it.name.startsWith(RingUuids.NAME_PREFIX, ignoreCase = true) }.thenByDescending { it.rssi },
    )
}
