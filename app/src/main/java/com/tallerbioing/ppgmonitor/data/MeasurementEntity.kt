package com.tallerbioing.ppgmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey


// ============================================================================
// MEDICIÓN GUARDADA EN LA BASE DE DATOS
// ============================================================================
//
// Cada objeto MeasurementEntity representa una medición almacenada
// localmente en el teléfono.
//
// Por ahora guardamos:
//
// - Fecha y hora
// - BPM
// - Actividad
// - Calidad de señal
// - Porcentaje de batería
//
// Más adelante podremos ampliar esta tabla para incluir:
// - SpO2
// - contacto
// - voltaje de batería
// - otros parámetros
//
// ============================================================================

@Entity(
    tableName = "measurements"
)
data class MeasurementEntity(

    // ------------------------------------------------------------------------
    // Identificador único generado automáticamente por Room
    // ------------------------------------------------------------------------

    @PrimaryKey(
        autoGenerate = true
    )
    val id: Long = 0,


    // ------------------------------------------------------------------------
    // Momento de la medición
    //
    // Se almacena como milisegundos desde Unix Epoch.
    //
    // Ejemplo:
    // System.currentTimeMillis()
    // ------------------------------------------------------------------------

    val timestamp: Long,


    // ------------------------------------------------------------------------
    // Frecuencia cardíaca
    // ------------------------------------------------------------------------

    val bpm: Int,


    // ------------------------------------------------------------------------
    // Actividad
    //
    // 0 = Reposo
    // 1 = Movimiento leve
    // 2 = Movimiento moderado
    // 3 = Error / inválido
    // ------------------------------------------------------------------------

    val activityCode: Int,


    // ------------------------------------------------------------------------
    // Calidad de señal
    //
    // 0 = Sin señal
    // 1 = Muy baja
    // 2 = Baja
    // 3 = Buena
    // 4 = Excelente
    // ------------------------------------------------------------------------

    val signalQuality: Int,


    // ------------------------------------------------------------------------
    // Porcentaje de batería
    // ------------------------------------------------------------------------

    val batteryPercentage: Int
)