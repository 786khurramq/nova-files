package com.nova.files

import com.nova.files.transfer.protocol.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random

class TransferProtocolTest {

    class Device(val name: String) {
        val identity = SoftwareIdentityKey()
        val fp = Crypto.fingerprint(identity.publicKeyEncoded)
        val trusted = mutableSetOf<String>()
        var acceptPairing = true
        var pairPrompts = 0
        var codesSeen = mutableListOf<String>()
    }

    private fun tmp(prefix: String) = Files.createTempDirectory(prefix).toFile()

    private fun randomFile(f: File, size: Int, seed: Long) {
        f.parentFile.mkdirs()
        val r = Random(seed); val b = ByteArray(size); r.nextBytes(b); f.writeBytes(b)
    }

    /** Runs a receiver server for exactly one connection. */
    private fun CoroutineScope.startReceiver(dev: Device, root: File, server: ServerSocket, results: MutableList<TransferResult>,
                                             onStart: (TransferControl) -> Unit = {}) = launch(Dispatchers.IO) {
        val s = server.accept()
        val ch = SecureChannel(s)
        try { ch.handshake(false, dev.identity, dev.name, "tv", "test") } catch (e: Exception) { ch.close(); return@launch }
        TransferEngine.serve(ch, object : TransferEngine.ReceiverHost {
            override fun isTrusted(peer: PeerInfo) = peer.fingerprint in dev.trusted
            override suspend fun confirmPairing(peer: PeerInfo, code: String): Boolean { dev.pairPrompts++; dev.codesSeen += code; return dev.acceptPairing }
            override fun onPaired(peer: PeerInfo) { dev.trusted += peer.fingerprint }
            override suspend fun reviewOffer(peer: PeerInfo, offer: Msg.Offer, trusted: Boolean, freeBytes: Long, neededBytes: Long): String? = null
            override fun sink() = FileReceiveSink(root)
            override fun onTransferStarted(peer: PeerInfo, offer: Msg.Offer, control: TransferControl) = onStart(control)
            override fun onTransferFinished(peer: PeerInfo, offer: Msg.Offer, result: TransferResult) { results += result }
        })
    }

    private suspend fun clientSend(dev: Device, port: Int, items: List<SendItem>, id: String,
                                   expectFp: String? = null, listener: TransferListener = object : TransferListener {},
                                   control: TransferControl = TransferControl()): TransferResult = withContext(Dispatchers.IO) {
        val s = Socket(); s.connect(InetSocketAddress("127.0.0.1", port), 5000)
        val ch = SecureChannel(s)
        try { ch.handshake(true, dev.identity, dev.name, "phone", "test", expectFp) } catch (e: Exception) { ch.close(); throw e }
        val newlyPaired = TransferEngine.negotiateTrust(ch, Purpose.TRANSFER, ch.peer.fingerprint in dev.trusted) { _, code ->
            dev.pairPrompts++; dev.codesSeen += code; dev.acceptPairing
        }
        if (newlyPaired) dev.trusted += ch.peer.fingerprint
        TransferEngine.send(ch, id, items, control, listener)
    }

    @Test fun pairThenSendFolderAndLargeFile() = runBlocking {
        val phone = Device("S24 Ultra"); val tv = Device("Shield")
        val src = tmp("src"); val dst = tmp("dst")
        randomFile(File(src, "Album/a.jpg"), 300_000, 1)
        randomFile(File(src, "Album/sub/b ü 日本.mp4"), 2_000_000, 2)
        File(src, "Album/empty").mkdirs()
        randomFile(File(src, "big.bin"), 40 * 1024 * 1024 + 123, 3)
        File(src, "zero.txt").writeText("")

        val server = ServerSocket(0)
        val results = mutableListOf<TransferResult>()
        val rx = startReceiver(tv, dst, server, results)
        val items = FileSendItem.expand(listOf(File(src, "Album"), File(src, "big.bin"), File(src, "zero.txt")))
        val t0 = System.nanoTime()
        val res = clientSend(phone, server.localPort, items, "t1")
        rx.join()
        val secs = (System.nanoTime() - t0) / 1e9
        println("Transferred ${res.bytes} bytes in %.2fs (%.1f MB/s)".format(secs, res.bytes / secs / 1e6))

        assertTrue(res.error ?: "", res.success)
        assertEquals(1, phone.pairPrompts); assertEquals(1, tv.pairPrompts)
        assertEquals("codes must match on both devices", phone.codesSeen[0], tv.codesSeen[0])
        assertTrue(File(dst, "Album/empty").isDirectory)
        for (rel in listOf("Album/a.jpg", "Album/sub/b ü 日本.mp4", "big.bin", "zero.txt"))
            assertArrayEquals(rel, File(src, rel).readBytes(), File(dst, rel).readBytes())
        assertTrue(results.single().success)

        // Second transfer: already trusted -> no prompts, and no overwrite.
        val rx2 = startReceiver(tv, dst, server, results)
        val res2 = clientSend(phone, server.localPort, FileSendItem.expand(listOf(File(src, "zero.txt"))), "t2", expectFp = tv.fp)
        rx2.join()
        assertTrue(res2.success)
        assertEquals(1, phone.pairPrompts)
        assertTrue(File(dst, "zero (1).txt").exists())
        server.close()
    }

