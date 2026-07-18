package com.ohv.shared.db

import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator
import com.ohv.shared.diagnostics.DebugDiagnostics
import com.ohv.shared.platform.getDocumentsDir
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 本地数据库服务（JSON 文件 + 内存缓存）
 *
 * v1.7 改动：增加 callback API（Listener 接口）
 *  - 替代直接暴露 StateFlow 给 Swift（cinterop 难消费）
 *  - iOS 通过 addListener + @Observable 镜像属性变化，无轮询
 *  - Android 仍可使用 internal StateFlow 直接读取
 *  - 内部仍用 StateFlow.update {} 原子 RMW
 */
@OptIn(ExperimentalAtomicApi::class)
class DatabaseService(documentsDir: String = getDocumentsDir()) {

    constructor() : this(getDocumentsDir())

    companion object {
        // 延迟初始化：避免单元测试加载类时触发 getDocumentsDir() 调用
        val shared: DatabaseService by lazy { DatabaseService() }

        private const val SAVE_ACTIVE_MASK = Long.MIN_VALUE
        private const val SAVE_VERSION_MASK = Long.MAX_VALUE
    }

    // ─── 内部 StateFlow（仅 commonMain 内部 / Android 使用）────────────────

    internal val _creators = MutableStateFlow<List<Creator>>(emptyList())
    val creators: StateFlow<List<Creator>> = _creators.asStateFlow()

    internal val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    internal val _audioItems = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioItems: StateFlow<List<AudioItem>> = _audioItems.asStateFlow()

    // ─── Callback API（替代 Listener 接口）───────────────────────────────────

    // 由于 Kotlin/Native cinterop 不支持 abstract class 的 subclass，
    // 改用 function type properties。Swift 可直接传 closure。
    // 限制：仅支持单个回调（当前只有一个 iOS wrapper 使用，足够）

    // 必须 @JvmField / 不带 backing field，否则 Swift 看不到 setter
    var creatorsCallback: DatabaseChangeCallback? = null
        private set
    var albumsCallback: AlbumsChangeCallback? = null
        private set
    var audioItemsCallback: AudioItemsChangeCallback? = null
        private set

    fun setOnCreatorsChangedCallback(callback: DatabaseChangeCallback?) {
        creatorsCallback = callback
        // 立即推送当前状态
        callback?.invoke(_creators.value)
    }

    fun setOnAlbumsChangedCallback(callback: AlbumsChangeCallback?) {
        albumsCallback = callback
        callback?.invoke(_albums.value)
    }

    fun setOnAudioItemsChangedCallback(callback: AudioItemsChangeCallback?) {
        audioItemsCallback = callback
        callback?.invoke(_audioItems.value)
    }

    private fun notifyCreatorsChanged(list: List<Creator>) {
        creatorsCallback?.invoke(list)
    }

    private fun notifyAlbumsChanged(list: List<Album>) {
        albumsCallback?.invoke(list)
    }

    private fun notifyAudioItemsChanged(list: List<AudioItem>) {
        audioItemsCallback?.invoke(list)
    }

    // ─── 文件 IO 与持久化 ─────────────────────────────────────────────────

    private val dbFilePath: String by lazy {
        "$documentsDir/ohv_db.json"
    }

    private val fileAccess: DatabaseFileAccess = DatabaseFileAccess(dbFilePath)

