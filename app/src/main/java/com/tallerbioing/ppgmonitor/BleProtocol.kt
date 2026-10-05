package com.tallerbioing.ppgmonitor

/**
 * Contrato Android del protocolo BLE B18 v2.
 *
 * Esta capa no recalcula variables fisiológicas: sólo valida, ensambla y
 * representa los resultados producidos por el firmware.
 */
const val B18_MAX_LOGICAL_LINE_BYTES = 192
const val B18_MAX_NOTIFY_PAYLOAD = 180
const val B18_PARTIAL_TIMEOUT_MS = 2_000L

private const val UINT32_MASK = 0xFFFF_FFFFL

data class B18Hello(
    val boot: String,
    val epoch: Long,
    val firmware: String,
    val capabilities: Int,
    val acquisitionHz: Int,
    val streamMaxHz: Int,
    val maxLineBytes: Int,
    val maxFragmentBytes: Int
)

data class B18Bpm(
    val bpm: Int?,
    val activity: Int,
    val quality: Int,
    val battery: Int?,
    val sequence: Long,
    val deviceTimeMs: Long,
    val visible: Boolean,
    val state: Int,
    val ageMs: Long?,
    val bpmSequence: Long
)

data class B18Ppg(
    val sequence: Long,
    val sampleTimeMs: Long?,
    val value: Float?,
    val valid: Boolean
)

data class B18Spo2(
    val sequence: Long,
    val deviceTimeMs: Long,
    val value: Float?,
    val valid: Boolean,
    val ageMs: Long?,
    val reason: Int
)

data class B18Prv(
    val sequence: Long,
    val deviceTimeMs: Long,
    val valid: Boolean,
    val ageMs: Long?,
    val spanMs: Long,
    val nn: Long,
    val total: Long,
    val pairs: Long,
    val cleanPercent: Int,
    val ppMeanMs: Float?,
    val rmssdMs: Float?,
    val sdnnMs: Float?,
    val pnn50Percent: Float?,
    val irregular: Long,
    val patterns: Long,
    val flag: Boolean?
)

data class B18Diagnostics(
    val sequence: Long,
    val deviceTimeMs: Long,
    val freeBytes: Long,
    val minFreeBytes: Long,
    val largestBlockBytes: Long,
    val stackMinBytes: Long,
    val txOk: Long,
    val txErrors: Long,
    val streamSkipped: Long,
    val snapshotSkipped: Long,
    val commandDrops: Long,
    val maxNotifyUs: Long,
    val formatErrors: Long
)

data class PpgDisplayPoint(
    val sequence: Long,
    val sampleTimeMs: Long?,
    val value: Float?,
    val valid: Boolean,
    val breakBefore: Boolean
)

sealed class B18Frame {
    data class Hello(val value: B18Hello) : B18Frame()
    data class Bpm(val value: B18Bpm) : B18Frame()
    data class Ppg(val value: B18Ppg) : B18Frame()
    data class Spo2(val value: B18Spo2) : B18Frame()
    data class Prv(val value: B18Prv) : B18Frame()
    data class Diagnostics(val value: B18Diagnostics) : B18Frame()
    data class Ack(val command: String) : B18Frame()
    data class Error(val error: String) : B18Frame()
}

sealed class B18AssemblerEvent {
    data class Line(val value: String) : B18AssemblerEvent()
    data object Resync : B18AssemblerEvent()
    data object Overflow : B18AssemblerEvent()
    data object InvalidAscii : B18AssemblerEvent()
}

/**
 * Ensamblador acotado por LF. El marcador ! invalida todo el parcial en curso
 * y descarta hasta el LF que cierra el marcador/resincronización.
 */
class B18LineAssembler {

    private val builder = StringBuilder()
    private var dropping = false
    private var resync = false
    private var invalidAscii = false

    val hasPartial: Boolean
        get() = builder.isNotEmpty() || dropping

    fun reset() {
        builder.clear()
        dropping = false
        resync = false
        invalidAscii = false
    }

