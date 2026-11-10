package com.dearded.spektralab

/** Serializes app-wide URI grants with the owners that publish and release them. */
internal object SourceGrantLock {
    val monitor = Any()
}
