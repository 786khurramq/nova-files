package com.nova.files.transfer.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class TransferProgress(
    val transferId: String,
    val currentFile: String,
    val fileIndex: Int,          // 0-based index among real files
    val fileCount: Int,
    val fileBytes: Long,
    val fileSize: Long,
    val bytesDone: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val paused: Boolean,
    val pausedByPeer: Boolean,
)

data class FileOutcome(val path: String, val size: Long, val ok: Boolean, val savedAs: String = "", val error: String = "")

data class TransferResult(
    val transferId: String,
    val success: Boolean,
    val files: List<FileOutcome>,
    val bytes: Long,
    val error: String? = null,
    val cancelled: Boolean = false,
)

interface TransferListener {
    fun onProgress(p: TransferProgress) {}
}

/** Pause / resume / cancel from either device. */
class TransferControl {
    @Volatile var pausedLocal = false; private set
    @Volatile var pausedRemote = false; internal set
    @Volatile var cancelled = false; internal set
    @Volatile var cancelledByPeer = false; internal set
    @Volatile internal var channel: SecureChannel? = null

    val paused get() = pausedLocal || pausedRemote

    fun pause() { if (!pausedLocal) { pausedLocal = true; trySend(Ctrl.PAUSE) } }
    fun resume() { if (pausedLocal) { pausedLocal = false; trySend(Ctrl.RESUME) } }
    fun cancel() {
        if (cancelled) return
        cancelled = true
        trySend(Ctrl.CANCEL)
        Thread { Thread.sleep(300); channel?.close() }.start()
    }

    private fun trySend(a: String) { Thread { runCatching { channel?.send(Msg.Control(a)) } }.start() }

    internal fun onRemote(a: String) {
        when (a) {
            Ctrl.PAUSE -> pausedRemote = true
            Ctrl.RESUME -> pausedRemote = false
            Ctrl.CANCEL -> { cancelledByPeer = true; cancelled = true }
        }
    }

    internal suspend fun awaitNotPaused() {
        while (paused && !cancelled) delay(150)
        checkCancelled()
    }

    internal fun checkCancelled() { if (cancelled) throw TransferCancelledException(cancelledByPeer) }
}

private class RateMeter {
    private val start = System.nanoTime()
    private var lastEmit = 0L
    private var windowStart = System.nanoTime(); private var windowBytes = 0L; var rate = 0L
    fun add(n: Long) {
        windowBytes += n
        val now = System.nanoTime()
        val dt = now - windowStart
        if (dt > 1_000_000_000L) {
            val inst = windowBytes * 1_000_000_000L / dt
            rate = if (rate == 0L) inst else (rate * 6 + inst * 4) / 10
            windowStart = now; windowBytes = 0
        }
    }
    fun shouldEmit(): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (now - lastEmit >= 200) { lastEmit = now; return true }
        return false
    }
}

object TransferEngine {

    /** Starts heartbeats + watchdog so dead links are detected within ~45 s, including while paused. */
    private fun startHeartbeat(ch: SecureChannel): Thread = Thread {
        try {
            while (!ch.socket.isClosed) {
                Thread.sleep(8000)
                runCatching { ch.send(Msg.Ping) }
                if (System.currentTimeMillis() - ch.lastReceivedAt > 45_000) { ch.close(); break }
            }
        } catch (_: InterruptedException) {}
    }.apply { isDaemon = true; name = "nova-heartbeat"; start() }

