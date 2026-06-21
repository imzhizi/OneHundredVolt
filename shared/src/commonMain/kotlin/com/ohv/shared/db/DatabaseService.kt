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
 * 移植自 iOS DatabaseService.swift
 *
 * v1.6 改动：
 * 1. upsert/delete 改用 StateFlow.update {} 保证 read-modify-write 原子性
 * 2. 防抖改用 AtomicBoolean.compareAndSet 保证线程安全
 * 3. 文件写入改 temp + rename 原子写入（POSIX rename 原子，Windows fallback）
 * 4. 加载失败时把损坏文件改名为 .corrupt.<ts> 保留
 * 5. 启动时清理上次崩溃遗留的 .tmp 文件
 * 6. 构造函数接受 documentsDir 参数以便单元测试使用临时目录
 *
 * v1.6.5 改动（iOS KMP 兼容）：
 * - 文件 IO 拆分为 DatabaseFileAccess expect/actual（绕过 java.io.File 在 iOS 不可用）
 */
@OptIn(ExperimentalAtomicApi::class)
class DatabaseService(documentsDir: String = getDocumentsDir()) {

    constructor() : this(getDocumentsDir())

    companion object {
        // 延迟初始化：避免单元测试加载类时触发 getDocumentsDir() 调用
        val shared: DatabaseService by lazy { DatabaseService() }
    }

    // ─── 内存存储（StateFlow 供 UI 观察）────────────────────────────────────

    private val _creators = MutableStateFlow<List<Creator>>(emptyList())
    val creators: StateFlow<List<Creator>> = _creators.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _audioItems = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioItems: StateFlow<List<AudioItem>> = _audioItems.asStateFlow()

    private val dbFilePath: String by lazy {
        "$documentsDir/ohv_db.json"
    }

    private val fileAccess: DatabaseFileAccess = DatabaseFileAccess(dbFilePath)

    private val ioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 防抖：compareAndSet 保证并发场景下只有一个协程进入
    private val pendingSave = AtomicBoolean(false)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        load()
    }

    // ─── Creator CRUD ─────────────────────────────────────────────────────────

    fun upsertCreator(creator: Creator) {
        _creators.update { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.id == creator.id }
            if (idx >= 0) list[idx] = creator else list.add(creator)
            list
        }
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
        scheduleSave()
    }

    fun deleteCreator(id: String) {
        _creators.update { it.filter { c -> c.id != id } }
        _albums.update { it.filter { a -> a.creatorId != id } }
        _audioItems.update { it.filter { i -> i.creatorId != id } }
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

    /**
     * 调度一次磁盘写入。100ms 防抖窗口内多次调用只触发一次。
     *
     * 线程安全：使用 AtomicBoolean.compareAndSet 确保并发场景下
     * 唯一一个协程能成功进入调度逻辑，避免双重写入。
     */
    private fun scheduleSave() {
        if (!pendingSave.compareAndSet(expectedValue = false, newValue = true)) return
        ioScope.launch {
            delay(100)
            try {
                flushToDisk()
            } finally {
                // 写完后再清 pending，允许后续 mutation 重新调度
                pendingSave.store(false)
            }
        }
    }

    /**
     * 立即将内存状态写入磁盘（原子写入）。
     *
     * 调用方负责线程切换（应在 IO 调度器上调用）。
     */
    internal fun flushToDisk() {
        // 在拿锁之外先读 StateFlow 值，避免持锁期间长时间阻塞其他 reader
        val snapshot = DbSnapshot(
            creators = _creators.value,
            albums = _albums.value,
            audioItems = _audioItems.value
        )
        try {
            val data = json.encodeToString(snapshot)
            fileAccess.writeAtomic(dbFilePath, data)
        } catch (e: Exception) {
            // 写入失败不抛异常（用户无感），下次 scheduleSave 会重试
            println("DatabaseService.flushToDisk failed: $e")
        }
    }

    /**
     * 启动时加载磁盘数据。
     *
     * 若文件损坏（JSON 解析失败），把原文件改名为 .corrupt.<ts> 保留，
     * 用户后续可手动恢复。损坏文件不删除，避免数据彻底丢失。
     *
     * 不论加载成功与否，都会清理遗留的 .tmp 文件。
     */
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
            // JSON 损坏：保留原文件供用户手动恢复
            try {
                fileAccess.renameAsCorrupt(dbFilePath)
            } catch (_: Exception) {
            }
        }
        // 无论加载成功与否，都清理遗留 .tmp
        try {
            fileAccess.cleanupTempFiles(dbFilePath)
        } catch (_: Exception) {
        }
    }
}