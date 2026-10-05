package com.tallerbioing.ppgmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

import com.tallerbioing.ppgmonitor.bp.BloodPressureModel
import com.tallerbioing.ppgmonitor.bp.BloodPressurePreprocessor
import com.tallerbioing.ppgmonitor.bp.BloodPressureStatus
import com.tallerbioing.ppgmonitor.bp.BloodPressureTransportEvent
import com.tallerbioing.ppgmonitor.bp.BloodPressureUiState
import com.tallerbioing.ppgmonitor.bp.BloodPressureWindowAssembler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

/**
 * Gestor BLE de APK-PPG para el contrato B18 v2.
 *
 * Responsabilidades:
 * - scan / conexión / reconexión;
 * - NUS GATT;
 * - negociación MTU sin depender de obtener 247;
 * - suscripción CCCD antes de HELLO:2;
 * - ensamblado por LF y parsing v2;
 * - cola serializada de escrituras;
 * - caducidad de B/S/O/R;
 * - exposición de estados a Compose.
 *
 * No recalcula BPM, SpO2 ni PRV.
 */
class BleManager(
    private val context: Context
) {

    companion object {
        private const val DEVICE_NAME = "PPG-Monitor-S3"
        private const val TAG = "B18BleManager"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val HELLO_TIMEOUT_MS = 2_000L
        private const val MTU_TIMEOUT_MS = 1_000L
        private const val COMMAND_TIMEOUT_MS = 2_000L
        private const val PARTIAL_TIMEOUT_MS = B18_PARTIAL_TIMEOUT_MS
        private const val B_STALE_MS = 2_000L
        private const val S_STALE_MS = 500L
        private const val SPO2_MAX_AGE_MS = 2_500L
        private const val PRV_MAX_AGE_MS = 6_000L
        private const val MAX_HELLO_ATTEMPTS = 3
        private const val MAX_COMMAND_ATTEMPTS = 2
        private const val MAX_PPG_SAMPLES = 240

        private val SERVICE_UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val RX_UUID =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val TX_UUID =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val PA_TX_UUID =
            UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    // ---------------------------------------------------------------------
    // Estado observable
    // ---------------------------------------------------------------------

    var connectionState by mutableStateOf("Desconectado")
        private set

    /**
     * true sólo cuando GATT está conectado, TX está suscripto y H:2 fue validado.
     */
    var isConnected by mutableStateOf(false)
        private set

    var lastPacket by mutableStateOf("Sin datos")
        private set

    var negotiatedMtu by mutableIntStateOf(23)
        private set

    var bootId by mutableStateOf<String?>(null)
        private set

    var epoch by mutableStateOf<Long?>(null)
        private set

    var bpm by mutableIntStateOf(0)
        private set

    var bpmVisible by mutableStateOf(false)
        private set

    var bpmStateCode by mutableIntStateOf(0)
        private set

    var bpmAgeMs by mutableStateOf<Long?>(null)
        private set

    var bpmSourceSequence by mutableStateOf<Long?>(null)
        private set

    var activityCode by mutableIntStateOf(-1)
        private set

    var signalQuality by mutableIntStateOf(0)
        private set

    var batteryPercentage by mutableIntStateOf(-1)
        private set

    var spo2 by mutableStateOf<Float?>(null)
        private set

    var spo2Valid by mutableStateOf(false)
        private set

    var spo2Reason by mutableIntStateOf(1)
        private set

    var prv by mutableStateOf<B18Prv?>(null)
        private set

    var diagnostics by mutableStateOf<B18Diagnostics?>(null)
        private set

    var bloodPressureState by mutableStateOf(BloodPressureUiState())
        private set

    val bpmStateText: String
        get() = when (bpmStateCode) {
            1 -> "Sin contacto"
            2 -> "Estabilizando"
            3 -> "Calculando"
            4 -> "Actualizado"
            5 -> "Retenido"
            6 -> "Último / recalculando"
            else -> "Sin datos"
        }

    val signalQualityText: String
        get() = when (signalQuality) {
            2 -> "Baja"
            3 -> "Media"
            4 -> "Alta"
            else -> "Sin señal utilizable"
        }

    // PPG continúa siendo sólo memoria. Los cortes se implementan vaciando la
    // ventana al detectar invalidez, discontinuidad o stream vencido.
    private val _ppgSamples = mutableStateListOf<Float>()
    val ppgSamples: List<Float>
        get() = _ppgSamples

    /**
     * Callback sólo para candidatos aptos para persistencia. MeasurementRecorder
     * aplica además deduplicación por boot/N y el intervalo histórico de 5 s.
     */
    var onTelemetryReceived: ((B18Bpm, String, Long) -> Unit)? = null

    // ---------------------------------------------------------------------
    // Android / BLE
    // ---------------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter
        get() = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var paCharacteristic: BluetoothGattCharacteristic? = null

    private var scanning = false
    private var closed = false
    private var reconnectScheduled = false
    private var autoReconnectEnabled = true

    private var gattConnected = false
    private var subscribed = false
    private var paSubscribed = false
    private var negotiated = false
    private var mtuPending = false
    private var activeDevice: BluetoothDevice? = null

    private val assembler = B18LineAssembler()
    private var partialStartedAtElapsed = 0L
    private var negotiationStarted = false

    private var helloAttempts = 0
    private var desiredStreamHz = 20

    private var lastBReceivedElapsed = 0L
    private var lastSReceivedElapsed = 0L
    private var lastPpgSequence: Long? = null
    private var lastPpgSampleTime: Long? = null

    private var spo2ReceivedElapsed = 0L
    private var spo2AgeAtReceive: Long? = null
    private var prvReceivedElapsed = 0L

    private val bloodPressureAssembler =
        BloodPressureWindowAssembler()

    private val bloodPressureScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default
        )

    private val bloodPressureModelLock =
        Any()

    private var bloodPressureModel:
        BloodPressureModel? = null

    private var bloodPressureInferenceRunning =
        false

    private var bloodPressureSessionGeneration =
        0L

    private data class PendingCommand(
        val command: String,
        var attempts: Int,
        val expectedAck: String?
    )

    private val writeQueue = ArrayDeque<String>()
    private var characteristicWriteInFlight = false
    private var pendingLogicalCommand: PendingCommand? = null

    // ---------------------------------------------------------------------
    // Permisos
    // ---------------------------------------------------------------------

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun permissionDenied() {
        connectionState = "Permisos Bluetooth denegados"
    }

    // ---------------------------------------------------------------------
    // API pública
    // ---------------------------------------------------------------------

    fun scanAndConnect() {
        if (closed) return
        autoReconnectEnabled = true

        if (!hasRequiredPermissions()) {
            permissionDenied()
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            connectionState = "Bluetooth desactivado"
            return
        }

        if (gattConnected || scanning) return

        startScan()
    }

    /**
     * B18 sólo admite HELLO:2, GET:STATE y STREAM:0/20.
     * HELLO es interno y se emite automáticamente tras CCCD.
     */
    fun sendCommand(command: String) {
        when (command) {
            "GET:STATE" -> enqueueLogicalCommand(command, "ACK:GET:STATE")
            "STREAM:0" -> {
                desiredStreamHz = 0
                enqueueLogicalCommand(command, "ACK:STREAM:0")
            }
            "STREAM:20" -> {
                desiredStreamHz = 20
                enqueueLogicalCommand(command, "ACK:STREAM:20")
            }
            else -> {
                lastPacket = "Comando no admitido por B18: $command"
            }
        }
    }

    fun close() {
        closed = true
        autoReconnectEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
        clearSessionState()
        bluetoothGatt?.close()
        bluetoothGatt = null
        activeDevice = null
        onTelemetryReceived = null
        bloodPressureScope.cancel()
        synchronized(bloodPressureModelLock) {
            bloodPressureModel?.close()
            bloodPressureModel = null
        }
        connectionState = "Desconectado"
    }

    // ---------------------------------------------------------------------
    // Scan y conexión
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            connectionState = "Escáner BLE no disponible"
            return
        }

        scanning = true
        connectionState = "Buscando PPG-Monitor-S3..."

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
    }

    private val scanTimeoutRunnable = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        connectionState = "No se encontró PPG-Monitor-S3"
        scheduleReconnect()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceMatch =
                result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true

            val nameMatch = try {
                result.scanRecord?.deviceName == DEVICE_NAME ||
                    result.device.name == DEVICE_NAME
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName == DEVICE_NAME
            }

            if (serviceMatch || nameMatch) {
                stopScan()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            connectionState = "Error de escaneo BLE: $errorCode"
            scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (closed) return

        connectionState = "Conectando..."
        activeDevice = device

        Log.i(TAG, "Intentando connectGatt a ${device.address}")

        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun scheduleReconnect() {
        if (closed || !autoReconnectEnabled || reconnectScheduled) return

        reconnectScheduled = true
        mainHandler.postDelayed({
            reconnectScheduled = false
            if (!closed && !gattConnected) {
                scanAndConnect()
            }
        }, RECONNECT_DELAY_MS)
    }

    // ---------------------------------------------------------------------
    // GATT callback
    // ---------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.i(
                TAG,
                "onConnectionStateChange status=$status newState=$newState " +
                    "device=${gatt.device.address}"
            )

            /*
             * IMPORTANTE:
             *
             * No rechazamos el primer callback sólo porque gatt !== bluetoothGatt.
             * En algunos stacks Android (incluido MIUI/Xiaomi) el callback inicial
             * puede llegar antes de que connectGatt() haya terminado de devolver
             * el objeto y antes, por tanto, de que la asignación a bluetoothGatt
             * quede visible. El código anterior podía cerrar una conexión válida
             * inmediatamente después de iniciarla.
             */
            val expectedAddress = activeDevice?.address
            if (expectedAddress != null &&
                gatt.device.address != expectedAddress
            ) {
                Log.w(TAG, "Callback GATT de otro dispositivo; se descarta")
                gatt.close()
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                // Adoptar explícitamente el GATT que realmente notificó conexión.
                bluetoothGatt = gatt
                gattConnected = true
                reconnectScheduled = false

                onMain {
                    clearLiveValues()
                    connectionState = "Descubriendo servicios..."
                }

                if (!gatt.discoverServices()) {
                    failCurrentGatt("No se pudo iniciar descubrimiento GATT")
                }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (bluetoothGatt == null || bluetoothGatt === gatt) {
                    bluetoothGatt = gatt
                    handleDisconnectedGatt(
                        gatt,
                        "Error GATT: $status"
                    )
                } else {
                    gatt.close()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (bluetoothGatt == null || bluetoothGatt === gatt) {
                    bluetoothGatt = gatt
                    handleDisconnectedGatt(
                        gatt,
                        "Desconectado"
                    )
                } else {
                    gatt.close()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                failCurrentGatt("Error al descubrir servicios: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            val pa = service?.getCharacteristic(PA_TX_UUID)

            if (service == null || rx == null || tx == null) {
                failCurrentGatt("Nordic UART Service B18 no encontrado")
                return
            }

            rxCharacteristic = rx
            txCharacteristic = tx
            paCharacteristic = pa

            onMain { connectionState = "Negociando MTU..." }

            // El funcionamiento no depende del resultado. Si la petición no se
            // inicia o el callback no llega, seguimos con MTU 23.
            mtuPending = true
            if (!gatt.requestMtu(247)) {
                mtuPending = false
                onMain { negotiatedMtu = 23 }
                enableNotifications(gatt)
            } else {
                mainHandler.removeCallbacks(mtuTimeoutRunnable)
                mainHandler.postDelayed(mtuTimeoutRunnable, MTU_TIMEOUT_MS)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt || !mtuPending) return

            mtuPending = false
            mainHandler.removeCallbacks(mtuTimeoutRunnable)

            onMain {
                negotiatedMtu =
                    if (status == BluetoothGatt.GATT_SUCCESS) mtu.coerceAtLeast(23)
                    else 23
            }
            enableNotifications(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== bluetoothGatt || descriptor.uuid != CCCD_UUID) return

            when (descriptor.characteristic.uuid) {
                TX_UUID -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failCurrentGatt("No se pudo habilitar Notify B18: $status")
                        return
                    }

                    subscribed = true

                    if (paCharacteristic != null) {
                        enablePaNotifications(gatt)
                    } else {
                        paSubscribed = false
                        onMain {
                            bloodPressureState =
                                BloodPressureUiState(
                                    status = BloodPressureStatus.RECHAZADA,
                                    message = "Firmware sin transporte PA B19"
                                )
                            connectionState = "Negociando protocolo B18..."
                        }
                        sendHello()
                    }
                }

                PA_TX_UUID -> {
                    paSubscribed =
                        status == BluetoothGatt.GATT_SUCCESS

                    onMain {
                        bloodPressureState =
                            if (paSubscribed) {
                                BloodPressureUiState(
                                    status = BloodPressureStatus.SENSANDO
                                )
                            } else {
                                BloodPressureUiState(
                                    status = BloodPressureStatus.RECHAZADA,
                                    message = "No se pudo habilitar Notify PA"
                                )
                            }

                        connectionState =
                            "Negociando protocolo B18..."
                    }

                    sendHello()
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt !== bluetoothGatt) return
            val copy = characteristic.value?.clone() ?: return

            when (characteristic.uuid) {
                TX_UUID ->
                    onMain {
                        consumeNotification(copy)
                    }

                PA_TX_UUID ->
                    onMain {
                        consumePaNotification(copy)
                    }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== bluetoothGatt) return
            val copy = value.clone()

            when (characteristic.uuid) {
                TX_UUID ->
                    onMain {
                        consumeNotification(copy)
                    }

                PA_TX_UUID ->
                    onMain {
                        consumePaNotification(copy)
                    }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== bluetoothGatt || characteristic.uuid != RX_UUID) return

            characteristicWriteInFlight = false

            if (status != BluetoothGatt.GATT_SUCCESS) {
                val pending = pendingLogicalCommand
                if (pending?.command == "HELLO:2") {
                    retryHelloOrDisconnect()
                } else if (pending != null && pending.attempts < MAX_COMMAND_ATTEMPTS) {
                    pending.attempts += 1
                    writeQueue.addFirst(pending.command)
                } else if (pending != null) {
                    onMain { lastPacket = "Fallo de escritura BLE: ${pending.command}" }
                    pendingLogicalCommand = null
                }
            }

            pumpWriteQueue()
        }
    }

    private val mtuTimeoutRunnable = Runnable {
        val gatt = bluetoothGatt ?: return@Runnable
        if (!mtuPending || !gattConnected) return@Runnable

        mtuPending = false
        negotiatedMtu = 23
        enableNotifications(gatt)
    }


    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        if (gatt !== bluetoothGatt) return

        val tx = txCharacteristic ?: run {
            failCurrentGatt("TX B18 no disponible")
            return
        }

        onMain { connectionState = "Activando notificaciones..." }

        if (!gatt.setCharacteristicNotification(tx, true)) {
            failCurrentGatt("No se pudo activar Notify localmente")
            return
        }

        val descriptor = tx.getDescriptor(CCCD_UUID) ?: run {
            failCurrentGatt("CCCD 0x2902 no encontrado")
            return
        }

        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        val started =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor)
                }
            }

        if (!started) {
            failCurrentGatt("No se pudo iniciar escritura CCCD")
        }
    }


    @SuppressLint("MissingPermission")
    private fun enablePaNotifications(
        gatt: BluetoothGatt
    ) {
        if (gatt !== bluetoothGatt) return

        val pa =
            paCharacteristic
                ?: run {
                    paSubscribed = false
                    onMain {
                        connectionState =
                            "Negociando protocolo B18..."
                    }
                    sendHello()
                    return
                }

        onMain {
            connectionState =
                "Activando transporte PA..."
        }

        if (!gatt.setCharacteristicNotification(pa, true)) {
            paSubscribed = false
            onMain {
                bloodPressureState =
                    BloodPressureUiState(
                        status = BloodPressureStatus.RECHAZADA,
                        message = "No se pudo activar Notify PA"
                    )
                connectionState =
                    "Negociando protocolo B18..."
            }
            sendHello()
            return
        }

        val descriptor =
            pa.getDescriptor(CCCD_UUID)

        if (descriptor == null) {
            paSubscribed = false
            onMain {
                bloodPressureState =
                    BloodPressureUiState(
                        status = BloodPressureStatus.RECHAZADA,
                        message = "CCCD PA no encontrado"
                    )
                connectionState =
                    "Negociando protocolo B18..."
            }
            sendHello()
            return
        }

        val value =
            BluetoothGattDescriptor
                .ENABLE_NOTIFICATION_VALUE

        val started =
            if (
                Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
            ) {
                gatt.writeDescriptor(
                    descriptor,
                    value
                ) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = value
                    gatt.writeDescriptor(
                        descriptor
                    )
                }
            }

        if (!started) {
            paSubscribed = false
            onMain {
                bloodPressureState =
                    BloodPressureUiState(
                        status = BloodPressureStatus.RECHAZADA,
                        message = "No se pudo iniciar CCCD PA"
                    )
                connectionState =
                    "Negociando protocolo B18..."
            }
            sendHello()
        }
    }


    // ---------------------------------------------------------------------
    // Negociación y framing
    // ---------------------------------------------------------------------

    private fun sendHello() {
        if (!gattConnected || !subscribed) return

        helloAttempts += 1
        negotiationStarted = false
        synchronized(assembler) { assembler.reset() }
        partialStartedAtElapsed = 0L

        pendingLogicalCommand = PendingCommand(
            command = "HELLO:2",
            attempts = helloAttempts,
            expectedAck = null
        )
        writeQueue.addLast("HELLO:2")
        pumpWriteQueue()

        mainHandler.removeCallbacks(helloTimeoutRunnable)
        mainHandler.postDelayed(helloTimeoutRunnable, HELLO_TIMEOUT_MS)
    }

    private val helloTimeoutRunnable = Runnable {
        if (!negotiated && gattConnected && subscribed) {
            retryHelloOrDisconnect()
        }
    }

    private fun retryHelloOrDisconnect() {
        pendingLogicalCommand = null
        writeQueue.clear()
        characteristicWriteInFlight = false
        synchronized(assembler) { assembler.reset() }
        partialStartedAtElapsed = 0L

        if (helloAttempts < MAX_HELLO_ATTEMPTS) {
            sendHello()
        } else {
            failCurrentGatt("B18 no respondió HELLO:2")
        }
    }

    private fun consumeNotification(bytes: ByteArray) {
        if (!gattConnected || !subscribed) return

        // Antes de H se descarta cualquier telemetría legacy completa. La
        // reconstrucción v2 comienza exclusivamente en una notificación cuyo
        // primer byte pertenece a "H:2,".
        if (!negotiated && !negotiationStarted) {
            val prefix = "H:2,".toByteArray(StandardCharsets.US_ASCII)
            if (bytes.size < prefix.size ||
                !prefix.indices.all { bytes[it] == prefix[it] }
            ) {
                return
            }
            negotiationStarted = true
            synchronized(assembler) { assembler.reset() }
        }

        val hadPartial = synchronized(assembler) { assembler.hasPartial }
        if (!hadPartial && bytes.isNotEmpty()) {
            partialStartedAtElapsed = SystemClock.elapsedRealtime()
        }

        val events = synchronized(assembler) { assembler.offer(bytes) }

        if (synchronized(assembler) { assembler.hasPartial }) {
            mainHandler.removeCallbacks(partialTimeoutRunnable)
            mainHandler.postDelayed(partialTimeoutRunnable, PARTIAL_TIMEOUT_MS)
        } else {
            partialStartedAtElapsed = 0L
            mainHandler.removeCallbacks(partialTimeoutRunnable)
        }

        for (event in events) {
            when (event) {
                is B18AssemblerEvent.Line -> handleLine(event.value)
                B18AssemblerEvent.Resync -> {
                    lastPacket = "Resincronización BLE"
                    if (!negotiated) retryHelloOrDisconnect()
                }
                B18AssemblerEvent.Overflow ->
                    lastPacket = "Línea BLE descartada: >192 bytes"
                B18AssemblerEvent.InvalidAscii ->
                    lastPacket = "Línea BLE descartada: ASCII inválido"
            }
        }
    }

    private val partialTimeoutRunnable = Runnable {
        val now = SystemClock.elapsedRealtime()
        if (partialStartedAtElapsed != 0L &&
            now - partialStartedAtElapsed >= PARTIAL_TIMEOUT_MS
        ) {
            synchronized(assembler) { assembler.reset() }
            partialStartedAtElapsed = 0L
            lastPacket = "Parcial BLE descartado por timeout"
            if (!negotiated) retryHelloOrDisconnect()
        }
    }

    private fun handleLine(line: String) {
        lastPacket = line

        val frame = BleProtocol.parse(line) ?: return

        if (!negotiated) {
            val hello = (frame as? B18Frame.Hello)?.value ?: return
            if (!validateHello(hello)) {
                failCurrentGatt("Contrato B18 incompatible")
                return
            }

            mainHandler.removeCallbacks(helloTimeoutRunnable)
            pendingLogicalCommand = null
            helloAttempts = 0
            negotiated = true
            bootId = hello.boot
            epoch = hello.epoch
            isConnected = true
            connectionState = "Conectado - B18 v2"

            bloodPressureState =
                if (paCharacteristic != null && paSubscribed) {
                    BloodPressureUiState(
                        status =
                            BloodPressureStatus.SENSANDO
                    )
                } else {
                    BloodPressureUiState(
                        status =
                            BloodPressureStatus.RECHAZADA,
                        message =
                            "Transporte PA B19 no disponible"
                    )
                }

            // Un epoch nuevo parte con STREAM=20. Sólo hay que restaurar una
            // preferencia diferente del valor por defecto.
            if (desiredStreamHz == 0) {
                enqueueLogicalCommand("STREAM:0", "ACK:STREAM:0")
            }

            // HELLO ya programa B/O/R/D; no es obligatorio GET:STATE.
            mainHandler.removeCallbacks(freshnessRunnable)
            mainHandler.post(freshnessRunnable)
            return
        }

        when (frame) {
            is B18Frame.Hello -> {
                // HELLO idempotente dentro de la época: actualiza contexto.
                if (validateHello(frame.value)) {
                    bootId = frame.value.boot
                    epoch = frame.value.epoch
                }
            }

            is B18Frame.Bpm -> handleBpm(frame.value)
            is B18Frame.Ppg -> handlePpg(frame.value)
            is B18Frame.Spo2 -> handleSpo2(frame.value)
            is B18Frame.Prv -> handlePrv(frame.value)
            is B18Frame.Diagnostics -> diagnostics = frame.value
            is B18Frame.Ack -> handleAck("ACK:${frame.command}")
            is B18Frame.Error -> handleError("ERR:${frame.error}")
        }
    }

    private fun validateHello(h: B18Hello): Boolean =
        h.firmware == "B18" &&
            h.capabilities == 31 &&
            h.acquisitionHz == 100 &&
            h.streamMaxHz == 20 &&
            h.maxLineBytes == 192 &&
            h.maxFragmentBytes == 180

    // ---------------------------------------------------------------------
    // Familias v2
    // ---------------------------------------------------------------------

    private fun handleBpm(value: B18Bpm) {
        lastBReceivedElapsed = SystemClock.elapsedRealtime()

        bpm = if (value.visible && value.bpm != null) value.bpm else 0
        bpmVisible = value.visible && value.bpm != null
        bpmStateCode = value.state
        bpmAgeMs = value.ageMs
        bpmSourceSequence = value.bpmSequence

        activityCode = if (value.activity in 0..2) value.activity else -1
        signalQuality = value.quality
        batteryPercentage = value.battery ?: -1

        val boot = bootId
        val ep = epoch
        if (
            boot != null &&
            ep != null &&
            value.visible &&
            value.state == 4 &&
            value.bpm != null &&
            value.ageMs != null &&
            value.ageMs < 5_000L
        ) {
            onTelemetryReceived?.invoke(value, boot, ep)
        }
    }

    private fun handlePpg(value: B18Ppg) {
        val now = SystemClock.elapsedRealtime()
        lastSReceivedElapsed = now

        if (!value.valid || value.value == null || value.sampleTimeMs == null) {
            clearPpgSamples()
            lastPpgSequence = value.sequence
            lastPpgSampleTime = null
            return
        }

        val previousSeq = lastPpgSequence
        val previousTime = lastPpgSampleTime

        val sequenceDiscontinuity =
            previousSeq != null &&
                value.sequence != BleProtocol.u32Next(previousSeq)

        val timeDiscontinuity =
            previousTime != null &&
                BleProtocol.u32Delta(value.sampleTimeMs, previousTime) > 500L

        if (sequenceDiscontinuity || timeDiscontinuity) {
            clearPpgSamples()
        }

        _ppgSamples.add(value.value)
        while (_ppgSamples.size > MAX_PPG_SAMPLES) {
            _ppgSamples.removeAt(0)
        }

        lastPpgSequence = value.sequence
        lastPpgSampleTime = value.sampleTimeMs
    }

    private fun handleSpo2(value: B18Spo2) {
        spo2ReceivedElapsed = SystemClock.elapsedRealtime()
        spo2AgeAtReceive = value.ageMs
        spo2Reason = value.reason

        val validNow =
            value.valid &&
                value.value != null &&
                value.ageMs != null &&
                value.ageMs < SPO2_MAX_AGE_MS

        spo2Valid = validNow
        spo2 = if (validNow) value.value else null
    }

    private fun handlePrv(value: B18Prv) {
        prvReceivedElapsed = SystemClock.elapsedRealtime()
        prv = if (
            value.valid &&
            value.ageMs != null &&
            value.ageMs < PRV_MAX_AGE_MS
        ) {
            value
        } else {
            value.copy(
                valid = false,
                ppMeanMs = null,
                rmssdMs = null,
                sdnnMs = null,
                pnn50Percent = null,
                flag = null
            )
        }
    }


    private fun consumePaNotification(
        bytes: ByteArray
    ) {
        if (
            !gattConnected ||
            !paSubscribed ||
            !negotiated
        ) {
            return
        }

        when (
            val event =
                bloodPressureAssembler
                    .offer(bytes)
        ) {
            is BloodPressureTransportEvent.Began -> {
                bloodPressureState =
                    BloodPressureUiState(
                        status =
                            BloodPressureStatus.TRANSFIRIENDO,
                        windowSeq =
                            event.windowSeq,
                        progressSamples = 0
                    )
            }

            is BloodPressureTransportEvent.Progress -> {
                bloodPressureState =
                    BloodPressureUiState(
                        status =
                            BloodPressureStatus.TRANSFIRIENDO,
                        windowSeq =
                            event.windowSeq,
                        progressSamples =
                            event.receivedSamples,
                        message =
                            "${event.receivedSamples}/" +
                                "${event.totalSamples} muestras"
                    )
            }

            is BloodPressureTransportEvent.Rejected -> {
                bloodPressureState =
                    BloodPressureUiState(
                        status =
                            BloodPressureStatus.RECHAZADA,
                        message =
                            event.reason
                    )
            }

            is BloodPressureTransportEvent.Complete -> {
                runBloodPressureInference(
                    event.window.windowSeq,
                    event.window.samples
                )
            }
        }
    }


    private fun runBloodPressureInference(
        windowSeq: Long,
        rawIr: LongArray
    ) {
        if (bloodPressureInferenceRunning) {
            bloodPressureState =
                BloodPressureUiState(
                    status =
                        BloodPressureStatus.RECHAZADA,
                    windowSeq =
                        windowSeq,
                    message =
                        "Inferencia PA ocupada"
                )
            return
        }

        bloodPressureInferenceRunning = true

        val generation =
            bloodPressureSessionGeneration

        bloodPressureState =
            BloodPressureUiState(
                status =
                    BloodPressureStatus.CALCULANDO,
                windowSeq =
                    windowSeq
            )

        bloodPressureScope.launch {
            try {
                val normalized =
                    BloodPressurePreprocessor
                        .preprocess(rawIr)
                        .normalized

                val model =
                    synchronized(
                        bloodPressureModelLock
                    ) {
                        bloodPressureModel
                            ?: BloodPressureModel
                                .fromAssets(
                                    context.applicationContext,
                                    numThreads = 2
                                )
                                .also {
                                    bloodPressureModel = it
                                }
                    }

                val estimate =
                    model.predict(normalized)

                mainHandler.post {
                    bloodPressureInferenceRunning =
                        false

                    if (
                        generation !=
                            bloodPressureSessionGeneration
                    ) {
                        return@post
                    }

                    bloodPressureState =
                        BloodPressureUiState(
                            status =
                                BloodPressureStatus.DISPONIBLE,
                            systolicMmHg =
                                estimate.systolicMmHg,
                            diastolicMmHg =
                                estimate.diastolicMmHg,
                            windowSeq =
                                windowSeq
                        )
                }
            } catch (
                error: Throwable
            ) {
                mainHandler.post {
                    bloodPressureInferenceRunning =
                        false

                    if (
                        generation !=
                            bloodPressureSessionGeneration
                    ) {
                        return@post
                    }

                    bloodPressureState =
                        BloodPressureUiState(
                            status =
                                BloodPressureStatus.RECHAZADA,
                            windowSeq =
                                windowSeq,
                            message =
                                error.message
                                    ?: "Ventana PA rechazada"
                        )
                }
            }
        }
    }


    // ---------------------------------------------------------------------
    // Caducidad local
    // ---------------------------------------------------------------------

    private val freshnessRunnable = object : Runnable {
        override fun run() {
            if (!negotiated || !gattConnected) return

            val now = SystemClock.elapsedRealtime()

            if (lastBReceivedElapsed != 0L &&
                now - lastBReceivedElapsed > B_STALE_MS
            ) {
                bpm = 0
                bpmVisible = false
                bpmStateCode = 0
                bpmAgeMs = null
                activityCode = -1
                signalQuality = 0
                batteryPercentage = -1
            }

            if (desiredStreamHz == 20 &&
                lastSReceivedElapsed != 0L &&
                now - lastSReceivedElapsed > S_STALE_MS
            ) {
                clearPpgSamples()
                lastPpgSampleTime = null
                lastPpgSequence = null
            }

            val currentSpo2 = spo2
            if (currentSpo2 != null && spo2ReceivedElapsed != 0L) {
                val receivedAge = spo2AgeAtReceive ?: SPO2_MAX_AGE_MS
                if (receivedAge + (now - spo2ReceivedElapsed) >= SPO2_MAX_AGE_MS) {
                    spo2 = null
                    spo2Valid = false
                    spo2Reason = 5
                }
            }

            val currentPrv = prv
            if (currentPrv?.valid == true && prvReceivedElapsed != 0L) {
                val receivedAge = currentPrv.ageMs ?: PRV_MAX_AGE_MS
                if (receivedAge + (now - prvReceivedElapsed) >= PRV_MAX_AGE_MS) {
                    prv = currentPrv.copy(
                        valid = false,
                        ppMeanMs = null,
                        rmssdMs = null,
                        sdnnMs = null,
                        pnn50Percent = null,
                        flag = null
                    )
                }
            }

            mainHandler.postDelayed(this, 250L)
        }
    }

    // ---------------------------------------------------------------------
    // Escrituras serializadas
    // ---------------------------------------------------------------------

    private fun enqueueLogicalCommand(command: String, expectedAck: String?) {
        if (!negotiated || !gattConnected || !subscribed) {
            lastPacket = "Comando no enviado: protocolo B18 no negociado"
            return
        }

        if (pendingLogicalCommand != null) {
            lastPacket = "Comando pendiente; espere respuesta B18"
            return
        }

        pendingLogicalCommand = PendingCommand(
            command = command,
            attempts = 1,
            expectedAck = expectedAck
        )

        writeQueue.addLast(command)
        pumpWriteQueue()
        armCommandTimeout()
    }

    private fun handleAck(fullAck: String) {
        val pending = pendingLogicalCommand ?: return
        if (pending.expectedAck == fullAck) {
            pendingLogicalCommand = null
            mainHandler.removeCallbacks(commandTimeoutRunnable)
        }
    }

    private fun handleError(fullError: String) {
        if (pendingLogicalCommand != null) {
            pendingLogicalCommand = null
            mainHandler.removeCallbacks(commandTimeoutRunnable)
        }
        lastPacket = fullError
    }

    private fun armCommandTimeout() {
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        mainHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS)
    }

    private val commandTimeoutRunnable = Runnable {
        val pending = pendingLogicalCommand ?: return@Runnable

        if (pending.attempts < MAX_COMMAND_ATTEMPTS) {
            pending.attempts += 1
            writeQueue.addLast(pending.command)
            pumpWriteQueue()
            armCommandTimeout()
        } else {
            lastPacket = "Timeout de comando: ${pending.command}"
            pendingLogicalCommand = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun pumpWriteQueue() {
        if (characteristicWriteInFlight) return

        val gatt = bluetoothGatt ?: return
        val rx = rxCharacteristic ?: return
        val command = writeQueue.pollFirst() ?: return

        val data = command.toByteArray(StandardCharsets.US_ASCII)

        if (data.isEmpty() || data.size > 31) {
            lastPacket = "Comando inválido por longitud"
            return
        }

        characteristicWriteInFlight = true

        val started =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    rx,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    rx.value = data
                    gatt.writeCharacteristic(rx)
                }
            }

        if (!started) {
            characteristicWriteInFlight = false
            val pending = pendingLogicalCommand
            if (pending?.command == "HELLO:2") {
                retryHelloOrDisconnect()
            } else if (pending != null && pending.attempts < MAX_COMMAND_ATTEMPTS) {
                pending.attempts += 1
                writeQueue.addFirst(pending.command)
                armCommandTimeout()
            } else {
                pendingLogicalCommand = null
                lastPacket = "No se pudo iniciar escritura BLE"
            }
        }
    }

    // ---------------------------------------------------------------------
    // Limpieza / desconexión
    // ---------------------------------------------------------------------

    private fun clearPpgSamples() {
        _ppgSamples.clear()
    }

    private fun clearLiveValues() {
        isConnected = false
        negotiated = false
        subscribed = false
        negotiationStarted = false

        bootId = null
        epoch = null

        bpm = 0
        bpmVisible = false
        bpmStateCode = 0
        bpmAgeMs = null
        bpmSourceSequence = null

        activityCode = -1
        signalQuality = 0
        batteryPercentage = -1

        spo2 = null
        spo2Valid = false
        spo2Reason = 1
        prv = null
        diagnostics = null

        bloodPressureAssembler.reset()
        bloodPressureSessionGeneration += 1L
        bloodPressureState =
            BloodPressureUiState(
                status =
                    BloodPressureStatus.SENSANDO
            )

        lastBReceivedElapsed = 0L
        lastSReceivedElapsed = 0L
        spo2ReceivedElapsed = 0L
        spo2AgeAtReceive = null
        prvReceivedElapsed = 0L
        lastPpgSequence = null
        lastPpgSampleTime = null

        clearPpgSamples()
    }

    private fun clearSessionState() {
        mainHandler.removeCallbacks(helloTimeoutRunnable)
        mainHandler.removeCallbacks(mtuTimeoutRunnable)
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        mainHandler.removeCallbacks(partialTimeoutRunnable)
        mainHandler.removeCallbacks(freshnessRunnable)

        synchronized(assembler) { assembler.reset() }
        partialStartedAtElapsed = 0L

        writeQueue.clear()
        characteristicWriteInFlight = false
        pendingLogicalCommand = null
        helloAttempts = 0

        gattConnected = false
        mtuPending = false
        paSubscribed = false
        rxCharacteristic = null
        txCharacteristic = null
        paCharacteristic = null

        clearLiveValues()
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnectedGatt(
        gatt: BluetoothGatt,
        reason: String
    ) {
        if (bluetoothGatt != null && gatt !== bluetoothGatt) {
            gatt.close()
            return
        }

        Log.w(TAG, "Cerrando GATT: $reason")

        clearSessionState()
        gatt.close()

        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
        }

        onMain {
            connectionState = reason
        }

        scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun failCurrentGatt(message: String) {
        onMain {
            connectionState = message
            isConnected = false
        }

        val gatt = bluetoothGatt
        if (gatt != null) {
            try {
                gatt.disconnect()
            } catch (_: SecurityException) {
                handleDisconnectedGatt(
                    gatt,
                    message
                )
            }
        } else {
            scheduleReconnect()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
