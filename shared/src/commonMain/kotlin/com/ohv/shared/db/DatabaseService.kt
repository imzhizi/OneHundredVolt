package com.ohv.shared.db

import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator
import com.ohv.shared.platform.getDocumentsDir
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地数据库服务（JSON 文件 + 内存缓存）
 * 移植自 iOS DatabaseService.swift
 * @Observable → StateFlow
 * DispatchQueue → Coroutines IO dispatcher
 */
class DatabaseService {

    companion object {
        val shared = DatabaseService()
    }

    // ─── 内存存储（StateFlow 供 UI 观察）────────────────────────────────────

    private val _creators = MutableStateFlow<List<Creator>>(emptyList())
    val creators: StateFlow<List<Creator>> = _creators.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _audioItems = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioItems: StateFlow<List<AudioItem>> = _audioItems.asStateFlow()

    private val dbFilePath: String by lazy {
        "${getDocumentsDir()}/ohv_db.json"
    }

    private val ioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pendingSave = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        load()
    }

    // ─── Creator CRUD ─────────────────────────────────────────────────────────

    fun upsertCreator(creator: Creator) {
        val list = _creators.value.toMutableList()
        val idx = list.indexOfFirst { it.id == creator.id }
        if (idx >= 0) list[idx] = creator else list.add(creator)
        _creators.value = list
        scheduleSave()
    }

    fun upsertCreators(list: List<Creator>) {
        val current = _creators.value.toMutableList()
        for (c in list) {
            val idx = current.indexOfFirst { it.id == c.id }
            if (idx >= 0) current[idx] = c else current.add(c)
        }
        _creators.value = current
        scheduleSave()
    }

    fun deleteCreator(id: String) {
        _creators.value = _creators.value.filter { it.id != id }
        _albums.value = _albums.value.filter { it.creatorId != id }
        _audioItems.value = _audioItems.value.filter { it.creatorId != id }
        scheduleSave()
    }

    fun selectedCreators(): List<Creator> = _creators.value.filter { it.isSelected }

    // ─── Album CRUD ───────────────────────────────────────────────────────────

    fun upsertAlbum(album: Album) {
        val list = _albums.value.toMutableList()
        val idx = list.indexOfFirst { it.id == album.id }
        if (idx >= 0) list[idx] = album else list.add(album)
        _albums.value = list
        scheduleSave()
    }

    fun upsertAlbums(list: List<Album>) {
        val current = _albums.value.toMutableList()
        for (a in list) {
            val idx = current.indexOfFirst { it.id == a.id }
            if (idx >= 0) current[idx] = a else current.add(a)
        }
        _albums.value = current
        scheduleSave()
    }

    fun albumsForCreator(creatorId: String): List<Album> =
        _albums.value.filter { it.creatorId == creatorId }.sortedBy { it.sortOrder }

    // ─── AudioItem CRUD ───────────────────────────────────────────────────────

    fun upsertAudioItem(item: AudioItem) {
        val list = _audioItems.value.toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            // 保留已缓存的 audioUrl
            val updated = item.copy().also { it.audioUrl = list[idx].audioUrl }
            list[idx] = updated
        } else {
            list.add(item)
        }
        _audioItems.value = list
        scheduleSave()
    }

    fun upsertAudioItems(items: List<AudioItem>) {
        val current = _audioItems.value.toMutableList()
        for (item in items) {
            val idx = current.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                val updated = item.copy().also { it.audioUrl = current[idx].audioUrl }
                current[idx] = updated
            } else {
                current.add(item)
            }
        }
        _audioItems.value = current
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
        pendingSave = false
        ioScope.launch {
            try {
                java.io.File(dbFilePath).delete()
            } catch (_: Exception) {}
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
        if (pendingSave) return
        pendingSave = true
        ioScope.launch {
            delay(100)
            flushToDisk()
        }
    }

    private fun flushToDisk() {
        pendingSave = false
        val snapshot = DbSnapshot(
            creators = _creators.value,
            albums = _albums.value,
            audioItems = _audioItems.value
        )
        try {
            val data = json.encodeToString(snapshot)
            writeFile(dbFilePath, data)
        } catch (_: Exception) {}
    }

    private fun load() {
        try {
            val data = readFile(dbFilePath) ?: return
            val snapshot = json.decodeFromString<DbSnapshot>(data)
            _creators.value = snapshot.creators
            _albums.value = snapshot.albums
            _audioItems.value = snapshot.audioItems
        } catch (_: Exception) {}
    }

    // ─── 文件 IO（纯 Kotlin，跨平台）─────────────────────────────────────────

    private fun writeFile(path: String, content: String) {
        val file = java.io.File(path)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun readFile(path: String): String? {
        val file = java.io.File(path)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }
}
