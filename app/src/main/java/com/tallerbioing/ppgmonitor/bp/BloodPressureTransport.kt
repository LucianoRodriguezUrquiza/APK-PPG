package com.tallerbioing.ppgmonitor.bp

/**
 * Binary B19 PA transport carried by UUID ...0004.
 *
 * All multi-byte fields are unsigned little-endian. CRC32 is IEEE reflected
 * (poly 0xEDB88320, init/final xor 0xFFFFFFFF) over exactly 700 uint32 IR
 * samples serialized little-endian.
 */
object BloodPressureTransportProtocol {
    const val VERSION = 1
    const val TYPE_BEGIN = 1
    const val TYPE_DATA = 2
    const val TYPE_END = 3

    const val SAMPLE_COUNT = 700
    const val SAMPLE_RATE_HZ = 100
    const val MIN_SPAN_MS = 6_500L
    const val MAX_SPAN_MS = 7_500L

    private const val MAGIC_P = 0x50
    private const val MAGIC_A = 0x41

    internal fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    internal fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    internal fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    internal fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    internal fun validateCommon(bytes: ByteArray): String? {
        if (bytes.size < 8) return "Paquete PA demasiado corto"
        if ((bytes[0].toInt() and 0xFF) != MAGIC_P ||
            (bytes[1].toInt() and 0xFF) != MAGIC_A
        ) {
            return "Magic PA inválido"
        }
        if ((bytes[2].toInt() and 0xFF) != VERSION) {
            return "Versión PA no soportada"
        }
        return null
    }

    fun crc32(samples: LongArray): Long {
        require(samples.size == SAMPLE_COUNT)
        var crc = 0xFFFF_FFFFL

        for (sample in samples) {
            require(sample in 0L..0xFFFF_FFFFL)

            for (byteIndex in 0 until 4) {
                crc =
                    crc xor
                        ((sample ushr (8 * byteIndex)) and 0xFFL)

                repeat(8) {
                    crc =
                        if ((crc and 1L) != 0L) {
                            (crc ushr 1) xor 0xEDB88320L
                        } else {
                            crc ushr 1
                        }
                }
            }
        }

        return (crc xor 0xFFFF_FFFFL) and 0xFFFF_FFFFL
    }

    /**
     * Test/simulation encoder matching the firmware packet layout exactly.
     */
    fun encodeWindow(
        windowSeq: Long,
        spanMs: Long,
        samples: LongArray,
        maxPayloadBytes: Int = 180
    ): List<ByteArray> {
        require(windowSeq in 0L..0xFFFF_FFFFL)
        require(samples.size == SAMPLE_COUNT)
        require(maxPayloadBytes >= 20)

        val crc = crc32(samples)
        val packets = mutableListOf<ByteArray>()

        val begin = ByteArray(20)
        begin[0] = MAGIC_P.toByte()
        begin[1] = MAGIC_A.toByte()
        begin[2] = VERSION.toByte()
        begin[3] = TYPE_BEGIN.toByte()
        writeU32(begin, 4, windowSeq)
        writeU16(begin, 8, SAMPLE_COUNT)
        writeU16(begin, 10, SAMPLE_RATE_HZ)
        writeU32(begin, 12, spanMs)
        writeU32(begin, 16, crc)
        packets += begin

        val samplesPerPacket =
            (maxPayloadBytes - 12) / 4

        require(samplesPerPacket > 0)

        var start = 0

        while (start < samples.size) {
            val count =
                minOf(
                    samplesPerPacket,
                    samples.size - start
                )

            val data =
                ByteArray(
                    12 + 4 * count
                )

            data[0] = MAGIC_P.toByte()
            data[1] = MAGIC_A.toByte()
            data[2] = VERSION.toByte()
            data[3] = TYPE_DATA.toByte()

            writeU32(data, 4, windowSeq)
            writeU16(data, 8, start)
            writeU16(data, 10, count)

            for (i in 0 until count) {
                writeU32(
                    data,
                    12 + 4 * i,
                    samples[start + i]
                )
            }

            packets += data
            start += count
        }

        val end = ByteArray(16)
        end[0] = MAGIC_P.toByte()
        end[1] = MAGIC_A.toByte()
        end[2] = VERSION.toByte()
        end[3] = TYPE_END.toByte()
        writeU32(end, 4, windowSeq)
        writeU16(end, 8, SAMPLE_COUNT)
        writeU16(end, 10, 0)
        writeU32(end, 12, crc)
        packets += end

        return packets
    }
}

data class BloodPressureRawWindow(
    val windowSeq: Long,
    val sampleRateHz: Int,
    val spanMs: Long,
    val samples: LongArray,
    val crc32: Long
)

sealed class BloodPressureTransportEvent {
    data class Began(
        val windowSeq: Long,
        val totalSamples: Int
    ) : BloodPressureTransportEvent()

    data class Progress(
        val windowSeq: Long,
        val receivedSamples: Int,
        val totalSamples: Int
    ) : BloodPressureTransportEvent()

    data class Complete(
        val window: BloodPressureRawWindow
    ) : BloodPressureTransportEvent()

    data class Rejected(
        val reason: String
    ) : BloodPressureTransportEvent()
}

class BloodPressureWindowAssembler {

    private var activeSeq: Long? = null
    private var expectedCrc = 0L
    private var spanMs = 0L
    private var nextIndex = 0
    private var samples =
        LongArray(
            BloodPressureTransportProtocol.SAMPLE_COUNT
        )

    fun reset() {
        activeSeq = null
        expectedCrc = 0L
        spanMs = 0L
        nextIndex = 0
        samples.fill(0L)
    }

