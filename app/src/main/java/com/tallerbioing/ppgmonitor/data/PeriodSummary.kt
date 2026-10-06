package com.tallerbioing.ppgmonitor.data

data class PeriodSummary(
    val averageBpm: Double?,
    val minimumBpm: Int?,
    val maximumBpm: Int?,
    val activity: ActivityDurations,
    val averageSpo2: Double?,
    val averageSystolic: Double?,
    val averageDiastolic: Double?,
    val averagePpMeanMs: Double?,
    val averageRmssdMs: Double?,
    val averageSdnnMs: Double?,
    val averagePnn50Percent: Double?,
    val validHeartCount: Int,
    val spo2Count: Int,
    val bloodPressureCount: Int,
    val prvCount: Int
)

object PeriodSummaryCalculator {

    private const val MIN_SIGNAL_QUALITY_FOR_HR_STATS = 3

    fun calculate(
        measurements: List<MeasurementEntity>,
        spo2Measurements: List<SpO2MeasurementEntity>,
        bloodPressureMeasurements: List<BloodPressureMeasurementEntity>,
        prvMeasurements: List<PrvMeasurementEntity>
    ): PeriodSummary {
        val reliableHeart =
            measurements.filter {
                it.bpm > 0 &&
                    it.signalQuality >= MIN_SIGNAL_QUALITY_FOR_HR_STATS
            }

        val bpms =
            reliableHeart.map {
                it.bpm
            }

        return PeriodSummary(
            averageBpm =
                bpms.takeIf { it.isNotEmpty() }
                    ?.average(),
            minimumBpm =
                bpms.minOrNull(),
            maximumBpm =
                bpms.maxOrNull(),
            activity =
                DailyStatistics.activityDurations(
                    measurements
                ),
            averageSpo2 =
                average(
                    spo2Measurements.map {
                        it.spo2Percent.toDouble()
                    }
                ),
            averageSystolic =
                average(
                    bloodPressureMeasurements.map {
                        it.systolicMmHg.toDouble()
                    }
                ),
            averageDiastolic =
                average(
                    bloodPressureMeasurements.map {
                        it.diastolicMmHg.toDouble()
                    }
                ),
            averagePpMeanMs =
                average(
                    prvMeasurements.map {
                        it.ppMeanMs.toDouble()
                    }
                ),
            averageRmssdMs =
                average(
                    prvMeasurements.map {
                        it.rmssdMs.toDouble()
                    }
                ),
            averageSdnnMs =
                average(
                    prvMeasurements.map {
                        it.sdnnMs.toDouble()
                    }
                ),
            averagePnn50Percent =
                average(
                    prvMeasurements.map {
                        it.pnn50Percent.toDouble()
                    }
                ),
            validHeartCount = reliableHeart.size,
            spo2Count = spo2Measurements.size,
            bloodPressureCount = bloodPressureMeasurements.size,
            prvCount = prvMeasurements.size
        )
    }

    private fun average(
        values: List<Double>
    ): Double? =
        values
            .takeIf { it.isNotEmpty() }
            ?.average()
}
