package tools.fmc.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// The keyless way out of a boot loop on a Watch 3 Pro (fmc_frontend `Watch.shellBlast`, docs/rescue.md "The shell").
// The face list lives in RAM that outlives the crash reboot (WfmPara_Load reads it at 0x3800f73a, fw 1.0.1.77), and the
// shell characteristic runs `dbg` with no auth: a zero over the list's magic makes the next boot rebuild it from the
// stock faces alone. A real looping watch answers no ATT request at all (fmc.tools logs, Windows and Android), so the
// command can't wait for a discovery: it goes to the shell's handle as Write Without Response from the moment the
// link is up, through a characteristic made by hand (handle 62 in the 1.0.1.77 GATT table), every 15 ms for 1.5 s.
// Then a discovery and `AT GETVERSION`: only a watch that stopped looping answers them.
// ponytail: the handle and the address are that firmware's; another build needs its own. The characteristic's handle
// and device are set by reflection, so an Android that hides those fields gets the discovery path only (logged).

@SuppressLint("MissingPermission") // the screen asks for BLUETOOTH_CONNECT/SCAN before it starts
class ShellRescuer(private val context: Context, private val emit: (Rescuer.Event) -> Unit) {
    private class Conn(val gatt: BluetoothGatt, val at: Long, val auto: Boolean) {
        var blast: ScheduledFuture<*>? = null
        var giveUp: ScheduledFuture<*>? = null
        var writes = 0
        var read: BluetoothGattCharacteristic? = null
        var write: BluetoothGattCharacteristic? = null
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val started = SystemClock.elapsedRealtime()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    /** The first Watch 3 Pro seen: no other watch nearby is touched after it. */
    private var address: String? = null
    private val clients = mutableMapOf<BluetoothGatt, Boolean>() // gatt -> autoConnect
    private var active: Conn? = null
    private var stopped = false

    private fun say(c: Conn?, text: String) {
        val t = "%7.3f".format((SystemClock.elapsedRealtime() - started) / 1000.0)
        emit(Rescuer.Event.Log(t + (c?.let { " +${SystemClock.elapsedRealtime() - it.at}ms" } ?: "") + " " + text))
    }

    private fun isPro3(name: String?) = name != null && PRO3.containsMatchIn(name)

    fun start(): Unit = synchronized(this) {
        val adapter = manager?.adapter ?: return say(null, "no bluetooth")
        // a bonded watch is connected by the system the moment it advertises, and then stops advertising: ride that
        // link (autoConnect) as well as scanning
        adapter.bondedDevices.firstOrNull { isPro3(it.name) && it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }?.let {
            address = it.address
            val g = it.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
            clients[g] = true
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scan)
        say(null, "shell: waiting for a Watch 3 Pro${if (address != null) " (bonded)" else ""}, scanning")
    }

