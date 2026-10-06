package com.tallerbioing.ppgmonitor

import com.tallerbioing.ppgmonitor.data.BloodPressureMeasurementEntity
import com.tallerbioing.ppgmonitor.data.MeasurementEntity
import com.tallerbioing.ppgmonitor.data.PrvMeasurementEntity
import com.tallerbioing.ppgmonitor.data.SpO2MeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CsvExporterTest {

    @Test
    fun exportsDetailedRowsWithPhoneDateTimeAndDailyWeeklySummaries() {
        val zone =
            ZoneId.of("UTC")

        val t0 =
            Instant.parse(
                "2026-10-05T12:00:00Z"
            ).toEpochMilli()

        val t1 =
            Instant.parse(
                "2026-10-05T12:00:05Z"
            ).toEpochMilli()

        val csv =
            CsvExporter.buildCsvFromData(
                patient =
                    PatientProfile(
                        nombre = "Paciente Test",
                        edad = "30",
                        patologia = "",
                        medicacion = "",
                        peso = "70",
                        altura = "170"
                    ),
                measurements =
                    listOf(
                        MeasurementEntity(
                            timestamp = t0,
                            bpm = 60,
                            activityCode = 0,
                            signalQuality = 4,
                            batteryPercentage = 90
                        ),
                        MeasurementEntity(
                            timestamp = t1,
                            bpm = 80,
                            activityCode = 1,
                            signalQuality = 4,
                            batteryPercentage = 80
                        )
                    ),
                spo2Measurements =
                    listOf(
                        SpO2MeasurementEntity(
                            timestamp = t0,
                            spo2Percent = 98f
                        ),
                        SpO2MeasurementEntity(
                            timestamp = t1,
                            spo2Percent = 100f
                        )
                    ),
                bloodPressureMeasurements =
                    listOf(
                        BloodPressureMeasurementEntity(
                            timestamp = t0,
                            systolicMmHg = 120f,
                            diastolicMmHg = 80f,
                            windowSeq = 1L
                        ),
                        BloodPressureMeasurementEntity(
                            timestamp = t1,
                            systolicMmHg = 110f,
                            diastolicMmHg = 70f,
                            windowSeq = 2L
                        )
                    ),
                prvMeasurements =
                    listOf(
                        PrvMeasurementEntity(
                            timestamp = t0,
                            ppMeanMs = 900f,
                            rmssdMs = 50f,
                            sdnnMs = 60f,
                            pnn50Percent = 20f,
                            spanMs = 30_000L,
                            nn = 30L,
                            total = 32L,
                            cleanPercent = 94
                        ),
                        PrvMeasurementEntity(
                            timestamp = t1,
                            ppMeanMs = 1_000f,
                            rmssdMs = 70f,
                            sdnnMs = 80f,
                            pnn50Percent = 40f,
                            spanMs = 31_000L,
                            nn = 31L,
                            total = 33L,
                            cleanPercent = 95
                        )
                    ),
                zoneId = zone
            )

        val lines =
            csv.removePrefix("\uFEFF")
                .trim()
                .lines()

        val header =
            lines.first()
                .split(',')

        fun row(
            type: String
        ): Map<String, String> {
            val values =
                lines.first {
                    it.startsWith(
                        "$type,"
                    )
                }
                    .split(',')

            return header
                .zip(values)
                .toMap()
        }

        assertTrue(
            header.contains("hora")
        )

        assertEquals(
            "12:00:00",
            row("MEDICION_FC_ACTIVIDAD")["hora"]
        )

        assertEquals(
            "98.000",
            row("MEDICION_SPO2")["spo2_porcentaje"]
        )

        assertEquals(
            "120.000",
            row("MEDICION_PRESION_ARTERIAL")["pas_mmhg"]
        )

        assertEquals(
            "50.000",
            row("MEDICION_PRV")["rmssd_ms"]
        )

        val daily =
            row("RESUMEN_DIARIO")

        assertEquals(
            "70.000",
            daily["fc_promedio_bpm"]
        )

        assertEquals(
            "60",
            daily["fc_min_bpm"]
        )

        assertEquals(
            "80",
            daily["fc_max_bpm"]
        )

        assertEquals(
            "99.000",
            daily["spo2_promedio_porcentaje"]
        )

        assertEquals(
            "115.000",
            daily["pas_promedio_mmhg"]
        )

        assertEquals(
            "75.000",
            daily["pad_promedio_mmhg"]
        )

        assertEquals(
            "60.000",
            daily["rmssd_promedio_ms"]
        )

        assertEquals(
            "70.000",
            daily["sdnn_promedio_ms"]
        )

        assertEquals(
            "30.000",
            daily["pnn50_promedio_porcentaje"]
        )

        assertEquals(
            "950.000",
            daily["pp_medio_promedio_ms"]
        )

        assertEquals(
            "05/10/2026",
            daily["periodo_inicio"]
        )

        val weekly =
            row("RESUMEN_SEMANAL")

        assertEquals(
            "05/10/2026",
            weekly["periodo_inicio"]
        )

        assertEquals(
            "11/10/2026",
            weekly["periodo_fin"]
        )

        assertTrue(
            lines.none {
                it.startsWith("NOTA,")
            }
        )
    }
}
