// ==========================================================================
// BleDeviceManager.kt -- Android BLE device management
//
// Handles BLE scanning, connection, and device control for Lovense and
// other BLE vibrators. Uses Android's BluetoothLeScanner for discovery
// and BluetoothGatt for communication.
// ==========================================================================

package com.ashairfoil.prism.haptics

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.*

// ---------------------------------------------------------------------------
// Device info
// ---------------------------------------------------------------------------

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int = 0,
    val isLovense: Boolean = false
)

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Ready
}

// ---------------------------------------------------------------------------
// BleDeviceManager
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
class BleDeviceManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    /** Check if BLE permissions are granted (required on Android 12+). */
    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, "android.permission.BLUETOOTH_CONNECT") ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, "android.permission.BLUETOOTH_SCAN") ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_FINE_LOCATION") ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /** Returns the list of required BLE permissions that are not yet granted. */
    fun getMissingPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(context, "android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED)
                needed.add("android.permission.BLUETOOTH_CONNECT")
            if (ContextCompat.checkSelfPermission(context, "android.permission.BLUETOOTH_SCAN") != PackageManager.PERMISSION_GRANTED)
                needed.add("android.permission.BLUETOOTH_SCAN")
        } else {
            if (ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED)
                needed.add("android.permission.ACCESS_FINE_LOCATION")
        }
        return needed
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // Session tracking: incremented on each connect, callbacks check this to ignore stale GATT
    @Volatile private var gattSession = 0

    // State
    @Volatile var connectionState: ConnectionState = ConnectionState.Disconnected
        private set
    @Volatile var connectedDeviceName: String? = null
        private set
    @Volatile var batteryLevel: Int = -1
        private set

    // Device capabilities (detected on connect via DeviceType response)
    @Volatile var isDualMotor: Boolean = false
        private set
    @Volatile var deviceTypeLetter: Char = ' '
        private set

    // BLE write gating -- Lovense devices drop commands if sent too fast.
    // We wait for the onCharacteristicWrite callback before sending the next.
    @Volatile private var writeInFlight = false
    private var pendingCommand: String? = null
    private val writeLock = Object()
    private var lastWriteMs: Long = 0
    private val minWriteIntervalMs = 50L  // 20Hz max command rate

    // Scan results (synchronized — accessed from Binder scan callback + UI thread)
    private val discoveredDevices = Collections.synchronizedMap(mutableMapOf<String, BleDeviceInfo>())
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // Callbacks
    var onDeviceDiscovered: ((BleDeviceInfo) -> Unit)? = null
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onBatteryUpdate: ((Int) -> Unit)? = null
    var onScanStopped: (() -> Unit)? = null

    // Auto-connect: when true, connect to first Lovense device found
    private var autoConnect = true

    // Known-device allowlist: only auto-connect to previously approved devices
    private val knownDeviceAddresses = java.util.concurrent.CopyOnWriteArraySet<String>()

    // Known Lovense service/characteristic UUID sets.
    // Older devices use Nordic UART; newer firmware uses Lovense-specific UUIDs.
    data class ServiceUuids(val service: UUID, val tx: UUID, val rx: UUID)

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    /** Start scanning for BLE devices. Auto-stops after timeout.
     *  When autoConnect is true, automatically connects to first Lovense device.
     */
    fun startScan(context: Context? = null): Boolean {
        android.util.Log.i(TAG, "startScan: scanner=$scanner isScanning=$isScanning adapter=${bluetoothAdapter?.isEnabled}")
        if (!hasBlePermissions()) {
            android.util.Log.e(TAG, "BLE permissions not granted! Missing: ${getMissingPermissions()}")
            return false
        }
        if (scanner == null) { android.util.Log.e(TAG, "No BLE scanner available!"); return false }
        if (isScanning) return false
        discoveredDevices.clear()
        autoConnect = true

        try {
            scanner.startScan(scanCallback)
            isScanning = true
            android.util.Log.i(TAG, "BLE scan STARTED — looking for Lovense devices...")
            handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "BLE scan start failed: ${e.message}", e)
            return false
        }
    }

    /** Stop scanning. */
    fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        isScanning = false
        if (connectionState == ConnectionState.Disconnected) {
            handler.post { onScanStopped?.invoke() }
        }
    }

    fun getDiscoveredDevices(): List<BleDeviceInfo> = synchronized(discoveredDevices) { discoveredDevices.values.toList() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName
                ?: device.name
                ?: discoveredDevices[device.address]?.name

            // Check service UUIDs for Lovense even if name is null
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val hasLovenseService = KNOWN_SERVICES.any { it.service in serviceUuids }
            val isLovense = hasLovenseService ||
                (name != null && (name.startsWith("LVS-") || name.contains("Lovense", ignoreCase = true)))

            val displayName = name ?: "Unknown-${device.address.takeLast(5)}"
            if (isLovense) {
                android.util.Log.i(TAG, "Found Lovense device: $displayName rssi=${result.rssi}")
            }

            val info = BleDeviceInfo(
                name = displayName,
                address = device.address,
                rssi = result.rssi,
                isLovense = isLovense
            )
            discoveredDevices[device.address] = info
            onDeviceDiscovered?.invoke(info)

            // Auto-connect to first Lovense device found
            if (isLovense && autoConnect && connectionState == ConnectionState.Disconnected) {
                android.util.Log.i(TAG, "Auto-connecting to Lovense: $displayName (${device.address})")
                autoConnect = false
                // Stop scanner without firing onScanStopped (we're about to connect)
                isScanning = false
                val cb: ScanCallback = this
                try { scanner?.stopScan(cb) } catch (_: Exception) {}
                connect(device.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            android.util.Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            isScanning = false
        }
    }

    // -----------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------

    /** Approve a device address for auto-connect. */
    fun approveDevice(address: String) {
        knownDeviceAddresses.add(address)
    }

    /** Connect to a device by address. */
    fun connect(address: String): Boolean {
        knownDeviceAddresses.add(address)
        if (!hasBlePermissions()) {
            android.util.Log.e(TAG, "BLE permissions not granted for connect!")
            return false
        }
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
        // Close any existing connection to prevent resource leak
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        gattSession++ // invalidate any pending callbacks from old connection
        connectionState = ConnectionState.Connecting
        onConnectionStateChanged?.invoke(connectionState)

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        return gatt != null
    }

    /** Disconnect from the current device. */
    fun disconnect() {
        stopScan()
        // Safety: stop motors before tearing down connection
        try {
            val wc = writeCharacteristic
            val g0 = gatt
            if (wc != null && g0 != null) {
                wc.value = "Vibrate:0;".toByteArray(Charsets.US_ASCII)
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g0.writeCharacteristic(wc)
            }
        } catch (_: Exception) {}
        val g = gatt
        gatt = null
        // disconnect() is async — close() should ideally wait for onConnectionStateChange,
        // but we close on a delay to avoid undefined behavior on some BLE stacks
        g?.disconnect()
        if (g != null) {
            handler.postDelayed({ g.close() }, 300)
        }
        writeCharacteristic = null
        notifyCharacteristic = null
        connectionState = ConnectionState.Disconnected
        connectedDeviceName = null
        batteryLevel = -1
        isDualMotor = false
        deviceTypeLetter = ' '
        onConnectionStateChanged?.invoke(connectionState)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        /** Ignore callbacks from a stale GATT connection */
        private fun isStale(gatt: BluetoothGatt): Boolean = gatt !== this@BleDeviceManager.gatt

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (isStale(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.e(TAG, "GATT error: status=$status newState=$newState")
                gatt.close()
                this@BleDeviceManager.gatt = null
                writeCharacteristic = null
                connectionState = ConnectionState.Disconnected
                handler.post { onConnectionStateChanged?.invoke(connectionState) }
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    connectionState = ConnectionState.Connected
                    connectedDeviceName = gatt.device.name
                    handler.post { onConnectionStateChanged?.invoke(connectionState) }
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    connectionState = ConnectionState.Disconnected
                    connectedDeviceName = null
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    handler.post { onConnectionStateChanged?.invoke(connectionState) }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isStale(gatt) || status != BluetoothGatt.GATT_SUCCESS) return

            // Try each known Lovense UUID set
            for (uuids in KNOWN_SERVICES) {
                val service = gatt.getService(uuids.service) ?: continue
                val tx = service.getCharacteristic(uuids.tx) ?: continue
                val rx = service.getCharacteristic(uuids.rx) ?: continue
                writeCharacteristic = tx
                notifyCharacteristic = rx
                enableNotificationsAndFinish(gatt, rx)
                return
            }

            // Fallback: scan ALL services for a writable + notifiable pair
            // (covers unknown firmware revisions)
            for (service in gatt.services) {
                var txCandidate: BluetoothGattCharacteristic? = null
                var rxCandidate: BluetoothGattCharacteristic? = null
                for (c in service.characteristics) {
                    val props = c.properties
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                    ) {
                        txCandidate = c
                    }
                    if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        rxCandidate = c
                    }
                }
                if (txCandidate != null && rxCandidate != null) {
                    writeCharacteristic = txCandidate
                    notifyCharacteristic = rxCandidate
                    enableNotificationsAndFinish(gatt, rxCandidate)
                    return
                }
            }
        }

        private fun enableNotificationsAndFinish(gatt: BluetoothGatt, rxChar: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(rxChar, true)
            val descriptor = rxChar.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
            connectionState = ConnectionState.Ready
            android.util.Log.i(TAG, "Lovense device READY: ${gatt.device.name}")
            handler.post { onConnectionStateChanged?.invoke(connectionState) }
            handler.postDelayed({ sendCommand("Battery;") }, 500)
            // Query device type to detect dual-motor devices (Domi 2, Edge, etc.)
            handler.postDelayed({ sendCommand("DeviceType;") }, 1200)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (isStale(gatt)) return
            // Previous write completed -- flush any queued command
            flushPendingWrite()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (isStale(gatt)) return
            if (characteristic.uuid == notifyCharacteristic?.uuid) {
                val response = characteristic.getStringValue(0) ?: return
                parseLovenseResponse(response)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    /**
     * Send a raw string command to the connected device.
     * Respects BLE write gating -- only one write in flight at a time.
     */
    fun sendCommand(command: String): Boolean {
        val characteristic = writeCharacteristic ?: return false
        val g = gatt ?: return false

        synchronized(writeLock) {
            val now = System.currentTimeMillis()
            // Auto-clear writeInFlight if the BLE callback hasn't arrived
            // within 200ms.
            if (writeInFlight && (now - lastWriteMs) > WRITE_TIMEOUT_MS) {
                writeInFlight = false
            }
            if (writeInFlight || (now - lastWriteMs) < minWriteIntervalMs) {
                // Queue this as the next command (overwrites any stale pending)
                pendingCommand = command
                return false
            }
            writeInFlight = true
            lastWriteMs = now
        }

        characteristic.value = command.toByteArray(Charsets.US_ASCII)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return g.writeCharacteristic(characteristic)
    }

    /** Flush pending command after a write completes. */
    private fun flushPendingWrite() {
        val cmd: String?
        synchronized(writeLock) {
            writeInFlight = false
            cmd = pendingCommand
            pendingCommand = null
        }
        cmd?.let { sendCommand(it) }
    }

    /**
     * Set vibration intensity.
     * @param level 0-20 (0 = off, 20 = maximum)
     */
    fun setIntensity(level: Int) {
        if (connectionState != ConnectionState.Ready) return
        val clamped = level.coerceIn(0, 20)
        sendCommand(LovenseProtocol.vibrate(clamped))
    }

    /**
     * Set vibration intensity from float (0.0 - 1.0).
     * Maps to Lovense protocol: Vibrate:X; where X is 0-20
     */
    fun setIntensityFloat(level: Float) {
        if (connectionState != ConnectionState.Ready) return
        val lovenseLevel = (level * 20f).toInt().coerceIn(0, 20)
        sendCommand(LovenseProtocol.vibrate(lovenseLevel))
    }

    /**
     * Set dual motor intensity (for Domi 2, Edge, etc.).
     * Motor 1 = head/primary, Motor 2 = handle/secondary.
     * Falls back to single motor if device doesn't support dual.
     * @param motor1 0-20 (primary motor)
     * @param motor2 0-20 (secondary motor)
     */
    fun setDualIntensity(motor1: Int, motor2: Int) {
        if (connectionState != ConnectionState.Ready) return
        if (isDualMotor) {
            sendCommand(LovenseProtocol.vibrate2(motor1, motor2))
        } else {
            sendCommand(LovenseProtocol.vibrate(maxOf(motor1, motor2).coerceIn(0, 20)))
        }
    }

    /** Request battery level update. */
    fun requestBattery() {
        if (connectionState == ConnectionState.Ready) {
            sendCommand(LovenseProtocol.battery())
        }
    }

    private fun parseLovenseResponse(response: String) {
        val trimmed = response.trim().removeSuffix(";")

        // Simple numeric response: "85;" -> battery 85%
        trimmed.toIntOrNull()?.let { level ->
            if (level in 0..100) {
                batteryLevel = level
                handler.post { onBatteryUpdate?.invoke(level) }
                return
            }
        }

        // Newer Lovense firmware: battery response as "Bxx;" where xx is 1-100
        if (trimmed.length >= 2 && trimmed[0].uppercaseChar() == 'B') {
            trimmed.substring(1).toIntOrNull()?.let { level ->
                if (level in 0..100) {
                    batteryLevel = level
                    handler.post { onBatteryUpdate?.invoke(level) }
                    return
                }
            }
        }

        // DeviceType response: single letter identifying the device model
        // Dual-motor devices: J=Domi2, W=Edge, A=Nora (vibe+rotate), P=Edge2
        if (trimmed.length == 1 && trimmed[0].isLetter()) {
            deviceTypeLetter = trimmed[0].uppercaseChar()
            isDualMotor = deviceTypeLetter in DUAL_MOTOR_TYPES
            android.util.Log.i(TAG, "DeviceType: '$deviceTypeLetter' isDualMotor=$isDualMotor")
            return
        }
        // Some firmware returns "Jxx:yy:zz;" format
        if (trimmed.isNotEmpty() && trimmed[0].isLetter() && trimmed.contains(':')) {
            deviceTypeLetter = trimmed[0].uppercaseChar()
            isDualMotor = deviceTypeLetter in DUAL_MOTOR_TYPES
            android.util.Log.i(TAG, "DeviceType(ext): '$deviceTypeLetter' isDualMotor=$isDualMotor")
        }
    }

    companion object {
        private const val TAG = "ChloeVR-BLE"

        // Lovense device type letters with dual vibration motors
        // J=Domi2, W=Edge, P=Edge2, A=Nora(vibe+rotate)
        private val DUAL_MOTOR_TYPES = setOf('J', 'W', 'P', 'A')

        private val KNOWN_SERVICES = listOf(
            // Nordic UART Service (older Lovense firmware)
            ServiceUuids(
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
            ),
            // Lovense-specific service (newer firmware, Domi 2 / Mission etc.)
            ServiceUuids(
                UUID.fromString("50300001-0023-4bd4-bbd5-a6920e4c5653"),
                UUID.fromString("50300002-0023-4bd4-bbd5-a6920e4c5653"),
                UUID.fromString("50300003-0023-4bd4-bbd5-a6920e4c5653")
            ),
            // Alternate Lovense service (some newer models)
            ServiceUuids(
                UUID.fromString("53300001-0023-4bd4-bbd5-a6920e4c5653"),
                UUID.fromString("53300002-0023-4bd4-bbd5-a6920e4c5653"),
                UUID.fromString("53300003-0023-4bd4-bbd5-a6920e4c5653")
            ),
            // Another variant seen on some Lovense devices
            ServiceUuids(
                UUID.fromString("57300001-0023-4bd4-bbd5-a6920e4c5653"),
                UUID.fromString("57300002-0023-4bd4-bbd5-a6920e4c5653"),
                UUID.fromString("57300003-0023-4bd4-bbd5-a6920e4c5653")
            )
        )

        /** CCCD descriptor UUID for enabling notifications. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15_000L
        /** Auto-clear writeInFlight if BLE callback hasn't arrived within this window. */
        private const val WRITE_TIMEOUT_MS = 200L
    }
}
