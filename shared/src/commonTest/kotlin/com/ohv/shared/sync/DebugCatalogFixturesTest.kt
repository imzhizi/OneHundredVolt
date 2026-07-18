package com.ohv.shared.sync

import com.ohv.shared.models.AudioItem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DebugCatalogFixturesTest {
    @BeforeTest
    fun setUp() {
        DebugCatalogFixtures.clearAll()
    }

    @AfterTest
    fun tearDown() {
        DebugCatalogFixtures.clearAll()
    }

    @Test
    fun `template can inject a new item and preserve existing catalog`() {
        val existing = item("i1", "第一期")
        val json = DebugCatalogFixtures.templateJson("a1", "new", listOf(existing))

        assertContains(json, "debug-new")
        assertContains(DebugCatalogFixtures.setJson("a1", json), "items")
        val resolved = DebugCatalogFixtures.resolve("a1") ?: error("fixture missing")

        assertEquals(2, resolved.size)
        assertEquals("i1", resolved.first().id)
        assertEquals("a1", resolved.last().albumId)
    }

    @Test
    fun `failure modes are resolved without touching network`() {
        val existing = listOf(item("i1", "第一期"), item("i2", "第二期"))

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", "changed", existing)
        )
        assertEquals("第一期 [Debug变更]", DebugCatalogFixtures.resolve("a1")?.first()?.title)

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", "empty", existing)
        )
        assertEquals(0, DebugCatalogFixtures.resolve("a1")?.size)

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", "duplicate", existing)
        )
        assertEquals(3, DebugCatalogFixtures.resolve("a1")?.size)

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", "error", existing)
        )
        assertFailsWith<DebugCatalogFixtures.FixtureError> {
            DebugCatalogFixtures.resolve("a1")
        }

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", "omit_last", existing)
        )
        assertEquals(listOf("i1"), DebugCatalogFixtures.resolve("a1")?.map { it.id })

        DebugCatalogFixtures.setJson(
            "a1",
            DebugCatalogFixtures.templateJson("a1", "timeout", existing)
        )
        val timeout = assertFailsWith<DebugCatalogFixtures.FixtureError> {
            DebugCatalogFixtures.resolve("a1")
        }
        assertEquals(DebugCatalogFixtures.SCENARIO_TIMEOUT, timeout.mode)
    }

    @Test
    fun `clear removes only the selected album override`() {
        val json = DebugCatalogFixtures.templateJson("a1", "empty", emptyList())
        DebugCatalogFixtures.setJson("a1", json)
        DebugCatalogFixtures.setJson("a2", json.replace("a1", "a2"))

        DebugCatalogFixtures.clearFixture("a1")

        assertNull(DebugCatalogFixtures.resolve("a1"))
        assertEquals(0, DebugCatalogFixtures.resolve("a2")?.size)
    }

    @Test
    fun `invalid editor input is reported and does not install override`() {
        val result = DebugCatalogFixtures.setJsonSafely("a1", "not-json")

        assertContains(result, "fixture 应用失败")
        assertNull(DebugCatalogFixtures.resolve("a1"))
    }

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
