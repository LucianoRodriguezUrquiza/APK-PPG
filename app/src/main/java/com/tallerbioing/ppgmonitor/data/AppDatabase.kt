package com.tallerbioing.ppgmonitor.data

import android.content.Context

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


// ============================================================================
// BASE DE DATOS PRINCIPAL DE LA APLICACIÓN
// ============================================================================
//
// Esta clase reúne:
//
// - Tabla de mediciones
// - Tabla de notas
//
// y expone:
//
// - MeasurementDao
// - PatientNoteDao
//
// ============================================================================

@Database(
    entities = [
        MeasurementEntity::class,
        PatientNoteEntity::class,
        SpO2MeasurementEntity::class,
        BloodPressureMeasurementEntity::class,
        PrvMeasurementEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {


    // ========================================================================
    // DAO DE MEDICIONES
    // ========================================================================

    abstract fun measurementDao():
            MeasurementDao


    // ========================================================================
    // DAO DE NOTAS
    // ========================================================================

    abstract fun patientNoteDao():
            PatientNoteDao

    abstract fun spO2MeasurementDao():
            SpO2MeasurementDao

    abstract fun bloodPressureMeasurementDao():
            BloodPressureMeasurementDao

    abstract fun prvMeasurementDao():
            PrvMeasurementDao


    companion object {

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {

                override fun migrate(
                    db: SupportSQLiteDatabase
                ) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS spo2_measurements (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            spo2Percent REAL NOT NULL
                        )
                        """.trimIndent()
                    )

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS blood_pressure_measurements (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            systolicMmHg REAL NOT NULL,
                            diastolicMmHg REAL NOT NULL,
                            windowSeq INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS prv_measurements (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            ppMeanMs REAL NOT NULL,
                            rmssdMs REAL NOT NULL,
                            sdnnMs REAL NOT NULL,
                            pnn50Percent REAL NOT NULL,
                            spanMs INTEGER NOT NULL,
                            nn INTEGER NOT NULL,
                            total INTEGER NOT NULL,
                            cleanPercent INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }


        // --------------------------------------------------------------------
        // Instancia única de la base de datos
        // --------------------------------------------------------------------

        @Volatile
        private var INSTANCE:
                AppDatabase? = null


        // --------------------------------------------------------------------
        // Obtener la base de datos
        // --------------------------------------------------------------------

        fun getDatabase(
            context: Context
        ): AppDatabase {


            return INSTANCE
                ?: synchronized(this) {


                    val instance =
                        Room.databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "ppg_monitor_database"
                        )
                            .addMigrations(
                                MIGRATION_1_2
                            )
                            .build()


                    INSTANCE =
                        instance


                    instance
                }
        }
    }
}