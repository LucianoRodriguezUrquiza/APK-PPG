package com.tallerbioing.ppgmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


// ============================================================================
// DAO DE MEDICIONES
// ============================================================================
//
// DAO = Data Access Object
//
// Este archivo define las operaciones que Room puede hacer sobre la tabla:
//
// measurements
//
// ============================================================================

@Dao
interface MeasurementDao {


    // ========================================================================
    // INSERTAR UNA MEDICIÓN
    // ========================================================================

    @Insert(
        onConflict = OnConflictStrategy.REPLACE
    )
    suspend fun insertMeasurement(
        measurement: MeasurementEntity
    )


    // ========================================================================
    // OBTENER TODAS LAS MEDICIONES
    // ========================================================================

    @Query(
        """
        SELECT *
        FROM measurements
        ORDER BY timestamp ASC
        """
    )
    suspend fun getAllMeasurements():
            List<MeasurementEntity>


    // ========================================================================
    // OBTENER MEDICIONES ENTRE DOS FECHAS/HORAS
    // ========================================================================
    //
    // Ejemplo:
    //
    // startTimestamp = comienzo del día
    // endTimestamp   = final del día
    //
    // Esto será lo que use "Registro diario".
    //
    // ========================================================================

    @Query(
        """
        SELECT *
        FROM measurements
        WHERE timestamp >= :startTimestamp
          AND timestamp <= :endTimestamp
        ORDER BY timestamp ASC
        """
    )
    suspend fun getMeasurementsBetween(
        startTimestamp: Long,
        endTimestamp: Long
    ): List<MeasurementEntity>


    // ========================================================================
    // CONTAR MEDICIONES
    // ========================================================================

    @Query(
        """
        SELECT COUNT(*)
        FROM measurements
        """
    )
    suspend fun getMeasurementCount():
            Int


    // ========================================================================
    // BORRAR TODAS LAS MEDICIONES
    // ========================================================================
    //
    // No vamos a usarlo en la interfaz todavía.
    // Queda disponible para mantenimiento / pruebas.
    //
    // ========================================================================

    @Query(
        """
        DELETE FROM measurements
        """
    )
    suspend fun deleteAllMeasurements()
}