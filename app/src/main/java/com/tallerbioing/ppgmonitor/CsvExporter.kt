package com.tallerbioing.ppgmonitor

import android.content.Context
import android.net.Uri

import com.tallerbioing.ppgmonitor.data.AppDatabase
import com.tallerbioing.ppgmonitor.data.BloodPressureMeasurementEntity
import com.tallerbioing.ppgmonitor.data.MeasurementEntity
import com.tallerbioing.ppgmonitor.data.PeriodSummary
import com.tallerbioing.ppgmonitor.data.PeriodSummaryCalculator
import com.tallerbioing.ppgmonitor.data.PrvMeasurementEntity
import com.tallerbioing.ppgmonitor.data.SpO2MeasurementEntity

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exportación integral del seguimiento en un único CSV UTF-8 con BOM.
 *
 * El archivo contiene:
 * - perfil de paciente;
 * - registros detallados con fecha y hora del teléfono;
 * - resúmenes diarios;
 * - resúmenes semanales.
 *
 * Las notas de paciente se mantienen en la APK pero no se exportan.
 *
 * Las distintas variables se almacenan con cadencias diferentes, por lo que
 * cada observación detallada ocupa su propia fila. "tipo_registro" permite
 * filtrar fácilmente cada familia desde Excel.
 */
object CsvExporter {

    private val columns =
        listOf(
            "tipo_registro",
            "timestamp_ms",
            "fecha",
            "hora",
            "fecha_hora",
            "zona_horaria",
            "nombre",
            "edad",
            "patologia",
            "medicacion",
            "peso_kg",
            "altura_cm",
            "bpm",
            "fc_promedio_bpm",
            "fc_min_bpm",
            "fc_max_bpm",
            "actividad_codigo",
            "actividad",
            "calidad_senal",
            "calidad_senal_promedio",
            "bateria_porcentaje",
            "bateria_promedio_porcentaje",
            "tiempo_observado_min",
            "tiempo_reposo_min",
            "tiempo_leve_min",
            "tiempo_moderado_min",
            "spo2_porcentaje",
            "spo2_promedio_porcentaje",
            "pas_mmhg",
            "pad_mmhg",
            "pas_promedio_mmhg",
            "pad_promedio_mmhg",
            "pa_window_seq",
            "pp_medio_ms",
            "rmssd_ms",
            "sdnn_ms",
            "pnn50_porcentaje",
            "prv_span_ms",
            "prv_nn",
            "prv_total",
            "prv_limpia_porcentaje",
            "pp_medio_promedio_ms",
            "rmssd_promedio_ms",
            "sdnn_promedio_ms",
            "pnn50_promedio_porcentaje",
            "n_fc_totales",
            "n_fc_validas",
            "n_spo2_validas",
            "n_pa_validas",
            "n_prv_validas",
            "periodo_inicio",
            "periodo_fin"
        )

    private data class DetailedRow(
        val timestamp: Long,
        val values: Map<String, String>
    )

