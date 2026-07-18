package com.ohv.shared.diagnostics

import com.ohv.shared.platform.isDebugBuild
import com.ohv.shared.util.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory diagnostics for Debug builds and field-test sessions.
 *
 * The buffer is intentionally bounded and never persists. Callers must not put
 * credentials, cookies, authorization headers, or audio URLs in messages.
 */
data class DebugLogEntry(
    val timestampMs: Long,
    val level: String,
    val category: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

object DebugDiagnostics {
    private const val MAX_ENTRIES = 300
    private const val REDACTED = "<redacted>"

    private val _entries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = _entries.asStateFlow()

    fun log(
        category: String,
        message: String,
        level: String = "INFO",
        details: Map<String, String> = emptyMap()
    ) {
        if (!isDebugBuild) return
        val safeDetails = details.mapValues { (key, value) ->
            if (isSensitiveKey(key)) REDACTED else value.take(240)
        }
        val entry = DebugLogEntry(
            timestampMs = currentTimeMillis(),
            level = level,
            category = category,
            message = message.take(500),
            details = safeDetails
        )
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun snapshot(): List<DebugLogEntry> = if (isDebugBuild) _entries.value else emptyList()

    fun count(): Int = if (isDebugBuild) _entries.value.size else 0

    fun clear() {
        _entries.value = emptyList()
    }

    fun exportText(maxEntries: Int = 200): String {
        if (!isDebugBuild) return ""
        return _entries.value.takeLast(maxEntries.coerceIn(1, MAX_ENTRIES)).joinToString("\n") { entry ->
            val details = entry.details.entries.joinToString(" ") { (key, value) -> "$key=$value" }
            val message = entry.message.replace('\n', ' ')
            "${entry.timestampMs} [${entry.level}] ${entry.category}: $message" +
                if (details.isEmpty()) "" else " ($details)"
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("token") ||
            normalized.contains("password") ||
            normalized.contains("cookie") ||
            normalized.contains("authorization") ||
            normalized.contains("secret") ||
            normalized.contains("audio_url") ||
            normalized == "url"
    }
}
