package com.ohv.shared.sync

import com.ohv.shared.models.AudioItem
import com.ohv.shared.platform.isDebugBuild
import com.ohv.shared.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Process-local catalog overrides used by the Debug diagnostic panels.
 * The override is never persisted and the normal API remains the fallback.
 */
@OptIn(ExperimentalAtomicApi::class)
object DebugCatalogFixtures {
    const val SCENARIO_NEW = "new"
    const val SCENARIO_CHANGED = "changed"
    const val SCENARIO_EMPTY = "empty"
    const val SCENARIO_DUPLICATE = "duplicate"
    const val SCENARIO_OMIT_LAST = "omit_last"
    const val SCENARIO_ERROR = "error"
    const val SCENARIO_TIMEOUT = "timeout"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val fixtures = AtomicReference<Map<String, FixturePayload>>(emptyMap())

    @Serializable
    private data class FixturePayload(
        val mode: String = "items",
        val items: List<AudioItem> = emptyList(),
        val message: String? = null
    )

    class FixtureError(
        val mode: String,
        override val message: String
    ) : IllegalStateException(message)

    /** Parse and install an object fixture or a raw AudioItem JSON array. */
    fun setJson(albumId: String, fixtureJson: String): String {
        check(isDebugBuild) { "目录 fixture 仅支持 Debug 构建" }
        require(albumId.isNotBlank()) { "专辑 ID 不能为空" }
        require(fixtureJson.isNotBlank()) { "fixture JSON 不能为空" }

        val payload = runCatching {
            json.decodeFromString<FixturePayload>(fixtureJson)
        }.getOrElse {
            FixturePayload(items = json.decodeFromString(fixtureJson))
        }.normalized()

        require(payload.mode in supportedModes) {
            "不支持的 fixture mode: ${payload.mode}"
        }
        updateFixtures { it + (albumId to payload) }
        return "已注入 $albumId：${payload.mode}"
    }

    /** Swift-friendly wrapper: malformed editor input becomes status text, not a crash. */
    fun setJsonSafely(albumId: String, fixtureJson: String): String = try {
        setJson(albumId, fixtureJson)
    } catch (e: Exception) {
        "fixture 应用失败：${e.message ?: "未知错误"}"
    }

    fun templateJson(albumId: String, scenario: String, existingItems: List<AudioItem>): String {
        check(isDebugBuild) { "目录 fixture 仅支持 Debug 构建" }
        require(albumId.isNotBlank()) { "专辑 ID 不能为空" }
        val normalized = existingItems.map { it.copy(albumId = albumId) }
        val payload = when (scenario.lowercase()) {
            SCENARIO_NEW -> FixturePayload(
                items = normalized + newItem(albumId, normalized)
            )
            SCENARIO_CHANGED -> FixturePayload(
                items = if (normalized.isEmpty()) {
                    listOf(newItem(albumId, normalized))
                } else {
                    normalized.toMutableList().apply {
                        this[0] = this[0].copy(title = "${this[0].title} [Debug变更]")
                    }
                }
            )
            SCENARIO_EMPTY -> FixturePayload(mode = SCENARIO_EMPTY)
            SCENARIO_DUPLICATE -> FixturePayload(
                mode = SCENARIO_DUPLICATE,
                items = normalized
            )
            SCENARIO_OMIT_LAST -> FixturePayload(
                mode = SCENARIO_OMIT_LAST,
                items = normalized
            )
            SCENARIO_ERROR -> FixturePayload(
                mode = SCENARIO_ERROR,
                message = "模拟目录请求失败"
            )
            SCENARIO_TIMEOUT -> FixturePayload(
                mode = SCENARIO_TIMEOUT,
                message = "模拟网络超时"
            )
            else -> error("不支持的 fixture scenario: $scenario")
        }
        return json.encodeToString(payload.normalized())
    }

    /** Return null when no override is installed, so callers use the API. */
    fun resolve(albumId: String): List<AudioItem>? {
        if (!isDebugBuild) return null
        val payload = fixtures.load()[albumId] ?: return null
        val items = payload.items.map { it.copy(albumId = albumId) }
        return when (payload.mode) {
            "items" -> items
            SCENARIO_EMPTY -> emptyList()
            SCENARIO_DUPLICATE -> if (items.isEmpty()) emptyList() else items + items.first()
            SCENARIO_OMIT_LAST -> items.dropLast(1)
            SCENARIO_ERROR, SCENARIO_TIMEOUT -> throw FixtureError(
                payload.mode,
                payload.message ?: "模拟目录请求失败"
            )
            else -> error("不支持的 fixture mode: ${payload.mode}")
        }
    }

    fun clearFixture(albumId: String) {
        if (!isDebugBuild) return
        updateFixtures { it - albumId }
    }

    fun clearAll() {
        if (!isDebugBuild) return
        fixtures.store(emptyMap())
    }

    fun hasFixture(albumId: String): Boolean = isDebugBuild && fixtures.load().containsKey(albumId)

    private val supportedModes = setOf(
        "items",
        SCENARIO_EMPTY,
        SCENARIO_DUPLICATE,
        SCENARIO_OMIT_LAST,
        SCENARIO_ERROR,
        SCENARIO_TIMEOUT
    )

    private fun FixturePayload.normalized(): FixturePayload = copy(mode = mode.lowercase())

    private fun newItem(albumId: String, existingItems: List<AudioItem>): AudioItem {
        val base = existingItems.firstOrNull()
        return AudioItem(
            id = "debug-new-$albumId-${currentTimeMillis()}",
            albumId = albumId,
            creatorId = base?.creatorId.orEmpty(),
            title = "Debug 新单集",
            coverUrl = base?.coverUrl,
            duration = base?.duration ?: 60.0,
            sortOrder = (existingItems.maxOfOrNull { it.sortOrder } ?: -1L) + 1L,
            publishTime = currentTimeMillis()
        )
    }

    private inline fun updateFixtures(transform: (Map<String, FixturePayload>) -> Map<String, FixturePayload>) {
        while (true) {
            val current = fixtures.load()
            if (fixtures.compareAndSet(current, transform(current))) return
        }
    }
}
