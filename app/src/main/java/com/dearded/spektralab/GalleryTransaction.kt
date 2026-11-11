package com.dearded.spektralab

enum class GalleryState { UNKNOWN, GONE, PENDING, PUBLISHED }

data class GalleryEntry(val state: GalleryState, val name: String = "")

/** Provider failures must not grant permission to render a second copy. */
internal object GalleryTransaction {
    fun read(query: () -> GalleryEntry?): GalleryEntry =
        runCatching { query() }.getOrNull() ?: GalleryEntry(GalleryState.UNKNOWN)

    fun discardPending(query: () -> GalleryEntry?, delete: () -> Int): GalleryEntry {
        val before = read(query)
        if (before.state != GalleryState.PENDING) return before
        val deleted = runCatching { delete() }.getOrElse { return GalleryEntry(GalleryState.UNKNOWN) }
        if (deleted > 0) return GalleryEntry(GalleryState.GONE)
        // A zero-row conditional delete may have raced publication or removal.
        val after = read(query)
        return when (after.state) {
            GalleryState.GONE, GalleryState.PUBLISHED -> after
            else -> GalleryEntry(GalleryState.UNKNOWN)
        }
    }
}