    fun offer(bytes: ByteArray): List<B18AssemblerEvent> {
        val out = mutableListOf<B18AssemblerEvent>()

        for (raw in bytes) {
            val value = raw.toInt() and 0xFF

            if (value == 0x0A) {
                when {
                    resync -> out += B18AssemblerEvent.Resync
                    invalidAscii -> out += B18AssemblerEvent.InvalidAscii
                    dropping -> out += B18AssemblerEvent.Overflow
                    builder.isNotEmpty() -> out += B18AssemblerEvent.Line(builder.toString())
                }
                builder.clear()
                dropping = false
                resync = false
                invalidAscii = false
                continue
            }

            if (resync || dropping || invalidAscii) {
                continue
            }

            if (value == '!'.code) {
                builder.clear()
                resync = true
                continue
            }

            if (value !in 0x20..0x7E) {
                builder.clear()
                invalidAscii = true
                continue
            }

            // 192 incluye LF, por lo que el contenido previo al LF admite 191 bytes.
            if (builder.length >= B18_MAX_LOGICAL_LINE_BYTES - 1) {
                builder.clear()
                dropping = true
                continue
            }

            builder.append(value.toChar())
        }

        return out
    }
}

object BleProtocol {

    fun parse(line: String): B18Frame? = when {
        line.startsWith("H:2,") -> parseHello(line)?.let(B18Frame::Hello)
        line.startsWith("B:") -> parseBpm(line)?.let(B18Frame::Bpm)
        line.startsWith("S:") -> parsePpg(line)?.let(B18Frame::Ppg)
        line.startsWith("O:") -> parseSpo2(line)?.let(B18Frame::Spo2)
        line.startsWith("R:") -> parsePrv(line)?.let(B18Frame::Prv)
        line.startsWith("D:") -> parseDiagnostics(line)?.let(B18Frame::Diagnostics)
        line.startsWith("ACK:") && line.length > 4 -> B18Frame.Ack(line.substring(4))
        line.startsWith("ERR:") && line.length > 4 -> B18Frame.Error(line.substring(4))
        else -> null
    }

    fun u32Delta(newer: Long, older: Long): Long =
        (newer - older) and UINT32_MASK

    fun u32Next(value: Long): Long =
        (value + 1L) and UINT32_MASK

