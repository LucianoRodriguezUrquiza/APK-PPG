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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.core.content.ContextCompat

import java.util.UUID


// ============================================================================
// BLE MANAGER
// ============================================================================
//
// ESP32 -> Android:
//
// - Telemetría:
//      B:078 A:0 C:4 P:084
//
// - Stream PPG filtrado:
//      S:-123.45
//
// - Respuestas:
//      ACK:...
//      ERR:...
//
//
// Android -> ESP32:
//
// - SCREEN:0
// - SCREEN:1
// - SCREEN:2
//
// - MODE:USO
// - MODE:CARGA
//
//
// Incluye:
//
// - reconexión automática
// - restauración automática del modo
// - recepción PPG en tiempo real
//
// ============================================================================

class BleManager(
    private val context: Context
) {

    companion object {

        // ====================================================================
        // DISPOSITIVO
        // ====================================================================

        private const val DEVICE_NAME =
            "PPG-Monitor-S3"


        // ====================================================================
        // RECONEXIÓN
        // ====================================================================

        private const val RECONNECT_DELAY_MS =
            2000L


        private const val SCAN_TIMEOUT_MS =
            10000L


        private const val MODE_RESEND_DELAY_MS =
            350L


        // ====================================================================
        // PPG
        //
        // Firmware:
        // 20 muestras por segundo
        //
        // 240 muestras ≈ 12 segundos visibles
        // ====================================================================

        private const val MAX_PPG_SAMPLES =
            240


        // ====================================================================
        // NORDIC UART SERVICE
        // ====================================================================

        private val SERVICE_UUID =
            UUID.fromString(
                "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
            )


        // Android -> ESP32
        private val RX_UUID =
            UUID.fromString(
                "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
            )


        // ESP32 -> Android
        private val TX_UUID =
            UUID.fromString(
                "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
            )


        private val CCCD_UUID =
            UUID.fromString(
                "00002902-0000-1000-8000-00805F9B34FB"
            )
    }


    // =========================================================================
    // ESTADOS DE INTERFAZ
    // =========================================================================

    var connectionState by
    mutableStateOf(
        "Desconectado"
    )
        private set


    var isConnected by
    mutableStateOf(
        false
    )
        private set


    var lastPacket by
    mutableStateOf(
        "Sin datos"
    )
        private set


    // =========================================================================
    // TELEMETRÍA
    // =========================================================================

    var bpm by
    mutableIntStateOf(
        0
    )
        private set


    var activityCode by
    mutableIntStateOf(
        -1
    )
        private set


    var signalQuality by
    mutableIntStateOf(
        0
    )
        private set


    var batteryPercentage by
    mutableIntStateOf(
        -1
    )
        private set


    // =========================================================================
    // PPG EN TIEMPO REAL
    //
    // Se mantiene únicamente en memoria.
    //
    // NO se guarda en Room.
    // NO se exporta automáticamente.
    //
    // =========================================================================

    private val _ppgSamples =
        mutableStateListOf<Float>()


    val ppgSamples: List<Float>
        get() =
            _ppgSamples


    // =========================================================================
    // CALLBACK DE TELEMETRÍA
    //
    // Este callback sigue siendo únicamente para:
    //
    // BPM
    // actividad
    // calidad
    // batería
    //
    // El stream S: NO pasa por este callback.
    //
    // =========================================================================

    var onTelemetryReceived:
            ((Int, Int, Int, Int) -> Unit)? =
        null


    // =========================================================================
    // OBJETOS BLE
    // =========================================================================

    private val mainHandler:
            Handler =
        Handler(
            Looper.getMainLooper()
        )


    private val bluetoothManager:
            BluetoothManager? =
        context.getSystemService(
            BluetoothManager::class.java
        )


    private val bluetoothAdapter
        get() =
            bluetoothManager?.adapter


    private var bluetoothGatt:
            BluetoothGatt? =
        null


    private var rxCharacteristic:
            BluetoothGattCharacteristic? =
        null


    private var txCharacteristic:
            BluetoothGattCharacteristic? =
        null


    private var scanning:
            Boolean =
        false


    // =========================================================================
    // RECONEXIÓN
    // =========================================================================

    private var autoReconnectEnabled:
            Boolean =
        false


    private var reconnectScheduled:
            Boolean =
        false


    private var closed:
            Boolean =
        false


    // =========================================================================
    // MODO DESEADO
    // =========================================================================

    private var desiredMode:
            DeviceMode =
        DeviceMode.USO


    // =========================================================================
    // PERMISOS
    // =========================================================================

    fun requiredPermissions():
            Array<String> {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

        } else {

            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }


    fun hasRequiredPermissions():
            Boolean {

        return requiredPermissions()
            .all { permission ->

                ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) ==
                        PackageManager.PERMISSION_GRANTED
            }
    }


    fun permissionDenied() {

        updateConnectionState(
            "Permisos Bluetooth rechazados"
        )
    }


    // =========================================================================
    // ACTUALIZAR ESTADO
    // =========================================================================

    private fun updateConnectionState(
        value: String
    ) {

        mainHandler.post {

            connectionState =
                value
        }
    }


    private fun updateConnected(
        value: Boolean
    ) {

        mainHandler.post {

            isConnected =
                value
        }
    }


    // =========================================================================
    // LIMPIAR PPG
    // =========================================================================

    private fun clearPpgSamples() {

        mainHandler.post {

            _ppgSamples.clear()
        }
    }


    // =========================================================================
    // MODO DEL DISPOSITIVO
    // =========================================================================

    fun setDesiredMode(
        mode: DeviceMode
    ) {

        desiredMode =
            mode


        // ---------------------------------------------------------------------
        // En Modo Carga no debe quedar congelada una onda vieja.
        // ---------------------------------------------------------------------

        if (
            mode ==
            DeviceMode.CARGA
        ) {

            clearPpgSamples()
        }


        if (
            isConnected
        ) {

            sendDesiredMode()
        }
    }


    private fun sendDesiredMode() {

        when (
            desiredMode
        ) {

            DeviceMode.USO -> {

                sendCommand(
                    "MODE:USO"
                )
            }


            DeviceMode.CARGA -> {

                sendCommand(
                    "MODE:CARGA"
                )
            }
        }
    }


    // =========================================================================
    // PAQUETE BLE
    // =========================================================================

    private fun updatePacket(
        value: String
    ) {

        mainHandler.post {

            val packet =
                value.trim()


            if (
                packet.isBlank()
            ) {

                return@post
            }


            // =================================================================
            // STREAM PPG
            //
            // Ejemplo:
            //
            // S:-123.45
            //
            // =================================================================

            if (
                packet.startsWith(
                    "S:"
                )
            ) {

                val sample =
                    packet
                        .substringAfter(
                            "S:"
                        )
                        .toFloatOrNull()


                if (
                    sample != null &&
                    sample.isFinite()
                ) {

                    _ppgSamples.add(
                        sample
                    )


                    // ---------------------------------------------------------
                    // Mantener solamente la ventana temporal definida.
                    // ---------------------------------------------------------

                    while (
                        _ppgSamples.size >
                        MAX_PPG_SAMPLES
                    ) {

                        _ppgSamples.removeAt(
                            0
                        )
                    }
                }


                return@post
            }


            // =================================================================
            // TELEMETRÍA NORMAL
            //
            // B:078 A:0 C:4 P:084
            //
            // =================================================================

            if (
                packet.startsWith(
                    "B:"
                )
            ) {

                // -------------------------------------------------------------
                // Este sí queda como "Último dato recibido".
                //
                // Los S: no lo pisan 20 veces por segundo.
                // -------------------------------------------------------------

                lastPacket =
                    packet


                val packetValid =
                    parseTelemetryPacket(
                        packet
                    )


                if (
                    packetValid
                ) {

                    onTelemetryReceived
                        ?.invoke(
                            bpm,
                            activityCode,
                            signalQuality,
                            batteryPercentage
                        )
                }


                return@post
            }


            // =================================================================
            // RESPUESTAS DEL FIRMWARE
            // =================================================================

            if (
                packet.startsWith(
                    "ACK:"
                ) ||
                packet.startsWith(
                    "ERR:"
                )
            ) {

                lastPacket =
                    packet


                return@post
            }
        }
    }


    // =========================================================================
    // PARSEO DE TELEMETRÍA
    //
    // Formato:
    //
    // B:078 A:1 C:4 P:084
    //
    // =========================================================================

    private fun parseTelemetryPacket(
        packet: String
    ): Boolean {

        return try {

            var parsedBpm:
                    Int? =
                null


            var parsedActivity:
                    Int? =
                null


            var parsedQuality:
                    Int? =
                null


            var parsedBattery:
                    Int? =
                null


            val fields =
                packet
                    .trim()
                    .split(
                        Regex(
                            "\\s+"
                        )
                    )


            fields.forEach { field ->

                when {

                    // ---------------------------------------------------------
                    // BPM
                    // ---------------------------------------------------------

                    field.startsWith(
                        "B:"
                    ) -> {

                        parsedBpm =
                            field
                                .substringAfter(
                                    "B:"
                                )
                                .toIntOrNull()
                    }


                    // ---------------------------------------------------------
                    // ACTIVIDAD
                    // ---------------------------------------------------------

                    field.startsWith(
                        "A:"
                    ) -> {

                        parsedActivity =
                            field
                                .substringAfter(
                                    "A:"
                                )
                                .toIntOrNull()
                    }


                    // ---------------------------------------------------------
                    // CALIDAD
                    // ---------------------------------------------------------

                    field.startsWith(
                        "C:"
                    ) -> {

                        parsedQuality =
                            field
                                .substringAfter(
                                    "C:"
                                )
                                .toIntOrNull()
                    }


                    // ---------------------------------------------------------
                    // BATERÍA
                    // ---------------------------------------------------------

                    field.startsWith(
                        "P:"
                    ) -> {

                        parsedBattery =
                            field
                                .substringAfter(
                                    "P:"
                                )
                                .toIntOrNull()
                    }
                }
            }


            if (
                parsedBpm == null ||
                parsedActivity == null ||
                parsedQuality == null ||
                parsedBattery == null
            ) {

                false

            } else {

                bpm =
                    parsedBpm


                activityCode =
                    parsedActivity


                signalQuality =
                    parsedQuality


                batteryPercentage =
                    parsedBattery


                true
            }

        } catch (
            _: Exception
        ) {

            false
        }
    }


    // =========================================================================
    // CONEXIÓN INICIAL
    // =========================================================================

    fun scanAndConnect() {

        closed =
            false


        autoReconnectEnabled =
            true


        startScan()
    }


    // =========================================================================
    // RUNNABLE DE RECONEXIÓN
    // =========================================================================

    private val reconnectRunnable:
            Runnable =
        Runnable {

            reconnectScheduled =
                false


            if (
                closed ||
                !autoReconnectEnabled ||
                isConnected ||
                scanning
            ) {

                return@Runnable
            }


            startScan()
        }


    // =========================================================================
    // TIMEOUT DE ESCANEO
    // =========================================================================

    @SuppressLint(
        "MissingPermission"
    )
    private val scanTimeoutRunnable:
            Runnable =
        Runnable {

            if (
                !scanning
            ) {

                return@Runnable
            }


            try {

                bluetoothAdapter
                    ?.bluetoothLeScanner
                    ?.stopScan(
                        scanCallback
                    )

            } catch (
                _: Exception
            ) {
            }


            scanning =
                false


            updateConnectionState(
                "PPG-Monitor-S3 no encontrado"
            )


            scheduleReconnect()
        }


    // =========================================================================
    // ESCANEO
    // =========================================================================

    @SuppressLint(
        "MissingPermission"
    )
    private fun startScan() {

        if (
            closed
        ) {

            return
        }


        if (
            !hasRequiredPermissions()
        ) {

            updateConnectionState(
                "Faltan permisos Bluetooth"
            )

            return
        }


        val adapter =
            bluetoothAdapter


        if (
            adapter == null
        ) {

            updateConnectionState(
                "Bluetooth no disponible"
            )

            return
        }


        if (
            !adapter.isEnabled
        ) {

            updateConnectionState(
                "Activá Bluetooth"
            )

            return
        }


        if (
            isConnected ||
            scanning
        ) {

            return
        }


        mainHandler.removeCallbacks(
            reconnectRunnable
        )


        reconnectScheduled =
            false


        try {

            bluetoothGatt
                ?.close()

        } catch (
            _: Exception
        ) {
        }


        bluetoothGatt =
            null


        rxCharacteristic =
            null


        txCharacteristic =
            null


        val scanner =
            adapter.bluetoothLeScanner


        if (
            scanner == null
        ) {

            updateConnectionState(
                "No se pudo iniciar el escaneo BLE"
            )


            scheduleReconnect()

            return
        }


        val settings:
                ScanSettings =
            ScanSettings
                .Builder()
                .setScanMode(
                    ScanSettings
                        .SCAN_MODE_LOW_LATENCY
                )
                .build()


        scanning =
            true


        updateConnectionState(
            "Buscando PPG-Monitor-S3..."
        )


        try {

            scanner.startScan(
                null,
                settings,
                scanCallback
            )

        } catch (
            _: Exception
        ) {

            scanning =
                false


            updateConnectionState(
                "No se pudo iniciar el escaneo BLE"
            )


            scheduleReconnect()

            return
        }


        mainHandler.removeCallbacks(
            scanTimeoutRunnable
        )


        mainHandler.postDelayed(
            scanTimeoutRunnable,
            SCAN_TIMEOUT_MS
        )
    }


    // =========================================================================
    // CALLBACK DE ESCANEO
    // =========================================================================

    private val scanCallback:
            ScanCallback =
        object :
            ScanCallback() {

            @SuppressLint(
                "MissingPermission"
            )
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                if (
                    !scanning
                ) {

                    return
                }


                val advertisedName =
                    result
                        .scanRecord
                        ?.deviceName


                val deviceName =
                    try {

                        result.device.name

                    } catch (
                        _: SecurityException
                    ) {

                        null
                    }


                val detectedName =
                    advertisedName
                        ?: deviceName


                if (
                    detectedName !=
                    DEVICE_NAME
                ) {

                    return
                }


                scanning =
                    false


                mainHandler.removeCallbacks(
                    scanTimeoutRunnable
                )


                try {

                    bluetoothAdapter
                        ?.bluetoothLeScanner
                        ?.stopScan(
                            this
                        )

                } catch (
                    _: Exception
                ) {
                }


                updateConnectionState(
                    "Dispositivo encontrado. Conectando..."
                )


                connectToDevice(
                    result.device
                )
            }


            override fun onScanFailed(
                errorCode: Int
            ) {

                scanning =
                    false


                mainHandler.removeCallbacks(
                    scanTimeoutRunnable
                )


                updateConnectionState(
                    "Error BLE Scan: $errorCode"
                )


                scheduleReconnect()
            }
        }


    // =========================================================================
    // CONEXIÓN
    // =========================================================================

    @SuppressLint(
        "MissingPermission"
    )
    private fun connectToDevice(
        device: BluetoothDevice
    ) {

        if (
            closed
        ) {

            return
        }


        updateConnectionState(
            "Conectando..."
        )


        bluetoothGatt =
            device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
    }


    // =========================================================================
    // PROGRAMAR RECONEXIÓN
    // =========================================================================

    private fun scheduleReconnect() {

        if (
            closed ||
            !autoReconnectEnabled ||
            isConnected ||
            scanning ||
            reconnectScheduled
        ) {

            return
        }


        reconnectScheduled =
            true


        updateConnectionState(
            "Reconectando..."
        )


        mainHandler.postDelayed(
            reconnectRunnable,
            RECONNECT_DELAY_MS
        )
    }


    // =========================================================================
    // LIMPIAR GATT
    // =========================================================================

    @SuppressLint(
        "MissingPermission"
    )
    private fun handleDisconnectedGatt(
        gatt: BluetoothGatt,
        stateText: String
    ) {

        updateConnected(
            false
        )


        updateConnectionState(
            stateText
        )


        // ---------------------------------------------------------------------
        // Si se pierde BLE, no queremos dejar una onda vieja congelada.
        // ---------------------------------------------------------------------

        clearPpgSamples()


        rxCharacteristic =
            null


        txCharacteristic =
            null


        try {

            gatt.close()

        } catch (
            _: Exception
        ) {
        }


        if (
            bluetoothGatt ===
            gatt
        ) {

            bluetoothGatt =
                null
        }


        scheduleReconnect()
    }


    // =========================================================================
    // CALLBACK GATT
    // =========================================================================

    private val gattCallback:
            BluetoothGattCallback =
        object :
            BluetoothGattCallback() {


            // =================================================================
            // CAMBIO DE CONEXIÓN
            // =================================================================

            @SuppressLint(
                "MissingPermission"
            )
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS &&
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    updateConnectionState(
                        "Descubriendo servicios..."
                    )


                    val started:
                            Boolean =
                        try {

                            gatt.discoverServices()

                        } catch (
                            _: Exception
                        ) {

                            false
                        }


                    if (
                        !started
                    ) {

                        handleDisconnectedGatt(
                            gatt,
                            "Error iniciando descubrimiento GATT"
                        )
                    }
                }


                else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    handleDisconnectedGatt(
                        gatt,
                        "Desconectado"
                    )
                }


                else if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    handleDisconnectedGatt(
                        gatt,
                        "Error GATT: $status"
                    )
                }
            }


            // =================================================================
            // SERVICIOS
            // =================================================================

            @SuppressLint(
                "MissingPermission"
            )
            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    handleDisconnectedGatt(
                        gatt,
                        "Error descubriendo servicios: $status"
                    )

                    return
                }


                val service =
                    gatt.getService(
                        SERVICE_UUID
                    )


                if (
                    service == null
                ) {

                    handleDisconnectedGatt(
                        gatt,
                        "Servicio BLE no encontrado"
                    )

                    return
                }


                rxCharacteristic =
                    service.getCharacteristic(
                        RX_UUID
                    )


                txCharacteristic =
                    service.getCharacteristic(
                        TX_UUID
                    )


                val tx =
                    txCharacteristic


                if (
                    rxCharacteristic == null ||
                    tx == null
                ) {

                    handleDisconnectedGatt(
                        gatt,
                        "RX/TX no encontradas"
                    )

                    return
                }


                val notifyEnabled =
                    try {

                        gatt.setCharacteristicNotification(
                            tx,
                            true
                        )

                    } catch (
                        _: Exception
                    ) {

                        false
                    }


                if (
                    !notifyEnabled
                ) {

                    handleDisconnectedGatt(
                        gatt,
                        "No se pudo activar Notify"
                    )

                    return
                }


                val descriptor =
                    tx.getDescriptor(
                        CCCD_UUID
                    )


                if (
                    descriptor == null
                ) {

                    handleDisconnectedGatt(
                        gatt,
                        "Descriptor 0x2902 no encontrado"
                    )

                    return
                }


                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {

                    val result =
                        gatt.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor
                                .ENABLE_NOTIFICATION_VALUE
                        )


                    if (
                        result !=
                        BluetoothStatusCodes.SUCCESS
                    ) {

                        handleDisconnectedGatt(
                            gatt,
                            "Error iniciando Notify: $result"
                        )

                        return
                    }

                } else {

                    @Suppress(
                        "DEPRECATION"
                    )
                    descriptor.value =
                        BluetoothGattDescriptor
                            .ENABLE_NOTIFICATION_VALUE


                    @Suppress(
                        "DEPRECATION"
                    )
                    val ok =
                        gatt.writeDescriptor(
                            descriptor
                        )


                    if (
                        !ok
                    ) {

                        handleDisconnectedGatt(
                            gatt,
                            "Error iniciando Notify"
                        )

                        return
                    }
                }


                updateConnectionState(
                    "Activando notificaciones..."
                )
            }


            // =================================================================
            // NOTIFY ACTIVADO
            // =================================================================

            @SuppressLint(
                "MissingPermission"
            )
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {

                if (
                    descriptor.uuid ==
                    CCCD_UUID &&
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    updateConnected(
                        true
                    )


                    updateConnectionState(
                        "Conectado"
                    )


                    // ---------------------------------------------------------
                    // Reenviar automáticamente MODE:USO o MODE:CARGA
                    // después de una reconexión.
                    // ---------------------------------------------------------

                    mainHandler.postDelayed(
                        {

                            if (
                                !closed &&
                                isConnected
                            ) {

                                sendDesiredMode()
                            }

                        },
                        MODE_RESEND_DELAY_MS
                    )

                } else {

                    handleDisconnectedGatt(
                        gatt,
                        "Error activando Notify: $status"
                    )
                }
            }


            // =================================================================
            // RECEPCIÓN MODERNA
            // =================================================================

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                if (
                    characteristic.uuid ==
                    TX_UUID
                ) {

                    val packet =
                        value.toString(
                            Charsets.UTF_8
                        )


                    updatePacket(
                        packet
                    )
                }
            }


            // =================================================================
            // RECEPCIÓN LEGACY
            // =================================================================

            @Suppress(
                "DEPRECATION"
            )
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic
            ) {

                if (
                    characteristic.uuid ==
                    TX_UUID
                ) {

                    val packet =
                        characteristic
                            .value
                            ?.toString(
                                Charsets.UTF_8
                            )
                            ?: return


                    updatePacket(
                        packet
                    )
                }
            }
        }


    // =========================================================================
    // ENVIAR COMANDO
    // =========================================================================

    @SuppressLint(
        "MissingPermission"
    )
    fun sendCommand(
        command: String
    ) {

        if (
            !isConnected
        ) {

            return
        }


        val gatt =
            bluetoothGatt


        val rx =
            rxCharacteristic


        if (
            gatt == null ||
            rx == null
        ) {

            return
        }


        val data =
            command.toByteArray(
                Charsets.UTF_8
            )


        // =====================================================================
        // ANDROID 13+
        // =====================================================================

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val result =
                gatt.writeCharacteristic(
                    rx,
                    data,
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_DEFAULT
                )


            if (
                result !=
                BluetoothStatusCodes.SUCCESS
            ) {

                updateConnectionState(
                    "Error enviando comando: $result"
                )
            }

        } else {

            // =================================================================
            // ANDROID 12 O INFERIOR
            // =================================================================

            @Suppress(
                "DEPRECATION"
            )
            rx.writeType =
                BluetoothGattCharacteristic
                    .WRITE_TYPE_DEFAULT


            @Suppress(
                "DEPRECATION"
            )
            rx.value =
                data


            @Suppress(
                "DEPRECATION"
            )
            val ok =
                gatt.writeCharacteristic(
                    rx
                )


            if (
                !ok
            ) {

                updateConnectionState(
                    "Error enviando comando"
                )
            }
        }
    }


    // =========================================================================
    // CERRAR BLE
    // =========================================================================

    @SuppressLint(
        "MissingPermission"
    )
    fun close() {

        closed =
            true


        autoReconnectEnabled =
            false


        reconnectScheduled =
            false


        mainHandler.removeCallbacks(
            reconnectRunnable
        )


        mainHandler.removeCallbacks(
            scanTimeoutRunnable
        )


        clearPpgSamples()


        try {

            bluetoothAdapter
                ?.bluetoothLeScanner
                ?.stopScan(
                    scanCallback
                )

        } catch (
            _: Exception
        ) {
        }


        scanning =
            false


        try {

            bluetoothGatt
                ?.disconnect()

        } catch (
            _: Exception
        ) {
        }


        try {

            bluetoothGatt
                ?.close()

        } catch (
            _: Exception
        ) {
        }


        bluetoothGatt =
            null


        rxCharacteristic =
            null


        txCharacteristic =
            null


        onTelemetryReceived =
            null


        updateConnected(
            false
        )
    }
}