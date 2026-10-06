package com.tallerbioing.ppgmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpO2MeasurementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: SpO2MeasurementEntity)

    @Query(
        """
        SELECT *
        FROM spo2_measurements
        ORDER BY timestamp ASC
        """
    )
    suspend fun getAll(): List<SpO2MeasurementEntity>

    @Query(
        """
        SELECT *
        FROM spo2_measurements
        WHERE timestamp >= :startTimestamp
          AND timestamp <= :endTimestamp
        ORDER BY timestamp ASC
        """
    )
    suspend fun getBetween(
        startTimestamp: Long,
        endTimestamp: Long
    ): List<SpO2MeasurementEntity>

    @Query("DELETE FROM spo2_measurements")
    suspend fun deleteAll()
}

@Dao
interface BloodPressureMeasurementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: BloodPressureMeasurementEntity)

    @Query(
        """
        SELECT *
        FROM blood_pressure_measurements
        ORDER BY timestamp ASC
        """
    )
    suspend fun getAll(): List<BloodPressureMeasurementEntity>

    @Query(
        """
        SELECT *
        FROM blood_pressure_measurements
        WHERE timestamp >= :startTimestamp
          AND timestamp <= :endTimestamp
        ORDER BY timestamp ASC
        """
    )
    suspend fun getBetween(
        startTimestamp: Long,
        endTimestamp: Long
    ): List<BloodPressureMeasurementEntity>

    @Query("DELETE FROM blood_pressure_measurements")
    suspend fun deleteAll()
}

@Dao
interface PrvMeasurementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: PrvMeasurementEntity)

    @Query(
        """
        SELECT *
        FROM prv_measurements
        ORDER BY timestamp ASC
        """
    )
    suspend fun getAll(): List<PrvMeasurementEntity>

    @Query(
        """
        SELECT *
        FROM prv_measurements
        WHERE timestamp >= :startTimestamp
          AND timestamp <= :endTimestamp
        ORDER BY timestamp ASC
        """
    )
    suspend fun getBetween(
        startTimestamp: Long,
        endTimestamp: Long
    ): List<PrvMeasurementEntity>

    @Query("DELETE FROM prv_measurements")
    suspend fun deleteAll()
}
