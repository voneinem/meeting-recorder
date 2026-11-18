package app.meetingrecorder.storage

import app.meetingrecorder.core.folder.MeetingFolderLayout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The steps of a [FakeFolderFiles] operation at which a failure can be injected. */
enum class FolderStep {
    LIST,
    READ,
    /** Before `<name>.part` is written (nothing changed yet). */
    STAGE_PART,
    /** After the part file is complete, before the old file is deleted (old file and `.part` exist). */
    DELETE_OLD,
    /** After the old file is deleted, before the rename (only `.part` exists). */
    RENAME,
    APPEND,
    DELETE,
}

/** In-memory [FolderFiles] with the staged-replace protocol of the real storage and injectable failures. */
class FakeFolderFiles(initial: Map<String, String> = emptyMap(), private val directReplace: Boolean = false) : FolderFiles {
    private val files = ConcurrentHashMap<String, ByteArray>().apply { initial.forEach { (k, v) -> put(k, v.toByteArray()) } }
    private val failures = ConcurrentHashMap<FolderStep, Int>()
    private val visits = ConcurrentHashMap<FolderStep, Int>()
    private val failOnVisit = ConcurrentHashMap<FolderStep, Int>()

    /** Makes only the [n]th visit (1-based, counted from now on) of [step] throw an [IOException]. */
    fun failOnNth(step: FolderStep, n: Int) {
        visits[step] = 0
        failOnVisit[step] = n
    }

    /** True when an injected failure of [failOnNth] has fired. */
    fun fired(step: FolderStep): Boolean = failOnVisit[step] == -1

    /** Every operation in order, for assertions: `replace:<name>`, `part-written:<name>`, `renamed:<name>`, ... */
    val operations: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Makes the next [times] visits of [step] throw an [IOException]. */
    fun failAt(step: FolderStep, times: Int = 1) {
        failures[step] = times
    }

    fun names(): Set<String> = files.keys.toSet()

    fun text(name: String): String? = files[name]?.toString(Charsets.UTF_8)

    fun putBytes(name: String, bytes: ByteArray) {
        files[name] = bytes
    }

    fun bytes(name: String): ByteArray? = files[name]

    fun put(name: String, text: String) {
        files[name] = text.toByteArray()
    }

    private fun maybeFail(step: FolderStep) {
        failOnVisit[step]?.let { target ->
            if (target > 0) {
                val count = visits.merge(step, 1, Int::plus)!!
                if (count == target) {
                    failOnVisit[step] = -1
                    throw IOException("injected failure at $step (visit $count)")
                }
            }
        }
        while (true) {
            val remaining = failures[step] ?: return
            if (remaining <= 0) return
            if (failures.replace(step, remaining, remaining - 1)) throw IOException("injected failure at $step")
        }
    }

    override fun list(): List<FolderEntry> {
        maybeFail(FolderStep.LIST)
        return files.entries.sortedBy { it.key }.map { FolderEntry(it.key, false, it.value.size.toLong()) }
    }

    override fun readText(name: String): String? {
        maybeFail(FolderStep.READ)
        return text(name)
    }

    override fun replaceText(name: String, text: String) {
        operations += "replace:$name"
        val partName = MeetingFolderLayout.partName(name)
        if (!files.containsKey(name)) {
            maybeFail(FolderStep.STAGE_PART)
            files[name] = text.toByteArray()
            return
        }
        if (directReplace) {
            maybeFail(FolderStep.STAGE_PART)
            files[name] = text.toByteArray()
            operations += "written-in-place:$name"
            return
        }
        maybeFail(FolderStep.STAGE_PART)
        files[partName] = text.toByteArray()
        operations += "part-written:$name"
        maybeFail(FolderStep.DELETE_OLD)
        files.remove(name)
        operations += "old-deleted:$name"
        maybeFail(FolderStep.RENAME)
        files[name] = files.remove(partName)!!
        operations += "renamed:$name"
    }

    override fun appendStore(name: String): FileStore {
        maybeFail(FolderStep.APPEND)
        if (!files.containsKey(name)) throw java.io.FileNotFoundException("$name does not exist")
        return MemoryStore(name, writable = true)
    }

    override fun openReadOnlyStore(name: String): FileStore {
        if (!files.containsKey(name)) throw java.io.FileNotFoundException("$name does not exist")
        return MemoryStore(name, writable = false)
    }

    override fun delete(name: String): Boolean {
        maybeFail(FolderStep.DELETE)
        operations += "delete:$name"
        files.remove(name)
        return true
    }

    private inner class MemoryStore(private val name: String, private val writable: Boolean) : FileStore {
        override fun length(): Long = files.getValue(name).size.toLong()

        override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            val data = files.getValue(name)
            if (offset >= data.size) return -1
            val count = minOf(length.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), buffer, bufferOffset, count)
            return count
        }

        override fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
            if (!writable) throw IOException("read-only")
            val old = files.getValue(name)
            val grown = old.copyOf(maxOf(old.size, (offset + length).toInt()))
            System.arraycopy(buffer, bufferOffset, grown, offset.toInt(), length)
            files[name] = grown
        }

        override fun truncate(length: Long) {
            if (!writable) throw IOException("read-only")
            files[name] = files.getValue(name).copyOf(length.toInt())
        }

        override fun sync() = Unit

        override fun close() = Unit
    }
}
