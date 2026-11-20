package tools.fmc.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import tools.fmc.companion.wire.RescueAction
import tools.fmc.companion.wire.RescuePlan
import tools.fmc.companion.wire.RescueWrite
import tools.fmc.companion.wire.Uuid
import tools.fmc.companion.wire.channelKey
import tools.fmc.companion.wire.hex
import tools.fmc.companion.wire.hexOrNull
import tools.fmc.companion.wire.rescueKeep
import tools.fmc.companion.wire.sha256Hex

// Rescue for a watch that restarts in a loop after a bad face (fmc_frontend /rescue, wire RescuePlan); iOS Rescue.swift.
// The phone waits for the watch without pause and sends one command the moment the stock session is up, on every boot,
// until it lands. Only watches this app holds keys for (SecureStore `stock-<id>`, `k-<id>`, id = sha256 of the
// address), found by that address: nothing on the watch has to be tapped, and no other watch is touched.
//
// Speed: frames are sealed before the watch appears; `connectGatt(autoConnect = true)` to each bonded known watch, and a
// SCAN_MODE_LOW_LATENCY scan that connects directly on the first advertisement while not connected. On connect: MTU
// (frames are 27 bytes; Android 14+ has already exchanged it, older ones stay at 23 otherwise), services (cached for a
// bonded watch), FFF1 notify, then the plan's writes; no info or dial list. Every step is logged with ms since connect.

object RescueModel {
    enum class Phase { IDLE, WAITING, CONNECTED, SENT, DONE, REFUSED }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    val log = mutableStateListOf<String>()
    private var rescuer: Rescuer? = null
    val running get() = phase == Phase.WAITING || phase == Phase.CONNECTED

    /** Watch ids (sha256 of the address, 16 hex) this app holds a key for. */
    fun knownWatches(store: SecureStore): List<String> =
        store.names().mapNotNull { n -> listOf("stock-", "k-").firstOrNull(n::startsWith)?.let { n.removePrefix(it) } }
            .filter { it.length == 16 }.distinct().sorted()

    /** What `Remove custom faces` keeps: the stock dials of the list the watch last reported. */
    fun keep(context: Context) = rescueKeep(facesOnWatch(context).all, stockDials(context).keys)

    fun start(context: Context, store: SecureStore, action: RescueAction) {
        log.clear()
        val plans = knownWatches(store).mapNotNull { id ->
            val k1 = store["stock-$id"]?.let(::hexOrNull)
            val secret = store["k-$id"]?.let(::channelKey)
            RescuePlan.of(action, k1, secret)?.let { plan ->
                add("watch ${id.take(8)}: ${if (k1 != null) "saved key" else "pairing secret"}")
                id to plan
            }
        }.toMap()
        if (plans.isEmpty()) return
        phase = Phase.WAITING
        rescuer = Rescuer(context.applicationContext, plans) { e -> appMain { handle(store, e) } }.also { it.start() }
    }

    fun stop() {
        rescuer?.stop()
        rescuer = null
        if (running) phase = Phase.IDLE
    }

    private fun handle(store: SecureStore, e: Rescuer.Event) {
        when (e) {
            is Rescuer.Event.Log -> add(e.line)
            Rescuer.Event.Connected -> if (phase == Phase.WAITING) phase = Phase.CONNECTED
            Rescuer.Event.Dropped -> if (phase == Phase.CONNECTED) phase = Phase.WAITING
            is Rescuer.Event.Key -> store["stock-${e.id}"] = e.k1.hex()
            Rescuer.Event.Sent -> { phase = Phase.SENT; stop() }
            Rescuer.Event.Done -> { phase = Phase.DONE; stop() }
            Rescuer.Event.Refused -> { phase = Phase.REFUSED; stop() }
        }
    }

    private fun add(line: String) {
        log += line
        DevLog.log(appContext, "rescue $line")
    }
}

