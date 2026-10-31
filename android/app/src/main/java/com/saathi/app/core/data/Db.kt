package com.saathi.app.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A captured SMS/notification. Only the redacted text is ever stored. */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePkg: String,
    val sender: String,
    val redactedText: String,
    val category: String = "other",
    val amount: Double? = null,
    val dueDate: String? = null,
    val merchant: String? = null,
    val summary: String? = null,
    val receivedAt: Long,
    /** none | info | amber | red */
    val risk: String = "none",
    val fromContact: Boolean = false,
    val isOtp: Boolean = false,
    val classified: Boolean = false,
)

/** Scam/safety events kept for cross-signal scoring and the Alerts screen. */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** message | call | link | welfare */
    val type: String,
    /** info | amber | red */
    val level: String,
    val reasons: String,
    val sourceMsgId: Long? = null,
    val detail: String? = null,
    val ts: Long,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** bill | appointment | renewal */
    val kind: String,
    val title: String,
    val body: String,
    val triggerAt: Long,
    val sourceId: Long? = null,
    val fired: Boolean = false,
)

@Entity(tableName = "action_log")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val goal: String,
    val actionJson: String,
    val reason: String?,
    val pkg: String,
    /** Plain-language line for the family-facing activity log. */
    val plain: String,
    /** ok | blocked | cancelled | failed | done */
    val outcome: String,
)

@Dao
interface MessageDao {
    @Insert suspend fun insert(m: MessageEntity): Long
    @Update suspend fun update(m: MessageEntity)
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun byId(id: Long): MessageEntity?
    @Query("SELECT * FROM messages ORDER BY receivedAt DESC LIMIT 200") fun recent(): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE receivedAt >= :since ORDER BY receivedAt DESC") suspend fun since(since: Long): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE classified = 0 AND fromContact = 0 AND isOtp = 0") suspend fun unclassified(): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE sender = :sender AND redactedText = :text AND receivedAt >= :since")
    suspend fun countDuplicates(sender: String, text: String, since: Long): Int
}

@Dao
interface EventDao {
    @Insert suspend fun insert(e: EventEntity): Long
    @Query("SELECT * FROM events ORDER BY ts DESC LIMIT 200") fun recent(): Flow<List<EventEntity>>
    @Query("SELECT * FROM events WHERE ts >= :since") suspend fun since(since: Long): List<EventEntity>
}

@Dao
interface ReminderDao {
    @Insert suspend fun insert(r: ReminderEntity): Long
    @Update suspend fun update(r: ReminderEntity)
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun byId(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY triggerAt") fun upcoming(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE fired = 0") suspend fun pending(): List<ReminderEntity>
}

@Dao
interface ActionLogDao {
    @Insert suspend fun insert(a: ActionLogEntity): Long
    @Query("SELECT * FROM action_log ORDER BY ts DESC LIMIT 300") fun recent(): Flow<List<ActionLogEntity>>
}

@Database(
    entities = [MessageEntity::class, EventEntity::class, ReminderEntity::class, ActionLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SaathiDb : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun events(): EventDao
    abstract fun reminders(): ReminderDao
    abstract fun actionLog(): ActionLogDao

    companion object {
        fun create(context: Context): SaathiDb =
            Room.databaseBuilder(context, SaathiDb::class.java, "saathi.db").fallbackToDestructiveMigration().build()
    }
}
