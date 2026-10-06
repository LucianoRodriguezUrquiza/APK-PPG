package com.tallerbioing.ppgmonitor.data

import kotlin.math.roundToLong

data class ActivityDurations(
    val restMs: Long,
    val lightMs: Long,
    val moderateMs: Long
) {
    val totalMs: Long
        get() =
            restMs +
                lightMs +
                moderateMs
}

object DailyStatistics {

    private const val EXPECTED_INTERVAL_MS = 5_000L
    private const val MAX_COUNTED_INTERVAL_MS = 10_000L

    /**
     * Estima tiempo observado sin convertir huecos largos de desconexión en
     * actividad. Cada muestra aporta hasta el siguiente timestamp, limitado a
     * 10 s. La última muestra aporta el intervalo esperado de 5 s.
     */
    fun activityDurations(
        measurements: List<MeasurementEntity>
    ): ActivityDurations {
        val valid =
            measurements
                .filter {
                    it.activityCode in 0..2
                }
                .sortedBy {
                    it.timestamp
                }

        if (valid.isEmpty()) {
            return ActivityDurations(
                restMs = 0L,
                lightMs = 0L,
                moderateMs = 0L
            )
        }

        var rest = 0L
        var light = 0L
        var moderate = 0L

        valid.forEachIndexed {
                index,
                measurement ->

            val duration =
                if (index < valid.lastIndex) {
                    val delta =
                        valid[index + 1].timestamp -
                            measurement.timestamp

                    if (delta <= 0L) {
                        0L
                    } else {
                        minOf(
                            delta,
                            MAX_COUNTED_INTERVAL_MS
                        )
                    }
                } else {
                    EXPECTED_INTERVAL_MS
                }

            when (measurement.activityCode) {
                0 -> rest += duration
                1 -> light += duration
                2 -> moderate += duration
            }
        }

        return ActivityDurations(
            restMs = rest,
            lightMs = light,
            moderateMs = moderate
        )
    }

    fun averageOrNull(
        values: List<Float>
    ): Float? =
        values
            .takeIf {
                it.isNotEmpty()
            }
            ?.map {
                it.toDouble()
            }
            ?.average()
            ?.toFloat()

    fun formatDuration(
        durationMs: Long
    ): String {
        if (durationMs <= 0L) return "0 s"

        if (durationMs < 60_000L) {
            val seconds =
                (durationMs / 1_000.0)
                    .roundToLong()
                    .coerceAtLeast(1L)

            return "${seconds} s"
        }

        val totalMinutes =
            (durationMs / 60_000.0)
                .roundToLong()

        if (totalMinutes < 60L) {
            return "${totalMinutes} min"
        }

        val hours =
            totalMinutes / 60L

        val minutes =
            totalMinutes % 60L

        return if (minutes == 0L) {
            "${hours} h"
        } else {
            "${hours} h ${minutes} min"
        }
    }
}
