package com.nova.files.cloud.transfer

import com.nova.files.cloud.core.CloudException
import com.nova.files.cloud.core.ConflictPolicy
import com.nova.files.cloud.core.HashAlgorithm
import com.nova.files.cloud.core.ProviderId
import com.nova.files.cloud.db.TransferEntity
import com.nova.files.cloud.db.TransferState
import com.nova.files.cloud.net.ProviderLimiter
import com.nova.files.cloud.net.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Live (in-memory) progress for the UI; the database holds the throttled, durable copy. */
data class LiveProgress(val id: String, val bytes: Long, val total: Long?, val bytesPerSecond: Long)

/**
 * Executes queued COPY/MOVE jobs between any two endpoints via [EndpointResolver] adapters.
 * Bounded buffer, bounded parallelism, per-provider limits, durable checkpoints, never overwrites
 * unless the job says REPLACE, and verifies size + checksum.
 */
class TransferEngine(
    private val queue: TransferQueue,
    private val resolver: EndpointResolver,
    private val limiter: ProviderLimiter = ProviderLimiter(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val bufferBytes: Int = 1 shl 20,          // configurable; upper target 8–16 MB for cloud streams
    maxParallel: Int = 2,
    private val onWaitingForNetwork: suspend () -> Unit = {},
) {
    private val parallel = Semaphore(maxParallel)
    private val drainLock = Mutex()
    private val controls = HashMap<String, Control>()
    private val _live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<String, LiveProgress>> = _live
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private class Control { @Volatile var pause = false; @Volatile var cancel = false }
    private class Stopped(val pause: Boolean) : Exception()

    fun pause(id: String) { synchronized(controls) { controls[id]?.pause = true } }
    fun cancel(id: String) { synchronized(controls) { controls[id]?.cancel = true } }

    /** Runs until no runnable job is left. Safe to call repeatedly; only one drain runs at a time. */
    suspend fun drain() {
        if (!drainLock.tryLock()) return
        _running.value = true
        try {
            while (true) {
                val jobs = queue.runnable().filter { synchronized(controls) { it.id !in controls } }
                if (jobs.isEmpty()) break
                coroutineScope { for (j in jobs) launch(Dispatchers.IO) { parallel.withPermit { runOne(j) } } }
            }
        } finally { _running.value = false; drainLock.unlock() }
    }

    private suspend fun runOne(job: TransferEntity) {
        val control = Control()
        synchronized(controls) { controls[job.id] = control }
        try {
            val src = Endpoint.decode(job.sourceJson); val dst = Endpoint.decode(job.destJson)
            val provider = (src as? Endpoint.Cloud ?: dst as? Endpoint.Cloud)?.provider?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
            val body: suspend () -> Unit = { execute(job, src, dst, control) }
            if (provider != null) limiter.of(provider).withPermit { body() } else body()
        } catch (e: CancellationException) {
            queue.setState(job.id, TransferState.QUEUED, "Paused by the system – will resume")
            throw e
        } finally {
            synchronized(controls) { controls.remove(job.id) }
            _live.update { it - job.id }
        }
    }

    private suspend fun execute(job: TransferEntity, srcE: Endpoint, dstE: Endpoint, control: Control) {
        try {
            retry.run(idempotent = true) { attempt ->
                if (attempt > 1) queue.setState(job.id, TransferState.RETRYING, "Retrying ($attempt)")
                val current = queue.get(job.id) ?: return@run
                try {
                    copyOnce(current, srcE, dstE, control)
                } catch (e: Stopped) {
                    queue.setState(job.id, if (e.pause) TransferState.PAUSED else TransferState.CANCELLED, if (e.pause) "Paused" else "Cancelled")
                }
            }
        } catch (e: CloudException) {
            // Retries exhausted: the final state (waiting for network / failed) is already recorded.
        }
    }

    private suspend fun copyOnce(job: TransferEntity, srcE: Endpoint, dstE: Endpoint, control: Control) = withContext(Dispatchers.IO) {
        try {
            queue.setState(job.id, TransferState.PREPARING, null)
            val source = resolver.source(srcE)
            val dest = resolver.destination(dstE)
            val policy = runCatching { ConflictPolicy.valueOf(job.conflictPolicy) }.getOrDefault(ConflictPolicy.KEEP_BOTH)
            var writer = dest.prepare(job.name, source.size, policy, job.checkpointJson)
                ?: run {
                    queue.update(job.copy(state = TransferState.COMPLETED, error = null, resultJson = EndpointJson.encodeToString(CommittedResult.serializer(), CommittedResult("", job.name, skipped = true))))
                    return@withContext
                }
            if (writer.startOffset > 0 && !source.supportsOffset) { writer.abort(false); writer = dest.prepare(job.name, source.size, policy, null)!! }
            val start = writer.startOffset
            queue.setState(job.id, TransferState.RUNNING, if (start > 0) "Resumed at ${start / 1048576} MB" else null)
            queue.progress(job.id, start, writer.checkpoint(), force = true)

            val md = if (start == 0L) MessageDigest.getInstance("SHA-256") else null
            var done = start
            var windowStart = System.nanoTime(); var windowBytes = 0L; var rate = 0L
            try {
                source.open(start).use { input ->
                    val buf = ByteArray(bufferBytes)
                    while (true) {
                        if (control.cancel) throw Stopped(false)
                        if (control.pause) throw Stopped(true)
                        val r = input.read(buf)
                        if (r < 0) break
                        writer.stream.write(buf, 0, r)
                        md?.update(buf, 0, r)
                        done += r; windowBytes += r
                        val now = System.nanoTime()
                        if (now - windowStart > 1_000_000_000L) { rate = windowBytes * 1_000_000_000L / (now - windowStart); windowStart = now; windowBytes = 0 }
                        _live.update { it + (job.id to LiveProgress(job.id, done, source.size, rate)) }
                        if (queue.progress(job.id, done, writer.checkpoint())) writer.stream.flush()
                    }
                }
            } catch (e: Stopped) {
                writer.stream.flush()
                writer.abort(keepPartial = e.pause && dest.supportsResume)
                queue.progress(job.id, done, if (e.pause) writer.checkpoint() else null, force = true)
                throw e
            } catch (e: Exception) {
                // Keep partial data so a retry/resume continues; the checkpoint is already persisted.
                runCatching { writer.stream.flush() }
                writer.abort(keepPartial = dest.supportsResume)
                queue.progress(job.id, done, writer.checkpoint(), force = true)
                throw e
            }

            source.size?.let { expected ->
                if (done != expected) { writer.abort(false); throw CloudException.Integrity("Incomplete transfer: got $done of $expected bytes") }
            }
            val result = writer.commit()
            val sha = md?.let { hex(it.digest()) } ?: result.location.takeIf { File(it).isFile }?.let { sha256File(File(it)) }
            source.knownHashes[HashAlgorithm.SHA256]?.let { known ->
                if (sha != null && !known.equals(sha, true)) throw CloudException.Integrity("Checksum mismatch – the copy is corrupt")
            }
            val latest = queue.get(job.id) ?: job
            queue.update(latest.copy(state = TransferState.COMPLETED, bytesDone = done, error = null, checkpointJson = null,
                name = result.name, resultJson = EndpointJson.encodeToString(CommittedResult.serializer(), result.copy(sha256 = sha))))
        } catch (e: Stopped) { throw e }
        catch (e: CloudException) {
            val (state, retryable) = when (e) {
                is CloudException.AuthRequired, is CloudException.CredentialsUnavailable -> TransferState.WAITING_AUTH to false
                is CloudException.Network -> TransferState.WAITING_NETWORK to true
                else -> TransferState.FAILED to e.retryable
            }
            queue.setState(job.id, state, e.userMessage)
            if (state == TransferState.WAITING_NETWORK) onWaitingForNetwork()
            if (retryable) throw e
        } catch (e: java.io.IOException) {
            queue.setState(job.id, TransferState.WAITING_NETWORK, e.message ?: "Connection lost")
            onWaitingForNetwork()
            throw CloudException.Network(e)
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256File(f: File): String = f.inputStream().use { i ->
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 20)
        while (true) { val r = i.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        hex(md.digest())
    }
}
