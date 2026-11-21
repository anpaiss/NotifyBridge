package com.anpaiss.notifybridge.karoo

/**
 * The last messages, for the Messages field and its list (21/11/2026): in memory
 * only, never on file, cleared when the ride ends. Called on the main thread.
 */
object MessageLog {
    const val MAX = 10

    data class Entry(val at: Long, val app: String, val title: String, val text: String)

    private val entries = ArrayDeque<Entry>()
    /** Messages of this ride, also those no longer in the list. */
    var count = 0
        private set
    private val listeners = mutableSetOf<() -> Unit>()

    /** Newest first. */
    fun all(): List<Entry> = entries.toList()

    fun add(e: Entry) {
        entries.addFirst(e)
        while (entries.size > MAX) entries.removeLast()
        count++
        changed()
    }

    fun clear() {
        if (count == 0 && entries.isEmpty()) return
        entries.clear()
        count = 0
        changed()
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    private fun changed() = listeners.toList().forEach { it() }
}
