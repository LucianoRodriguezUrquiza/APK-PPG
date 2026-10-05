package com.tallerbioing.ppgmonitor.bp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodPressureTransportTest {

    @Test
    fun roundTripReconstructsExactlyAtMtu247SizedPayload() {
        val raw = loadFirstGoldenRaw()
        val packets =
            BloodPressureTransportProtocol
                .encodeWindow(
                    windowSeq = 7L,
                    spanMs = 6_990L,
                    samples = raw,
                    maxPayloadBytes = 180
                )

        val assembler =
            BloodPressureWindowAssembler()

        var complete:
            BloodPressureTransportEvent.Complete? =
                null

        for (packet in packets) {
            val event =
                assembler.offer(packet)

            if (
                event is
                    BloodPressureTransportEvent.Complete
            ) {
                complete = event
            }
        }

        val window =
            requireNotNull(complete)
                .window

        assertEquals(7L, window.windowSeq)
        assertEquals(100, window.sampleRateHz)
        assertEquals(6_990L, window.spanMs)
        assertArrayEquals(raw, window.samples)
        assertEquals(
            BloodPressureTransportProtocol
                .crc32(raw),
            window.crc32
        )
    }

    @Test
    fun roundTripAlsoWorksWithMtu23Payload() {
        val raw = loadFirstGoldenRaw()
        val packets =
            BloodPressureTransportProtocol
                .encodeWindow(
                    windowSeq = 8L,
                    spanMs = 7_000L,
                    samples = raw,
                    maxPayloadBytes = 20
                )

        val assembler =
            BloodPressureWindowAssembler()

        var completed = false

        for (packet in packets) {
            completed =
                completed ||
                    assembler.offer(packet) is
                        BloodPressureTransportEvent.Complete
        }

        assertTrue(completed)
    }

    @Test
    fun duplicateDataBlockIsRejected() {
        val raw = loadFirstGoldenRaw()
        val packets =
            BloodPressureTransportProtocol
                .encodeWindow(
                    windowSeq = 9L,
                    spanMs = 6_990L,
                    samples = raw
                )

        val assembler =
            BloodPressureWindowAssembler()

        assertTrue(
            assembler.offer(packets[0]) is
                BloodPressureTransportEvent.Began
        )

        assertTrue(
            assembler.offer(packets[1]) is
                BloodPressureTransportEvent.Progress
        )

        val duplicate =
            assembler.offer(packets[1])

        assertTrue(
            duplicate is
                BloodPressureTransportEvent.Rejected
        )
    }

    @Test
    fun corruptedPayloadFailsCrc() {
        val raw = loadFirstGoldenRaw()
        val packets =
            BloodPressureTransportProtocol
                .encodeWindow(
                    windowSeq = 10L,
                    spanMs = 6_990L,
                    samples = raw
                )
                .map {
                    it.copyOf()
                }
                .toMutableList()

        // Corrupt one raw IR byte, leaving BEGIN/END CRC untouched.
        packets[2][12] =
            (packets[2][12].toInt() xor 0x01)
                .toByte()

        val assembler =
            BloodPressureWindowAssembler()

        var last:
            BloodPressureTransportEvent? =
                null

        for (packet in packets) {
            last = assembler.offer(packet)
        }

        assertTrue(
            last is
                BloodPressureTransportEvent.Rejected
        )
    }

    private fun loadFirstGoldenRaw(): LongArray {
        val stream =
            requireNotNull(
                javaClass.classLoader
                    ?.getResourceAsStream(
                        "bp_golden_stages.txt"
                    )
            )

        stream.bufferedReader()
            .useLines {
                    lines ->

                lines.forEach {
                        source ->

                    val line =
                        source.trim()

                    if (
                        line.startsWith(
                            "RAW|"
                        )
                    ) {
                        return line
                            .split('|')
                            .drop(1)
                            .map {
                                it.toLong()
                            }
                            .toLongArray()
                    }
                }
            }

        error(
            "No se encontró RAW golden"
        )
    }
}
