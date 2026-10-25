package com.tallerbioing.ppgmonitor.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sleep_periods")
data class SleepPeriod(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null
) {
    fun contains(timestamp: Long): Boolean = timestamp >= startMs &&
        (endMs == null || timestamp < endMs)
    fun durationMs(): Long? = endMs?.minus(startMs)
}

@Dao
abstract class SleepPeriodDao {
    @Query("SELECT * FROM sleep_periods ORDER BY startMs DESC, id DESC")
    abstract fun observeAll(): Flow<List<SleepPeriod>>

    @Query("SELECT * FROM sleep_periods ORDER BY startMs, id")
    abstract suspend fun getAll(): List<SleepPeriod>

    @Query("SELECT * FROM sleep_periods WHERE endMs IS NULL LIMIT 1")
    abstract suspend fun active(): SleepPeriod?

    @Query("SELECT MAX(endMs) FROM sleep_periods")
    abstract suspend fun lastEnd(): Long?

    @Insert
    protected abstract suspend fun insert(period: SleepPeriod): Long

    @Query("UPDATE sleep_periods SET endMs = :now WHERE id = :id AND endMs IS NULL")
    protected abstract suspend fun finish(id: Long, now: Long)

    // Room serializes transactions: repeated/concurrent taps cannot create duplicates.
    @Transaction
    open suspend fun start(now: Long): Long {
        active()?.let { return it.id }
        require(now >= (lastEnd() ?: Long.MIN_VALUE)) {
            "El reloj del teléfono retrocedió. Corregí la fecha antes de iniciar."
        }
        return insert(SleepPeriod(startMs = now))
    }

    @Transaction
    open suspend fun end(now: Long) {
        val period = active() ?: return
        require(now >= period.startMs) {
            "El reloj del teléfono retrocedió. Corregí la fecha antes de finalizar."
        }
        finish(period.id, now)
    }

    @Query("DELETE FROM sleep_periods")
    abstract suspend fun deleteAll()
}
