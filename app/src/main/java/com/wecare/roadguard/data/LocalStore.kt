package com.wecare.roadguard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "outbox", indices = [Index(value = ["operationId"], unique = true)])
data class PendingOperation(@PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val operationId: String, val ownerUid: String, val action: String, val payload: String,
    val localAudio: String? = null, val status: String = "queued", val error: String? = null,
    val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "sessions")
data class LocalTrip(@PrimaryKey val id: String, val ownerUid: String, val role: String,
    val phase: String = "Monitoring", val active: Boolean = true, val interrupted: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(), val impactOptIn: Boolean = false)

@Dao
interface LocalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(operation: PendingOperation)
    @Query("SELECT * FROM outbox WHERE ownerUid = :uid ORDER BY sequence") fun observeOutbox(uid: String): Flow<List<PendingOperation>>
    @Query("SELECT * FROM outbox WHERE ownerUid = :uid AND status = 'queued' ORDER BY CASE WHEN action IN ('createAlert', 'resolveAlert') THEN 0 WHEN action = 'endTrip' THEN 1 WHEN action = 'sendMessage' THEN 2 ELSE 3 END, sequence LIMIT 1") suspend fun next(uid: String): PendingOperation?
    @Query("UPDATE outbox SET status = :status, error = :error WHERE operationId = :id") suspend fun status(id: String, status: String, error: String? = null)
    @Query("DELETE FROM outbox WHERE operationId = :id") suspend fun remove(id: String)
    @Query("DELETE FROM outbox WHERE ownerUid = :uid") suspend fun clearOutbox(uid: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveTrip(trip: LocalTrip)
    @Query("SELECT * FROM sessions WHERE ownerUid = :uid ORDER BY startedAt DESC") fun trips(uid: String): Flow<List<LocalTrip>>
    @Query("SELECT * FROM sessions WHERE active = 1 LIMIT 1") suspend fun activeTrip(): LocalTrip?
    @Query("UPDATE sessions SET interrupted = 1 WHERE active = 1") suspend fun markInterrupted()
    @Query("DELETE FROM sessions WHERE ownerUid = :uid") suspend fun clearTrips(uid: String)
    @Query("DELETE FROM sessions WHERE startedAt < :before AND active = 0") suspend fun pruneTrips(before: Long)
    @Query("SELECT * FROM outbox WHERE createdAt < :before") suspend fun expiredOperations(before: Long): List<PendingOperation>
}

@Database(entities = [PendingOperation::class, LocalTrip::class], version = 1, exportSchema = true)
abstract class LocalStore : RoomDatabase() { abstract fun dao(): LocalDao }