    suspend fun buildCsv(
        context: Context
    ): String {
        val applicationContext =
            context.applicationContext

        val database =
            AppDatabase.getDatabase(
                applicationContext
            )

        val measurements =
            database
                .measurementDao()
                .getAllMeasurements()

        val spo2Measurements =
            database
                .spO2MeasurementDao()
                .getAll()

        val bloodPressureMeasurements =
            database
                .bloodPressureMeasurementDao()
                .getAll()

        val prvMeasurements =
            database
                .prvMeasurementDao()
                .getAll()

        val patient =
            PatientPreferences.load(
                applicationContext
            )

        val zoneId =
            ZoneId.systemDefault()

        val builder =
            StringBuilder()

        // BOM UTF-8 para que Excel reconozca correctamente tildes, ñ, SpO₂, etc.
        builder.append('\uFEFF')

        builder.appendLine(
            columns.joinToString(",")
        )

        if (patient != null) {
            appendRow(
                builder,
                mapOf(
                    "tipo_registro" to "PACIENTE",
                    "zona_horaria" to zoneId.id,
                    "nombre" to patient.nombre,
                    "edad" to patient.edad,
                    "patologia" to patient.patologia,
                    "medicacion" to patient.medicacion,
                    "peso_kg" to patient.peso,
                    "altura_cm" to patient.altura
                )
            )
        }

        // -----------------------------------------------------------------
        // REGISTROS DETALLADOS
        // -----------------------------------------------------------------

        val detailedRows =
            buildList {
                measurements.forEach {
                    add(
                        DetailedRow(
                            timestamp = it.timestamp,
                            values =
                                measurementRow(
                                    it,
                                    zoneId
                                )
                        )
                    )
                }

                spo2Measurements.forEach {
                    add(
                        DetailedRow(
                            timestamp = it.timestamp,
                            values =
                                spo2Row(
                                    it,
                                    zoneId
                                )
                        )
                    )
                }

                bloodPressureMeasurements.forEach {
                    add(
                        DetailedRow(
                            timestamp = it.timestamp,
                            values =
                                bloodPressureRow(
                                    it,
                                    zoneId
                                )
                        )
                    )
                }

                prvMeasurements.forEach {
                    add(
                        DetailedRow(
                            timestamp = it.timestamp,
                            values =
                                prvRow(
                                    it,
                                    zoneId
                                )
                        )
                    )
                }
            }
                .sortedBy {
                    it.timestamp
                }

        detailedRows.forEach {
            appendRow(
                builder,
                it.values
            )
        }

        // -----------------------------------------------------------------
        // RESÚMENES DIARIOS
        // -----------------------------------------------------------------

        val allDates =
            buildSet {
                measurements.forEach {
                    add(
                        localDate(
                            it.timestamp,
                            zoneId
                        )
                    )
                }
                spo2Measurements.forEach {
                    add(
                        localDate(
                            it.timestamp,
                            zoneId
                        )
                    )
                }
                bloodPressureMeasurements.forEach {
                    add(
                        localDate(
                            it.timestamp,
                            zoneId
                        )
                    )
                }
                prvMeasurements.forEach {
                    add(
                        localDate(
                            it.timestamp,
                            zoneId
                        )
                    )
                }
            }
                .sorted()

        allDates.forEach {
                date ->

            val heart =
                measurements.filter {
                    localDate(
                        it.timestamp,
                        zoneId
                    ) == date
                }

            val spo2 =
                spo2Measurements.filter {
                    localDate(
                        it.timestamp,
                        zoneId
                    ) == date
                }

            val pressure =
                bloodPressureMeasurements.filter {
                    localDate(
                        it.timestamp,
                        zoneId
                    ) == date
                }

            val prv =
                prvMeasurements.filter {
                    localDate(
                        it.timestamp,
                        zoneId
                    ) == date
                }

            val summary =
                PeriodSummaryCalculator
                    .calculate(
                        measurements = heart,
                        spo2Measurements = spo2,
                        bloodPressureMeasurements = pressure,
                        prvMeasurements = prv
                    )

            appendRow(
                builder,
                summaryRow(
                    type = "RESUMEN_DIARIO",
                    startDate = date,
                    endDate = date,
                    summary = summary,
                    rawMeasurements = heart,
                    zoneId = zoneId
                )
            )
        }

        // -----------------------------------------------------------------
        // RESÚMENES SEMANALES (lunes a domingo)
        // -----------------------------------------------------------------

        val weekStarts =
            allDates
                .map {
                    weekStart(it)
                }
                .distinct()
                .sorted()

        weekStarts.forEach {
                startDate ->

            val endDate =
                startDate.plusDays(6)

            fun inWeek(
                timestamp: Long
            ): Boolean {
                val date =
                    localDate(
                        timestamp,
                        zoneId
                    )

                return !date.isBefore(
                    startDate
                ) &&
                    !date.isAfter(
                        endDate
                    )
            }

            val heart =
                measurements.filter {
                    inWeek(it.timestamp)
                }

            val spo2 =
                spo2Measurements.filter {
                    inWeek(it.timestamp)
                }

            val pressure =
                bloodPressureMeasurements.filter {
                    inWeek(it.timestamp)
                }

            val prv =
                prvMeasurements.filter {
                    inWeek(it.timestamp)
                }

            val summary =
                PeriodSummaryCalculator
                    .calculate(
                        measurements = heart,
                        spo2Measurements = spo2,
                        bloodPressureMeasurements = pressure,
                        prvMeasurements = prv
                    )

            appendRow(
                builder,
                summaryRow(
                    type = "RESUMEN_SEMANAL",
                    startDate = startDate,
                    endDate = endDate,
                    summary = summary,
                    rawMeasurements = heart,
                    zoneId = zoneId
                )
            )
        }

        return builder.toString()
    }

