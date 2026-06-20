package com.ohv.shared.db

import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * DatabaseService 单元测试（commonTest）
 *
 * 覆盖 v1.6 review 风险矩阵：
 * - 单线程连续写入内容正确
 * - 多线程并发写入无文件损坏
 * - 损坏文件 load 时被改名为 .corrupt.<ts>
 * - 启动时清理遗留 .tmp 文件
 * - clearAll 删除数据库文件
 */
class DatabaseServiceTest {

    private lateinit var tempDir: File
    private lateinit var service: DatabaseService

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory(prefix = "ohv_db_test_").toFile()
        service = DatabaseService(tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { service.clearAll() }
        tempDir.deleteRecursively()
    }

    // ─── 基础 CRUD ─────────────────────────────────────────────────────────────

    @Test
    fun `upsert and read creators`() = runTest {
        service.upsertCreator(sampleCreator("c1", "Alice"))
        service.upsertCreator(sampleCreator("c2", "Bob"))

        service.flushToDisk()

        val creators = service.creators.value
        assertEquals(2, creators.size)
        assertEquals("Alice", creators.first { it.id == "c1" }.name)
        assertEquals("Bob", creators.first { it.id == "c2" }.name)
    }

    @Test
    fun `upsertAlbum preserves existing audioUrl`() = runTest {
        service.upsertAudioItem(sampleAudioItem("a1", "E1", audioUrl = "https://cached.example/m.m4a"))
        service.flushToDisk()

        // 重新 upsert 同一个 item，audioUrl 应保留
        service.upsertAudioItem(sampleAudioItem("a1", "E1 (renamed)", audioUrl = null))
        val item = service.audioItemById("a1")
        assertNotNull(item)
        assertEquals("E1 (renamed)", item.title)
        assertEquals("https://cached.example/m.m4a", item.audioUrl)
    }

    @Test
    fun `deleteCreator cascades to albums and audioItems`() = runTest {
        service.upsertCreator(sampleCreator("c1", "Alice"))
        service.upsertAlbum(sampleAlbum("a1", "c1", "Album1"))
        service.upsertAudioItem(sampleAudioItem("i1", "a1", "c1", "E1"))

        service.deleteCreator("c1")

        assertTrue(service.creators.value.isEmpty())
        assertTrue(service.albums.value.isEmpty())
        assertTrue(service.audioItems.value.isEmpty())
    }

    // ─── 持久化：单线程连续写入 ────────────────────────────────────────────────

    @Test
    fun `100 sequential writes produce final state on disk`() = runTest {
        repeat(100) { i ->
            service.upsertCreator(sampleCreator("c$i", "Name$i"))
        }
        service.flushToDisk()

        // 重新加载一个新 service，验证磁盘内容
        val reload = DatabaseService(tempDir.absolutePath)
        assertEquals(100, reload.creators.value.size)
        assertEquals("Name99", reload.creators.value.last().name)
    }

    // ─── 持久化：多线程并发写入（核心风险点）───────────────────────────────────

