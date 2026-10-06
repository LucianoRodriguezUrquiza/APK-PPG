package com.tallerbioing.ppgmonitor.data

import android.content.Context
import com.tallerbioing.ppgmonitor.B18Prv
import com.tallerbioing.ppgmonitor.B18Spo2
import com.tallerbioing.ppgmonitor.bp.BloodPressureEstimate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistencia independiente de las variables exploratorias.
 *
 * No modifica los datos ni recalcula variables fisiológicas: únicamente guarda
 * resultados que ya fueron declarados válidos por B18/B19 y por el modelo PA.
 */
class ExploratoryMeasurementRecorder(
    context: Context
) {

    companion object {
        private const val SPO2_SAVE_INTERVAL_MS = 10_000L
        private const val PRV_SAVE_INTERVAL_MS = 10_000L
        private const val SPO2_MAX_AGE_MS = 2_500L
        private const val PRV_MAX_AGE_MS = 6_000L
    }

    private val database =
        AppDatabase.getDatabase(context.applicationContext)

    private val spo2Dao =
        database.spO2MeasurementDao()

    private val bloodPressureDao =
        database.bloodPressureMeasurementDao()

    private val prvDao =
        database.prvMeasurementDao()

    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private var lastSpo2SavedAt = 0L
    private var lastSpo2Boot: String? = null
    private var lastSpo2Sequence: Long? = null

    private var lastPrvSavedAt = 0L
    private var lastPrvBoot: String? = null
    private var lastPrvSequence: Long? = null

    private var lastPaBoot: String? = null
    private var lastPaWindowSeq: Long? = null

    fun recordSpo2(
        value: B18Spo2,
        boot: String
    ) {
        val spo2 = value.value ?: return
        val age = value.ageMs ?: return

        if (
            !value.valid ||
            value.reason != 0 ||
            age >= SPO2_MAX_AGE_MS ||
            !spo2.isFinite() ||
            spo2 !in 0f..100f
        ) {
            return
        }

        val sourceIsNew =
            lastSpo2Boot != boot ||
                lastSpo2Sequence != value.sequence

        if (!sourceIsNew) return

        lastSpo2Boot = boot
        lastSpo2Sequence = value.sequence

        val now = System.currentTimeMillis()

        if (
            lastSpo2SavedAt != 0L &&
            now - lastSpo2SavedAt <
                SPO2_SAVE_INTERVAL_MS
        ) {
            return
        }

        lastSpo2SavedAt = now

        scope.launch {
            spo2Dao.insert(
                SpO2MeasurementEntity(
                    timestamp = now,
                    spo2Percent = spo2
                )
            )
        }
    }

    fun recordPrv(
        value: B18Prv,
        boot: String
    ) {
        val age = value.ageMs ?: return
        val pp = value.ppMeanMs ?: return
        val rmssd = value.rmssdMs ?: return
        val sdnn = value.sdnnMs ?: return
        val pnn50 = value.pnn50Percent ?: return

        if (
            !value.valid ||
            age >= PRV_MAX_AGE_MS ||
            !pp.isFinite() ||
            !rmssd.isFinite() ||
            !sdnn.isFinite() ||
            !pnn50.isFinite()
        ) {
            return
        }

        val sourceIsNew =
            lastPrvBoot != boot ||
                lastPrvSequence != value.sequence

        if (!sourceIsNew) return

        lastPrvBoot = boot
        lastPrvSequence = value.sequence

        val now = System.currentTimeMillis()

        if (
            lastPrvSavedAt != 0L &&
            now - lastPrvSavedAt <
                PRV_SAVE_INTERVAL_MS
        ) {
            return
        }

        lastPrvSavedAt = now

        scope.launch {
            prvDao.insert(
                PrvMeasurementEntity(
                    timestamp = now,
                    ppMeanMs = pp,
                    rmssdMs = rmssd,
                    sdnnMs = sdnn,
                    pnn50Percent = pnn50,
                    spanMs = value.spanMs,
                    nn = value.nn,
                    total = value.total,
                    cleanPercent = value.cleanPercent
                )
            )
        }
    }

    fun recordBloodPressure(
        estimate: BloodPressureEstimate,
        windowSeq: Long,
        boot: String
    ) {
        val sourceIsNew =
            lastPaBoot != boot ||
                lastPaWindowSeq != windowSeq

        if (!sourceIsNew) return

        if (
            !estimate.systolicMmHg.isFinite() ||
            !estimate.diastolicMmHg.isFinite() ||
            estimate.systolicMmHg <=
                estimate.diastolicMmHg
        ) {
            return
        }

        lastPaBoot = boot
        lastPaWindowSeq = windowSeq

        val now = System.currentTimeMillis()

        scope.launch {
            bloodPressureDao.insert(
                BloodPressureMeasurementEntity(
                    timestamp = now,
                    systolicMmHg =
                        estimate.systolicMmHg,
                    diastolicMmHg =
                        estimate.diastolicMmHg,
                    windowSeq = windowSeq
                )
            )
        }
    }

    fun close() {
        scope.cancel()
    }
}
