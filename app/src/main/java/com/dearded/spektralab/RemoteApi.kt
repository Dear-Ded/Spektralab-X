package com.dearded.spektralab

import org.json.JSONArray
import org.json.JSONObject

/*
 * Remote processing, contract only (docs/REMOTE.md, API v1).
 *
 * The alpha ships no implementation: the app has no network permission and
 * nothing leaves the phone. What is fixed here is the shape a later release
 * builds on, so the queue, the job format and the server can be written
 * against one definition:
 *
 *  - [RemoteBackend]: what a paired server can do, one call per endpoint;
 *  - [RemoteJob]: the job body POST /v1/jobs takes, built from a local
 *    [ProcessingJob] so a queued export can go either way unchanged;
 *  - [RemoteBackends.active]: the one switch. It is [RemoteBackends.Disabled]
 *    in this build, and nothing calls into it yet.
 *
 * Changing anything here means changing docs/REMOTE.md in the same commit.
 */

const val REMOTE_API_VERSION = 1

/** GET /v1/info. */
data class RemoteInfo(val name: String, val api: List<Int>, val serverVersion: String, val fingerprint: String)

/** One post-process step the server has registered (never sent by the phone as code). */
data class RemoteHook(
    val name: String,
    /** "before_film" or "after_film". */
    val stage: String,
    val license: String,
    val minVramGb: Int,
    val description: String = "",
)

/** GET /v1/capabilities, the parts the phone decides on. */
data class RemoteCapabilities(
    val hostVersion: String,
    val formats: List<String>,
    val colorSpaces: List<String>,
    val maxUploadBytes: Long,
    /** What the server can apply itself; anything false must be refused or warned about, never dropped. */
    val look: Boolean,
    val frame: Boolean,
    val highlightStops: Boolean,
    val hooks: List<RemoteHook>,
    val queueDepth: Int,
)

enum class RemoteJobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** GET /v1/jobs/{id}. */
data class RemoteJobStatus(
    val jobId: String,
    val state: RemoteJobState,
    val progress: Float,
    val phase: String,
    val etaSeconds: Int,
    /** RFC 9457 `code` on failure: the host's error names plus unauthorized, too_large, busy, version_mismatch, hook_failed. */
    val errorCode: String? = null,
    val resultSha256: String? = null,
    val resultBytes: Long = 0,
)

/** A hook the user picked for this job, with arguments the hook's schema allows. */
data class RemoteHookCall(val name: String, val args: JSONObject = JSONObject())

/**
 * The job body of POST /v1/jobs. Built from the same [ProcessingJob] a local
 * export uses. Phone-GPU limits (working edge, max pixels) and file paths are
 * deliberately not part of it: the server makes its own paths and has no
 * single-buffer cap.
 */
