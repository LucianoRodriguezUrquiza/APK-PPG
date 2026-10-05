package com.tallerbioing.ppgmonitor.bp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BloodPressureTransportPipelineInstrumentedTest {

    private data class Golden(
        val seq: Int,
        val raw: LongArray,
        val oracleSbp: Double,
        val oracleDbp: Double
    )

    @Test
    fun simulatedBleTransportToTfliteMatchesPythonOracle() {
        val context =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext

        var maxSbp = 0.0
        var maxDbp = 0.0

        BloodPressureModel
            .fromAssets(
                context,
                numThreads = 1
            )
            .use {
                    model ->

                for (
                    golden in
                    loadGoldens()
                ) {
                    val assembler =
                        BloodPressureWindowAssembler()

                    var completed:
                        BloodPressureRawWindow? =
                            null

                    val packets =
                        BloodPressureTransportProtocol
                            .encodeWindow(
                                windowSeq =
                                    golden.seq.toLong(),
                                spanMs = 6_990L,
                                samples = golden.raw,
                                maxPayloadBytes = 180
                            )

                    for (packet in packets) {
                        val event =
                            assembler.offer(packet)

                        if (
                            event is
                                BloodPressureTransportEvent.Complete
                        ) {
                            completed =
                                event.window
                        }
                    }

                    val window =
                        requireNotNull(
                            completed
                        )

                    assertTrue(
                        window.samples
                            .contentEquals(
                                golden.raw
                            )
                    )

                    val normalized =
                        BloodPressurePreprocessor
                            .preprocess(
                                window.samples
                            )
                            .normalized

                    val estimate =
                        model.predict(
                            normalized
                        )

                    val sbpError =
                        abs(
                            estimate
                                .systolicMmHg
                                .toDouble() -
                                golden.oracleSbp
                        )

                    val dbpError =
                        abs(
                            estimate
                                .diastolicMmHg
                                .toDouble() -
                                golden.oracleDbp
                        )

                    maxSbp =
                        maxOf(
                            maxSbp,
                            sbpError
                        )

                    maxDbp =
                        maxOf(
                            maxDbp,
                            dbpError
                        )

                    assertTrue(
                        "PAS seq=${golden.seq}: $sbpError",
                        sbpError < 0.01
                    )

                    assertTrue(
                        "PAD seq=${golden.seq}: $dbpError",
                        dbpError < 0.01
                    )
                }
            }

        println(
            "BP_TRANSPORT_PIPELINE_MAX " +
                "sbp=$maxSbp dbp=$maxDbp"
        )

        assertTrue(maxSbp < 0.01)
        assertTrue(maxDbp < 0.01)
    }

    private fun loadGoldens(): List<Golden> {
        val stream =
            InstrumentationRegistry
                .getInstrumentation()
                .context
                .assets
                .open(
                    "bp_golden_stages.txt"
                )

        val result =
            mutableListOf<Golden>()

        var seq = -1
        var oracleSbp = Double.NaN
        var oracleDbp = Double.NaN
        var raw: LongArray? = null

        fun flush() {
            if (seq < 0 || raw == null) return

            result +=
                Golden(
                    seq = seq,
                    raw = requireNotNull(raw),
                    oracleSbp = oracleSbp,
                    oracleDbp = oracleDbp
                )
        }

        stream.bufferedReader()
            .useLines {
                    lines ->

                lines.forEach {
                        source ->

                    val line =
                        source.trim()

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
                    }
                }
            }

        flush()

        require(
            result.size == 5
        )

        return result
    }
}
