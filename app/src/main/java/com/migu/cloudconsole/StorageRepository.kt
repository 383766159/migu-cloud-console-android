package com.migu.cloudconsole

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StorageRepository(context: Context) {
    private val rootDir = File(context.filesDir, "automation").apply { mkdirs() }
    private val stateFile = File(rootDir, "state.json")
    private val systemLogFile = File(rootDir, "system-logs.ndjson")
    private val cloudLogFile = File(rootDir, "cloud-logs.ndjson")
    private val gson = Gson()

    suspend fun readState(): AppState? = withContext(Dispatchers.IO) {
        if (!stateFile.exists()) return@withContext null
        runCatching { gson.fromJson(stateFile.readText(Charsets.UTF_8), AppState::class.java) }.getOrNull()
    }

    suspend fun writeState(state: AppState) = withContext(Dispatchers.IO) {
        stateFile.writeText(gson.toJson(state), Charsets.UTF_8)
    }

    suspend fun appendLog(kind: LogKind, entry: LogEntry) = withContext(Dispatchers.IO) {
        val target = if (kind == LogKind.SYSTEM) systemLogFile else cloudLogFile
        target.appendText(gson.toJson(entry) + "\n", Charsets.UTF_8)
    }

    suspend fun readLogs(kind: LogKind, limit: Int): List<LogEntry> = withContext(Dispatchers.IO) {
        val target = if (kind == LogKind.SYSTEM) systemLogFile else cloudLogFile
        if (!target.exists()) return@withContext emptyList()
        val type = object : TypeToken<LogEntry>() {}.type
        val lines = target.readLines(Charsets.UTF_8).takeLast(limit)
        lines.asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { gson.fromJson<LogEntry>(it, type) }.getOrNull() }
            .toList()
    }
}

enum class LogKind {
    SYSTEM,
    CLOUD,
}
