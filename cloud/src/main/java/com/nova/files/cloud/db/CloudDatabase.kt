package com.nova.files.cloud.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class AuthStatus { OK, NEEDS_RECONNECT, REVOKED }
enum class ConnectionStatus { UNKNOWN, ONLINE, OFFLINE, ERROR }

/** One connected account. Credentials are NOT stored here – only a reference into the encrypted store. */
@Entity(
    tableName = "cloud_accounts",
    indices = [Index(value = ["providerId", "providerAccountId"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey val id: String,                 // internal UUID
    val providerId: String,
    val providerAccountId: String,              // immutable provider identity, not the email
    val displayName: String,
    val email: String?,
    val avatarUrl: String?,
    val authStatus: AuthStatus,
    val connectionStatus: ConnectionStatus,
    val quotaUsed: Long?,
    val quotaTotal: Long?,
    val lastSyncAt: Long?,
    val createdAt: Long,
    val configJson: String,                     // non-secret provider settings
    val credentialRef: String,                  // key in the encrypted credential store
)

/** Cached metadata, scoped to (accountId, fileId). Deleted with its account. */
@Entity(
    tableName = "cloud_items",
    primaryKeys = ["accountId", "fileId"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "parentId"])],
)
data class CloudItemEntity(
    val accountId: String,
    val fileId: String,
    val parentId: String?,
    val name: String,
    val mimeType: String?,
    val kind: String,
    val size: Long?,
    val modified: Long?,
    val revision: String?,
    val hashesJson: String,
    val thumbnailRef: String?,
    val extraJson: String,
    val cachedAt: Long,
)

/** Pagination / change-tracking cursors per folder (or "" for account-wide change feeds). */
@Entity(
    tableName = "cloud_cursors",
    primaryKeys = ["accountId", "scope"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
)
data class CursorEntity(val accountId: String, val scope: String, val cursor: String, val updatedAt: Long)

enum class TransferKind { COPY, MOVE, WIRELESS_SEND, WIRELESS_RECEIVE }
enum class TransferState { QUEUED, PREPARING, RUNNING, PAUSED, WAITING_AUTH, WAITING_NETWORK, RETRYING, COMPLETED, FAILED, CANCELLED;
    val finished get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val runnable get() = this == QUEUED || this == WAITING_NETWORK || this == RETRYING
}

/**
 * One transfer in the shared queue (cloud, local and wireless). Endpoints are serialised
 * [com.nova.files.cloud.transfer.Endpoint]s that carry explicit account ids.
 */
@Entity(tableName = "transfers", indices = [Index("state"), Index("sourceAccountId"), Index("destAccountId")])
data class TransferEntity(
    @PrimaryKey val id: String,
    val kind: TransferKind,
    val sourceJson: String,
    val destJson: String,
    val sourceAccountId: String?,
    val destAccountId: String?,
    val name: String,
    val originalName: String,
    val expectedSize: Long?,
    val bytesDone: Long,
    val state: TransferState,
    val retryCount: Int,
    val error: String?,
    val conflictPolicy: String,
    val resumable: Boolean,
    val checkpointJson: String?,                // provider upload session / partial file
    val resultJson: String?,                    // final location, checksum…
    val groupId: String?,                       // batch/folder grouping
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM cloud_accounts ORDER BY providerId, displayName") fun observeAll(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM cloud_accounts") suspend fun all(): List<AccountEntity>
    @Query("SELECT * FROM cloud_accounts WHERE id = :id") suspend fun byId(id: String): AccountEntity?
    @Query("SELECT * FROM cloud_accounts WHERE providerId = :provider AND providerAccountId = :providerAccountId")
    suspend fun byIdentity(provider: String, providerAccountId: String): AccountEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(a: AccountEntity)
    @Update suspend fun update(a: AccountEntity)
    @Query("UPDATE cloud_accounts SET authStatus = :status WHERE id = :id") suspend fun setAuth(id: String, status: AuthStatus)
    @Query("UPDATE cloud_accounts SET quotaUsed = :used, quotaTotal = :total, lastSyncAt = :at, connectionStatus = 'ONLINE' WHERE id = :id")
    suspend fun setQuota(id: String, used: Long?, total: Long?, at: Long)
    @Query("DELETE FROM cloud_accounts WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM cloud_items WHERE accountId = :accountId AND parentId IS :parentId ORDER BY name COLLATE NOCASE")
    suspend fun children(accountId: String, parentId: String?): List<CloudItemEntity>
    @Upsert suspend fun upsert(items: List<CloudItemEntity>)
    @Query("DELETE FROM cloud_items WHERE accountId = :accountId AND parentId IS :parentId") suspend fun clearFolder(accountId: String, parentId: String?)
    @Query("DELETE FROM cloud_items WHERE accountId = :accountId AND fileId IN (:ids)") suspend fun remove(accountId: String, ids: List<String>)
    @Query("SELECT COUNT(*) FROM cloud_items WHERE accountId = :accountId") suspend fun count(accountId: String): Int
    @Upsert suspend fun putCursor(c: CursorEntity)
    @Query("SELECT * FROM cloud_cursors WHERE accountId = :accountId AND scope = :scope") suspend fun cursor(accountId: String, scope: String): CursorEntity?
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC LIMIT 300") fun observeRecent(): Flow<List<TransferEntity>>
    @Query("SELECT * FROM transfers WHERE id = :id") suspend fun byId(id: String): TransferEntity?
    @Query("SELECT * FROM transfers WHERE state IN ('QUEUED','RETRYING','WAITING_NETWORK') AND kind IN ('COPY','MOVE') ORDER BY createdAt")
    suspend fun runnable(): List<TransferEntity>
    @Query("SELECT * FROM transfers WHERE state IN ('PREPARING','RUNNING')") suspend fun inFlight(): List<TransferEntity>
    @Query("SELECT * FROM transfers WHERE (sourceAccountId = :accountId OR destAccountId = :accountId) AND state NOT IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun pendingForAccount(accountId: String): List<TransferEntity>
    @Insert suspend fun insert(t: TransferEntity)
    @Update suspend fun update(t: TransferEntity)
    @Query("UPDATE transfers SET bytesDone = :bytes, checkpointJson = :checkpoint, updatedAt = :at WHERE id = :id")
    suspend fun progress(id: String, bytes: Long, checkpoint: String?, at: Long)
    @Query("UPDATE transfers SET state = :state, error = :error, updatedAt = :at WHERE id = :id")
    suspend fun setState(id: String, state: TransferState, error: String?, at: Long)
    @Query("DELETE FROM transfers WHERE state IN ('COMPLETED','FAILED','CANCELLED')") suspend fun clearFinished()
}

@Database(entities = [AccountEntity::class, CloudItemEntity::class, CursorEntity::class, TransferEntity::class], version = 1, exportSchema = true)
abstract class CloudDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun items(): ItemDao
    abstract fun transfers(): TransferDao

    companion object {
        const val NAME = "nova_cloud.db"
        /** Future schema changes MUST add a Migration here; destructive fallback is deliberately not enabled. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        fun open(ctx: Context): CloudDatabase =
            Room.databaseBuilder(ctx.applicationContext, CloudDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
