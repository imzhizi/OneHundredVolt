package com.ohv.shared.db

import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator
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
    }

    // ─── 内部 StateFlow（仅 commonMain 内部 / Android 使用）────────────────

    internal val _creators = MutableStateFlow<List<Creator>>(emptyList())
    val creators: StateFlow<List<Creator>> = _creators.asStateFlow()

    internal val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    internal val _audioItems = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioItems: StateFlow<List<AudioItem>> = _audioItems.asStateFlow()

    // ─── Listener 管理 ──────────────────────────────────────────────────────

    /**
     * 变更通知监听器
     *
     * 三种数据各自独立通知，便于监听方只关心自己需要的数据。
     * 回调在 Shared 内部线程触发，调用方需自行切到主线程（@MainActor / Dispatchers.Main）。
     */
    interface Listener {
        fun onCreatorsChanged(creators: List<Creator>)
        fun onAlbumsChanged(albums: List<Album>)
        fun onAudioItemsChanged(items: List<AudioItem>)
    }

    // 用 AtomicReference 持有不可变快照，实现 lock-free 的并发安全
    private val listenersRef = kotlin.concurrent.atomics.AtomicReference<List<Listener>>(emptyList())

    fun addListener(listener: Listener) {
        while (true) {
            val current = listenersRef.load()
            val updated = current + listener
            if (listenersRef.compareAndSet(expectedValue = current, newValue = updated)) {
                // 注册时立即推送当前状态（避免遗漏初始值）
                listener.onCreatorsChanged(_creators.value)
                listener.onAlbumsChanged(_albums.value)
                listener.onAudioItemsChanged(_audioItems.value)
                return
            }
        }
    }

    fun removeListener(listener: Listener) {
        while (true) {
            val current = listenersRef.load()
            val updated = current.filter { it !== listener }
            if (listenersRef.compareAndSet(expectedValue = current, newValue = updated)) return
            if (updated.size == current.size) return  // 没找到，提前返回
        }
    }

    private fun notifyCreatorsChanged(list: List<Creator>) {
        listenersRef.load().forEach { it.onCreatorsChanged(list) }
    }

    private fun notifyAlbumsChanged(list: List<Album>) {
        listenersRef.load().forEach { it.onAlbumsChanged(list) }
    }

    private fun notifyAudioItemsChanged(list: List<AudioItem>) {
        listenersRef.load().forEach { it.onAudioItemsChanged(list) }
    }

    // ─── 文件 IO 与持久化 ─────────────────────────────────────────────────

    private val dbFilePath: String by lazy {
        "$documentsDir/ohv_db.json"
    }

    private val fileAccess: DatabaseFileAccess = DatabaseFileAccess(dbFilePath)

    private val ioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val pendingSave = AtomicBoolean(false)

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

    // ─── AudioItem CRUD ───────────────────────────────────────────────────────

    fun upsertAudioItem(item: AudioItem) {
        _audioItems.update { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.id == item.id }
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
                val idx = mutable.indexOfFirst { it.id == item.id }
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

    // ─── 清空 ─────────────────────────────────────────────────────────────────

    fun clearAll() {
        _creators.value = emptyList()
        _albums.value = emptyList()
        _audioItems.value = emptyList()
        pendingSave.store(false)
        notifyCreatorsChanged(emptyList())
        notifyAlbumsChanged(emptyList())
        notifyAudioItemsChanged(emptyList())
        ioScope.launch {
            try {
                fileAccess.deleteFile(dbFilePath)
            } catch (_: Exception) {
            }
        }
    }

    // ─── 持久化（防抖，100ms 内多次调用只写一次）─────────────────────────────

    @Serializable
    private data class DbSnapshot(
        val creators: List<Creator>,
        val albums: List<Album>,
        val audioItems: List<AudioItem>
    )

    private fun scheduleSave() {
        if (!pendingSave.compareAndSet(expectedValue = false, newValue = true)) return
        ioScope.launch {
            delay(100)
            try {
                flushToDisk()
            } finally {
                pendingSave.store(false)
            }
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
            println("DatabaseService.flushToDisk failed: $e")
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
            }
        } catch (e: Exception) {
            try {
                fileAccess.renameAsCorrupt(dbFilePath)
            } catch (_: Exception) {
            }
        }
        try {
            fileAccess.cleanupTempFiles(dbFilePath)
        } catch (_: Exception) {
        }
    }
}