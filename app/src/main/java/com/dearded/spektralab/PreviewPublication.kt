package com.dearded.spektralab

internal data class PreviewIntent<T>(val revision: Long, val edit: T)

internal data class PreviewFrame<T>(
    val source: Long,
    val session: Long,
    val intent: PreviewIntent<T>,
    val tier: String,
    val output: String,
    val sequence: Long,
)

/** Identity shared by Main publications and the serial engine's retained display render. */
internal class PreviewPublication<T>(initial: T) {
    private var source = 0L
    private var session = 0L
    private var sequence = 0L
    private var requested = PreviewIntent(0L, initial)
    private var cached: PreviewFrame<T>? = null
    private var shown: PreviewFrame<T>? = null

    @Synchronized fun request(edit: T): PreviewIntent<T> {
        if (requested.edit != edit) requested = PreviewIntent(requested.revision + 1, edit)
        return requested
    }

    @Synchronized fun intent(): PreviewIntent<T> = requested

    @Synchronized fun replaceSource(handle: Long) {
        source++
        session = handle
        cached = null
        shown = null
    }

    @Synchronized fun frame(handle: Long, intent: PreviewIntent<T>, tier: String, output: String) =
        PreviewFrame(source, handle, intent, tier, output, ++sequence)

    @Synchronized fun keep(frame: PreviewFrame<T>) {
        if (belongs(frame)) cached = frame
    }

    @Synchronized fun cachedTier(handle: Long): String? =
        cached?.takeIf { handle == session && belongs(it) }?.tier

    @Synchronized fun current(frame: PreviewFrame<T>): Boolean =
        belongs(frame) && frame.intent == requested

    @Synchronized fun publish(frame: PreviewFrame<T>, allowStaleLive: Boolean = false): Boolean {
        if (!belongs(frame)) return false
        // A live pass may finish mid-drag so the slider keeps making progress.
        // Slow previews and re-packs must still belong to the current edit.
        if (frame.intent != requested && !(allowStaleLive && frame.tier == "live")) return false
        shown?.let { previous ->
            if (frame.sequence < previous.sequence || frame.intent.revision < previous.intent.revision) return false
            if (frame.intent == previous.intent && frame.output == previous.output &&
                quality(frame.tier) < quality(previous.tier)) return false
        }
        shown = frame
        return true
    }

    private fun belongs(frame: PreviewFrame<T>) = frame.source == source && frame.session == session && session != 0L
    private fun quality(tier: String) = when (tier) { "full" -> 2; "preview" -> 1; else -> 0 }
}
