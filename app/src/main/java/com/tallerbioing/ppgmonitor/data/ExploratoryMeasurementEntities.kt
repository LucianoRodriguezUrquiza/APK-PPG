package com.tallerbioing.ppgmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spo2_measurements")
data class SpO2MeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val spo2Percent: Float
)

@Entity(tableName = "blood_pressure_measurements")
data class BloodPressureMeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val systolicMmHg: Float,
    val diastolicMmHg: Float,
    val windowSeq: Long
)

@Entity(tableName = "prv_measurements")
data class PrvMeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val ppMeanMs: Float,
    val rmssdMs: Float,
    val sdnnMs: Float,
    val pnn50Percent: Float,
    val spanMs: Long,
    val nn: Long,
    val total: Long,
    val cleanPercent: Int
)
