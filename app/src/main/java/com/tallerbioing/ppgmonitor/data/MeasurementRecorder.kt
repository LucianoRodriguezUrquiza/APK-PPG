package com.tallerbioing.ppgmonitor.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


// ============================================================================
// REGISTRADOR AUTOMÁTICO DE MEDICIONES
// ============================================================================
//
// Recibe los valores ya interpretados desde BleManager.
//
// Guarda una medición cada 5 segundos cuando:
//
// - BPM > 0
// - existe señal óptica
// - actividad válida
// - batería válida
//
// ============================================================================

class MeasurementRecorder(
    context: Context
) {

    // ------------------------------------------------------------------------
    // DAO
    // ------------------------------------------------------------------------

    private val measurementDao =
        AppDatabase
            .getDatabase(context)
            .measurementDao()


    // ------------------------------------------------------------------------
    // Coroutine para operaciones de base de datos
    // ------------------------------------------------------------------------

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )


    // ------------------------------------------------------------------------
    // Intervalo entre registros
    //
    // 5 segundos = 5000 ms
    // ------------------------------------------------------------------------

    private val saveIntervalMs =
        5000L


    private var lastSavedTimestamp =
        0L


    // ========================================================================
    // RECIBIR TELEMETRÍA
    // ========================================================================

    fun recordTelemetry(

        bpm: Int,

        activityCode: Int,

        signalQuality: Int,

        batteryPercentage: Int
    ) {

        val now =
            System.currentTimeMillis()


        // --------------------------------------------------------------------
        // Validar medición
        // --------------------------------------------------------------------

        val validMeasurement =

            bpm > 0 &&

                    signalQuality > 0 &&

                    activityCode in 0..2 &&

                    batteryPercentage in 0..100


        if (!validMeasurement) {

            return
        }


        // --------------------------------------------------------------------
        // Evitar guardar paquetes BLE cada 500 ms
        //
        // Solo guardamos una muestra cada 5 segundos.
        // --------------------------------------------------------------------

        if (
            now - lastSavedTimestamp <
            saveIntervalMs
        ) {

            return
        }


        lastSavedTimestamp =
            now


        // --------------------------------------------------------------------
        // Insertar en Room
        // --------------------------------------------------------------------

        val measurement =
            MeasurementEntity(

                timestamp = now,

                bpm = bpm,

                activityCode =
                    activityCode,

                signalQuality =
                    signalQuality,

                batteryPercentage =
                    batteryPercentage
            )


        scope.launch {

            measurementDao
                .insertMeasurement(
                    measurement
                )
        }
    }


    // ========================================================================
    // CERRAR
    // ========================================================================

    fun close() {

        scope.cancel()
    }
}