    fun offer(
        packet: ByteArray
    ): BloodPressureTransportEvent {
        BloodPressureTransportProtocol
            .validateCommon(packet)
            ?.let {
                reset()
                return BloodPressureTransportEvent
                    .Rejected(it)
            }

        val type =
            packet[3].toInt() and 0xFF

        val seq =
            BloodPressureTransportProtocol
                .readU32(packet, 4)

        return when (type) {
            BloodPressureTransportProtocol.TYPE_BEGIN ->
                handleBegin(packet, seq)

            BloodPressureTransportProtocol.TYPE_DATA ->
                handleData(packet, seq)

            BloodPressureTransportProtocol.TYPE_END ->
                handleEnd(packet, seq)

            else -> {
                reset()
                BloodPressureTransportEvent
                    .Rejected(
                        "Tipo de paquete PA desconocido"
                    )
            }
        }
    }

    private fun handleBegin(
        packet: ByteArray,
        seq: Long
    ): BloodPressureTransportEvent {
        if (packet.size != 20) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "BEGIN PA con longitud inválida"
                )
        }

        val count =
            BloodPressureTransportProtocol
                .readU16(packet, 8)

        val rate =
            BloodPressureTransportProtocol
                .readU16(packet, 10)

        val span =
            BloodPressureTransportProtocol
                .readU32(packet, 12)

        val crc =
            BloodPressureTransportProtocol
                .readU32(packet, 16)

        if (
            count !=
                BloodPressureTransportProtocol
                    .SAMPLE_COUNT
        ) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "BEGIN PA sampleCount inválido"
                )
        }

        if (
            rate !=
                BloodPressureTransportProtocol
                    .SAMPLE_RATE_HZ
        ) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "BEGIN PA sampleRate inválido"
                )
        }

        if (
            span !in
                BloodPressureTransportProtocol.MIN_SPAN_MS..
                    BloodPressureTransportProtocol.MAX_SPAN_MS
        ) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "BEGIN PA spanMs fuera de rango"
                )
        }

        activeSeq = seq
        expectedCrc = crc
        spanMs = span
        nextIndex = 0
        samples.fill(0L)

        return BloodPressureTransportEvent
            .Began(
                windowSeq = seq,
                totalSamples = count
            )
    }

    private fun handleData(
        packet: ByteArray,
        seq: Long
    ): BloodPressureTransportEvent {
        val active =
            activeSeq
                ?: return BloodPressureTransportEvent
                    .Rejected(
                        "DATA PA sin BEGIN"
                    )

        if (seq != active) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "DATA PA windowSeq incorrecto"
                )
        }

        if (packet.size < 16) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "DATA PA demasiado corto"
                )
        }

        val startIndex =
            BloodPressureTransportProtocol
                .readU16(packet, 8)

        val count =
            BloodPressureTransportProtocol
                .readU16(packet, 10)

        val expectedSize =
            12 + 4 * count

        if (
            count <= 0 ||
            packet.size != expectedSize
        ) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "DATA PA count/longitud inválidos"
                )
        }

        if (startIndex != nextIndex) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "DATA PA con hueco, duplicado o fuera de orden"
                )
        }

        if (
            startIndex + count >
            BloodPressureTransportProtocol.SAMPLE_COUNT
        ) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "DATA PA excede 700 muestras"
                )
        }

        for (i in 0 until count) {
            samples[startIndex + i] =
                BloodPressureTransportProtocol
                    .readU32(
                        packet,
                        12 + 4 * i
                    )
        }

        nextIndex += count

        return BloodPressureTransportEvent
            .Progress(
                windowSeq = seq,
                receivedSamples = nextIndex,
                totalSamples =
                    BloodPressureTransportProtocol
                        .SAMPLE_COUNT
            )
    }

    private fun handleEnd(
        packet: ByteArray,
        seq: Long
    ): BloodPressureTransportEvent {
        val active =
            activeSeq
                ?: return BloodPressureTransportEvent
                    .Rejected(
                        "END PA sin BEGIN"
                    )

        if (seq != active) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "END PA windowSeq incorrecto"
                )
        }

        if (packet.size != 16) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "END PA con longitud inválida"
                )
        }

        val count =
            BloodPressureTransportProtocol
                .readU16(packet, 8)

        val reserved =
            BloodPressureTransportProtocol
                .readU16(packet, 10)

        val endCrc =
            BloodPressureTransportProtocol
                .readU32(packet, 12)

        if (
            count !=
                BloodPressureTransportProtocol
                    .SAMPLE_COUNT ||
            reserved != 0
        ) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "END PA inválido"
                )
        }

        if (
            nextIndex !=
                BloodPressureTransportProtocol
                    .SAMPLE_COUNT
        ) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "Ventana PA incompleta"
                )
        }

        if (endCrc != expectedCrc) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "CRC PA BEGIN/END no coincide"
                )
        }

        val completed =
            samples.copyOf()

        val localCrc =
            BloodPressureTransportProtocol
                .crc32(completed)

        if (localCrc != expectedCrc) {
            reset()
            return BloodPressureTransportEvent
                .Rejected(
                    "CRC32 PA inválido"
                )
        }

        val window =
            BloodPressureRawWindow(
                windowSeq = active,
                sampleRateHz =
                    BloodPressureTransportProtocol
                        .SAMPLE_RATE_HZ,
                spanMs = spanMs,
                samples = completed,
                crc32 = localCrc
            )

        reset()

        return BloodPressureTransportEvent
            .Complete(window)
    }
}