    fun stop(): Unit = synchronized(this) {
        stopped = true
        timer.shutdownNow()
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scan) }
        clients.keys.forEach { it.disconnect(); it.close() }
        clients.clear()
        active = null
    }

    /** The scan only finds the watch. From then on a direct connect is always pending, so the controller takes the
     *  watch's first advertisement after each restart: one opened on a scan result came ~400 ms later in the
     *  emulator, past the fault, and the shell opens only 200 ms into a link. */
    private val scan = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult): Unit = synchronized(this@ShellRescuer) {
            if (stopped || address != null && r.device.address != address || !isPro3(r.scanRecord?.deviceName ?: r.device.name)) return
            address = r.device.address
            say(null, "found, rssi ${r.rssi}")
            runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(this) }
            arm(r.device)
        }
    }

    // ponytail: the GATT cache is left alone. A bond's cache that matches the watch's Database Hash spares the stack's
    // own discovery, which is what our writes wait behind; refresh() would force it on every link (emulator).
    private fun arm(d: BluetoothDevice) {
        if (stopped || clients.values.any { !it }) return
        val g = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        clients[g] = false
    }

    /** The shell's write characteristic without a discovery: what BluetoothGatt reads off one is its service's device
     *  and its own instance id, which is the ATT handle. */
    private fun blindShell(d: BluetoothDevice): BluetoothGattCharacteristic? = runCatching {
        val svc = BluetoothGattService(SHELL_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val ch = BluetoothGattCharacteristic(SHELL_WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        svc.addCharacteristic(ch) // sets the characteristic's service
        BluetoothGattCharacteristic::class.java.getDeclaredField("mInstance").apply { isAccessible = true }.setInt(ch, SHELL_WRITE_HANDLE)
        BluetoothGattService::class.java.getDeclaredField("mDevice").apply { isAccessible = true }.set(svc, d)
        ch
    }.onFailure { say(active, "no blind write on this Android: $it") }.getOrNull()

    private fun send(g: BluetoothGatt, ch: BluetoothGattCharacteristic, line: String): Boolean {
        val data = "$line\r\n".toByteArray()
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, data, type) == 0
        else @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = data; g.writeCharacteristic(ch) }
    }

    /** Let a missed try go: a direct client closes and the scan opens the next one; an autoConnect one stays armed
     *  and reconnects by itself after the watch restarts. */
    private fun miss(c: Conn, why: String) {
        if (active !== c) return
        say(c, why)
        active = null
        c.blast?.cancel(false)
        c.giveUp?.cancel(false)
        emit(Rescuer.Event.Dropped)
        if (!c.auto) { clients.remove(c.gatt); c.gatt.disconnect(); c.gatt.close(); arm(c.gatt.device) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int): Unit = synchronized(this@ShellRescuer) {
            if (stopped || g !in clients) return
            if (active?.gatt !== g) say(active, "link state $state, status $status${if (clients[g] == true) " (autoConnect)" else ""}")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if (active != null) return // the other client of the same link
                val c = Conn(g, SystemClock.elapsedRealtime(), clients[g] == true)
                active = c
                emit(Rescuer.Event.Connected)
                say(c, "connected${if (c.auto) " (autoConnect)" else ""}")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) // 7.5–15 ms: more writes in the window
                val ch = blindShell(g.device)
                val end = c.at + BLAST_MS
                c.blast = timer.scheduleWithFixedDelay({
                    synchronized(this@ShellRescuer) {
                        if (active !== c) return@synchronized
                        if (SystemClock.elapsedRealtime() < end && ch != null) {
                            if (send(g, ch, COMMAND)) c.writes++
                        } else {
                            c.blast?.cancel(false)
                            say(c, "${c.writes} blind writes, discovering")
                            g.discoverServices()
                            c.giveUp = timer.schedule({ synchronized(this@ShellRescuer) { miss(c, "no answer") } },
                                ANSWER_MS, TimeUnit.MILLISECONDS)
                        }
                    }
                }, 0, EVERY_MS, TimeUnit.MILLISECONDS)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                active?.takeIf { it.gatt === g }?.let { miss(it, "dropped, status $status") }
                // a dropped link or a direct connect that timed out: armed again at once
                if (clients[g] == false) { clients.remove(g); g.close(); arm(g.device) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int): Unit = synchronized(this@ShellRescuer) {
            val c = active?.takeIf { it.gatt === g } ?: return
            val svc = g.getService(SHELL_SERVICE)
            c.read = svc?.getCharacteristic(SHELL_READ)
            c.write = svc?.getCharacteristic(SHELL_WRITE)
            val d = c.read?.getDescriptor(CCCD) ?: return say(c, "services, status $status, no shell")
            say(c, "services")
            g.setCharacteristicNotification(c.read, true)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int): Unit = synchronized(this@ShellRescuer) {
            val c = active?.takeIf { it.gatt === g } ?: return
            c.write?.let { say(c, "→ AT GETVERSION${if (send(g, it, "AT GETVERSION")) "" else " (not taken)"}") }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            changed(g, value)

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) changed(g, ch.value.copyOf())
        }
    }

    private fun changed(g: BluetoothGatt, value: ByteArray): Unit = synchronized(this) {
        val c = active?.takeIf { it.gatt === g } ?: return
        val s = String(value).trim()
        say(c, "← $s")
        if ("GETVERSION:" in s) {
            c.giveUp?.cancel(false)
            say(c, "the watch answers: it no longer restarts")
            emit(Rescuer.Event.Done)
        }
    }

    private companion object {
        const val COMMAND = "dbg mwb 0x3800f73a 0"
        const val SHELL_WRITE_HANDLE = 62
        const val EVERY_MS = 15L
        const val BLAST_MS = 1500L
        const val ANSWER_MS = 4000L
        val PRO3 = Regex("watch (3 pro|pro 3)", RegexOption.IGNORE_CASE)
        val SHELL_SERVICE: UUID = UUID.fromString("77d4e67c-2fe2-2334-0d35-9ccd078f529c")
        val SHELL_WRITE: UUID = UUID.fromString("77d4ff01-2fe2-2334-0d35-9ccd078f529c")
        val SHELL_READ: UUID = UUID.fromString("77d4ff02-2fe2-2334-0d35-9ccd078f529c")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
