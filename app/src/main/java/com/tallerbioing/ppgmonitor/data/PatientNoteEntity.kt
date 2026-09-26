package com.tallerbioing.ppgmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey


// ============================================================================
// ENTIDAD: NOTAS DE LA PACIENTE
// ============================================================================
//
// Cada objeto PatientNoteEntity representa una nota guardada
// permanentemente en la base de datos local.
//
// Ejemplo:
//
// 07/09/2026 - 18:30
// "Sentí cansancio después de caminar."
//
// ============================================================================

@Entity(
    tableName = "patient_notes"
)
data class PatientNoteEntity(

    // ------------------------------------------------------------------------
    // Identificador único de la nota
    // Room lo genera automáticamente.
    // ------------------------------------------------------------------------

    @PrimaryKey(
        autoGenerate = true
    )
    val id: Long = 0,


    // ------------------------------------------------------------------------
    // Fecha y hora de creación de la nota.
    //
    // Se guarda como timestamp en milisegundos.
    // Ejemplo:
    // System.currentTimeMillis()
    // ------------------------------------------------------------------------

    val timestamp: Long,


    // ------------------------------------------------------------------------
    // Texto escrito por la paciente
    // ------------------------------------------------------------------------

    val text: String
)