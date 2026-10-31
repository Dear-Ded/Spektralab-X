package com.dearded.spektralab

import android.content.Context
import java.io.File

/**
 * The engine reads its resources from a directory (it mmaps the constants
 * blob), so the APK's `assets/engine` tree is copied to internal storage once
 * per build, keyed by `stamp.txt`.
 */
object EngineAssets {
    private const val ROOT = "engine"

    fun install(context: Context): File {
        val target = File(context.filesDir, ROOT)
        val stampFile = File(target, "stamp.txt")
        val shipped = context.assets.open("$ROOT/stamp.txt").use { it.readBytes().decodeToString() }
        if (stampFile.isFile && stampFile.readText() == shipped) return target

        val staging = File(context.filesDir, "$ROOT.partial")
        staging.deleteRecursively()
        copyTree(context, ROOT, staging)
        target.deleteRecursively()
        if (!staging.renameTo(target)) error("cannot install engine resources to $target")
        return target
    }

    private fun copyTree(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyTree(context, "$assetPath/$child", File(dest, child))
    }
}
