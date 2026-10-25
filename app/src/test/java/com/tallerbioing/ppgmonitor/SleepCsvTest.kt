package com.tallerbioing.ppgmonitor

import com.tallerbioing.ppgmonitor.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SleepCsvTest {
    @Test fun boundariesMidnightOpenIntervalsAndMissingSamples() {
        val start = Instant.parse("2026-10-26T02:00:00Z").toEpochMilli()
        val end = start + 8 * 60 * 60 * 1000
        val period = SleepPeriod(7, start, end)
        assertEquals(28_800_000L, period.durationMs())
        assertFalse(period.contains(start - 1))
        assertTrue(period.contains(start))
        assertTrue(period.contains(end - 1))
        assertFalse(period.contains(end))
        val open = SleepPeriod(8, end + 86_400_000L)
        assertTrue(open.contains(open.startMs + 1000))
        val times = listOf(start - 1, start, end - 1, end, open.startMs)
        val measurements = times.map { MeasurementEntity(timestamp = it, bpm = 70,
            activityCode = 1, signalQuality = 4, batteryPercentage = 80) }
        val csv = CsvExporter.buildCsvFromData(null, measurements,
            listOf(SpO2MeasurementEntity(timestamp = start, spo2Percent = 98f)),
            listOf(BloodPressureMeasurementEntity(timestamp = end, systolicMmHg = 110f,
                diastolicMmHg = 70f, windowSeq = 1)),
            listOf(PrvMeasurementEntity(timestamp = start, ppMeanMs = 900f, rmssdMs = 30f,
                sdnnMs = 40f, pnn50Percent = 10f, spanMs = 30_000, nn = 30,
                total = 31, cleanPercent = 97)),
            ZoneId.of("America/Argentina/Buenos_Aires"), listOf(period, open))
        val lines = csv.removePrefix("\uFEFF").trim().lines().map { it.split(',') }
        val header = lines.first()
        fun value(row: List<String>, name: String) = row[header.indexOf(name)]
        val heart = lines.drop(1).filter { value(it, "tipo_registro") == "MEDICION_FC_ACTIVIDAD" }
        assertEquals(5, heart.size) // never invent measurements across gaps
        assertEquals(listOf("VIGILIA", "SUENO", "SUENO", "VIGILIA", "SUENO"),
            heart.map { value(it, "Estado_sueno") })
        assertEquals("25/10/2026", value(heart[1], "fecha"))
        assertEquals("26/10/2026", value(heart[2], "fecha"))
        assertEquals("7", value(heart[1], "sueno_periodo_id"))
        assertEquals("", value(heart.last(), "sueno_fin_ms"))
        assertEquals("1", value(heart[1], "actividad_codigo")) // sleep never replaces activity
        val details = lines.drop(1).filter { value(it, "tipo_registro").startsWith("MEDICION_") }
        assertEquals(8, details.size)
        assertTrue(details.all { value(it, "Estado_sueno") in listOf("VIGILIA", "SUENO") })
        val intervals = lines.drop(1).filter { value(it, "tipo_registro") == "PERIODO_SUENO_DECLARADO" }
        assertEquals(2, intervals.size)
        assertEquals("28800000", value(intervals.first(), "sueno_duracion_ms"))
        val oldCsv = CsvExporter.buildCsvFromData(null, measurements, emptyList(), emptyList(),
            emptyList(), ZoneId.of("UTC"))
        assertTrue(oldCsv.contains("VIGILIA"))
    }
}
