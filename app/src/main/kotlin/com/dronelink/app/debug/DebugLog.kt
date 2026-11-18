package com.dronelink.app.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small thread-safe scrolling log for the on-screen debug display (v0.3.0
 * requirement 6). Pure Kotlin — no Android deps — so JVM unit tests can
 * exercise it directly. Exported via [exportText].
 */
object DebugLog {

    data class Entry(
        val atMillis: Long,
        val tag: String,
        val message: String,
    ) {
        val stamp: String get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(atMillis))
    }

    const val CAPACITY = 512

    private val ring = ArrayDeque<Entry>()

    fun log(tag: String, message: String) {
        synchronized(ring) {
            ring.addLast(Entry(System.currentTimeMillis(), tag, message))
            while (ring.size > CAPACITY) ring.removeFirst()
        }
    }

    fun entries(): List<Entry> = synchronized(ring) { ring.toList() }

    fun clear() = synchronized(ring) { ring.clear() }

    /** Stamped, newline-joined export. */
    fun exportText(): String =
        synchronized(ring) { ring.toList() }.joinToString("\n") { "${it.stamp} [${it.tag}] ${it.message}" }
}
