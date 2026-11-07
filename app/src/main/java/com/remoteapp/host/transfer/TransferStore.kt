package com.remoteapp.host.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@Serializable
data class TransferFile(val id: String, val name: String, val size: Long, val createdAt: Long)

@Serializable
data class TransferList(val files: List<TransferFile>, val maxFileBytes: Long, val maxTotalBytes: Long, val usedBytes: Long)

class TransferException(val code: String) : Exception(code)

/** Only immutable copies in the app's transfer directory are addressable, by random ID. */
class TransferStore(
    private val root: File,
    val maxFileBytes: Long = 100L * 1024 * 1024,
    private val maxTotalBytes: Long = 512L * 1024 * 1024,
    private val maxFiles: Int = 256,
) {
    private val codec = Json { ignoreUnknownKeys = true }
    private val files = linkedMapOf<String, TransferFile>()
    private val pending = mutableMapOf<String, Pending>()

    class Pending internal constructor(val id: String, val name: String, internal val directory: File, internal val output: OutputStream) {
        internal var size = 0L
    }

    init {
        check(root.isDirectory || root.mkdirs()) { "Cannot create transfer directory" }
        root.listFiles().orEmpty().forEach { directory ->
            if (directory.name.startsWith(".pending-")) directory.deleteRecursively()
            else if (validId(directory.name)) {
                runCatching {
                    val entry = codec.decodeFromString<TransferFile>(File(directory, "meta.json").readText())
                    if (entry.id == directory.name && File(directory, "data").isFile && File(directory, "data").length() == entry.size) {
                        validateName(entry.name)
                        files[entry.id] = entry
                    }
                }
            }
        }
    }

    @Synchronized fun list() = TransferList(files.values.sortedByDescending { it.createdAt }, maxFileBytes, maxTotalBytes, files.values.sumOf { it.size })

    @Synchronized fun begin(name: String): Pending {
        validateName(name)
        if (pending.size >= 2) throw TransferException("TRANSFER_BUSY")
        if (files.size + pending.size >= maxFiles) throw TransferException("TRANSFER_LIMIT")
        val id = UUID.randomUUID().toString()
        val directory = File(root, ".pending-$id")
        if (!directory.mkdir()) throw TransferException("STORAGE_UNAVAILABLE")
        return try {
            Pending(id, name, directory, File(directory, "data").outputStream()).also { pending[id] = it }
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    @Synchronized fun append(item: Pending, buffer: ByteArray, count: Int) {
        check(pending[item.id] === item)
        require(count in 0..buffer.size)
        if (item.size + count > maxFileBytes) throw TransferException("FILE_TOO_LARGE")
        if (files.values.sumOf { it.size } + pending.values.sumOf { it.size } + count > maxTotalBytes) throw TransferException("TRANSFER_LIMIT")
        if (root.usableSpace < count + 4L * 1024 * 1024) throw TransferException("STORAGE_FULL")
        item.output.write(buffer, 0, count)
        item.size += count
    }

    @Synchronized fun commit(item: Pending, expectedLength: Long? = null): TransferFile {
        check(pending[item.id] === item)
        if (expectedLength != null && expectedLength != item.size) throw TransferException("INCOMPLETE_UPLOAD")
        item.output.close()
        val entry = TransferFile(item.id, item.name, item.size, System.currentTimeMillis())
        File(item.directory, "meta.json").writeText(codec.encodeToString(entry))
        if (!item.directory.renameTo(File(root, item.id))) throw TransferException("STORAGE_UNAVAILABLE")
        pending.remove(item.id)
        files[item.id] = entry
        return entry
    }

    @Synchronized fun abort(item: Pending) {
        if (pending.remove(item.id) === item) {
            runCatching { item.output.close() }
            item.directory.deleteRecursively()
        }
    }

    fun import(name: String, input: InputStream, checkActive: () -> Unit = {}): TransferFile {
        val item = begin(name)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count == -1) break
                append(item, buffer, count)
            }
            checkActive()
            return commit(item)
        } finally { abort(item) }
    }

    @Synchronized fun entry(id: String): TransferFile {
        if (!validId(id)) throw TransferException("FILE_NOT_FOUND")
        return files[id] ?: throw TransferException("FILE_NOT_FOUND")
    }

    @Synchronized fun open(id: String): InputStream {
        entry(id)
        return File(File(root, id), "data").inputStream()
    }

    @Synchronized fun remove(id: String) {
        entry(id)
        if (!File(root, id).deleteRecursively()) throw TransferException("STORAGE_UNAVAILABLE")
        files.remove(id)
    }

    companion object {
        private fun validId(id: String) = id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        fun validateName(name: String) {
            if (name.isBlank() || name == "." || name == ".." || name.toByteArray(Charsets.UTF_8).size > 240 ||
                name.any { it == '/' || it == '\\' || it.isISOControl() }) throw TransferException("INVALID_FILENAME")
        }
    }
}