    private fun parseHello(line: String): B18Hello? {
        val p = line.split(',')
        if (p.size != 9 || p[0] != "H:2") return null

        val boot = p[1]
        if (!Regex("^[0-9A-F]{16}$").matches(boot)) return null

        return B18Hello(
            boot = boot,
            epoch = u32(p[2]) ?: return null,
            firmware = p[3],
            capabilities = p[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null,
            acquisitionHz = p[5].toIntOrNull()?.takeIf { it > 0 } ?: return null,
            streamMaxHz = p[6].toIntOrNull()?.takeIf { it >= 0 } ?: return null,
            maxLineBytes = p[7].toIntOrNull()?.takeIf { it > 0 } ?: return null,
            maxFragmentBytes = p[8].toIntOrNull()?.takeIf { it > 0 } ?: return null
        )
    }

    private fun parseBpm(line: String): B18Bpm? {
        val f = line.split(' ')
        if (f.size != 10) return null

        val bpmToken = nullableInt(tag(f[0], "B:") ?: return null, 0, 999) ?: return null
        val activity = int(tag(f[1], "A:") ?: return null, 0, 3) ?: return null
        val quality = int(tag(f[2], "C:") ?: return null, 0, 4) ?: return null
        if (quality == 1) return null
        val batteryToken = nullableInt(tag(f[3], "P:") ?: return null, 0, 100) ?: return null
        val sequence = u32(tag(f[4], "I:") ?: return null) ?: return null
        val time = u32(tag(f[5], "T:") ?: return null) ?: return null
        val visible = bool(tag(f[6], "V:") ?: return null) ?: return null
        val state = int(tag(f[7], "E:") ?: return null, 0, 6) ?: return null
        val ageToken = nullableU32(tag(f[8], "D:") ?: return null) ?: return null
        val sourceSeq = u32(tag(f[9], "N:") ?: return null) ?: return null

        val bpm = bpmToken.value
        val battery = batteryToken.value
        val age = ageToken.value

        return B18Bpm(
            bpm = bpm,
            activity = activity,
            quality = quality,
            battery = battery,
            sequence = sequence,
            deviceTimeMs = time,
            visible = visible,
            state = state,
            ageMs = age,
            bpmSequence = sourceSeq
        )
    }

    private fun parsePpg(line: String): B18Ppg? {
        val p = line.split(',')
        if (p.size != 4 || !p[0].startsWith("S:")) return null

        val sequence = u32(p[0].substring(2)) ?: return null
        val valid = bool(p[3]) ?: return null
        val sampleTime = nullableU32(p[1]) ?: return null
        val value = nullableFloat(p[2], -999999f, 999999f) ?: return null

        if (valid && (sampleTime.value == null || value.value == null)) return null
        if (!valid && (sampleTime.value != null || value.value != null)) return null

        return B18Ppg(sequence, sampleTime.value, value.value, valid)
    }

    private fun parseSpo2(line: String): B18Spo2? {
        val p = line.split(',')
        if (p.size != 6 || !p[0].startsWith("O:")) return null

        val sequence = u32(p[0].substring(2)) ?: return null
        val time = u32(p[1]) ?: return null
        val value = nullableFloat(p[2], 0f, 100f) ?: return null
        val valid = bool(p[3]) ?: return null
        val age = nullableU32(p[4]) ?: return null
        val reason = int(p[5], 0, 5) ?: return null

        if (valid && (value.value == null || age.value == null || reason != 0)) return null
        if (!valid && value.value != null) return null

        return B18Spo2(sequence, time, value.value, valid, age.value, reason)
    }

    private fun parsePrv(line: String): B18Prv? {
        val p = line.split(',')
        if (p.size != 16 || !p[0].startsWith("R:")) return null

        val sequence = u32(p[0].substring(2)) ?: return null
        val time = u32(p[1]) ?: return null
        val valid = bool(p[2]) ?: return null
        val age = nullableU32(p[3]) ?: return null
        val span = u32(p[4]) ?: return null
        val nn = u32(p[5]) ?: return null
        val total = u32(p[6]) ?: return null
        val pairs = u32(p[7]) ?: return null
        val clean = int(p[8], 0, 100) ?: return null
        val pp = nullableFloat(p[9], 0f, 100000f) ?: return null
        val rmssd = nullableFloat(p[10], 0f, 100000f) ?: return null
        val sdnn = nullableFloat(p[11], 0f, 100000f) ?: return null
        val pnn50 = nullableFloat(p[12], 0f, 100f) ?: return null
        val irregular = u32(p[13]) ?: return null
        val patterns = u32(p[14]) ?: return null
        val flag = nullableBool(p[15]) ?: return null

        if (valid && listOf(pp.value, rmssd.value, sdnn.value, pnn50.value).any { it == null }) return null
        if (!valid && listOf(pp.value, rmssd.value, sdnn.value, pnn50.value).any { it != null }) return null
        if (!valid && flag.value != null) return null

        return B18Prv(
            sequence, time, valid, age.value, span, nn, total, pairs, clean,
            pp.value, rmssd.value, sdnn.value, pnn50.value, irregular, patterns, flag.value
        )
    }

    private fun parseDiagnostics(line: String): B18Diagnostics? {
        val p = line.split(',')
        if (p.size != 13 || !p[0].startsWith("D:")) return null

        val values = LongArray(13)
        values[0] = u32(p[0].substring(2)) ?: return null
        for (i in 1..12) {
            values[i] = u32(p[i]) ?: return null
        }

        return B18Diagnostics(
            sequence = values[0],
            deviceTimeMs = values[1],
            freeBytes = values[2],
            minFreeBytes = values[3],
            largestBlockBytes = values[4],
            stackMinBytes = values[5],
            txOk = values[6],
            txErrors = values[7],
            streamSkipped = values[8],
            snapshotSkipped = values[9],
            commandDrops = values[10],
            maxNotifyUs = values[11],
            formatErrors = values[12]
        )
    }

    private fun tag(value: String, prefix: String): String? =
        value.takeIf { it.startsWith(prefix) }?.substring(prefix.length)

    private fun int(value: String, min: Int, max: Int): Int? =
        value.toIntOrNull()?.takeIf { it in min..max }

    private data class NullableToken<T>(val value: T?)

    private fun nullableInt(
        value: String,
        min: Int,
        max: Int
    ): NullableToken<Int>? =
        if (value == "NA") NullableToken(null)
        else int(value, min, max)?.let { NullableToken(it) }

    private fun u32(value: String): Long? =
        value.toLongOrNull()?.takeIf { it in 0L..UINT32_MASK }

    private fun nullableU32(value: String): NullableToken<Long>? =
        if (value == "NA") NullableToken(null)
        else u32(value)?.let { NullableToken(it) }

    private fun bool(value: String): Boolean? = when (value) {
        "0" -> false
        "1" -> true
        else -> null
    }

    private fun nullableBool(value: String): NullableToken<Boolean>? =
        if (value == "NA") NullableToken(null)
        else bool(value)?.let { NullableToken(it) }

    private fun nullableFloat(
        value: String,
        min: Float,
        max: Float
    ): NullableToken<Float>? {
        if (value == "NA") return NullableToken(null)
        val parsed = value.toFloatOrNull() ?: return null
        if (!parsed.isFinite() || parsed < min || parsed > max) return null
        return NullableToken(parsed)
    }
}
