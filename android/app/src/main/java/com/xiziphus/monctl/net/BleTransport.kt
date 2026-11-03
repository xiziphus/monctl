package com.xiziphus.monctl.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** The Mac answered, but the token's key didn't open (or didn't seal) the message. */
class BleAuthException : IOException("token rejected over Bluetooth")

/**
 * The Bluetooth LE route to ble/monctl-ble.swift on the Mac, which forwards to
 * server.py on loopback. Used when neither the LAN nor the tunnel answers.
 *
 * One connection is kept open while requests keep coming and dropped after
 * [IDLE_MS] of quiet. Every message is sealed with a key derived from the
 * token (see BleCrypto); reading the NONCE characteristic starts a session,
 * and each request carries a counter that must increase within it.
 */
@SuppressLint("MissingPermission")   // every entry point checks [permitted] first
object BleTransport {
    private val SERVICE = UUID.fromString("6D6F6E63-746C-4000-8000-000000000001")
    private val NONCE_C = UUID.fromString("6D6F6E63-746C-4000-8000-000000000002")
    private val REQ_C = UUID.fromString("6D6F6E63-746C-4000-8000-000000000003")
    private val RESP_C = UUID.fromString("6D6F6E63-746C-4000-8000-000000000004")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val AUTH_FAIL = -1            // 0xFFFFFFFF
    private const val IDLE_MS = 30_000L
    private const val SCAN_MS = 8_000L
    private const val STEP_MS = 10_000L

    data class Response(val status: Int, val body: ByteArray)

    private class Link(val gatt: BluetoothGatt, val cb: Callback) {
        lateinit var session: ByteArray
        lateinit var key: ByteArray
        lateinit var token: String
        var counter = 0
        var mtu = 23
    }

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var link: Link? = null
    private var idle: Job? = null

    fun permitted(ctx: Context): Boolean {
        val need = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return need.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** What Settings asks for before turning the route on. */
    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun adapterOn(ctx: Context): Boolean =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Throws IOException on any transport failure, BleAuthException on a token mismatch. */
    suspend fun request(ctx: Context, token: String, method: String, path: String, body: String?): Response =
        lock.withLock {
            idle?.cancel()
            try {
                // A link the Mac dropped (range, sleep, helper restart) fails at
                // its first write, before anything is delivered: reconnect first.
                link?.takeIf { it.cb.dead }?.let { close() }
                val l = link?.takeIf { it.token == token } ?: run { close(); connect(ctx, token).also { link = it } }
                exchange(l, method, path, body)
            } catch (e: Throwable) {
                close()
                throw if (e is IOException) e else IOException("Bluetooth: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                idle = scope.launch {
                    delay(IDLE_MS)
                    lock.withLock { close() }
                }
            }
        }

    private fun close() {
        link?.let { runCatching { it.gatt.disconnect(); it.gatt.close() } }
        link = null
    }

    // --- connect: scan, GATT, MTU, services, notifications, session nonce ---

    private suspend fun connect(ctx: Context, token: String): Link {
        if (!permitted(ctx)) throw IOException("Bluetooth permission not granted")
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("no Bluetooth on this phone")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = scan(adapter.bluetoothLeScanner ?: throw IOException("Bluetooth is off"))

        val cb = Callback()
        val gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("couldn't open a Bluetooth connection")
        val l = Link(gatt, cb)
        try {
            step("connect") { cb.connected.await() }
            gatt.requestMtu(517)
            l.mtu = runCatching { step("mtu") { cb.mtu.await() } }.getOrDefault(23)
            if (!gatt.discoverServices()) throw IOException("service discovery refused")
            step("services") { cb.services.await() }
            val svc = gatt.getService(SERVICE) ?: throw IOException("the Mac's MonCtl service is missing")
            val resp = svc.getCharacteristic(RESP_C)
            gatt.setCharacteristicNotification(resp, true)
            val cccd = resp.getDescriptor(CCCD) ?: throw IOException("notify descriptor missing")
            cb.descriptor = CompletableDeferred()
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            step("notify") { cb.descriptor.await() }
            cb.read = CompletableDeferred()
            if (!gatt.readCharacteristic(svc.getCharacteristic(NONCE_C))) throw IOException("nonce read refused")
            l.session = step("nonce") { cb.read.await() }
            if (l.session.size != 16) throw IOException("bad session nonce")
            l.key = BleCrypto.key(token)
            l.token = token
            return l
        } catch (e: Throwable) {
            runCatching { gatt.disconnect(); gatt.close() }
            throw e
        }
    }

    private suspend fun scan(scanner: android.bluetooth.le.BluetoothLeScanner): BluetoothDevice {
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                found.complete(r.device)
            }

            override fun onScanFailed(code: Int) {
                found.completeExceptionally(IOException("Bluetooth scan failed ($code)"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        return try {
            withTimeout(SCAN_MS) { found.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("Mac not found over Bluetooth (out of range, or monctl-ble not running)")
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    // --- one request ----------------------------------------------------------

    private suspend fun exchange(l: Link, method: String, path: String, body: String?): Response {
        val req = kotlinx.serialization.json.buildJsonObject {
            put("m", kotlinx.serialization.json.JsonPrimitive(method))
            put("p", kotlinx.serialization.json.JsonPrimitive(path))
            if (body != null) put("b", MonctlClient.json.parseToJsonElement(body))
        }
        val counter = ++l.counter
        val sealed = BleCrypto.seal(l.key, BleCrypto.aad(l.session, 'Q', counter), req.toString().toByteArray())
        val frame = BleCrypto.be32(counter) + sealed

        val reply = CompletableDeferred<ByteArray>()
        l.cb.reply = reply
        l.cb.inbox.reset()
        val chr = l.gatt.getService(SERVICE)?.getCharacteristic(REQ_C) ?: throw IOException("request characteristic missing")
        val size = (l.mtu - 3 - 1).coerceAtLeast(19)
        var i = 0
        while (i < frame.size) {
            val end = minOf(i + size, frame.size)
            val chunk = byteArrayOf(if (end == frame.size) 1 else 0) + frame.copyOfRange(i, end)
            l.cb.write = CompletableDeferred()
            writeCharacteristic(l.gatt, chr, chunk)
            step("write") { l.cb.write.await() }
            i = end
        }
        // Wi-Fi joins take up to 45 s on the Mac.
        val msg = withTimeout(80_000) { reply.await() }
        val got = BleCrypto.readBe32(msg)
        if (got == AUTH_FAIL) throw BleAuthException()
        if (got != counter || msg.size < 4 + 12 + 16) throw IOException("Bluetooth reply out of order")
        val plain = try {
            BleCrypto.open(l.key, BleCrypto.aad(l.session, 'R', counter), msg.copyOfRange(4, msg.size))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw BleAuthException()
        }
        val status = (plain[0].toInt() and 0xFF shl 8) or (plain[1].toInt() and 0xFF)
        return Response(status, plain.copyOfRange(2, plain.size))
    }

    private suspend fun <T> step(what: String, block: suspend () -> T): T = try {
        withTimeout(STEP_MS) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw IOException("Bluetooth $what timed out")
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = v
            g.writeCharacteristic(c)
        }
        if (!ok) throw IOException("Bluetooth write refused")
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
        if (!ok) throw IOException("Bluetooth descriptor write refused")
    }

    /**
     * GATT callbacks arrive on a binder thread; each operation waits on its
     * own deferred. API 33 added value-carrying overloads and stopped calling
     * the old ones, so both are implemented.
     */
    private class Callback : BluetoothGattCallback() {
        val connected = CompletableDeferred<Unit>()
        val mtu = CompletableDeferred<Int>()
        val services = CompletableDeferred<Unit>()
        var descriptor = CompletableDeferred<Unit>()
        var read = CompletableDeferred<ByteArray>()
        var write = CompletableDeferred<Unit>()
        var reply = CompletableDeferred<ByteArray>()
        val inbox = ByteArrayOutputStream()
        @Volatile
        var dead = false

        private fun failAll(e: IOException) {
            listOf(connected, mtu, services, descriptor, read, write, reply).forEach { it.completeExceptionally(e) }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            when {
                state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> connected.complete(Unit)
                state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS -> {
                    dead = true
                    failAll(IOException("Bluetooth disconnected ($status)"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this.mtu.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) services.complete(Unit)
            else services.completeExceptionally(IOException("service discovery failed ($status)"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) descriptor.complete(Unit)
            else descriptor.completeExceptionally(IOException("notify setup failed ($status)"))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) read.complete(value)
            else read.completeExceptionally(IOException("read failed ($status)"))
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) write.complete(Unit)
            else write.completeExceptionally(IOException("write failed ($status): the Mac is busy or the session ended"))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isEmpty()) return
            synchronized(inbox) {
                inbox.write(value, 1, value.size - 1)
                if (value[0].toInt() and 1 == 1) {
                    val msg = inbox.toByteArray()
                    inbox.reset()
                    reply.complete(msg)
                }
            }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, c, c.value ?: ByteArray(0))
        }
    }
}
