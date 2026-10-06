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
        private const val PA_SETUP_RETRY_DELAY_MS = 300L
        private const val PA_PROLONGED_NO_CONTACT_MS = 5_000L

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
    private var paSetupRetryUsed = false
    private var paGattCacheRefreshUsed = false
    private var paGattCacheReconnectPending = false
    private var paGattCacheRefreshDetail = "no ejecutado"
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
    private var paNoContactStartedElapsed = 0L

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

            if (
                paGattCacheReconnectPending &&
                newState == BluetoothProfile.STATE_DISCONNECTED &&
                (bluetoothGatt == null || bluetoothGatt === gatt)
            ) {
                paGattCacheReconnectPending = false
                bluetoothGatt = gatt

                val device =
                    activeDevice ?: gatt.device

                val refreshResult =
                    refreshGattCache(gatt)

                paGattCacheRefreshDetail =
                    refreshResult.second

                Log.w(
                    TAG,
                    "PA B19: refresh GATT cache -> " +
                        "${refreshResult.second}; reconectando una única vez"
                )

                clearSessionState()
                gatt.close()

                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }

                onMain {
                    bloodPressureState =
                        BloodPressureUiState(
                            status =
                                BloodPressureStatus.SENSANDO,
                            message =
                                "PA B19: caché GATT actualizada; reconectando (1/1)..."
                        )
                    connectionState =
                        "Reconectando tras limpiar caché GATT..."
                }

                mainHandler.postDelayed(
                    {
                        if (
                            !closed &&
                            !gattConnected
                        ) {
                            connectToDevice(device)
                        }
                    },
                    600L
                )

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

            val paCccd =
                pa?.getDescriptor(CCCD_UUID)

            Log.i(
                TAG,
                "PA B19 discovery: uuid0004=${pa != null} " +
                    "cccd=${paCccd != null} retryUsed=$paSetupRetryUsed"
            )

            if (pa == null) {
                if (
                    retryPaDiscoveryOnce(
                        gatt,
                        "UUID ...0004 no descubierta"
                    )
                ) {
                    return
                }

                if (
                    requestPaGattCacheRefreshReconnectOnce(
                        gatt,
                        "UUID ...0004 siguió ausente tras redescubrir servicios"
                    )
                ) {
                    return
                }

                setPaRejected(
                    "PA B19: UUID ...0004 sigue sin aparecer incluso tras limpiar caché " +
                        "GATT y reconectar. refresh=$paGattCacheRefreshDetail"
                )
            } else if (paCccd == null) {
                if (
                    retryPaDiscoveryOnce(
                        gatt,
                        "UUID ...0004 presente pero CCCD 0x2902 ausente"
                    )
                ) {
                    return
                }

                if (
                    requestPaGattCacheRefreshReconnectOnce(
                        gatt,
                        "UUID ...0004 apareció sin CCCD 0x2902"
                    )
                ) {
                    return
                }

                setPaRejected(
                    "PA B19: UUID ...0004 existe pero CCCD 0x2902 sigue ausente incluso " +
                        "tras limpiar caché GATT y reconectar. refresh=$paGattCacheRefreshDetail"
                )
            } else {
                onMain {
                    bloodPressureState =
                        BloodPressureUiState(
                            status =
                                BloodPressureStatus.SENSANDO,
                            message =
                                "PA B19: UUID ...0004 y CCCD descubiertos; habilitando Notify"
                        )
                }
            }

            continueAfterServiceDiscovery(gatt)
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

                        if (
                            requestPaGattCacheRefreshReconnectOnce(
                                gatt,
                                "UUID ...0004 no disponible al habilitar notificaciones"
                            )
                        ) {
                            return
                        }

                        if (
                            bloodPressureState.status !=
                                BloodPressureStatus.RECHAZADA
                        ) {
                            setPaRejected(
                                "PA B19: UUID ...0004 no disponible tras refresh/reconexión. " +
                                    "refresh=$paGattCacheRefreshDetail"
                            )
                        }

                        proceedToHello()
                    }
                }

                PA_TX_UUID -> {
                    Log.i(
                        TAG,
                        "PA B19 CCCD write status=$status retryUsed=$paSetupRetryUsed"
                    )

                    if (
                        status !=
                            BluetoothGatt.GATT_SUCCESS
                    ) {
                        if (
                            retryPaSubscriptionOnce(
                                gatt,
                                "escritura CCCD falló (status=$status)"
                            )
                        ) {
                            return
                        }

                        paSubscribed = false
                        setPaRejected(
                            "PA B19: UUID ...0004 y CCCD existen, pero falló habilitar Notify " +
                                "(status=$status) tras 1 reintento"
                        )
                        proceedToHello()
                        return
                    }

                    paSubscribed = true

                    onMain {
                        bloodPressureState =
                            BloodPressureUiState(
                                status =
                                    BloodPressureStatus.SENSANDO,
                                message =
                                    "PA disponible: UUID ...0004 + CCCD + Notify habilitados"
                            )
                    }

                    proceedToHello()
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
    private fun continueAfterServiceDiscovery(
        gatt: BluetoothGatt
    ) {
        if (gatt !== bluetoothGatt) return

        onMain {
            connectionState =
                "Negociando MTU..."
        }

        // B18 behavior is preserved. PA discovery is optional and never blocks
        // the validated NUS path after its single controlled retry.
        mtuPending = true

        if (!gatt.requestMtu(247)) {
            mtuPending = false
            onMain {
                negotiatedMtu = 23
            }
            enableNotifications(gatt)
        } else {
            mainHandler.removeCallbacks(
                mtuTimeoutRunnable
            )
            mainHandler.postDelayed(
                mtuTimeoutRunnable,
                MTU_TIMEOUT_MS
            )
        }
    }


    @SuppressLint("MissingPermission")
    private fun retryPaDiscoveryOnce(
        gatt: BluetoothGatt,
        reason: String
    ): Boolean {
        if (paSetupRetryUsed) {
            return false
        }

        paSetupRetryUsed = true

        Log.w(
            TAG,
            "PA B19: $reason; reintentando discoverServices una única vez"
        )

        onMain {
            bloodPressureState =
                BloodPressureUiState(
                    status =
                        BloodPressureStatus.SENSANDO,
                    message =
                        "PA B19: $reason. Reintentando descubrimiento (1/1)..."
                )
            connectionState =
                "Reintentando descubrimiento PA..."
        }

        mainHandler.postDelayed(
            {
                if (
                    gatt !== bluetoothGatt ||
                    !gattConnected
                ) {
                    return@postDelayed
                }

                if (!gatt.discoverServices()) {
                    setPaRejected(
                        "PA B19: el reintento de descubrimiento GATT no pudo iniciarse"
                    )
                    continueAfterServiceDiscovery(
                        gatt
                    )
                }
            },
            PA_SETUP_RETRY_DELAY_MS
        )

        return true
    }


    @SuppressLint("MissingPermission")
    private fun requestPaGattCacheRefreshReconnectOnce(
        gatt: BluetoothGatt,
        reason: String
    ): Boolean {
        if (
            paGattCacheRefreshUsed ||
            paGattCacheReconnectPending
        ) {
            return false
        }

        paGattCacheRefreshUsed = true
        paGattCacheReconnectPending = true

        Log.w(
            TAG,
            "PA B19: $reason; solicitando refresh GATT + reconexión única"
        )

        onMain {
            bloodPressureState =
                BloodPressureUiState(
                    status =
                        BloodPressureStatus.SENSANDO,
                    message =
                        "PA B19: $reason. Limpiando caché GATT y reconectando (1/1)..."
                )
            connectionState =
                "Limpiando caché GATT PA..."
        }

        try {
            gatt.disconnect()
        } catch (
            error: SecurityException
        ) {
            paGattCacheReconnectPending = false

            val refreshResult =
                refreshGattCache(gatt)

            paGattCacheRefreshDetail =
                refreshResult.second

            setPaRejected(
                "PA B19: no se pudo desconectar para refrescar caché GATT. " +
                    "refresh=$paGattCacheRefreshDetail"
            )
        }

        return true
    }


    private fun refreshGattCache(
        gatt: BluetoothGatt
    ): Pair<Boolean, String> =
        try {
            val method =
                gatt.javaClass.getMethod(
                    "refresh"
                )

            val result =
                method.invoke(gatt)

            val ok =
                (result as? Boolean) == true

            Pair(
                ok,
                if (ok) {
                    "refresh() OK"
                } else {
                    "refresh() devolvió false"
                }
            )
        } catch (
            error: Throwable
        ) {
            Log.w(
                TAG,
                "PA B19: BluetoothGatt.refresh() no disponible",
                error
            )

            Pair(
                false,
                "refresh() no disponible: " +
                    error.javaClass.simpleName
            )
        }


    private fun setPaRejected(
        message: String
    ) {
        Log.w(TAG, message)

        onMain {
            bloodPressureState =
                BloodPressureUiState(
                    status =
                        BloodPressureStatus.RECHAZADA,
                    message = message
                )
        }
    }


    private fun proceedToHello() {
        onMain {
            connectionState =
                "Negociando protocolo B18..."
        }
        sendHello()
    }


    @SuppressLint("MissingPermission")
    private fun retryPaSubscriptionOnce(
        gatt: BluetoothGatt,
        reason: String
    ): Boolean {
        if (paSetupRetryUsed) {
            return false
        }

        paSetupRetryUsed = true

        Log.w(
            TAG,
            "PA B19: $reason; reintentando suscripción una única vez"
        )

        onMain {
            bloodPressureState =
                BloodPressureUiState(
                    status =
                        BloodPressureStatus.SENSANDO,
                    message =
                        "PA B19: $reason. Reintentando Notify (1/1)..."
                )
        }

        mainHandler.postDelayed(
            {
                if (
                    gatt !== bluetoothGatt ||
                    !gattConnected
                ) {
                    return@postDelayed
                }

                enablePaNotifications(
                    gatt
                )
            },
            PA_SETUP_RETRY_DELAY_MS
        )

        return true
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

        if (pa == null) {
            paSubscribed = false
            setPaRejected(
                "PA B19: UUID ...0004 no fue descubierta tras 1 reintento"
            )
            proceedToHello()
            return
        }

        val descriptor =
            pa.getDescriptor(CCCD_UUID)

        if (descriptor == null) {
            paSubscribed = false

            if (
                retryPaDiscoveryOnce(
                    gatt,
                    "UUID ...0004 presente pero CCCD 0x2902 ausente"
                )
            ) {
                return
            }

            setPaRejected(
                "PA B19: UUID ...0004 existe, pero no tiene CCCD 0x2902 tras 1 reintento"
            )
            proceedToHello()
            return
        }

        onMain {
            connectionState =
                "Activando transporte PA..."
        }

        Log.i(
            TAG,
            "PA B19: UUID ...0004 + CCCD presentes; setCharacteristicNotification()"
        )

        if (
            !gatt.setCharacteristicNotification(
                pa,
                true
            )
        ) {
            paSubscribed = false

            if (
                retryPaSubscriptionOnce(
                    gatt,
                    "setCharacteristicNotification() devolvió false"
                )
            ) {
                return
            }

            setPaRejected(
                "PA B19: UUID ...0004 + CCCD presentes, pero la activación local de Notify " +
                    "falló tras 1 reintento"
            )
            proceedToHello()
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

            if (
                retryPaSubscriptionOnce(
                    gatt,
                    "no pudo iniciarse la escritura del CCCD"
                )
            ) {
                return
            }

            setPaRejected(
                "PA B19: UUID ...0004 + CCCD presentes, pero no pudo iniciarse la " +
                    "habilitación de Notify tras 1 reintento"
            )
            proceedToHello()
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

            if (paSubscribed) {
                bloodPressureState =
                    BloodPressureUiState(
                        status =
                            BloodPressureStatus.SENSANDO,
                        message =
                            "PA disponible: UUID ...0004 + CCCD + Notify habilitados"
                    )
            } else if (
                bloodPressureState.status !=
                    BloodPressureStatus.RECHAZADA
            ) {
                setPaRejected(
                    when {
                        paCharacteristic == null ->
                            "PA B19: UUID ...0004 no descubierta"

                        paCharacteristic
                            ?.getDescriptor(CCCD_UUID) == null ->
                            "PA B19: UUID ...0004 descubierta, pero CCCD 0x2902 ausente"

                        else ->
                            "PA B19: UUID ...0004 + CCCD presentes, pero Notify no habilitado"
                    }
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
        val now = SystemClock.elapsedRealtime()
        lastBReceivedElapsed = now

        if (value.state == 1) {
            if (paNoContactStartedElapsed == 0L) {
                paNoContactStartedElapsed = now
            }
        } else {
            paNoContactStartedElapsed = 0L

            if (
                bloodPressureState.message ==
                    "Señal insuficiente"
            ) {
                bloodPressureState =
                    bloodPressureState.copy(
                        status =
                            BloodPressureStatus.SENSANDO,
                        message = null
                    )
            }
        }

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
                    bloodPressureState.copy(
                        status =
                            BloodPressureStatus.TRANSFIRIENDO,
                        windowSeq =
                            event.windowSeq,
                        progressSamples = 0,
                        message = null
                    )
            }

            is BloodPressureTransportEvent.Progress -> {
                bloodPressureState =
                    bloodPressureState.copy(
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
                    bloodPressureState.copy(
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
                bloodPressureState.copy(
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
            bloodPressureState.copy(
                status =
                    BloodPressureStatus.CALCULANDO,
                windowSeq =
                    windowSeq,
                progressSamples = 700,
                message = null
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
                        bloodPressureState.copy(
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

            if (
                paNoContactStartedElapsed != 0L &&
                now - paNoContactStartedElapsed >=
                    PA_PROLONGED_NO_CONTACT_MS
            ) {
                if (
                    bloodPressureState.message !=
                        "Señal insuficiente" ||
                    bloodPressureState.systolicMmHg != null ||
                    bloodPressureState.diastolicMmHg != null
                ) {
                    bloodPressureState =
                        bloodPressureState.copy(
                            status =
                                BloodPressureStatus.RECHAZADA,
                            systolicMmHg = null,
                            diastolicMmHg = null,
                            progressSamples = 0,
                            message =
                                "Señal insuficiente"
                        )
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
        paNoContactStartedElapsed = 0L
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
        paSetupRetryUsed = false
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
