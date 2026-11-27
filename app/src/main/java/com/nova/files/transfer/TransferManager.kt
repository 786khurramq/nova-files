package com.nova.files.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import com.nova.files.BuildConfig
import com.nova.files.core.DeviceProfile
import com.nova.files.core.SettingsRepository
import com.nova.files.transfer.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

enum class TransferStatus { CONNECTING, PAIRING, WAITING_APPROVAL, TRANSFERRING, RETRYING, DONE, FAILED, CANCELLED }

data class ActiveTransfer(
    val id: String,
    val outgoing: Boolean,
    val peerName: String,
    val peerType: String,
    val status: TransferStatus,
    val fileCount: Int,
    val totalBytes: Long,
    val progress: TransferProgress? = null,
    val result: TransferResult? = null,
    val message: String? = null,
    val receiveRoot: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    internal val control: TransferControl? = null,
) {
    val active get() = status !in setOf(TransferStatus.DONE, TransferStatus.FAILED, TransferStatus.CANCELLED)
    fun pause() = control?.pause()
    fun resume() = control?.resume()
    fun cancel() = control?.cancel()
}

sealed class Prompt(val id: String = UUID.randomUUID().toString()) {
    class Pairing(val peer: PeerInfo, val code: String, val outgoing: Boolean, val answer: CompletableDeferred<Boolean>) : Prompt()
    class IncomingOffer(val peer: PeerInfo, val trusted: Boolean, val offer: Msg.Offer, val freeBytes: Long, val neededBytes: Long, val answer: CompletableDeferred<Boolean>) : Prompt()
}

data class ServerState(val running: Boolean = false, val port: Int = 0, val error: String? = null, val addresses: List<String> = emptyList())

/**
 * Central coordinator for discovery, the authenticated receive server and outgoing transfers.
 * Lives for the whole process; [TransferService] keeps the process alive while needed.
 */
