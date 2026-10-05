package com.tallerbioing.ppgmonitor.bp

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class BloodPressurePreprocessorTest {

    private data class GoldenWindow(
        val seq: Int,
        val raw: DoubleArray,
        val resampled: DoubleArray,
        val filtered: DoubleArray,
        val normalized: FloatArray,
        val oracleSbp: Double,
        val oracleDbp: Double
    )

    @Test
    fun goldenStagesMatchCanonicalPythonOracle() {
        val windows = loadGoldens()
        require(windows.size == 5)

        var maxResampled = 0.0
        var maxFiltered = 0.0
        var maxNormalized = 0.0

        for (window in windows) {
            val actual =
                BloodPressurePreprocessor
                    .preprocess(window.raw)

            maxResampled =
                maxOf(
                    maxResampled,
                    maxAbs(
                        actual.resampled,
                        window.resampled
                    )
                )

            maxFiltered =
                maxOf(
                    maxFiltered,
                    maxAbs(
                        actual.filtered,
                        window.filtered
                    )
                )

            maxNormalized =
                maxOf(
                    maxNormalized,
                    maxAbs(
                        actual.normalized,
                        window.normalized
                    )
                )
        }

        println(
            "BP_STAGE_MAX " +
                "resampled=$maxResampled " +
                "filtered=$maxFiltered " +
                "normalized=$maxNormalized"
        )

        // These tolerances are deliberately much tighter than anything
        // physiologically meaningful. The filtered-domain tolerance is larger
        // because sub-nanounit FIR summation differences are recursively
        // amplified by the 8th-order direct-form IIR before z-normalization.
        assertTrue(
            "resample_poly mismatch: $maxResampled",
            maxResampled < 1e-6
        )

        assertTrue(
            "filtfilt mismatch: $maxFiltered",
            maxFiltered < 1e-2
        )

        assertTrue(
            "z-score mismatch: $maxNormalized",
            maxNormalized < 1e-5
        )
    }

    @Test
    fun rejectsWrongSampleCount() {
        expectReject {
            BloodPressurePreprocessor
                .preprocess(
                    DoubleArray(699) {
                        100000.0
                    }
                )
        }
    }

    @Test
    fun rejectsNonFiniteInput() {
        val raw =
            DoubleArray(700) {
                100000.0
            }

        raw[100] =
            Double.NaN

        expectReject {
            BloodPressurePreprocessor
                .preprocess(raw)
        }
    }

    @Test
    fun rejectsLossOfContactRange() {
        val raw =
            DoubleArray(700) {
                100000.0
            }

        raw[100] =
            7999.0

        expectReject {
            BloodPressurePreprocessor
                .preprocess(raw)
        }
    }

    @Test
    fun rejectsSaturationRange() {
        val raw =
            DoubleArray(700) {
                100000.0
            }

        raw[100] =
            260000.0

        expectReject {
            BloodPressurePreprocessor
                .preprocess(raw)
        }
    }

    @Test
    fun modelOutputValidationNeverClips() {
        val valid =
            BloodPressureModel
                .validateEstimate(
                    systolic = 109.1455f,
                    diastolic = 63.4895f
                )

        assertTrue(
            valid.systolicMmHg == 109.1455f
        )

        assertTrue(
            valid.diastolicMmHg == 63.4895f
        )

        expectReject {
            BloodPressureModel
                .validateEstimate(
                    systolic = 59.99f,
                    diastolic = 40.0f
                )
        }

        expectReject {
            BloodPressureModel
                .validateEstimate(
                    systolic = 120.0f,
                    diastolic = 150.01f
                )
        }

        expectReject {
            BloodPressureModel
                .validateEstimate(
                    systolic = 80.0f,
                    diastolic = 80.0f
                )
        }
    }

    private fun loadGoldens(): List<GoldenWindow> {
        val stream =
            requireNotNull(
                javaClass.classLoader
                    ?.getResourceAsStream(
                        "bp_golden_stages.txt"
                    )
            ) {
                "bp_golden_stages.txt no encontrado"
            }

        val result =
            mutableListOf<GoldenWindow>()

        var seq = -1
        var oracleSbp = Double.NaN
        var oracleDbp = Double.NaN
        var raw: DoubleArray? = null
        var resampled: DoubleArray? = null
        var filtered: DoubleArray? = null
        var normalized: FloatArray? = null

        fun flush() {
            if (seq < 0) return

            result +=
                GoldenWindow(
                    seq = seq,
                    raw = requireNotNull(raw),
                    resampled = requireNotNull(resampled),
                    filtered = requireNotNull(filtered),
                    normalized = requireNotNull(normalized),
                    oracleSbp = oracleSbp,
                    oracleDbp = oracleDbp
                )
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
                            resampled = null
                            filtered = null
                            normalized = null
                        }

                        "RAW" ->
                            raw =
                                parts
                                    .drop(1)
                                    .map {
                                        it.toDouble()
                                    }
                                    .toDoubleArray()

                        "RESAMPLED" ->
                            resampled =
                                parts
                                    .drop(1)
                                    .map {
                                        it.toDouble()
                                    }
                                    .toDoubleArray()

                        "FILTERED" ->
                            filtered =
                                parts
                                    .drop(1)
                                    .map {
                                        it.toDouble()
                                    }
                                    .toDoubleArray()

                        "NORMALIZED" ->
                            normalized =
                                parts
                                    .drop(1)
                                    .map {
                                        it.toFloat()
                                    }
                                    .toFloatArray()

                        else ->
                            error(
                                "Línea golden desconocida"
                            )
                    }
                }
            }

        flush()

        return result
    }

    private fun maxAbs(
        a: DoubleArray,
        b: DoubleArray
    ): Double {
        require(
            a.size == b.size
        )

        var max = 0.0

        for (i in a.indices) {
            max =
                maxOf(
                    max,
                    abs(
                        a[i] -
                            b[i]
                    )
                )
        }

        return max
    }

    private fun maxAbs(
        a: FloatArray,
        b: FloatArray
    ): Double {
        require(
            a.size == b.size
        )

        var max = 0.0

        for (i in a.indices) {
            max =
                maxOf(
                    max,
                    abs(
                        a[i].toDouble() -
                            b[i].toDouble()
                    )
                )
        }

        return max
    }

    private fun expectReject(
        block: () -> Unit
    ) {
        try {
            block()
            fail(
                "Se esperaba rechazo"
            )
        } catch (
            _: IllegalArgumentException
        ) {
            // Expected.
        }
    }
}