    @Test fun interruptedTransferResumes() = runBlocking {
        val phone = Device("Phone"); val tv = Device("MINIX")
        phone.trusted += tv.fp; tv.trusted += phone.fp
        val src = tmp("src"); val dst = tmp("dst")
        randomFile(File(src, "movie.mkv"), 30 * 1024 * 1024, 9)
        val server = ServerSocket(0)
        val results = mutableListOf<TransferResult>()

        // Receiver kills the link after ~10 MB, simulating Wi-Fi loss.
        var rxControl: TransferControl? = null
        val rx = startReceiver(tv, dst, server, results) { rxControl = it }
        val items = FileSendItem.expand(listOf(File(src, "movie.mkv")))
        var killed = false
        val first = runCatching {
            clientSend(phone, server.localPort, items, "r1", listener = object : TransferListener {
                override fun onProgress(p: TransferProgress) {
                    if (!killed && p.bytesDone > 10 * 1024 * 1024) { killed = true; throw RuntimeException("link down") }
                }
            })
        }
        rx.cancelAndJoin()
        assertTrue(first.isFailure || first.getOrNull()?.success == false)
        val partials = File(dst, ".nova-partial").listFiles { f -> f.name.endsWith(".part") }!!
        assertEquals(1, partials.size)
        val kept = partials[0].length()
        assertTrue("partial kept: $kept", kept > 0)

        val server2 = ServerSocket(0)
        val rx2 = startReceiver(tv, dst, server2, results)
        var firstReported = -1L
        val res = clientSend(phone, server2.localPort, items, "r1", listener = object : TransferListener {
            override fun onProgress(p: TransferProgress) { if (firstReported < 0) firstReported = p.bytesDone }
        })
        rx2.join()
        assertTrue(res.error ?: "", res.success)
        assertTrue("resumed from offset", firstReported >= kept)
        assertArrayEquals(File(src, "movie.mkv").readBytes(), File(dst, "movie.mkv").readBytes())
        assertEquals(0, File(dst, ".nova-partial").listFiles { f -> f.name.endsWith(".part") }!!.size)
        server2.close()
    }

    @Test fun pinnedIdentityMismatchIsRejected() = runBlocking {
        val phone = Device("Phone"); val tv = Device("TV"); val impostor = Device("Impostor")
        val server = ServerSocket(0)
        val rx = startReceiver(impostor, tmp("dst"), server, mutableListOf())
        val r = runCatching { clientSend(phone, server.localPort, emptyList(), "x", expectFp = tv.fp) }
        assertTrue(r.exceptionOrNull()?.message?.contains("Security check failed") == true)
        rx.cancelAndJoin(); server.close()
    }

    @Test fun declinedPairingStopsTransfer() = runBlocking {
        val phone = Device("Phone"); val tv = Device("TV"); tv.acceptPairing = false
        val src = tmp("src"); val dst = tmp("dst"); File(src, "a.txt").writeText("hi")
        val server = ServerSocket(0)
        val rx = startReceiver(tv, dst, server, mutableListOf())
        val r = runCatching { clientSend(phone, server.localPort, FileSendItem.expand(listOf(File(src, "a.txt"))), "x") }
        rx.join()
        assertTrue(r.exceptionOrNull()?.message, r.exceptionOrNull()?.message?.contains("declined") == true)
        assertFalse(File(dst, "a.txt").exists())
        assertTrue(phone.trusted.isEmpty() && tv.trusted.isEmpty())
        server.close()
    }

    @Test fun pathSafety() {
        for (bad in listOf("../etc/passwd", "a/../../b", "/abs", "a\\b", "", "a//b", "./x"))
            assertThrows(bad, ProtocolException::class.java) { PathSafety.sanitize(bad) }
        assertEquals(listOf("a_b", "c"), PathSafety.sanitize("a:b/c"))
        assertEquals(listOf("_nova-partial", "x"), PathSafety.sanitize(".nova-partial/x"))
        assertEquals(listOf("_CON.txt"), PathSafety.sanitize("CON.txt"))
    }

    @Test fun hkdfAndCodesDeterministic() {
        val a = Crypto.hkdf(ByteArray(32) { 1 }, ByteArray(32) { 2 }, "x", 42)
        val b = Crypto.hkdf(ByteArray(32) { 1 }, ByteArray(32) { 2 }, "x", 42)
        assertArrayEquals(a, b); assertEquals(42, a.size)
    }
}
