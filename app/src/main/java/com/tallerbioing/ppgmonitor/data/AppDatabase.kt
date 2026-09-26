package com.tallerbioing.ppgmonitor.data

import android.content.Context

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase


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
        PatientNoteEntity::class
    ],
    version = 1,
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


    companion object {


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
                            .build()


                    INSTANCE =
                        instance


                    instance
                }
        }
    }
}