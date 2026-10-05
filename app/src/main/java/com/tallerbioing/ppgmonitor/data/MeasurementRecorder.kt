package com.tallerbioing.ppgmonitor.data

import android.content.Context
import com.tallerbioing.ppgmonitor.B18Bpm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Registrador histórico conservador para B18.
 *
 * Room no cambia: se siguen guardando fecha/hora, BPM, actividad, calidad y
 * batería. La información de boot/epoch/N se usa sólo en memoria para decidir
 * si una promoción de BPM es realmente nueva.
 */
class MeasurementRecorder(
    context: Context
) {

    private val measurementDao =
        AppDatabase
            .getDatabase(context)
            .measurementDao()

    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private val saveIntervalMs = 5_000L
    private var lastSavedTimestamp = 0L

    /**
     * Se conserva entre reconexiones mientras el proceso Android siga vivo.
     * Si boot no cambia, un N repetido no vuelve a registrarse aunque cambie
     * epoch. Si N cambia, se considera una nueva promoción posible.
     */
    private var lastRecordedBoot: String? = null
    private var lastRecordedBpmSequence: Long? = null

    fun recordTelemetry(
        telemetry: B18Bpm,
        boot: String,
        epoch: Long
    ) {
        @Suppress("UNUSED_VARIABLE")
        val transportEpoch = epoch

        val bpm = telemetry.bpm ?: return
        val battery = telemetry.battery ?: return
        val age = telemetry.ageMs ?: return

        // Contrato B18 para una nueva actualización apta para el recorder.
        val sourceIsNew =
            lastRecordedBoot != boot ||
                lastRecordedBpmSequence != telemetry.bpmSequence

        val validMeasurement =
            telemetry.visible &&
                telemetry.state == 4 &&
                age < 5_000L &&
                sourceIsNew &&
                bpm > 0 &&
                telemetry.activity in 0..2 &&
                telemetry.quality in setOf(2, 3, 4) &&
                battery in 0..100

        if (!validMeasurement) return

        val now = System.currentTimeMillis()

        // Se marca N como visto antes del rate-limit para impedir que una
        // retransmisión del mismo N sea guardada más tarde como si fuera nueva.
        lastRecordedBoot = boot
        lastRecordedBpmSequence = telemetry.bpmSequence

        if (now - lastSavedTimestamp < saveIntervalMs) {
            return
        }

        lastSavedTimestamp = now

        val measurement =
            MeasurementEntity(
                timestamp = now,
                bpm = bpm,
                activityCode = telemetry.activity,
                signalQuality = telemetry.quality,
                batteryPercentage = battery
            )

        scope.launch {
            measurementDao.insertMeasurement(measurement)
        }
    }

    fun close() {
        scope.cancel()
    }
}