    /**
     * Mutual trust step. Already-trusted pairs pass silently; otherwise both users must confirm the same
     * 6-digit code. Returns true if a new pairing was established.
     */
    suspend fun negotiateTrust(
        ch: SecureChannel,
        purpose: String,
        trustsPeer: Boolean,
        confirm: suspend (peer: PeerInfo, code: String) -> Boolean,
    ): Boolean {
        ch.send(Msg.Trust(trustsPeer, purpose))
        val peerTrust = ch.receiveMsg() as? Msg.Trust ?: throw ProtocolException("Unexpected reply during pairing")
        if (trustsPeer && peerTrust.trustsPeer) return false
        val mine = if (trustsPeer) true else try {
            withTimeout(180_000) { confirm(ch.peer, ch.verificationCode) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { false }
        ch.send(Msg.PairDecision(mine))
        val theirs = ch.receiveMsg() as? Msg.PairDecision ?: throw ProtocolException("Unexpected reply during pairing")
        if (!mine) throw ProtocolException("Pairing declined on this device")
        if (!theirs.accept) throw ProtocolException("Pairing declined on ${ch.peer.name}")
        return true
    }

    fun resumeKey(senderId: String, item: OfferItem): String =
        Crypto.hex(Crypto.sha256("$senderId|${item.path}|${item.size}|${item.mtime}".toByteArray())).take(32)

    // ------------------------------------------------------------------ SENDER
    suspend fun send(
        ch: SecureChannel,
        transferId: String,
        items: List<SendItem>,
        control: TransferControl,
        listener: TransferListener,
    ): TransferResult = coroutineScope {
        control.channel = ch
        val offerItems = items.map { OfferItem(it.relPath, it.size, it.mtime, it.isDir) }
        offerItems.forEach { PathSafety.sanitize(it.path) }
        val totalBytes = items.filter { !it.isDir }.sumOf { it.size }
        ch.send(Msg.Offer(transferId, offerItems, totalBytes))

        val reply = try { ch.receiveMsg() } catch (e: Exception) {
            if (control.cancelled) throw TransferCancelledException(false) else throw e
        }
        val offsets = when (reply) {
            is Msg.Accept -> reply.offsets
            is Msg.Reject -> return@coroutineScope TransferResult(transferId, false, emptyList(), 0, reply.reason)
            is Msg.Control -> { control.onRemote(reply.action); throw TransferCancelledException(true) }
            else -> throw ProtocolException("Unexpected reply to offer")
        }
        if (offsets.size != items.size) throw ProtocolException("Bad accept")

        val acks = Channel<Msg.FileAck>(Channel.UNLIMITED)
        var readerError: Throwable? = null
        ch.socket.soTimeout = 0 // heartbeat watchdog takes over dead-link detection
        val hb = startHeartbeat(ch)
        val reader = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    when (val f = ch.receive()) {
                        is Incoming.M -> when (val m = f.msg) {
                            is Msg.FileAck -> acks.send(m)
                            is Msg.Control -> control.onRemote(m.action)
                            is Msg.Error -> throw ProtocolException(m.message)
                            is Msg.Done -> { acks.close(); return@launch }
                            else -> {}
                        }
                        is Incoming.D -> throw ProtocolException("Unexpected data from receiver")
                    }
                }
            } catch (e: Throwable) { readerError = e; acks.close(e) }
        }

