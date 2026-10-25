package com.tallerbioing.ppgmonitor.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepPersistenceTest {
    @Test fun concurrentStartsReopenAndVersionTwoMigrationPreserveHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sleep-persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
        var db = open()
        try {
            db.measurementDao().insertMeasurement(MeasurementEntity(timestamp = 100, bpm = 70,
                activityCode = 0, signalQuality = 4, batteryPercentage = 80))
            // Simulate a v2 database with all existing tables/rows and no sleep table.
            db.openHelper.writableDatabase.execSQL("DROP TABLE sleep_periods")
            db.openHelper.writableDatabase.version = 2
            db.close()
            db = open() // real 2 -> 3 migration and Room schema validation
            assertEquals(1, db.measurementDao().getAllMeasurements().size)
            val dao = db.sleepPeriodDao()
            val ids = coroutineScope { (1..12).map { async(Dispatchers.IO) { dao.start(1000) } }.awaitAll() }
            assertEquals(1, ids.toSet().size)
            assertEquals(1, dao.getAll().size)
            db.close()
            db = open() // persisted active state, independent of BLE/UI process memory
            assertEquals(1000L, db.sleepPeriodDao().active()!!.startMs)
            db.sleepPeriodDao().end(28_801_000)
            db.sleepPeriodDao().end(28_801_001) // idempotent finish
            assertNull(db.sleepPeriodDao().active())
            assertEquals(28_800_000L, db.sleepPeriodDao().getAll().single().durationMs())
            assertEquals(1, db.measurementDao().getAllMeasurements().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
