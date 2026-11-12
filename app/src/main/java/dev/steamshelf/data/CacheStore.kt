package dev.steamshelf.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Small JSON-file cache for the library and enrichment data (a few MB even for huge libraries). */
class CacheStore(
    private val dir: File,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = withContext(io) {
        lock.withLock {
            val file = File(dir, name)
            if (!file.exists()) return@withLock null
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
        }
    }

    suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) = withContext(io) {
        val text = json.encodeToString(serializer, value)
        lock.withLock {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name))) {
                File(dir, name).delete()
                tmp.renameTo(File(dir, name))
            }
        }
    }

    suspend fun clear() = withContext(io) {
        lock.withLock { dir.deleteRecursively() }
    }
}
