package com.dearded.spektralab

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Private JSON writes, co-created with GPT (OpenAI). Failure leaves the previous file in place. */
internal fun writeAtomicText(file: File, text: String) {
    file.parentFile?.mkdirs()
    val part = File(file.parentFile, file.name + ".part")
    FileOutputStream(part).use { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.fd.sync()
    }
    // Same-directory replacement must succeed atomically; never delete the good file to retry.
    Files.move(part.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}