    @Test
    fun `1000 concurrent writes from 8 threads do not corrupt file`() = runTest {
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()

        try {
            repeat(8) { threadIdx ->
                pool.execute {
                    try {
                        latch.await()
                        repeat(125) { i ->
                            val id = "t${threadIdx}_$i"
                            runBlocking { service.upsertCreator(sampleCreator(id, "Name$id")) }
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
            latch.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "Threads did not finish in 60s")

            assertTrue(errors.isEmpty(), "Concurrent writes had errors: $errors")

            // 等最后一次防抖写入完成
            service.flushToDisk()

            // 重新加载，验证 JSON 可解析（无损坏）且数量正确
            val reload = DatabaseService(tempDir.absolutePath)
            val loaded = reload.creators.value
            assertEquals(8 * 125, loaded.size, "Should have 1000 creators after concurrent writes")

            // 验证每个 ID 都存在
            for (t in 0 until 8) {
                for (i in 0 until 125) {
                    val id = "t${t}_$i"
                    assertNotNull(loaded.firstOrNull { it.id == id }, "Missing creator $id")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent writes leave no stale tmp files`() = runTest {
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(1)
        try {
            repeat(4) { threadIdx ->
                pool.execute {
                    latch.await()
                    repeat(50) { i ->
                        val id = "t${threadIdx}_$i"
                        runBlocking { service.upsertCreator(sampleCreator(id, "Name$id")) }
                    }
                }
            }
            latch.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

            service.flushToDisk()

            // 验证磁盘上没有遗留 .tmp 文件
            val tmpFiles = tempDir.listFiles { f -> f.name.startsWith("ohv_db.json.tmp.") }
                ?: emptyArray()
            assertTrue(tmpFiles.isEmpty(), "Found ${tmpFiles.size} stale tmp files: ${tmpFiles.map { it.name }}")
        } finally {
            pool.shutdownNow()
        }
    }

    // ─── 损坏恢复 ─────────────────────────────────────────────────────────────

    @Test
    fun `corrupt file is renamed on load and app starts with empty data`() = runTest {
        // 写入一个损坏的 JSON 文件
        val dbFile = File(tempDir, "ohv_db.json")
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("{ this is not valid JSON", Charsets.UTF_8)

        // 创建新 service 应该 rename 损坏文件
        val newService = DatabaseService(tempDir.absolutePath)

        // 数据应该为空（启动时不 crash）
        assertTrue(newService.creators.value.isEmpty())
        assertTrue(newService.albums.value.isEmpty())
        assertTrue(newService.audioItems.value.isEmpty())

        // 损坏文件应该被改名为 .corrupt.<ts>
        val corruptFiles = tempDir.listFiles { f -> f.name.startsWith("ohv_db.json.corrupt.") }
            ?: emptyArray()
        assertEquals(1, corruptFiles.size, "Expected exactly 1 corrupt backup file")
    }

    @Test
    fun `stale tmp files are cleaned on startup`() = runTest {
        // 模拟上次崩溃遗留的 .tmp 文件
        val tmp1 = File(tempDir, "ohv_db.json.tmp.12345.67890")
        val tmp2 = File(tempDir, "ohv_db.json.tmp.12345.99999")
        tmp1.writeText("leftover1")
        tmp2.writeText("leftover2")

        // 创建新 service 应该清理这些 .tmp
        DatabaseService(tempDir.absolutePath)

        val remainingTmp = tempDir.listFiles { f -> f.name.startsWith("ohv_db.json.tmp.") }
            ?: emptyArray()
        assertTrue(remainingTmp.isEmpty(), "Expected tmp files to be cleaned, found ${remainingTmp.size}")
    }

    // ─── clearAll ─────────────────────────────────────────────────────────────

    @Test
    fun `clearAll removes database file`() = runTest {
        service.upsertCreator(sampleCreator("c1", "Alice"))
        service.flushToDisk()
        assertTrue(File(tempDir, "ohv_db.json").exists())

        service.clearAll()

        // 等删除操作完成
        delay(100)
        assertFalse(File(tempDir, "ohv_db.json").exists())
        assertTrue(service.creators.value.isEmpty())
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun sampleCreator(id: String, name: String) = Creator(
        id = id,
        name = name,
        avatarUrl = null,
        urlSlug = "slug-$id",
        doing = null,
        isSelected = true
    )

    private fun sampleAlbum(id: String, creatorId: String, title: String) = Album(
        id = id,
        creatorId = creatorId,
        title = title,
        coverUrl = null,
        audioCount = 1,
        totalDuration = 0.0,
        sortOrder = 0,
        isAccessible = true,
        description = null
    )

    private fun sampleAudioItem(
        id: String,
        title: String,
        audioUrl: String? = null,
        albumId: String = "a1",
        creatorId: String = "c1"
    ): AudioItem {
        // audioUrl 是 @Transient 字段，构造后单独设置
        return AudioItem(
            id = id,
            albumId = albumId,
            creatorId = creatorId,
            title = title,
            coverUrl = null,
            duration = 60.0,
            sortOrder = 0L,
            publishTime = 0L
        ).apply {
            this.audioUrl = audioUrl
        }
    }
}