private fun appMain(block: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(block)

/** GATT for the rescue. Callbacks come on binder threads: everything below runs under the object's lock. */
@SuppressLint("MissingPermission") // the screen asks for BLUETOOTH_CONNECT/SCAN before it starts
class Rescuer(private val context: Context, private val plans: Map<String, RescuePlan>, private val emit: (Event) -> Unit) {
    sealed class Event {
        class Log(val line: String) : Event()
        data object Connected : Event()
        data object Dropped : Event()
        class Key(val id: String, val k1: ByteArray) : Event()
        data object Sent : Event()
        data object Done : Event()
        data object Refused : Event()
    }

    private class Conn(val id: String, val gatt: BluetoothGatt, val at: Long) {
        var write: BluetoothGattCharacteristic? = null
        var notifying = false
        var mtu = 23
        val queue = ArrayDeque<RescueWrite>()
        var busy = false
        var finish: Event? = null
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val started = SystemClock.elapsedRealtime()
    /** autoConnect clients (bonded watches): they re-arm themselves after a drop. */
    private val auto = mutableMapOf<String, BluetoothGatt>()
    /** Direct clients opened on an advertisement. */
    private val direct = mutableMapOf<String, BluetoothGatt>()
    private val active = mutableMapOf<String, Conn>()
    private val seen = mutableSetOf<String>()
    private var stopped = false
    /** Repeats what the watch hasn't answered every 50 ms: it drops writes until its command service is up. */
    private val retry = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    private fun idOf(d: BluetoothDevice) = sha256Hex(d.address).take(16)

    private fun say(c: Conn?, text: String) {
        val t = "%7.3f".format((SystemClock.elapsedRealtime() - started) / 1000.0)
        emit(Event.Log(t + (c?.let { " +${SystemClock.elapsedRealtime() - it.at}ms" } ?: "") + " " + text))
    }

    fun start(): Unit = synchronized(this) {
        val adapter = manager?.adapter ?: return say(null, "no bluetooth")
        for (d in adapter.bondedDevices) {
            val id = idOf(d)
            if (id in plans && d.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                auto[id] = d.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scan)
        retry.scheduleWithFixedDelay({
            synchronized(this) {
                for (c in active.values) if (c.notifying && !c.busy && c.queue.isEmpty()) enqueue(c, plans.getValue(c.id).retry())
            }
        }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        say(null, "waiting for ${plans.size} known watch(es), ${auto.size} bonded, scanning")
    }

    fun stop(): Unit = synchronized(this) {
        stopped = true
        retry.shutdownNow()
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scan) }
        (auto.values + direct.values).forEach { it.disconnect(); it.close() }
        auto.clear(); direct.clear(); active.clear()
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult): Unit = synchronized(this@Rescuer) {
            val id = idOf(r.device)
            if (stopped || id !in plans) return
            if (seen.add(id)) say(active[id], "advertising, rssi ${r.rssi}")
            if (id !in active && id !in direct) {
                direct[id] = r.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int): Unit = synchronized(this@Rescuer) {
            val id = idOf(g.device)
            if (stopped) return
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if (id in active) return // the other client of the same link
                val c = Conn(id, g, SystemClock.elapsedRealtime())
                active[id] = c
                seen.remove(id)
                emit(Event.Connected)
                say(c, "connected${if (auto[id] === g) " (autoConnect)" else ""}")
                g.requestMtu(247)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                val c = active[id]
                if (c?.gatt === g) {
                    active.remove(id)
                    say(c, "dropped, status $status")
                    emit(Event.Dropped)
                }
                if (direct[id] === g) { direct.remove(id); g.close() } // the scan opens the next one
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int): Unit = synchronized(this@Rescuer) {
            val c = active[idOf(g.device)]?.takeIf { it.gatt === g } ?: return
            c.mtu = mtu
            say(c, "mtu $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int): Unit = synchronized(this@Rescuer) {
            val c = active[idOf(g.device)]?.takeIf { it.gatt === g } ?: return
            fun char(u: String) = g.services.firstNotNullOfOrNull { it.getCharacteristic(UUID.fromString(u)) }
            val w = char(Uuid.CMD_WRITE)
            val n = char(Uuid.CMD_NOTIFY)
            val d = n?.getDescriptor(CCCD)
            if (w == null || d == null) return say(c, "no FFF1/FFF2, status $status")
            c.write = w
            say(c, "services")
            g.setCharacteristicNotification(n, true)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int): Unit = synchronized(this@Rescuer) {
            val c = active[idOf(g.device)]?.takeIf { it.gatt === g } ?: return
            say(c, "notify on")
            c.notifying = true
            enqueue(c, plans.getValue(c.id).connected())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int): Unit = synchronized(this@Rescuer) {
            val c = active[idOf(g.device)]?.takeIf { it.gatt === g } ?: return
            c.busy = false
            if (status != BluetoothGatt.GATT_SUCCESS) say(c, "write status $status")
            pump(c)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            changed(g, ch, value)

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) changed(g, ch, ch.value.copyOf())
        }
    }

    private fun changed(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Unit = synchronized(this) {
        val c = active[idOf(g.device)]?.takeIf { it.gatt === g } ?: return
        if (stopped) return
        val plan = plans.getValue(c.id)
        val before = plan.k1
        val step = plan.notify(ch.uuid.toString(), value)
        step.note?.let { say(c, "← $it") }
        // a bind replaced the authkey on the watch: the saved one is the new one now
        plan.k1?.let { if (!it.contentEquals(before)) emit(Event.Key(c.id, it)) }
        when {
            step.refused -> c.finish = Event.Refused
            step.done -> c.finish = if (plan.action == RescueAction.FactoryReset) Event.Sent else Event.Done
        }
        enqueue(c, step.writes)
    }

    private fun enqueue(c: Conn, writes: List<RescueWrite>) {
        c.queue.addAll(writes)
        pump(c)
    }

    /** One GATT write at a time, as Android requires; the next goes from the previous one's callback. */
    private fun pump(c: Conn) {
        if (c.busy) return
        val w = c.queue.removeFirstOrNull() ?: return c.finish?.let { e ->
            say(c, if (e == Event.Refused) "refused" else "action sent on an authenticated session")
            emit(e)
        } ?: Unit
        val ch = c.write ?: return
        val noResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (w.data.size <= c.mtu - 3 && noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) c.gatt.writeCharacteristic(ch, w.data, type) == 0
        else @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = w.data; c.gatt.writeCharacteristic(ch) }
        say(c, "→ ${w.what}${if (ok) "" else " (not taken)"}")
        c.busy = ok
        if (!ok) pump(c)
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

@Composable
fun RescueScreen(store: SecureStore, back: () -> Unit) {
    val context = LocalContext.current
    val m = RescueModel
    val known = remember { m.knownWatches(store) }
    val keep = remember { m.keep(context) }
    var removeFaces by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(m.running) {
        view.keepScreenOn = m.running
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { m.stop() } }
    val start = withBluetooth {
        m.start(context, store, if (removeFaces && keep != null) keep else RescueAction.FactoryReset)
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(if (removeFaces) R.string.rescue_remove_title else R.string.rescue_erase_title)) },
        text = { Text(stringResource(if (removeFaces) R.string.rescue_remove_text else R.string.rescue_erase_text)) },
        confirmButton = {
            TextButton({ confirming = false; start() }) {
                Text(stringResource(if (removeFaces) R.string.rescue_remove else R.string.rescue_erase), color = Fmc.danger)
            }
        },
        dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.cancel)) } },
    )
    Column {
        Bar(stringResource(R.string.rescue_title), back)
        LazyColumn {
            item { Section { Text(stringResource(R.string.rescue_intro)) } }
            if (known.isEmpty()) item {
                Section {
                    Text(stringResource(R.string.rescue_no_key_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.rescue_no_key))
                }
            } else {
                item {
                    Section(footer = stringResource(if (removeFaces) R.string.rescue_remove_footer else R.string.rescue_reset_footer)) {
                        @Composable
                        fun option(label: Int, value: Boolean) = Row(
                            Modifier.fillMaxWidth().selectable(removeFaces == value, enabled = !m.running) { removeFaces = value },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(removeFaces == value, null, enabled = !m.running)
                            Text(stringResource(label))
                        }
                        option(R.string.rescue_reset, false)
                        if (keep != null) option(R.string.rescue_remove_faces, true)
                    }
                }
                item {
                    Section(footer = stringResource(R.string.rescue_keep_open)) {
                        if (m.running) OutlinedButton({ m.stop() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rescue_stop)) }
                        else Button({ confirming = true }, Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Fmc.danger)) {
                            Text(stringResource(if (removeFaces) R.string.rescue_remove_faces else R.string.rescue_reset))
                        }
                    }
                }
            }
            if (m.phase != RescueModel.Phase.IDLE) item {
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (m.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(when (m.phase) {
                            RescueModel.Phase.CONNECTED -> R.string.rescue_connected
                            RescueModel.Phase.SENT -> R.string.rescue_sent
                            RescueModel.Phase.DONE -> R.string.rescue_done
                            RescueModel.Phase.REFUSED -> R.string.rescue_refused
                            else -> R.string.rescue_waiting
                        }), color = if (m.phase == RescueModel.Phase.REFUSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (m.log.isNotEmpty()) item {
                Section(stringResource(R.string.rescue_log)) {
                    TextButton({
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("rescue", m.log.joinToString("\n")))
                    }) { Text(stringResource(R.string.rescue_copy_log)) }
                    SelectionContainer {
                        Text(m.log.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
