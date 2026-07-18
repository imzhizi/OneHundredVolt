package com.ohv.shared.sync

import com.ohv.shared.db.DatabaseService
import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class IncrementalUpdateServiceTest {

    private lateinit var tempDir: File
    private lateinit var db: DatabaseService

    @BeforeTest
    fun setUp() {
        DebugCatalogFixtures.clearAll()
        tempDir = createTempDirectory(prefix = "ohv_incremental_test_").toFile()
        db = DatabaseService(tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        DebugCatalogFixtures.clearAll()
        db.clearAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `new item is inserted and repeated check does not increase unread count`() = runTest {
        val original = item("i1", "第一期").apply { audioUrl = "https://cached.example/i1.m4a" }
        val added = item("i2", "第二期")
        db.upsertAlbum(album())
        db.upsertAudioItem(original)

        val service = IncrementalUpdateService(db) { listOf(original, added) }
        val first = service.checkAlbums(listOf("a1"), force = true, nowMs = 1_000L)

        assertEquals(1, first.addedCount)
        assertEquals(0, first.changedCount)
        assertEquals(2, db.audioItemsForAlbum("a1").size)
        assertEquals("https://cached.example/i1.m4a", db.audioItemById("i1")?.audioUrl)
        assertEquals(1, db.albumById("a1")?.unreadUpdateCount)
        assertEquals(1_000L, db.albumById("a1")?.lastCheckedAt)

        val second = service.checkAlbums(listOf("a1"), force = true, nowMs = 2_000L)
        assertEquals(0, second.addedCount)
        assertEquals(0, second.changedCount)
        assertEquals(1, db.albumById("a1")?.unreadUpdateCount)
    }

    @Test
    fun `changed item updates metadata without replacing local cache`() = runTest {
        val local = item("i1", "旧标题").apply { audioUrl = "https://cached.example/i1.m4a" }
        val remote = item("i1", "新标题").copy(duration = 120.0, sortOrder = 3L)
        db.upsertAlbum(album())
        db.upsertAudioItem(local)

        val result = IncrementalUpdateService(db) { listOf(remote) }
            .checkAlbums(listOf("a1"), force = true, nowMs = 3_000L)

        assertEquals(0, result.addedCount)
        assertEquals(1, result.changedCount)
        assertEquals("新标题", db.audioItemById("i1")?.title)
        assertEquals(120.0, db.audioItemById("i1")?.duration)
        assertEquals("https://cached.example/i1.m4a", db.audioItemById("i1")?.audioUrl)
    }

    @Test
    fun `empty response with existing items is rejected and leaves state untouched`() = runTest {
        db.upsertAlbum(album().copy(lastCheckedAt = 500L))
        db.upsertAudioItem(item("i1", "第一期"))

        val result = IncrementalUpdateService(db) { emptyList() }
            .checkAlbums(listOf("a1"), force = true, nowMs = 4_000L)

        assertEquals(1, result.failures.size)
        assertEquals("远端目录为空，疑似响应不完整", result.failures.single().message)
        assertEquals(500L, db.albumById("a1")?.lastCheckedAt)
        assertEquals(1, db.audioItemsForAlbum("a1").size)
    }

    @Test
    fun `duplicate remote ids are rejected`() = runTest {
        db.upsertAlbum(album())
        val duplicate = item("i1", "第一期")

        val result = IncrementalUpdateService(db) { listOf(duplicate, duplicate.copy(title = "重复")) }
            .checkAlbums(listOf("a1"), force = true, nowMs = 5_000L)

        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().message.contains("重复单集"))
        assertNull(db.albumById("a1")?.lastCheckedAt)
    }

    @Test
    fun `remote omission does not delete or hide local item`() = runTest {
        db.upsertAlbum(album())
        db.upsertAudioItems(listOf(item("i1", "第一期"), item("i2", "第二期")))

        val result = IncrementalUpdateService(db) { listOf(item("i2", "第二期")) }
            .checkAlbums(listOf("a1"), force = true, nowMs = 6_000L)

        assertTrue(result.failures.isEmpty())
        assertEquals(2, db.audioItemsForAlbum("a1").size)
        assertEquals(2, db.albumById("a1")?.audioCount)
    }

    @Test
    fun `same post can be stored in multiple album catalogs`() = runTest {
        val firstAlbumItem = item("shared-post", "同一帖子").copy(albumId = "a1")
        val secondAlbumItem = firstAlbumItem.copy(albumId = "a2")
        db.upsertAlbum(album().copy(id = "a1"))
        db.upsertAlbum(album().copy(id = "a2"))

        db.upsertAudioItem(firstAlbumItem)
        db.upsertAudioItem(secondAlbumItem)

        assertEquals(2, db.audioItems.value.size)
        assertEquals(1, db.audioItemsForAlbum("a1").size)
        assertEquals(1, db.audioItemsForAlbum("a2").size)

        db.deleteAudioItem("shared-post")

        assertTrue(db.audioItems.value.isEmpty())
        assertEquals(0, db.albumById("a1")?.audioCount)
        assertEquals(0.0, db.albumById("a1")?.totalDuration)
        assertEquals(0, db.albumById("a2")?.audioCount)
        assertEquals(0.0, db.albumById("a2")?.totalDuration)
    }

    @Test
    fun `recent album is skipped until due and can be forced due`() = runTest {
        db.upsertAlbum(album().copy(lastCheckedAt = 10_000L, unreadUpdateCount = 2))
        val service = IncrementalUpdateService(db) { emptyList() }

        val skipped = service.checkAlbums(listOf("a1"), force = false, nowMs = 10_001L)
        assertTrue(skipped.summaries.single().skipped)
        assertEquals(2, skipped.summaries.single().unreadCount)

        service.markAllAlbumsDue()
        assertTrue(service.albumsDueForCheck(nowMs = 10_001L).any { it.id == "a1" })
    }

    @Test
    fun `debug fixture drives diff and preserves state after simulated failure`() = runTest {
        val original = item("i1", "第一期")
        db.upsertAlbum(album())
        db.upsertAudioItem(original)

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", DebugCatalogFixtures.SCENARIO_NEW, listOf(original))
        )
        val service = IncrementalUpdateService(db) {
            DebugCatalogFixtures.resolve("a1") ?: error("fixture missing")
        }
        val success = service.checkAlbums(listOf("a1"), force = true, nowMs = 11_000L)

        assertEquals(1, success.addedCount)
        assertEquals(2, db.audioItemsForAlbum("a1").size)
        assertEquals(11_000L, db.albumById("a1")?.lastCheckedAt)

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", DebugCatalogFixtures.SCENARIO_ERROR, emptyList())
        )
        val failed = service.checkAlbums(listOf("a1"), force = true, nowMs = 12_000L)

        assertEquals(1, failed.failures.size)
        assertEquals(2, db.audioItemsForAlbum("a1").size)
        assertEquals(11_000L, db.albumById("a1")?.lastCheckedAt)
    }

    private fun album() = Album(
        id = "a1",
        creatorId = "c1",
        title = "测试专辑",
        coverUrl = null,
        description = null,
        audioCount = 1,
        totalDuration = 60.0,
        sortOrder = 0,
        isAccessible = true
    )

    private fun item(id: String, title: String) = AudioItem(
        id = id,
        albumId = "a1",
        creatorId = "c1",
        title = title,
        coverUrl = null,
        duration = 60.0,
        sortOrder = 0L,
        publishTime = 0L
    )
}