    fun writeCsv(
        context: Context,
        uri: Uri,
        csvContent: String
    ) {
        context
            .contentResolver
            .openOutputStream(uri)
            ?.bufferedWriter(
                Charsets.UTF_8
            )
            ?.use {
                it.write(csvContent)
            }
    }

    private fun measurementRow(
        measurement: MeasurementEntity,
        zoneId: ZoneId
    ): Map<String, String> {
        val activityText =
            when (
                measurement.activityCode
            ) {
                0 -> "Reposo"
                1 -> "Movimiento leve"
                2 -> "Movimiento moderado"
                else -> "Desconocido"
            }

        return timestampFields(
            measurement.timestamp,
            zoneId
        ) +
            mapOf(
                "tipo_registro" to "MEDICION_FC_ACTIVIDAD",
                "bpm" to measurement.bpm.toString(),
                "actividad_codigo" to measurement.activityCode.toString(),
                "actividad" to activityText,
                "calidad_senal" to measurement.signalQuality.toString(),
                "bateria_porcentaje" to measurement.batteryPercentage.toString()
            )
    }

    private fun spo2Row(
        measurement: SpO2MeasurementEntity,
        zoneId: ZoneId
    ): Map<String, String> =
        timestampFields(
            measurement.timestamp,
            zoneId
        ) +
            mapOf(
                "tipo_registro" to "MEDICION_SPO2",
                "spo2_porcentaje" to decimal(
                    measurement.spo2Percent.toDouble()
                )
            )

    private fun bloodPressureRow(
        measurement: BloodPressureMeasurementEntity,
        zoneId: ZoneId
    ): Map<String, String> =
        timestampFields(
            measurement.timestamp,
            zoneId
        ) +
            mapOf(
                "tipo_registro" to "MEDICION_PRESION_ARTERIAL",
                "pas_mmhg" to decimal(
                    measurement.systolicMmHg.toDouble()
                ),
                "pad_mmhg" to decimal(
                    measurement.diastolicMmHg.toDouble()
                ),
                "pa_window_seq" to measurement.windowSeq.toString()
            )

    private fun prvRow(
        measurement: PrvMeasurementEntity,
        zoneId: ZoneId
    ): Map<String, String> =
        timestampFields(
            measurement.timestamp,
            zoneId
        ) +
            mapOf(
                "tipo_registro" to "MEDICION_PRV",
                "pp_medio_ms" to decimal(
                    measurement.ppMeanMs.toDouble()
                ),
                "rmssd_ms" to decimal(
                    measurement.rmssdMs.toDouble()
                ),
                "sdnn_ms" to decimal(
                    measurement.sdnnMs.toDouble()
                ),
                "pnn50_porcentaje" to decimal(
                    measurement.pnn50Percent.toDouble()
                ),
                "prv_span_ms" to measurement.spanMs.toString(),
                "prv_nn" to measurement.nn.toString(),
                "prv_total" to measurement.total.toString(),
                "prv_limpia_porcentaje" to measurement.cleanPercent.toString()
            )

