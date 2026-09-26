package com.tallerbioing.ppgmonitor.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


// ============================================================================
// DAO DE NOTAS DE LA PACIENTE
// ============================================================================
//
// Este DAO permite:
//
// - Guardar notas
// - Leer todas las notas
// - Leer notas entre dos fechas
// - Borrar una nota
// - Borrar todas las notas
//
// ============================================================================

@Dao
interface PatientNoteDao {


    // ========================================================================
    // INSERTAR UNA NOTA
    // ========================================================================

    @Insert(
        onConflict = OnConflictStrategy.REPLACE
    )
    suspend fun insertNote(
        note: PatientNoteEntity
    )


    // ========================================================================
    // OBTENER TODAS LAS NOTAS
    // ========================================================================

    @Query(
        """
        SELECT *
        FROM patient_notes
        ORDER BY timestamp DESC
        """
    )
    suspend fun getAllNotes():
            List<PatientNoteEntity>


    // ========================================================================
    // OBTENER NOTAS ENTRE DOS FECHAS
    // ========================================================================
    //
    // Útil para:
    //
    // - consultar las notas de un día
    // - consultar una semana
    // - filtrar por rango de fechas
    //
    // ========================================================================

    @Query(
        """
        SELECT *
        FROM patient_notes
        WHERE timestamp >= :startTimestamp
          AND timestamp <= :endTimestamp
        ORDER BY timestamp DESC
        """
    )
    suspend fun getNotesBetween(
        startTimestamp: Long,
        endTimestamp: Long
    ): List<PatientNoteEntity>


    // ========================================================================
    // BORRAR UNA NOTA
    // ========================================================================

    @Delete
    suspend fun deleteNote(
        note: PatientNoteEntity
    )


    // ========================================================================
    // BORRAR TODAS LAS NOTAS
    // ========================================================================

    @Query(
        """
        DELETE FROM patient_notes
        """
    )
    suspend fun deleteAllNotes()


    // ========================================================================
    // CONTAR NOTAS
    // ========================================================================

    @Query(
        """
        SELECT COUNT(*)
        FROM patient_notes
        """
    )
    suspend fun getNoteCount():
            Int
}