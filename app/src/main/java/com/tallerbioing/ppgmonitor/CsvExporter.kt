package com.tallerbioing.ppgmonitor

import android.content.Context
import android.net.Uri

import com.tallerbioing.ppgmonitor.data.AppDatabase
import com.tallerbioing.ppgmonitor.data.MeasurementEntity
import com.tallerbioing.ppgmonitor.data.PatientNoteEntity

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


// ============================================================================
// EXPORTADOR CSV
// ============================================================================
//
// Genera un único archivo CSV con:
//
// - Datos de la paciente
// - Todas las mediciones guardadas
// - Todas las notas
//
// El archivo queda en UTF-8 con BOM para mejorar compatibilidad con Excel.
//
// ============================================================================

object CsvExporter {


    // ========================================================================
    // GENERAR CONTENIDO CSV
    // ========================================================================

    suspend fun buildCsv(
        context: Context
    ): String {

        val database =
            AppDatabase.getDatabase(
                context.applicationContext
            )


        val measurements =
            database
                .measurementDao()
                .getAllMeasurements()


        val notes =
            database
                .patientNoteDao()
                .getAllNotes()


        val patient =
            PatientPreferences.load(
                context
            )


        val builder =
            StringBuilder()


        // --------------------------------------------------------------------
        // BOM UTF-8
        // --------------------------------------------------------------------

        builder.append('\uFEFF')


        // ====================================================================
        // ENCABEZADO GENERAL
        // ====================================================================

        builder.appendLine(
            listOf(
                "tipo_registro",
                "timestamp",
                "fecha_hora",
                "nombre",
                "edad",
                "patologia",
                "medicacion",
                "peso_kg",
                "altura_cm",
                "bpm",
                "actividad_codigo",
                "actividad",
                "calidad_senal",
                "bateria_porcentaje",
                "nota"
            ).joinToString(",")
        )


        // ====================================================================
        // DATOS DE LA PACIENTE
        // ====================================================================

        if (
            patient != null
        ) {

            builder.appendLine(

                listOf(

                    "PACIENTE",

                    "",

                    "",

                    patient.nombre,

                    patient.edad,

                    patient.patologia,

                    patient.medicacion,

                    patient.peso,

                    patient.altura,

                    "",

                    "",

                    "",

                    "",

                    "",

                    ""

                )
                    .joinToString(",") {
                        escapeCsv(it)
                    }
            )
        }


        // ====================================================================
        // MEDICIONES
        // ====================================================================

        measurements.forEach { measurement ->

            builder.appendLine(

                measurementToCsvRow(
                    measurement
                )
            )
        }


        // ====================================================================
        // NOTAS
        // ====================================================================

        notes.forEach { note ->

            builder.appendLine(

                noteToCsvRow(
                    note
                )
            )
        }


        return builder.toString()
    }


    // ========================================================================
    // ESCRIBIR ARCHIVO
    // ========================================================================

    fun writeCsv(
        context: Context,
        uri: Uri,
        csvContent: String
    ) {

        context
            .contentResolver
            .openOutputStream(
                uri
            )
            ?.bufferedWriter(
                Charsets.UTF_8
            )
            ?.use { writer ->

                writer.write(
                    csvContent
                )
            }
    }


    // ========================================================================
    // CONVERTIR MEDICIÓN A FILA CSV
    // ========================================================================

    private fun measurementToCsvRow(
        measurement: MeasurementEntity
    ): String {

        val dateTime =
            formatTimestamp(
                measurement.timestamp
            )


        val activityText =
            when (
                measurement.activityCode
            ) {

                0 ->
                    "Reposo"

                1 ->
                    "Movimiento leve"

                2 ->
                    "Movimiento moderado"

                else ->
                    "Desconocido"
            }


        return listOf(

            "MEDICION",

            measurement
                .timestamp
                .toString(),

            dateTime,

            "",

            "",

            "",

            "",

            "",

            "",

            measurement
                .bpm
                .toString(),

            measurement
                .activityCode
                .toString(),

            activityText,

            measurement
                .signalQuality
                .toString(),

            measurement
                .batteryPercentage
                .toString(),

            ""

        )
            .joinToString(",") {
                escapeCsv(it)
            }
    }


    // ========================================================================
    // CONVERTIR NOTA A FILA CSV
    // ========================================================================

    private fun noteToCsvRow(
        note: PatientNoteEntity
    ): String {

        val dateTime =
            formatTimestamp(
                note.timestamp
            )


        return listOf(

            "NOTA",

            note
                .timestamp
                .toString(),

            dateTime,

            "",

            "",

            "",

            "",

            "",

            "",

            "",

            "",

            "",

            "",

            "",

            note.text

        )
            .joinToString(",") {
                escapeCsv(it)
            }
    }


    // ========================================================================
    // FORMATEAR TIMESTAMP
    // ========================================================================

    private fun formatTimestamp(
        timestamp: Long
    ): String {

        return Instant
            .ofEpochMilli(
                timestamp
            )
            .atZone(
                ZoneId.systemDefault()
            )
            .format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm:ss"
                )
            )
    }


    // ========================================================================
    // ESCAPAR CAMPO CSV
    // ========================================================================
    //
    // Si el texto contiene coma, comillas o salto de línea:
    //
    // ejemplo:
    //
    // hoy me sentí cansada, pero bien
    //
    // se guarda como:
    //
    // "hoy me sentí cansada, pero bien"
    //
    // ========================================================================

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


        return if (
            requiresQuotes
        ) {

            "\"$escaped\""

        } else {

            escaped
        }
    }
}