package com.dearded.spektralab

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last [capacity] engine events, for the copyable diagnostics report.
 * Process-wide (it lives in EngineHost), so a report taken after reopening
 * the editor still shows what happened before. Thread-safe.
 */
class EventLog(private val capacity: Int = 40) {
    private val items = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun add(event: String) = synchronized(items) {
        items.addLast("${clock.format(Date())} $event")
        while (items.size > capacity) items.removeFirst()
    }

    fun snapshot(): List<String> = synchronized(items) { items.toList() }
}