        val outcomes = ArrayList<FileOutcome>()
        val fileItems = items.indices.filter { !items[it].isDir }
        var done = offsets.indices.filter { !items[it].isDir }.sumOf { offsets[it] }
        val meter = RateMeter()
        val buf = ByteArray(DATA_CHUNK)
        try {
            for ((n, idx) in fileItems.withIndex()) {
                val item = items[idx]
                val offset = offsets[idx].coerceIn(0, item.size)
                control.awaitNotPaused()
                val md = MessageDigest.getInstance("SHA-256")
                item.open().use { input ->
                    // Hash the already-received prefix so the final checksum covers the whole file.
                    var skipped = 0L
                    while (skipped < offset) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), offset - skipped).toInt())
                        if (r < 0) throw ProtocolException("${item.relPath} changed while sending")
                        md.update(buf, 0, r); skipped += r
                    }
                    ch.send(Msg.FileStart(idx, offset))
                    var sent = offset
                    while (sent < item.size) {
                        if (control.paused) { ch.flush(); control.awaitNotPaused() }
                        control.checkCancelled()
                        readerError?.let { throw it }
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), item.size - sent).toInt())
                        if (r < 0) throw ProtocolException("${item.relPath} is shorter than expected (file changed?)")
                        md.update(buf, 0, r)
                        ch.sendData(buf, 0, r)
                        sent += r; done += r; meter.add(r.toLong())
                        if (meter.shouldEmit()) listener.onProgress(progress(transferId, item, n, fileItems.size, sent, done, totalBytes, meter.rate, control))
                    }
                }
                ch.send(Msg.FileEnd(idx, Crypto.hex(md.digest())))
                val ack = try { withTimeout(300_000) { acks.receive() } } catch (e: Exception) {
                    readerError?.let { throw it }; control.checkCancelled(); throw ProtocolException("No confirmation from receiver")
                }
                outcomes += FileOutcome(item.relPath, item.size, ack.ok, ack.savedAs, ack.error)
                listener.onProgress(progress(transferId, item, n + 1, fileItems.size, item.size, done, totalBytes, meter.rate, control))
            }
            ch.send(Msg.Done)
            runCatching { withTimeout(15_000) { reader.join() } }
            val ok = outcomes.all { it.ok }
            TransferResult(transferId, ok, outcomes, done, if (ok) null else "${outcomes.count { !it.ok }} file(s) failed verification")
        } catch (e: TransferCancelledException) {
            TransferResult(transferId, false, outcomes, done, e.message, cancelled = true)
        } catch (e: Exception) {
            if (control.cancelled) TransferResult(transferId, false, outcomes, done, TransferCancelledException(control.cancelledByPeer).message, cancelled = true)
            else throw e
        } finally {
            hb.interrupt(); reader.cancel(); ch.close()
        }
    }

    private fun progress(id: String, item: SendItem, n: Int, count: Int, fileBytes: Long, done: Long, total: Long, rate: Long, c: TransferControl) =
        TransferProgress(id, item.relPath.substringAfterLast('/'), n, count, fileBytes, item.size, done, total, rate, c.paused, c.pausedRemote)

    // ---------------------------------------------------------------- RECEIVER
    interface ReceiverHost {
        fun isTrusted(peer: PeerInfo): Boolean
        suspend fun confirmPairing(peer: PeerInfo, code: String): Boolean
        fun onPaired(peer: PeerInfo)
        /** Decide on an offer. Return null to accept, or a reason string to reject. */
        suspend fun reviewOffer(peer: PeerInfo, offer: Msg.Offer, trusted: Boolean, freeBytes: Long, neededBytes: Long): String?
        fun sink(): FileReceiveSink
        fun onTransferStarted(peer: PeerInfo, offer: Msg.Offer, control: TransferControl) {}
        fun onProgress(peer: PeerInfo, p: TransferProgress) {}
        fun onTransferFinished(peer: PeerInfo, offer: Msg.Offer, result: TransferResult) {}
    }

    /** Serves one authenticated connection (handshake already done). */
    suspend fun serve(ch: SecureChannel, host: ReceiverHost) {
        try {
            val trustMsg = ch.receiveMsg() as? Msg.Trust ?: throw ProtocolException("Expected trust message")
            val trusted = host.isTrusted(ch.peer)
            // Mirror negotiateTrust but we already consumed the peer's Trust message.
            ch.send(Msg.Trust(trusted, trustMsg.purpose))
            var nowTrusted = trusted
            if (!(trusted && trustMsg.trustsPeer)) {
                val mine = if (trusted) true else try {
                    withTimeout(180_000) { host.confirmPairing(ch.peer, ch.verificationCode) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) { false }
                ch.send(Msg.PairDecision(mine))
                val theirs = ch.receiveMsg() as? Msg.PairDecision ?: throw ProtocolException("Unexpected reply during pairing")
                if (!mine || !theirs.accept) return
                if (!trusted) { host.onPaired(ch.peer); nowTrusted = true }
            }
            while (true) {
                when (val m = ch.receiveMsg()) {
                    is Msg.Offer -> receiveOffer(ch, host, m, nowTrusted)
                    is Msg.Bye -> return
                    else -> throw ProtocolException("Unexpected message")
                }
            }
        } catch (_: ProtocolException) {
        } finally { ch.close() }
    }

    private suspend fun receiveOffer(ch: SecureChannel, host: ReceiverHost, offer: Msg.Offer, trusted: Boolean) {
        val sink = host.sink()
        try {
            sink.prepare()
            offer.items.forEach { PathSafety.sanitize(it.path) }
            if (offer.items.any { it.size < 0 }) throw ProtocolException("Invalid size")
        } catch (e: Exception) { ch.send(Msg.Reject(e.message ?: "Invalid offer")); return }

        val offsets = offer.items.map { item ->
            if (item.dir) 0L else {
                val part = sink.partialFile(resumeKey(ch.peer.deviceId, item))
                val len = if (part.exists()) part.length() else 0L
                if (len > item.size) { part.delete(); 0L } else len
            }
        }
        val needed = offer.items.indices.sumOf { if (offer.items[it].dir) 0L else offer.items[it].size - offsets[it] }
        val free = sink.freeBytes()
        if (needed + 32L * 1024 * 1024 > free) {
            ch.send(Msg.Reject("Not enough storage on receiving device: needs ${fmt(needed)}, ${fmt(free)} free"))
            return
        }
        val reason = host.reviewOffer(ch.peer, offer, trusted, free, needed)
        if (reason != null) { ch.send(Msg.Reject(reason)); return }

        offer.items.filter { it.dir }.forEach { runCatching { sink.ensureDir(it.path) } }
        ch.send(Msg.Accept(offsets))

        val control = TransferControl().also { it.channel = ch }
        host.onTransferStarted(ch.peer, offer, control)
        ch.socket.soTimeout = 0
        val hb = startHeartbeat(ch)
        val outcomes = ArrayList<FileOutcome>()
        val fileIdx = offer.items.indices.filter { !offer.items[it].dir }
        var done = fileIdx.sumOf { offsets[it] }
        val meter = RateMeter()
        var curOut: FileOutputStream? = null
        var curMd: MessageDigest? = null
        var curIndex = -1
        var curWritten = 0L
        var curPart: File? = null
        try {
            loop@ while (true) {
                if (control.cancelled) throw TransferCancelledException(control.cancelledByPeer)
                when (val f = ch.receive()) {
                    is Incoming.D -> {
                        val out = curOut ?: throw ProtocolException("Data outside file")
                        val item = offer.items[curIndex]
                        if (curWritten + f.length > item.size) throw ProtocolException("Received more data than announced")
                        out.write(f.bytes, 0, f.length); curMd!!.update(f.bytes, 0, f.length)
                        curWritten += f.length; done += f.length; meter.add(f.length.toLong())
                        if (meter.shouldEmit()) host.onProgress(ch.peer, TransferProgress(offer.transferId, item.path.substringAfterLast('/'),
                            fileIdx.indexOf(curIndex), fileIdx.size, curWritten, item.size, done, offer.totalBytes, meter.rate, control.paused, control.pausedRemote))
                    }
                    is Incoming.M -> when (val m = f.msg) {
                        is Msg.FileStart -> {
                            if (m.index !in offer.items.indices || offer.items[m.index].dir) throw ProtocolException("Bad file index")
                            val item = offer.items[m.index]
                            if (m.offset != offsets[m.index]) throw ProtocolException("Resume offset mismatch")
                            val part = sink.partialFile(resumeKey(ch.peer.deviceId, item))
                            val md = MessageDigest.getInstance("SHA-256")
                            if (m.offset > 0) hashPrefix(part, m.offset, md) else part.delete()
                            curPart = part; curMd = md; curIndex = m.index; curWritten = m.offset
                            curOut = FileOutputStream(part, m.offset > 0)
                        }
                        is Msg.FileEnd -> {
                            val item = offer.items[m.index]
                            if (m.index != curIndex) throw ProtocolException("Bad file end")
                            curOut!!.flush(); curOut!!.fd.sync(); curOut!!.close(); curOut = null
                            val digest = Crypto.hex(curMd!!.digest())
                            val ack = if (curWritten != item.size) {
                                Msg.FileAck(m.index, false, error = "Size mismatch")
                            } else if (!digest.equals(m.sha256, true)) {
                                curPart!!.delete()
                                Msg.FileAck(m.index, false, error = "Checksum mismatch – file discarded")
                            } else {
                                val dest = sink.finalize(curPart!!, item.path)
                                Msg.FileAck(m.index, true, savedAs = dest.relativeTo(sink.root).path)
                            }
                            outcomes += FileOutcome(item.path, item.size, ack.ok, ack.savedAs, ack.error)
                            ch.send(ack)
                            curIndex = -1
                        }
                        is Msg.Control -> control.onRemote(m.action)
                        is Msg.Done -> { ch.send(Msg.Done); break@loop }
                        is Msg.Error -> throw ProtocolException(m.message)
                        is Msg.Ping -> {}
                        else -> throw ProtocolException("Unexpected message")
                    }
                }
            }
            host.onTransferFinished(ch.peer, offer, TransferResult(offer.transferId, outcomes.all { it.ok }, outcomes, done))
        } catch (e: Exception) {
            runCatching { curOut?.close() }
            val cancelled = control.cancelled || e is TransferCancelledException
            // Keep partial data for resume after network drops; discard if a user cancelled.
            if (cancelled) offer.items.filter { !it.dir }.forEach { sink.partialFile(resumeKey(ch.peer.deviceId, it)).delete() }
            val msg = when {
                cancelled -> TransferCancelledException(control.cancelledByPeer).message
                else -> (e.message ?: "Connection lost") + " – partial data kept for resume"
            }
            host.onTransferFinished(ch.peer, offer, TransferResult(offer.transferId, false, outcomes, done, msg, cancelled))
            throw ProtocolException(msg ?: "Failed", e)
        } finally {
            hb.interrupt()
        }
    }

    private fun hashPrefix(f: File, len: Long, md: MessageDigest) {
        f.inputStream().use { i: InputStream ->
            val b = ByteArray(1 shl 20); var left = len
            while (left > 0) {
                val r = i.read(b, 0, minOf(b.size.toLong(), left).toInt())
                if (r < 0) throw ProtocolException("Partial file damaged")
                md.update(b, 0, r); left -= r
            }
        }
        // Truncate anything beyond the agreed offset.
        java.io.RandomAccessFile(f, "rw").use { it.setLength(len) }
    }

    fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
