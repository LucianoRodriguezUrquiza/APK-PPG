package com.tallerbioing.ppgmonitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyStatisticsTest {

    @Test
    fun activityDurationsUseTimestampsAndCapLongGaps() {
        val measurements =
            listOf(
                measurement(
                    timestamp = 0L,
                    activity = 0
                ),
                measurement(
                    timestamp = 5_000L,
                    activity = 0
                ),
                measurement(
                    timestamp = 10_000L,
                    activity = 1
                ),
                // 30 s de hueco: se limita a 10 s y no se atribuyen 30 s.
                measurement(
                    timestamp = 40_000L,
                    activity = 2
                )
            )

        val result =
            DailyStatistics
                .activityDurations(
                    measurements
                )

        assertEquals(
            10_000L,
            result.restMs
        )

        assertEquals(
            10_000L,
            result.lightMs
        )

        assertEquals(
            5_000L,
            result.moderateMs
        )

        assertEquals(
            25_000L,
            result.totalMs
        )
    }

    @Test
    fun activityDurationsIgnoreInvalidActivityCodes() {
        val result =
            DailyStatistics
                .activityDurations(
                    listOf(
                        measurement(
                            timestamp = 0L,
                            activity = 3
                        )
                    )
                )

        assertEquals(0L, result.totalMs)
    }

    @Test
    fun averageOrNullReturnsNullForNoValues() {
        assertNull(
            DailyStatistics
                .averageOrNull(
                    emptyList()
                )
        )
    }

    @Test
    fun averageOrNullUsesAllValidStoredValues() {
        assertEquals(
            98.0f,
            DailyStatistics
                .averageOrNull(
                    listOf(
                        97.0f,
                        98.0f,
                        99.0f
                    )
                )!!,
            0.0001f
        )
    }

    private fun measurement(
        timestamp: Long,
        activity: Int
    ): MeasurementEntity =
        MeasurementEntity(
            timestamp = timestamp,
            bpm = 70,
            activityCode = activity,
            signalQuality = 4,
            batteryPercentage = 80
        )
}