data class RemoteJob(
    val sourceSha256: String,
    val sourceName: String,
    val raw: Boolean,
    val editJson: String,
    val lookJson: String,
    val frameJson: String,
    val wbK: Double,
    val wbTint: Double,
    val highlightStops: Float,
    /** "jpeg" or "tiff16". */
    val format: String,
    /** 0 = native. */
    val longEdge: Int,
    /** "srgb" or "display-p3". */
    val colorSpace: String,
    val quality: Int,
    val hooks: List<RemoteHookCall>,
    val clientJobId: Long,
    val appVersion: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", JSONObject().put("sha256", sourceSha256).put("name", sourceName).put("raw", raw))
        .put("edit", JSONObject(editJson))
        .put("look", lookJson.takeIf { it.isNotEmpty() }?.let(::JSONObject) ?: JSONObject.NULL)
        .put("frame", frameJson.takeIf { it.isNotEmpty() }?.let(::JSONObject) ?: JSONObject.NULL)
        .put("white_balance", if (wbK > 0) JSONObject().put("temperature_k", wbK).put("tint", wbTint) else JSONObject.NULL)
        .put("highlight_stops", highlightStops.toDouble())
        .put("output", JSONObject().put("format", format).put("long_edge", longEdge)
            .put("color_space", colorSpace).put("quality", quality))
        .put("hooks", JSONArray().also { a -> hooks.forEach { a.put(JSONObject().put("name", it.name).put("args", it.args)) } })
        .put("client", JSONObject().put("job_id", clientJobId).put("app_version", appVersion))

    /** What [caps] cannot apply; the caller must refuse or warn, never send silently. */
    fun unsupportedBy(caps: RemoteCapabilities): List<String> = buildList {
        if (lookJson.isNotEmpty() && !caps.look) add("look")
        if (frameJson.isNotEmpty() && !caps.frame) add("frame")
        if (highlightStops > 0f && !caps.highlightStops) add("highlight_stops")
        if (format !in caps.formats) add("format:$format")
        val known = caps.hooks.map { it.name }.toSet()
        hooks.filter { it.name !in known }.forEach { add("hook:${it.name}") }
    }

    companion object {
        /** The local export [job] as a remote job for the uploaded source [sha256]. */
        fun of(job: ProcessingJob, sha256: String, hooks: List<RemoteHookCall> = emptyList()) = RemoteJob(
            sourceSha256 = sha256,
            sourceName = job.name,
            raw = job.raw,
            editJson = job.editJson,
            lookJson = job.lookJson,
            frameJson = job.frameJson,
            wbK = job.wbK,
            wbTint = job.wbTint,
            highlightStops = job.highlightStops,
            format = if (job.format == ExportFormat.TIFF) "tiff16" else "jpeg",
            // Share size is the engine's preview tier; full is the server's native size.
            longEdge = if (job.size == ExportSize.SHARE) 3400 else 0,
            colorSpace = if (job.wideGamut && job.format == ExportFormat.JPEG) "display-p3" else "srgb",
            quality = 95,
            hooks = hooks,
            clientJobId = job.id,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}

/**
 * One paired server. Every call is blocking and runs off the main thread;
 * failures throw [RemoteException] with the server's problem `code`.
 */
interface RemoteBackend {
    val enabled: Boolean

    fun info(): RemoteInfo
    fun capabilities(): RemoteCapabilities

    /** HEAD /v1/sources/{sha256}: bytes the server already has (== size: nothing to send). */
    fun uploadedBytes(sha256: String): Long
    /** PUT /v1/sources/{sha256}, one chunk from [offset]; returns the new offset. */
    fun uploadChunk(sha256: String, total: Long, offset: Long, bytes: ByteArray, length: Int): Long

    /** POST /v1/jobs, idempotent on [RemoteJob.clientJobId]; returns the server's job id. */
    fun submit(job: RemoteJob): String
    fun status(jobId: String): RemoteJobStatus
    fun cancel(jobId: String)
    /** GET /v1/jobs/{id}/result from [offset] (Range), streamed into [sink]. */
    fun download(jobId: String, offset: Long, sink: java.io.OutputStream)
}

class RemoteException(val code: String, message: String) : Exception(message)

object RemoteBackends {
    /** This build: remote processing is planned for the first stable release (docs/ROADMAP.md M4). */
    object Disabled : RemoteBackend {
        override val enabled = false
        private fun off(): Nothing = throw RemoteException("unsupported", tr("远程处理将在正式版提供"))
        override fun info() = off()
        override fun capabilities() = off()
        override fun uploadedBytes(sha256: String) = off()
        override fun uploadChunk(sha256: String, total: Long, offset: Long, bytes: ByteArray, length: Int) = off()
        override fun submit(job: RemoteJob) = off()
        override fun status(jobId: String) = off()
        override fun cancel(jobId: String) = off()
        override fun download(jobId: String, offset: Long, sink: java.io.OutputStream) = off()
    }

    val active: RemoteBackend get() = Disabled
}
