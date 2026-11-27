package com.nova.files.cloud.transfer

import com.nova.files.cloud.core.ConflictPolicy
import com.nova.files.cloud.db.CloudDatabase
import com.nova.files.cloud.db.TransferEntity
import com.nova.files.cloud.db.TransferKind
import com.nova.files.cloud.db.TransferState
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The single persistent transfer queue shared by cloud, local and wireless transfers.
 * Progress is written at bounded intervals, not for every network buffer.
 */
class TransferQueue(private val db: CloudDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.transfers()
    private val lastWrite = HashMap<String, Pair<Long, Long>>()   // id -> (time, bytes)

    fun observe(): Flow<List<TransferEntity>> = dao.observeRecent()
    suspend fun get(id: String) = dao.byId(id)
    suspend fun runnable() = dao.runnable()

    suspend fun enqueue(
        kind: TransferKind, source: Endpoint, dest: Endpoint, name: String, expectedSize: Long?,
        conflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH, groupId: String? = null, id: String = UUID.randomUUID().toString(),
        state: TransferState = TransferState.QUEUED, resumable: Boolean = true,
    ): TransferEntity {
        val t = now()
        val e = TransferEntity(id, kind, source.encode(), dest.encode(), source.cloudAccountId, dest.cloudAccountId, name, name, expectedSize, 0,
            state, 0, null, conflict.name, resumable, null, null, groupId, t, t)
        dao.insert(e)
        return e
    }

    /** Throttled progress: at most every [intervalMs] or every [byteStep] bytes, plus forced writes. */
    suspend fun progress(id: String, bytes: Long, checkpoint: String?, force: Boolean = false, intervalMs: Long = 1500, byteStep: Long = 8L shl 20): Boolean {
        val t = now()
        val last = synchronized(lastWrite) { lastWrite[id] }
        if (!force && last != null && t - last.first < intervalMs && bytes - last.second < byteStep) return false
        dao.progress(id, bytes, checkpoint, t)
        synchronized(lastWrite) { lastWrite[id] = t to bytes }
        return true
    }

    suspend fun setState(id: String, state: TransferState, error: String? = null) {
        dao.setState(id, state, error, now())
        if (state.finished) synchronized(lastWrite) { lastWrite.remove(id) }
    }

    suspend fun update(e: TransferEntity) = dao.update(e.copy(updatedAt = now()))

    suspend fun clearFinished() = dao.clearFinished()

    /**
     * After process death or reboot nothing is actually running. Resumable copy jobs go back to the
     * queue; non-resumable or wireless ones are marked failed with a clear reason (they can be retried).
     */
    suspend fun recoverAfterRestart(): Int {
        var n = 0
        for (t in dao.inFlight()) {
            n++
            when {
                t.kind == TransferKind.WIRELESS_SEND || t.kind == TransferKind.WIRELESS_RECEIVE ->
                    setState(t.id, TransferState.FAILED, "Interrupted when the app stopped – use Retry")
                t.resumable -> setState(t.id, TransferState.QUEUED, "Resuming after interruption")
                else -> setState(t.id, TransferState.FAILED, "Interrupted – this transfer can't resume and must restart")
            }
        }
        return n
    }
}
