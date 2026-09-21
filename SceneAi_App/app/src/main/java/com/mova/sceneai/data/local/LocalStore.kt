package com.mova.sceneai.data.local

import android.content.Context
import com.mova.sceneai.data.model.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地记录存储。
 *
 * 刻意不用 Room：数据量小（上限 200 条）、无查询需求，一个 JSON 文件加互斥锁
 * 完全够用，而且能省掉一整套注解处理器与 schema 迁移。**规模到了再换**。
 */
class LocalStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.filesDir, "mova_history.json")
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private val serializer = ListSerializer(HistoryEntry.serializer())

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.value = runCatching {
                if (!file.exists()) emptyList()
                else json.decodeFromString(serializer, file.readText())
            }.getOrElse { emptyList() }
        }
    }

    suspend fun add(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            _entries.value = next
            persist(next)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.value = emptyList()
            runCatching { file.delete() }
        }
    }

    private fun persist(list: List<HistoryEntry>) {
        runCatching { file.writeText(json.encodeToString(serializer, list)) }
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}