    private val ioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val saveState = AtomicLong(0L)
    private val clearRequested = AtomicBoolean(false)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        load()
    }

    // ─── Creator CRUD ────────────────────────────────────────────────────────

    fun upsertCreator(creator: Creator) {
        _creators.update { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.id == creator.id }
            if (idx >= 0) list[idx] = creator else list.add(creator)
            list
        }
        notifyCreatorsChanged(_creators.value)
        scheduleSave()
    }

    fun upsertCreators(list: List<Creator>) {
        _creators.update { current ->
            val mutable = current.toMutableList()
            for (c in list) {
                val idx = mutable.indexOfFirst { it.id == c.id }
                if (idx >= 0) mutable[idx] = c else mutable.add(c)
            }
            mutable
        }
        notifyCreatorsChanged(_creators.value)
        scheduleSave()
    }

    fun deleteCreator(id: String) {
        _creators.update { it.filter { c -> c.id != id } }
        _albums.update { it.filter { a -> a.creatorId != id } }
        _audioItems.update { it.filter { i -> i.creatorId != id } }
        notifyCreatorsChanged(_creators.value)
        notifyAlbumsChanged(_albums.value)
        notifyAudioItemsChanged(_audioItems.value)
        scheduleSave()
    }

    fun selectedCreators(): List<Creator> = _creators.value.filter { it.isSelected }

    // ─── Album CRUD ───────────────────────────────────────────────────────────

    fun upsertAlbum(album: Album) {
        _albums.update { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.id == album.id }
            if (idx >= 0) list[idx] = album else list.add(album)
            list
        }
        notifyAlbumsChanged(_albums.value)
        scheduleSave()
    }

    fun upsertAlbums(list: List<Album>) {
        _albums.update { current ->
            val mutable = current.toMutableList()
            for (a in list) {
                val idx = mutable.indexOfFirst { it.id == a.id }
                if (idx >= 0) mutable[idx] = a else mutable.add(a)
            }
            mutable
        }
        notifyAlbumsChanged(_albums.value)
        scheduleSave()
    }

    fun albumsForCreator(creatorId: String): List<Album> =
        _albums.value.filter { it.creatorId == creatorId }.sortedBy { it.sortOrder }

    fun albumById(id: String): Album? =
        _albums.value.firstOrNull { it.id == id }

    /** 将所有专辑标记为待检查，供 Debug 和手工重试使用。 */
    fun markAllAlbumsDue() {
        _albums.update { albums ->
            albums.map { it.copy(lastCheckedAt = 0L) }
        }
        notifyAlbumsChanged(_albums.value)
        scheduleSave()
        DebugDiagnostics.log("db", "all albums marked due")
    }

    fun markAlbumUpdatesRead(albumId: String) {
        var changed = false
        _albums.update { albums ->
            albums.map { album ->
                if (album.id == albumId && album.unreadUpdateCount != 0) {
                    changed = true
                    album.copy(unreadUpdateCount = 0)
                } else {
                    album
                }
            }
        }
        if (changed) {
            notifyAlbumsChanged(_albums.value)
            scheduleSave()
        }
    }

    fun markAllAlbumUpdatesRead() {
        var changed = false
        _albums.update { albums ->
            albums.map { album ->
                if (album.unreadUpdateCount != 0) {
                    changed = true
                    album.copy(unreadUpdateCount = 0)
                } else {
                    album
                }
            }
        }
        if (changed) {
            notifyAlbumsChanged(_albums.value)
            scheduleSave()
        }
    }

    // ─── AudioItem CRUD ───────────────────────────────────────────────────────

    fun upsertAudioItem(item: AudioItem) {
        _audioItems.update { current ->
            val list = current.toMutableList()
            // 一个帖子可以同时归属于多个专辑；本地目录项的主键因此是
            // (albumId, id)，而播放进度和缓存仍按帖子 id 共享。
            val idx = list.indexOfFirst { it.id == item.id && it.albumId == item.albumId }
            if (idx >= 0) {
                // 保留已缓存的 audioUrl
                val updated = item.copy().also { it.audioUrl = list[idx].audioUrl }
                list[idx] = updated
            } else {
                list.add(item)
            }
            list
        }
        notifyAudioItemsChanged(_audioItems.value)
        scheduleSave()
    }

    fun upsertAudioItems(items: List<AudioItem>) {
        _audioItems.update { current ->
            val mutable = current.toMutableList()
            for (item in items) {
                val idx = mutable.indexOfFirst { it.id == item.id && it.albumId == item.albumId }
                if (idx >= 0) {
                    val updated = item.copy().also { it.audioUrl = mutable[idx].audioUrl }
                    mutable[idx] = updated
                } else {
                    mutable.add(item)
                }
            }
            mutable
        }
        notifyAudioItemsChanged(_audioItems.value)
        scheduleSave()
    }

    fun audioItemsForAlbum(albumId: String): List<AudioItem> =
        _audioItems.value.filter { it.albumId == albumId }.sortedBy { it.sortOrder }

    fun audioItemById(id: String): AudioItem? =
        _audioItems.value.firstOrNull { it.id == id }

    fun deleteAudioItem(id: String) {
        val removed = _audioItems.value.filter { it.id == id }
        if (removed.isEmpty()) return

        _audioItems.update { items -> items.filterNot { it.id == id } }
        val affectedAlbumIds = removed.map { it.albumId }.toSet()
        val remainingByAlbum = _audioItems.value.groupBy { it.albumId }
        _albums.update { albums ->
            albums.map { album ->
                if (album.id !in affectedAlbumIds) {
                    album
                } else {
                    val remaining = remainingByAlbum[album.id].orEmpty()
                    album.copy(
                        audioCount = remaining.size,
                        totalDuration = remaining.sumOf { it.duration }
                    )
                }
            }
        }
        notifyAudioItemsChanged(_audioItems.value)
        notifyAlbumsChanged(_albums.value)
        scheduleSave()
        DebugDiagnostics.log("db", "audio item deleted", details = mapOf("id" to id))
    }

    // ─── 清空 ─────────────────────────────────────────────────────────────────

    fun clearAll() {
        _creators.value = emptyList()
        _albums.value = emptyList()
        _audioItems.value = emptyList()
        notifyCreatorsChanged(emptyList())
        notifyAlbumsChanged(emptyList())
        notifyAudioItemsChanged(emptyList())
        scheduleSave(removeFile = true)
        try {
            // Keep clearAll observable immediately; the pending worker repeats the
            // deletion if an earlier write was already in progress.
            fileAccess.deleteFile(dbFilePath)
        } catch (_: Exception) {
        }
        DebugDiagnostics.log("db", "database cleared")
    }

    // ─── 持久化（防抖，100ms 内多次调用只写一次）─────────────────────────────

    @Serializable
    private data class DbSnapshot(
        val creators: List<Creator>,
        val albums: List<Album>,
        val audioItems: List<AudioItem>
    )

    private fun scheduleSave(removeFile: Boolean = false) {
        clearRequested.store(removeFile)

        var startWorker = false
        while (true) {
            val state = saveState.load()
            val next = ((state and SAVE_VERSION_MASK) + 1L) or SAVE_ACTIVE_MASK
            if (saveState.compareAndSet(state, next)) {
                startWorker = state and SAVE_ACTIVE_MASK == 0L
                break
            }
        }

        if (startWorker) {
            ioScope.launch {
                delay(100)
                persistPendingChanges()
            }
        }
    }

    private fun persistPendingChanges() {
        while (true) {
            val state = saveState.load()
            if (clearRequested.load()) {
                try {
                    fileAccess.deleteFile(dbFilePath)
                } catch (_: Exception) {
                }
            } else {
                flushToDisk()
            }

            // A mutation during IO increments the version and keeps the worker active.
            // Continue until the version saved above is still current.
            if (saveState.compareAndSet(state, state and SAVE_VERSION_MASK)) return
        }
    }

    internal fun flushToDisk() {
        val snapshot = DbSnapshot(
            creators = _creators.value,
            albums = _albums.value,
            audioItems = _audioItems.value
        )
        try {
            val data = json.encodeToString(snapshot)
            fileAccess.writeAtomic(dbFilePath, data)
        } catch (e: Exception) {
            DebugDiagnostics.log("db", "database flush failed", "ERROR", mapOf(
                "errorType" to e::class.simpleName.orEmpty(),
                "error" to (e.message ?: "unknown")
            ))
        }
    }

    private fun load() {
        try {
            val data = fileAccess.readFile(dbFilePath)
            if (data != null) {
                val snapshot = json.decodeFromString<DbSnapshot>(data)
                _creators.value = snapshot.creators
                _albums.value = snapshot.albums
                _audioItems.value = snapshot.audioItems
                DebugDiagnostics.log("db", "database loaded", details = mapOf(
                    "creators" to snapshot.creators.size.toString(),
                    "albums" to snapshot.albums.size.toString(),
                    "audioItems" to snapshot.audioItems.size.toString()
                ))
            }
        } catch (e: Exception) {
            try {
                fileAccess.renameAsCorrupt(dbFilePath)
            } catch (_: Exception) {
            }
            DebugDiagnostics.log("db", "database was corrupt; moved aside", "ERROR", mapOf(
                "errorType" to e::class.simpleName.orEmpty()
            ))
        }
        try {
            fileAccess.cleanupTempFiles(dbFilePath)
        } catch (_: Exception) {
        }
    }
}
