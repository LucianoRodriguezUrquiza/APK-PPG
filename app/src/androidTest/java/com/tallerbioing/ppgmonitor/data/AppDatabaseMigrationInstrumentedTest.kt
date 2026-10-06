package com.tallerbioing.ppgmonitor.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentedTest {

    companion object {
        private const val TEST_DB =
            "ppg_monitor_migration_test.db"
    }

    private val context
        get() =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migration1To2PreservesOldMeasurementsAndCreatesExploratoryTables() {
        val path =
            context.getDatabasePath(TEST_DB)

        path.parentFile?.mkdirs()

        SQLiteDatabase
            .openOrCreateDatabase(
                path,
                null
            )
            .use {
                    db ->

                db.execSQL(
                    """
                    CREATE TABLE measurements (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        bpm INTEGER NOT NULL,
                        activityCode INTEGER NOT NULL,
                        signalQuality INTEGER NOT NULL,
                        batteryPercentage INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE patient_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        text TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO measurements
                    (timestamp, bpm, activityCode, signalQuality, batteryPercentage)
                    VALUES (1000, 72, 0, 4, 80)
                    """.trimIndent()
                )

                db.version = 1
            }

        val database =
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                TEST_DB
            )
                .addMigrations(
                    AppDatabase.MIGRATION_1_2
                )
                .build()

        try {
            runBlocking {
                assertEquals(
                    1,
                    database
                        .measurementDao()
                        .getMeasurementCount()
                )

                database
                    .spO2MeasurementDao()
                    .insert(
                        SpO2MeasurementEntity(
                            timestamp = 2_000L,
                            spo2Percent = 98f
                        )
                    )

                database
                    .bloodPressureMeasurementDao()
                    .insert(
                        BloodPressureMeasurementEntity(
                            timestamp = 2_000L,
                            systolicMmHg = 112f,
                            diastolicMmHg = 66f,
                            windowSeq = 1L
                        )
                    )

                database
                    .prvMeasurementDao()
                    .insert(
                        PrvMeasurementEntity(
                            timestamp = 2_000L,
                            ppMeanMs = 900f,
                            rmssdMs = 50f,
                            sdnnMs = 55f,
                            pnn50Percent = 20f,
                            spanMs = 30_000L,
                            nn = 30L,
                            total = 32L,
                            cleanPercent = 94
                        )
                    )

                assertTrue(
                    database
                        .spO2MeasurementDao()
                        .getBetween(
                            0L,
                            3_000L
                        )
                        .isNotEmpty()
                )

                assertTrue(
                    database
                        .bloodPressureMeasurementDao()
                        .getBetween(
                            0L,
                            3_000L
                        )
                        .isNotEmpty()
                )

                assertTrue(
                    database
                        .prvMeasurementDao()
                        .getBetween(
                            0L,
                            3_000L
                        )
                        .isNotEmpty()
                )
            }
        } finally {
            database.close()
        }
    }
}