    private fun summaryRow(
        type: String,
        startDate: LocalDate,
        endDate: LocalDate,
        summary: PeriodSummary,
        rawMeasurements: List<MeasurementEntity>,
        zoneId: ZoneId
    ): Map<String, String> {
        val averageSignalQuality =
            rawMeasurements
                .takeIf {
                    it.isNotEmpty()
                }
                ?.map {
                    it.signalQuality.toDouble()
                }
                ?.average()

        val averageBattery =
            rawMeasurements
                .filter {
                    it.batteryPercentage in 0..100
                }
                .takeIf {
                    it.isNotEmpty()
                }
                ?.map {
                    it.batteryPercentage.toDouble()
                }
                ?.average()

        return mapOf(
            "tipo_registro" to type,
            "fecha" to if (
                startDate == endDate
            ) {
                formatDate(startDate)
            } else {
                ""
            },
            "zona_horaria" to zoneId.id,
            "fc_promedio_bpm" to decimalOrEmpty(
                summary.averageBpm
            ),
            "fc_min_bpm" to (
                summary.minimumBpm
                    ?.toString()
                    ?: ""
                ),
            "fc_max_bpm" to (
                summary.maximumBpm
                    ?.toString()
                    ?: ""
                ),
            "calidad_senal_promedio" to decimalOrEmpty(
                averageSignalQuality
            ),
            "bateria_promedio_porcentaje" to decimalOrEmpty(
                averageBattery
            ),
            "tiempo_observado_min" to durationMinutes(
                summary.activity.totalMs
            ),
            "tiempo_reposo_min" to durationMinutes(
                summary.activity.restMs
            ),
            "tiempo_leve_min" to durationMinutes(
                summary.activity.lightMs
            ),
            "tiempo_moderado_min" to durationMinutes(
                summary.activity.moderateMs
            ),
            "spo2_promedio_porcentaje" to decimalOrEmpty(
                summary.averageSpo2
            ),
            "pas_promedio_mmhg" to decimalOrEmpty(
                summary.averageSystolic
            ),
            "pad_promedio_mmhg" to decimalOrEmpty(
                summary.averageDiastolic
            ),
            "pp_medio_promedio_ms" to decimalOrEmpty(
                summary.averagePpMeanMs
            ),
            "rmssd_promedio_ms" to decimalOrEmpty(
                summary.averageRmssdMs
            ),
            "sdnn_promedio_ms" to decimalOrEmpty(
                summary.averageSdnnMs
            ),
            "pnn50_promedio_porcentaje" to decimalOrEmpty(
                summary.averagePnn50Percent
            ),
            "n_fc_totales" to rawMeasurements.size.toString(),
            "n_fc_validas" to summary.validHeartCount.toString(),
            "n_spo2_validas" to summary.spo2Count.toString(),
            "n_pa_validas" to summary.bloodPressureCount.toString(),
            "n_prv_validas" to summary.prvCount.toString(),
            "periodo_inicio" to formatDate(startDate),
            "periodo_fin" to formatDate(endDate)
        )
    }

    private fun timestampFields(
        timestamp: Long,
        zoneId: ZoneId
    ): Map<String, String> {
        val dateTime =
            Instant
                .ofEpochMilli(timestamp)
                .atZone(zoneId)

        return mapOf(
            "timestamp_ms" to timestamp.toString(),
            "fecha" to dateTime.format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy"
                )
            ),
            "hora" to dateTime.format(
                DateTimeFormatter.ofPattern(
                    "HH:mm:ss"
                )
            ),
            "fecha_hora" to dateTime.format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm:ss"
                )
            ),
            "zona_horaria" to zoneId.id
        )
    }

    private fun localDate(
        timestamp: Long,
        zoneId: ZoneId
    ): LocalDate =
        Instant
            .ofEpochMilli(timestamp)
            .atZone(zoneId)
            .toLocalDate()

    private fun weekStart(
        date: LocalDate
    ): LocalDate =
        date.minusDays(
            (
                date.dayOfWeek.value -
                    1
                ).toLong()
        )

    private fun formatDate(
        date: LocalDate
    ): String =
        date.format(
            DateTimeFormatter.ofPattern(
                "dd/MM/yyyy"
            )
        )

    private fun durationMinutes(
        durationMs: Long
    ): String =
        String.format(
            Locale.US,
            "%.2f",
            durationMs / 60_000.0
        )

    private fun decimalOrEmpty(
        value: Double?
    ): String =
        value?.let {
            decimal(it)
        } ?: ""

    private fun decimal(
        value: Double
    ): String =
        String.format(
            Locale.US,
            "%.3f",
            value
        )

    private fun appendRow(
        builder: StringBuilder,
        values: Map<String, String>
    ) {
        builder.appendLine(
            columns
                .joinToString(",") {
                    escapeCsv(
                        values[it] ?: ""
                    )
                }
        )
    }

    private fun escapeCsv(
        value: String
    ): String {
        val escaped =
            value.replace(
                "\"",
                "\"\""
            )

        val requiresQuotes =
            escaped.contains(",") ||
                escaped.contains("\"") ||
                escaped.contains("\n") ||
                escaped.contains("\r")

        return if (requiresQuotes) {
            "\"$escaped\""
        } else {
            escaped
        }
    }
}
