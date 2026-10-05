package com.tallerbioing.ppgmonitor.bp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BloodPressureModelInstrumentedTest {

    private data class GoldenWindow(
        val seq: Int,
        val raw: LongArray,
        val oracleSbp: Double,
        val oracleDbp: Double
    )

    @Test
    fun androidTfliteMatchesPythonOracleOnHistoricalWindows() {
        val instrumentation =
            InstrumentationRegistry
                .getInstrumentation()

        val targetContext =
            instrumentation
                .targetContext

        val windows =
            loadGoldens()

        var maxSbpError = 0.0
        var maxDbpError = 0.0

        BloodPressureModel
            .fromAssets(
                targetContext,
                numThreads = 1
            )
            .use { model ->

                for (window in windows) {
                    val stages =
                        BloodPressurePreprocessor
                            .preprocess(
                                window.raw
                            )

                    val estimate =
                        model.predict(
                            stages.normalized
                        )

                    val sbpError =
                        abs(
                            estimate
                                .systolicMmHg
                                .toDouble() -
                                window.oracleSbp
                        )

                    val dbpError =
                        abs(
                            estimate
                                .diastolicMmHg
                                .toDouble() -
                                window.oracleDbp
                        )

                    maxSbpError =
                        maxOf(
                            maxSbpError,
                            sbpError
                        )

                    maxDbpError =
                        maxOf(
                            maxDbpError,
                            dbpError
                        )

                    assertEquals(
                        "PAS seq=${window.seq}",
                        window.oracleSbp,
                        estimate
                            .systolicMmHg
                            .toDouble(),
                        0.01
                    )

                    assertEquals(
                        "PAD seq=${window.seq}",
                        window.oracleDbp,
                        estimate
                            .diastolicMmHg
                            .toDouble(),
                        0.01
                    )
                }
            }

        println(
            "BP_ANDROID_TFLITE_MAX " +
                "sbp=$maxSbpError " +
                "dbp=$maxDbpError"
        )

        assertTrue(
            maxSbpError < 0.01
        )

        assertTrue(
            maxDbpError < 0.01
        )
    }

    private fun loadGoldens(): List<GoldenWindow> {
        val instrumentation =
            InstrumentationRegistry
                .getInstrumentation()

        val stream =
            instrumentation
                .context
                .assets
                .open(
                    "bp_golden_stages.txt"
                )

        val result =
            mutableListOf<GoldenWindow>()

        var seq = -1
        var oracleSbp = Double.NaN
        var oracleDbp = Double.NaN
        var raw: LongArray? = null

        fun flush() {
            if (
                seq >= 0 &&
                raw != null
            ) {
                result +=
                    GoldenWindow(
                        seq = seq,
                        raw = requireNotNull(raw),
                        oracleSbp = oracleSbp,
                        oracleDbp = oracleDbp
                    )
            }
        }

        stream.bufferedReader()
            .useLines { lines ->

                lines.forEach {
                        sourceLine ->

                    val line =
                        sourceLine.trim()

                    if (
                        line.isEmpty() ||
                        line.startsWith("#")
                    ) {
                        return@forEach
                    }

                    val parts =
                        line.split('|')

                    when (
                        parts.first()
                    ) {
                        "SEQ" -> {
                            flush()

                            seq =
                                parts[1]
                                    .toInt()

                            oracleSbp =
                                parts[2]
                                    .toDouble()

                            oracleDbp =
                                parts[3]
                                    .toDouble()

                            raw = null
                        }

                        "RAW" ->
                            raw =
                                parts
                                    .drop(1)
                                    .map {
                                        it.toLong()
                                    }
                                    .toLongArray()

                        else -> {
                            // Stage arrays are verified by the JVM unit test.
                        }
                    }
                }
            }

        flush()

        require(
            result.size == 5
        ) {
            "Se esperaban cinco ventanas BP15 golden"
        }

        return result
    }
}
