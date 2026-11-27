package com.nova.files.cloud

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nova.files.cloud.accounts.AccountRepository
import com.nova.files.cloud.auth.CredentialResult
import com.nova.files.cloud.auth.Credentials
import com.nova.files.cloud.core.*
import com.nova.files.cloud.db.*
import com.nova.files.cloud.net.CloudHttp
import com.nova.files.cloud.net.RetryPolicy
import com.nova.files.cloud.transfer.*
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseAndTransferTest {
    private lateinit var ctx: Context
    private lateinit var db: CloudDatabase

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CloudDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun repo(store: com.nova.files.cloud.auth.CredentialStore = credentialStore()) =
        AccountRepository(db, store, CloudHttp(), emptyMap())

    // ------------------------------------------------------------------ schema / migrations

    @Test fun exportedSchemaV1CreatesAndValidates() {
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CloudDatabase::class.java)
        helper.createDatabase("migration-test", 1).close()
        // Opening with the real builder validates the on-disk schema against the entities (identity hash).
        Room.databaseBuilder(ctx, CloudDatabase::class.java, "migration-test").addMigrations(*CloudDatabase.MIGRATIONS).build().apply {
            openHelper.writableDatabase; close()
        }
    }

    // ------------------------------------------------------------------ accounts

    @Test fun multipleAccountsPerProviderAndNoDuplicates() = runBlocking {
        val r = repo()
        val a1 = r.connect(ProviderId.ONEDRIVE, AccountProfile("ms-1", "Home", "a@outlook.com"), Credentials.OAuth("t1", "r1", Long.MAX_VALUE))
        val a2 = r.connect(ProviderId.ONEDRIVE, AccountProfile("ms-2", "Work", "a@outlook.com"), Credentials.OAuth("t2", "r2", Long.MAX_VALUE))
        assertNotEquals(a1.id, a2.id)                                                // same email, different identity → two accounts
        val again = r.connect(ProviderId.ONEDRIVE, AccountProfile("ms-1", "Home renamed", null), Credentials.OAuth("t3", "r3", Long.MAX_VALUE))
        assertEquals(a1.id, again.id)                                                // reconnect, not duplicate
        assertEquals(2, r.all().size)
        assertEquals("Home renamed", r.get(a1.id)!!.displayName)
        // Same providerAccountId on a different provider is a different account.
        r.connect(ProviderId.DROPBOX, AccountProfile("ms-1", "Box", null), Credentials.OAuth("t", "r", Long.MAX_VALUE))
        assertEquals(3, r.all().size)
        // Reconnecting with the wrong identity is refused.
        assertTrue(runCatching { r.connect(ProviderId.ONEDRIVE, AccountProfile("ms-9", "x", null), Credentials.OAuth("t", "r", 0), expectAccountId = a1.id) }.exceptionOrNull() is CloudException.Forbidden)
    }

    @Test fun removingOneAccountLeavesOthersUntouched() = runBlocking {
        val store = credentialStore()
        val r = repo(store)
        val a = r.connect(ProviderId.DROPBOX, AccountProfile("dbx-a", "A", null), Credentials.OAuth("ta", "ra", Long.MAX_VALUE))
        val b = r.connect(ProviderId.DROPBOX, AccountProfile("dbx-b", "B", null), Credentials.OAuth("tb", "rb", Long.MAX_VALUE))
        fun item(acc: String, id: String) = CloudItemEntity(acc, id, null, id, null, "FILE", 1, 0, null, "{}", null, "{}", 0)
        db.items().upsert(listOf(item(a.id, "same-id"), item(a.id, "x"), item(b.id, "same-id")))
        val q = TransferQueue(db)
        val ta = q.enqueue(TransferKind.COPY, Endpoint.Local("/f"), Endpoint.Cloud(a.id, "DROPBOX", parentId = "/"), "f", 1)
        val tb = q.enqueue(TransferKind.COPY, Endpoint.Local("/g"), Endpoint.Cloud(b.id, "DROPBOX", parentId = "/"), "g", 1)

        val report = r.remove(a.id)
        assertEquals(1, report.cancelledTransfers); assertEquals(2, report.cachedItemsRemoved)
        assertNull(r.get(a.id)); assertNotNull(r.get(b.id))
        assertEquals(0, db.items().count(a.id)); assertEquals(1, db.items().count(b.id))   // cache identity is (account, fileId)
        assertEquals(TransferState.CANCELLED, q.get(ta.id)!!.state)
        assertEquals(TransferState.QUEUED, q.get(tb.id)!!.state)
        assertEquals(CredentialResult.Missing, store.get(a.credentialRef))
        assertTrue(store.get(b.credentialRef) is CredentialResult.Ok)
    }

    @Test fun sessionsAuthenticateAsTheirOwnAccountOnly() = runBlocking {
        val server = MockWebServer().apply { repeat(2) { enqueue(MockResponse()) }; start() }
        val r = repo()
        val a = r.connect(ProviderId.GOOGLE_DRIVE, AccountProfile("g-a", "A", null), Credentials.OAuth("tok-A", "r", Long.MAX_VALUE))
        val b = r.connect(ProviderId.GOOGLE_DRIVE, AccountProfile("g-b", "B", null), Credentials.OAuth("tok-B", "r", Long.MAX_VALUE))
        val sb = r.session(b.id); val sa = r.session(a.id)
        assertEquals(b.id, sb.accountId)
        sb.http.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        sa.http.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertEquals("Bearer tok-B", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-A", server.takeRequest().getHeader("Authorization"))
        r.markNeedsReconnect(a.id)
        assertTrue(runCatching { r.session(a.id) }.exceptionOrNull() is CloudException.AuthRequired)
        assertNotNull(r.session(b.id))
        server.shutdown()
    }

    // ------------------------------------------------------------------ queue + engine

    private fun data(n: Int, seed: Int = 1) = Random(seed).nextBytes(n)

    /** Source that fails once after [failAfter] bytes, like a dropped connection. */
    private class FlakySource(val bytes: ByteArray, var failAfter: Long) : TransferSource {
        val offsets = mutableListOf<Long>()
        override val name = "movie.bin"; override val size: Long = bytes.size.toLong(); override val supportsOffset = true
        override fun open(offset: Long): InputStream {
            offsets += offset
            val limit = failAfter; failAfter = Long.MAX_VALUE
            return object : InputStream() {
                var pos = offset
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= bytes.size) return -1
                    if (pos >= limit) throw IOException("connection reset")
                    val n = minOf(len.toLong(), bytes.size - pos, (limit - pos).coerceAtLeast(1)).toInt()
                    System.arraycopy(bytes, pos.toInt(), b, off, n); pos += n; return n
                }
            }
        }
    }

    private fun engine(q: TransferQueue, resolver: EndpointResolver) =
        TransferEngine(q, resolver, retry = RetryPolicy(maxAttempts = 3, sleep = {}), bufferBytes = 64 * 1024)

    private fun registerDisk(dir: File) = ShadowStatFs.registerStats(dir, 1_000_000, 900_000, 900_000)

    @Test fun interruptedCopyResumesFromCheckpointAndVerifies() = runBlocking {
        val out = tempDir("dst"); registerDisk(out)
        val bytes = data(3_000_000)
        val src = FlakySource(bytes, failAfter = 1_200_000)
        val resolver = object : EndpointResolver {
            override fun source(e: Endpoint) = src
            override fun destination(e: Endpoint) = LocalDirDestination(File((e as Endpoint.Local).path))
        }
        val q = TransferQueue(db)
        val job = q.enqueue(TransferKind.COPY, Endpoint.ContentUri("content://x"), Endpoint.Local(out.path), "movie.bin", bytes.size.toLong())
        engine(q, resolver).drain()
        val done = q.get(job.id)!!
        assertEquals(done.error, TransferState.COMPLETED, done.state)
        assertTrue("second attempt resumed mid-file: ${src.offsets}", src.offsets.size == 2 && src.offsets[1] > 0)
        assertArrayEquals(bytes, File(out, "movie.bin").readBytes())
        assertFalse(out.listFiles()!!.any { it.name.endsWith(".novapart") })
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertTrue(done.resultJson!!.contains(sha))
    }

    @Test fun processDeathRecoveryRequeuesResumableAndFailsWireless() = runBlocking {
        val q = TransferQueue(db)
        val copy = q.enqueue(TransferKind.COPY, Endpoint.Local("/a"), Endpoint.Local("/b"), "a", 10, state = TransferState.RUNNING)
        val noResume = q.enqueue(TransferKind.COPY, Endpoint.Local("/a"), Endpoint.Local("/b"), "a", 10, state = TransferState.RUNNING, resumable = false)
        val wireless = q.enqueue(TransferKind.WIRELESS_SEND, Endpoint.Local("/a"), Endpoint.Wireless("dev", "Shield"), "a", 10, state = TransferState.RUNNING)
        val finished = q.enqueue(TransferKind.COPY, Endpoint.Local("/a"), Endpoint.Local("/b"), "a", 10, state = TransferState.COMPLETED)
        assertEquals(3, q.recoverAfterRestart())
        assertEquals(TransferState.QUEUED, q.get(copy.id)!!.state)
        assertEquals(TransferState.FAILED, q.get(noResume.id)!!.state)
        assertTrue(q.get(noResume.id)!!.error!!.contains("restart"))
        assertEquals(TransferState.FAILED, q.get(wireless.id)!!.state)
        assertEquals(TransferState.COMPLETED, q.get(finished.id)!!.state)
    }

    @Test fun conflictPoliciesNeverOverwriteSilently() = runBlocking {
        val out = tempDir("dst"); registerDisk(out)
        val srcFile = File(tempDir("src"), "a.txt").apply { writeText("new") }
        File(out, "a.txt").writeText("old")
        val q = TransferQueue(db)
        val e = engine(q, LocalEndpointResolver(ctx))
        suspend fun run(p: ConflictPolicy): TransferEntity {
            val j = q.enqueue(TransferKind.COPY, Endpoint.Local(srcFile.path), Endpoint.Local(out.path), "a.txt", 3, conflict = p)
            e.drain(); return q.get(j.id)!!
        }
        assertEquals(TransferState.COMPLETED, run(ConflictPolicy.KEEP_BOTH).state)
        assertEquals("new", File(out, "a (1).txt").readText()); assertEquals("old", File(out, "a.txt").readText())
        val skipped = run(ConflictPolicy.SKIP)
        assertTrue(skipped.state == TransferState.COMPLETED && skipped.resultJson!!.contains("\"skipped\":true"))
        val ask = run(ConflictPolicy.ASK)
        assertEquals(TransferState.FAILED, ask.state); assertEquals("old", File(out, "a.txt").readText())
        assertEquals(TransferState.COMPLETED, run(ConflictPolicy.REPLACE).state)
        assertEquals("new", File(out, "a.txt").readText())
    }

    @Test fun zeroByteAndInsufficientStorage() = runBlocking {
        val out = tempDir("dst"); registerDisk(out)
        val empty = File(tempDir("src"), "empty.txt").apply { writeBytes(ByteArray(0)) }
        val q = TransferQueue(db)
        val e = engine(q, LocalEndpointResolver(ctx))
        val j = q.enqueue(TransferKind.COPY, Endpoint.Local(empty.path), Endpoint.Local(out.path), "empty.txt", 0)
        e.drain()
        assertEquals(TransferState.COMPLETED, q.get(j.id)!!.state); assertEquals(0, File(out, "empty.txt").length())
        val small = tempDir("tiny"); ShadowStatFs.registerStats(small, 100, 1, 1)   // ~4 KB free
        val big = File(tempDir("src"), "big.bin").apply { writeBytes(ByteArray(2_000_000)) }
        val j2 = q.enqueue(TransferKind.COPY, Endpoint.Local(big.path), Endpoint.Local(small.path), "big.bin", 2_000_000)
        e.drain()
        val f = q.get(j2.id)!!
        assertEquals(TransferState.FAILED, f.state); assertTrue(f.error!!.contains("Not enough space"))
    }

    @Test fun progressWritesAreThrottled() = runBlocking {
        var clock = 0L
        val q = TransferQueue(db) { clock }
        val j = q.enqueue(TransferKind.COPY, Endpoint.Local("/a"), Endpoint.Local("/b"), "a", 100L shl 20)
        var writes = 0
        for (i in 1..1000) { clock += 10; if (q.progress(j.id, i * 64L * 1024, null)) writes++ }   // 10 s, 64 MB
        assertTrue("writes=$writes", writes in 5..12)
        assertTrue(q.progress(j.id, 999, null, force = true))
    }

    @Test fun pausedJobKeepsPartialAndResumes() = runBlocking {
        val out = tempDir("dst"); registerDisk(out)
        val bytes = data(2_000_000, 7)
        val q = TransferQueue(db)
        lateinit var eng: TransferEngine
        var paused = false
        val src = object : TransferSource {
            override val name = "p.bin"; override val size: Long = bytes.size.toLong(); override val supportsOffset = true
            override fun open(offset: Long): InputStream = object : java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!paused && pos > 800_000) { paused = true; runBlocking { eng.pause(q.runnable().firstOrNull()?.id ?: currentId) } }
                    return super.read(b, off, len)
                }
            }
        }
        val resolver = object : EndpointResolver {
            override fun source(e: Endpoint) = src
            override fun destination(e: Endpoint) = LocalDirDestination(File((e as Endpoint.Local).path))
        }
        eng = engine(q, resolver)
        val j = q.enqueue(TransferKind.COPY, Endpoint.ContentUri("content://p"), Endpoint.Local(out.path), "p.bin", bytes.size.toLong())
        currentId = j.id
        eng.drain()
        val p = q.get(j.id)!!
        assertEquals(TransferState.PAUSED, p.state)
        assertTrue(out.listFiles()!!.any { it.name == ".p.bin.novapart" && it.length() > 0 })
        q.setState(j.id, TransferState.QUEUED)
        eng.drain()
        assertEquals(TransferState.COMPLETED, q.get(j.id)!!.state)
        assertArrayEquals(bytes, File(out, "p.bin").readBytes())
    }
    private var currentId = ""
}