class TransferManager(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val profile: DeviceProfile,
    val identity: AndroidIdentityKey,
    val trust: TrustStore,
    val history: HistoryStore,
) {
    private val tag = "NovaTransfer"
    val discovery = Discovery(ctx, scope, identity.deviceId)

    private val _transfers = MutableStateFlow<List<ActiveTransfer>>(emptyList())
    val transfers: StateFlow<List<ActiveTransfer>> = _transfers
    private val _prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val prompts: StateFlow<List<Prompt>> = _prompts
    private val _server = MutableStateFlow(ServerState())
    val server: StateFlow<ServerState> = _server
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    /** Transfers accepted recently; re-offers of the same id (auto-retry) resume without asking again. */
    private val recentlyAccepted = mutableMapOf<String, Long>()

    val deviceName: String get() = settings.settings.value.deviceName.ifBlank { profile.defaultName }

    fun toast(msg: String) { _messages.value = msg }
    fun consumeMessage() { _messages.value = null }

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) { runServer() }
        scope.launch {
            settings.settings.map { Triple(it.deviceName, it.discoverable, it.receiveDir) }.distinctUntilChanged().collect { (_, disc, _) ->
                val port = _server.value.port
                if (port > 0) discovery.updateAdvertisement(deviceName, profile.typeTag, port, disc)
            }
        }
        scope.launch { while (isActive) { _server.update { it.copy(addresses = Discovery.localIpv4Addresses()) }; delay(5000) } }
    }

    private suspend fun runServer() {
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(DEFAULT_TRANSFER_PORT)) }
        } catch (e: IOException) {
            Log.w(tag, "Default port busy, using ephemeral: ${e.message}")
            try { ServerSocket(0) } catch (e2: IOException) { _server.value = ServerState(false, 0, "Cannot open network port: ${e2.message}"); return }
        }
        serverSocket = ss
        _server.value = ServerState(true, ss.localPort, null, Discovery.localIpv4Addresses())
        withContext(Dispatchers.Main) { discovery.start(deviceName, profile.typeTag, ss.localPort, settings.settings.value.discoverable) }
        while (scope.isActive && !ss.isClosed) {
            val s = try { ss.accept() } catch (e: IOException) { break }
            scope.launch(Dispatchers.IO) { handleIncoming(s) }
        }
    }

    // ------------------------------------------------------------- receiving
    fun receiveRoot(): File {
        val custom = settings.settings.value.receiveDir
        val candidates = listOfNotNull(
            custom.takeIf { it.isNotBlank() }?.let { File(it) },
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NovaFiles"),
            ctx.getExternalFilesDir("Received"),
            File(ctx.filesDir, "Received"),
        )
        return candidates.first { writable(it) }
    }

    private fun writable(dir: File): Boolean = runCatching {
        dir.mkdirs()
        val probe = File(dir, ".nova-write-test"); probe.writeText("ok"); probe.delete(); true
    }.getOrDefault(false)

    private suspend fun handleIncoming(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 0
        val ch = SecureChannel(socket)
        try {
            socket.soTimeout = 15_000
            ch.handshake(false, identity, deviceName, profile.typeTag, BuildConfig.VERSION_NAME)
            socket.soTimeout = 240_000 // bounded wait during pairing / approval; heartbeat takes over during transfer
        } catch (e: Exception) { Log.w(tag, "Incoming handshake failed: ${e.message}"); ch.close(); return }
        val peer = ch.peer
        trust.touch(peer.fingerprint, socket.inetAddress.hostAddress ?: "", 0, peer.name)
        var active: ActiveTransfer? = null
        TransferEngine.serve(ch, object : TransferEngine.ReceiverHost {
            override fun isTrusted(peer: PeerInfo) = trust.byFingerprint(peer.fingerprint) != null
            override suspend fun confirmPairing(peer: PeerInfo, code: String) = ask { Prompt.Pairing(peer, code, false, it) }
            override fun onPaired(peer: PeerInfo) = remember(peer, socket.inetAddress.hostAddress ?: "", 0)
            override fun sink() = FileReceiveSink(receiveRoot())

            override suspend fun reviewOffer(peer: PeerInfo, offer: Msg.Offer, trusted: Boolean, freeBytes: Long, neededBytes: Long): String? {
                synchronized(recentlyAccepted) {
                    recentlyAccepted.entries.removeIf { System.currentTimeMillis() - it.value > 30 * 60_000 }
                    if (recentlyAccepted.containsKey("${peer.deviceId}:${offer.transferId}")) return null
                }
                val ok = if (trusted && settings.settings.value.autoAcceptTrusted) true
                else {
                    TransferService.ensureRunning(ctx)
                    TransferService.notifyIncoming(ctx, peer.name, offer.items.count { !it.dir })
                    ask { Prompt.IncomingOffer(peer, trusted, offer, freeBytes, neededBytes, it) }
                }
                if (!ok) return "Declined by ${deviceName}"
                synchronized(recentlyAccepted) { recentlyAccepted["${peer.deviceId}:${offer.transferId}"] = System.currentTimeMillis() }
                return null
            }

            override fun onTransferStarted(peer: PeerInfo, offer: Msg.Offer, control: TransferControl) {
                val t = ActiveTransfer(offer.transferId, false, peer.name, peer.deviceType, TransferStatus.TRANSFERRING,
                    offer.items.count { !it.dir }, offer.totalBytes, control = control, receiveRoot = receiveRoot().path)
                active = t; upsert(t); TransferService.ensureRunning(ctx)
            }

            override fun onProgress(peer: PeerInfo, p: TransferProgress) {
                active?.let { a -> active = a.copy(progress = p); upsert(active!!) }
            }

            override fun onTransferFinished(peer: PeerInfo, offer: Msg.Offer, result: TransferResult) {
                val a = active ?: return
                val status = when { result.success -> TransferStatus.DONE; result.cancelled -> TransferStatus.CANCELLED; else -> TransferStatus.FAILED }
                upsert(a.copy(status = status, result = result, message = result.error, control = null))
                history.add(HistoryEntry(offer.transferId, false, peer.name, peer.deviceId, peer.deviceType, System.currentTimeMillis(),
                    offer.items.count { !it.dir }, result.bytes, result.success, result.cancelled, result.error,
                    result.files.map { HistoryFile(it.path, it.size, it.ok, it.savedAs, it.error) }, baseDir = receiveRoot().path))
                TransferService.notifyFinished(ctx, false, peer.name, result)
            }
        })
    }

    private suspend fun ask(build: (CompletableDeferred<Boolean>) -> Prompt): Boolean {
        val d = CompletableDeferred<Boolean>()
        val p = build(d)
        _prompts.update { it + p }
        return try { kotlinx.coroutines.withTimeoutOrNull(180_000) { d.await() } ?: false } finally { _prompts.update { list -> list.filterNot { it.id == p.id } } }
    }

    fun answer(p: Prompt, accept: Boolean) {
        when (p) { is Prompt.Pairing -> p.answer.complete(accept); is Prompt.IncomingOffer -> p.answer.complete(accept) }
    }

    private fun remember(peer: PeerInfo, host: String, port: Int) {
        trust.upsert(TrustedDevice(peer.deviceId, peer.fingerprint, peer.name, peer.deviceType, System.currentTimeMillis(), host, port, System.currentTimeMillis()))
    }

    private fun upsert(t: ActiveTransfer) {
        _transfers.update { list -> (list.filterNot { it.id == t.id && it.outgoing == t.outgoing } + t).sortedByDescending { it.startedAt }.take(30) }
    }

    fun clearFinished() { _transfers.update { list -> list.filter { it.active } } }

    // ------------------------------------------------------------- sending
    data class Target(val name: String, val type: String, val host: String, val port: Int, val deviceId: String?, val pinnedFingerprint: String? = null)

    fun targetFor(d: NearbyDevice) = Target(d.name, d.type, d.host, d.port, d.deviceId, trust.byId(d.deviceId)?.fingerprint)

    fun send(target: Target, items: List<SendItem>, sources: List<String> = emptyList(), transferId: String = UUID.randomUUID().toString()) {
        if (items.isEmpty()) return
        val fileCount = items.count { !it.isDir }
        val total = items.filter { !it.isDir }.sumOf { it.size }
        val control = TransferControl()
        var t = ActiveTransfer(transferId, true, target.name, target.type, TransferStatus.CONNECTING, fileCount, total, control = control)
        upsert(t)
        TransferService.ensureRunning(ctx)
        scope.launch(Dispatchers.IO) {
            var attempt = 0
            var result: TransferResult? = null
            var lastError: String? = null
            var acceptedOnce = false
            while (attempt < 4 && result == null && !control.cancelled) {
                attempt++
                try {
                    val ch = connect(target)
                    val peer = ch.peer
                    t = t.copy(peerName = peer.name, peerType = peer.deviceType, status = TransferStatus.PAIRING); upsert(t)
                    val trusted = trust.byFingerprint(peer.fingerprint) != null
                    val pinnedByQr = target.pinnedFingerprint != null && !trusted
                    val newly = TransferEngine.negotiateTrust(ch, Purpose.TRANSFER, trusted) { p, code ->
                        if (pinnedByQr) true else ask { Prompt.Pairing(p, code, true, it) }
                    }
                    if (newly || pinnedByQr) remember(peer, target.host, target.port)
                    trust.touch(peer.fingerprint, target.host, target.port, peer.name)
                    t = t.copy(status = if (acceptedOnce) TransferStatus.TRANSFERRING else TransferStatus.WAITING_APPROVAL); upsert(t)
                    val r = TransferEngine.send(ch, transferId, items, control, object : TransferListener {
                        override fun onProgress(p: TransferProgress) {
                            acceptedOnce = true
                            t = t.copy(status = TransferStatus.TRANSFERRING, progress = p); upsert(t)
                        }
                    })
                    result = r
                } catch (e: ProtocolException) {
                    lastError = e.message
                    // Security / decline errors are final; only network failures are retried.
                    val m = e.message ?: ""
                    if (m.contains("declined", true) || m.contains("Security", true) || m.contains("version", true) || m.contains("Authentication", true) || !acceptedOnce) break
                } catch (e: TransferCancelledException) {
                    result = TransferResult(transferId, false, emptyList(), 0, e.message, true)
                } catch (e: IOException) {
                    lastError = friendlyNetworkError(e)
                    if (!acceptedOnce && attempt >= 2) break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName; break
                }
                if (result == null && !control.cancelled) {
                    t = t.copy(status = TransferStatus.RETRYING, message = "Connection lost – retrying (${attempt}/3)…"); upsert(t)
                    delay(2000L * attempt)
                }
            }
            val r = result ?: TransferResult(transferId, false, emptyList(), t.progress?.bytesDone ?: 0, lastError ?: if (control.cancelled) "Cancelled" else "Failed", control.cancelled)
            val status = when { r.success -> TransferStatus.DONE; r.cancelled -> TransferStatus.CANCELLED; else -> TransferStatus.FAILED }
            t = t.copy(status = status, result = r, message = r.error, control = null); upsert(t)
            history.add(HistoryEntry(transferId, true, t.peerName, target.deviceId ?: "", t.peerType, System.currentTimeMillis(), fileCount,
                r.bytes, r.success, r.cancelled, r.error, r.files.map { HistoryFile(it.path, it.size, it.ok, it.savedAs, it.error) }, sources))
            TransferService.notifyFinished(ctx, true, t.peerName, r)
        }
    }

    private fun friendlyNetworkError(e: IOException): String = when {
        e is java.net.ConnectException -> "Could not reach device – check both are on the same network and Nova Files is open on the receiver"
        e is java.net.SocketTimeoutException -> "Connection timed out"
        e is java.net.NoRouteToHostException -> "No route to device – different network or client isolation enabled on router"
        else -> e.message ?: "Network error"
    }

    private fun connect(target: Target): SecureChannel {
        val s = Socket()
        s.tcpNoDelay = true; s.keepAlive = true
        s.sendBufferSize = 1 shl 20; s.receiveBufferSize = 1 shl 20
        s.connect(InetSocketAddress(target.host, target.port), 8000)
        s.soTimeout = 15_000
        val ch = SecureChannel(s)
        try {
            ch.handshake(true, identity, deviceName, profile.typeTag, BuildConfig.VERSION_NAME, target.pinnedFingerprint)
        } catch (e: Exception) { ch.close(); throw e }
        s.soTimeout = 240_000
        return ch
    }

    /** Pair without sending (both devices confirm the same code). */
    fun pair(target: Target) {
        scope.launch(Dispatchers.IO) {
            try {
                val ch = connect(target)
                val trusted = trust.byFingerprint(ch.peer.fingerprint) != null
                val newly = TransferEngine.negotiateTrust(ch, Purpose.PAIR, trusted) { p, code -> ask { Prompt.Pairing(p, code, true, it) } }
                if (newly || target.pinnedFingerprint != null) remember(ch.peer, target.host, target.port)
                ch.send(Msg.Bye); ch.close()
                toast(if (newly) "Paired with ${ch.peer.name}" else "${ch.peer.name} is already trusted")
            } catch (e: IOException) { toast(friendlyNetworkError(e)) }
            catch (e: Exception) { toast(e.message ?: "Pairing failed") }
        }
    }

    /** Parses manual "host" / "host:port" entries and nova:// QR links. */
    fun parseTarget(input: String): Target? {
        val s = input.trim()
        if (s.startsWith("nova://", true)) {
            val u = Uri.parse(s)
            val h = u.getQueryParameter("h") ?: return null
            val p = u.getQueryParameter("p")?.toIntOrNull() ?: DEFAULT_TRANSFER_PORT
            return Target(u.getQueryParameter("n") ?: h, u.getQueryParameter("t") ?: "phone", h, p, u.getQueryParameter("id"), u.getQueryParameter("fp"))
        }
        val host = s.substringBefore(':')
        if (!Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$").matches(host) && !Regex("^[A-Za-z0-9.-]+$").matches(host)) return null
        val port = s.substringAfter(':', "").toIntOrNull() ?: DEFAULT_TRANSFER_PORT
        if (port !in 1..65535) return null
        return Target(host, "phone", host, port, null)
    }

    fun pairingLink(): String? {
        val st = _server.value
        val ip = st.addresses.firstOrNull() ?: return null
        return "nova://pair?h=$ip&p=${st.port}&id=${identity.deviceId}&fp=${identity.fingerprint}&t=${profile.typeTag}&n=${Uri.encode(deviceName)}"
    }

    /** Saves shared items into the receive folder on this device (no network involved). */
    suspend fun saveLocally(items: List<SendItem>): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val root = receiveRoot()
        var n = 0
        for (it in items.filter { i -> !i.isDir }) {
            val name = com.nova.files.core.ExternalContent.safeName(it.relPath.substringAfterLast('/'))
            val target = FileReceiveSink.uniqueName(File(root, name))
            val tmp = File(root, ".${target.name}.novatmp")
            try {
                it.open().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                if (tmp.renameTo(target)) n++
            } finally { if (tmp.exists()) tmp.delete() }
        }
        n
    }

    // ------------------------------------------------------------- send items from content URIs
    fun itemsFromUris(uris: List<Uri>): List<SendItem> = uris.filter { com.nova.files.core.ExternalContent.isSafe(ctx, it) }.mapNotNull { uri ->
        runCatching {
            if (uri.scheme == "file") { val f = File(uri.path!!); if (!f.isFile) return@runCatching null; return@runCatching FileSendItem(f, f.name) }
            var name = "file"; var size = -1L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            name = com.nova.files.core.ExternalContent.safeName(name)
            if (size < 0) {
                // Unknown length: stage a copy so size and checksum are exact.
                val f = File(ctx.cacheDir, "share-${System.nanoTime()}-$name")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                FileSendItem(f, name)
            } else UriSendItem(ctx, uri, name, size)
        }.getOrNull()
    }
}

class UriSendItem(private val ctx: Context, val uri: Uri, override val relPath: String, override val size: Long) : SendItem {
    override val mtime: Long = 0
    override val isDir = false
    override fun open(): InputStream = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $relPath")
}
