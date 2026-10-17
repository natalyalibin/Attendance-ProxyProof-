package com.example.attendence

import android.util.Log
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock

/** GATT transport owned by the admin service or employee Activity. Advertisements contain no personal data. */
@SuppressLint("MissingPermission")
class BleAttendance(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var scan: ScanCallback? = null
    private var advert: AdvertiseCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var server: BluetoothGattServer? = null
    private var client: BluetoothGatt? = null
    private var timeout: Runnable? = null
    val supported get() = adapter != null
    val enabled get() = adapter?.isEnabled == true

    private fun deadline(millis: Long, action: () -> Unit) {
        clearDeadline()
        timeout = Runnable(action).also { handler.postDelayed(it, millis) }
    }
    private fun clearDeadline() { timeout?.let { handler.removeCallbacks(it) }; timeout = null }

    fun submit(office: String, prepare: (Int) -> Attendance, delivered: (String) -> Unit, failed: (String) -> Unit) {
        stop()
        Log.d("AttendanceBLE", "Starting BLE operation")
        val run = generation
        fun fail(message: String) { Log.w("AttendanceBLE", message); if (generation == run) { stop(); failed(message) } }
        val deviceScanner = adapter?.bluetoothLeScanner ?: return fail("Bluetooth is unavailable. Turn it on and try again.")
        scanner = deviceScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (generation != run || scan !== this) return@post
                    if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(AttendanceRules.officeUuid(office))) != true) return@post
                    stopScan()
                    val entry = try { prepare(result.rssi) } catch (_: Exception) {
                        fail("Couldn’t save the pending attendance. Please try again."); return@post
                    }
                    connect(result.device, office, entry, run, delivered, ::fail)
                }
            }
            override fun onScanFailed(errorCode: Int) { handler.post { if (scan === this) fail("Couldn’t scan (code $errorCode). Wait a moment and retry.") } }
        }
        scan = callback
        deadline(15_000) { fail("Campus station not found. Move closer, check the campus code, and ask the professor to start the station.") }
        try {
            deviceScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(AttendanceRules.officeUuid(office))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (_: Exception) { fail("Couldn’t start scanning. Check Bluetooth and permissions.") }
    }

    private fun connect(device: BluetoothDevice, office: String, entry: Attendance, run: Int,
                        delivered: (String) -> Unit, fail: (String) -> Unit) {
        val packets = try { AttendanceProtocol.packets(entry) } catch (_: Exception) { fail("Invalid attendance record. Couldn’t send it."); return }
        var packetIndex = 0
        var writeCharacteristic: BluetoothGattCharacteristic? = null
        fun writeNext(gatt: BluetoothGatt) {
            val characteristic = writeCharacteristic ?: return fail("This campus station needs the updated app.")
            try {
                val packet = packets[packetIndex]
                Log.d("AttendanceBLE", "Writing packet $packetIndex/${packets.size}, bytes=${packet.size}")
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = packet
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                Log.d("AttendanceBLE", "Write queued=$accepted")
                if (!accepted) fail("Couldn’t send attendance. Retry when the station is ready.")
            } catch (_: Exception) { fail("Bluetooth transfer interrupted. Retry to confirm delivery.") }
        }
        deadline(30_000) { fail("No receipt from the professor. Retry this pending record near the campus station.") }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { Log.d("AttendanceBLE", "Client connection status=$status state=$newState"); handler.post {
                if (generation != run || client !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Connection lost before the receipt. Retry to confirm delivery.")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { if (!gatt.discoverServices()) fail("Couldn’t read the campus service. Please retry.") }
                    catch (_: Exception) { fail("Couldn’t read the campus service. Please retry.") }
                }
            } }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { Log.d("AttendanceBLE", "Services discovered status=$status"); handler.post {
                if (generation != run || client !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Campus service discovery failed. Please retry."); return@post }
                writeCharacteristic = gatt.getService(AttendanceRules.officeUuid(office))?.getCharacteristic(AttendanceProtocol.characteristicUuid)
                writeNext(gatt)
            } }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { Log.d("AttendanceBLE", "Write receipt packet=$packetIndex status=$status"); handler.post {
                if (generation != run || client !== gatt || characteristic.uuid != AttendanceProtocol.characteristicUuid) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Professor couldn’t accept attendance. Check the campus code and retry."); return@post }
                packetIndex++
                if (packetIndex == packets.size) { stop(); delivered(entry.id) }
                else writeNext(gatt)
            } }
        }
        try {
            client = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (client == null) fail("Couldn’t connect to the campus station. Please retry.")
        } catch (_: Exception) { fail("Couldn’t connect to the campus station. Check Bluetooth permissions.") }
    }

    fun broadcast(office: String, receive: (Attendance) -> Boolean, received: (Attendance) -> Unit,
                  started: () -> Unit, failed: (String) -> Unit) {
        stop()
        Log.d("AttendanceBLE", "Starting BLE operation")
        val run = generation
        fun fail(message: String) { Log.w("AttendanceBLE", message); if (generation == run) { stop(); failed(message) } }
        val deviceAdvertiser = adapter?.bluetoothLeAdvertiser ?: return fail("This phone does not support BLE advertising. Use another Android phone for the campus station.")
        advertiser = deviceAdvertiser
        data class Peer(val buffer: AttendanceMessageBuffer = AttendanceMessageBuffer(), var touched: Long = SystemClock.elapsedRealtime())
        val peers = mutableMapOf<String, Peer>()
        fun startAdvertising() {
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { handler.post { if (generation == run) { clearDeadline(); started() } } }
                override fun onStartFailure(errorCode: Int) { handler.post { fail("Couldn’t start the station (code $errorCode). Try again or use another phone.") } }
            }
            advert = callback
            try {
                deviceAdvertiser.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),
                    AdvertiseData.Builder().addServiceUuid(ParcelUuid(AttendanceRules.officeUuid(office))).setIncludeDeviceName(false).build(), callback)
            } catch (_: Exception) { fail("Couldn’t start the station. Check Bluetooth and permissions.") }
        }
        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) { handler.post {
                if (generation != run) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else fail("Couldn’t create the attendance service. Please retry.")
            } }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { handler.post {
                if (generation == run && newState == BluetoothProfile.STATE_DISCONNECTED) peers.remove(device.address)
            } }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                       preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                Log.d("AttendanceBLE", "Server write bytes=${value.size} offset=$offset prepared=$preparedWrite response=$responseNeeded")
                val packet = value.copyOf()
                handler.post {
                    if (generation != run) return@post
                    var status = BluetoothGatt.GATT_FAILURE
                    var newEntry: Attendance? = null
                    try {
                        require(!preparedWrite && responseNeeded && offset == 0 && characteristic.uuid == AttendanceProtocol.characteristicUuid)
                        val now = SystemClock.elapsedRealtime()
                        peers.entries.removeAll { now - it.value.touched > 30_000 }
                        require(peers.containsKey(device.address) || peers.size < 8)
                        val peer = peers.getOrPut(device.address) { Peer() }
                        peer.touched = now
                        val entry = peer.buffer.accept(packet)
                        if (entry != null) {
                            require(entry.office == office)
                            // Persist before acknowledging COMMIT. A lost response can be retried by ID.
                            if (receive(entry)) newEntry = entry
                            peers.remove(device.address)
                        }
                        status = BluetoothGatt.GATT_SUCCESS
                    } catch (error: Exception) { Log.e("AttendanceBLE", "Record/packet rejected", error); peers.remove(device.address) }
                    try { if (responseNeeded) Log.d("AttendanceBLE", "Server response status=$status queued=${server?.sendResponse(device, requestId, status, offset, null)}") } catch (error: Exception) { Log.e("AttendanceBLE", "Response failed", error) }
                    newEntry?.let(received)
                }
            }
            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) { handler.post {
                if (generation == run) {
                    peers.remove(device.address)
                    try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null) } catch (_: Exception) { }
                }
            } }
        }
        deadline(15_000) { fail("The campus station took too long to start. Please retry.") }
        try {
            server = manager?.openGattServer(context, callback)
            val service = BluetoothGattService(AttendanceRules.officeUuid(office), BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(AttendanceProtocol.characteristicUuid, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            if (server?.addService(service) != true) fail("This phone couldn’t open the attendance service. Try another phone.")
        } catch (_: Exception) { fail("Couldn’t open the campus station. Check Bluetooth and permissions.") }
    }

    private fun stopScan() {
        clearDeadline()
        val old = scan; scan = null
        try { old?.let { scanner?.stopScan(it) } } catch (_: Exception) { }
        scanner = null
    }
    fun stop() {
        generation++
        stopScan()
        val oldClient = client; client = null
        try { oldClient?.disconnect() } catch (_: Exception) { }
        try { oldClient?.close() } catch (_: Exception) { }
        val oldAdvert = advert; advert = null
        try { oldAdvert?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) { }
        advertiser = null
        try { server?.close() } catch (_: Exception) { }
        server = null
    }
}
