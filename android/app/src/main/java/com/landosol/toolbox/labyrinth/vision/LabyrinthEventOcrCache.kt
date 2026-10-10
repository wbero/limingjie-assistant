package com.landosol.toolbox.labyrinth.vision

/** Called under the resolver's lock. Request identity is separate from visual similarity. */
internal class LabyrinthEventOcrCache {
    data class Key(val fingerprint: Long, val rect: EntryPixelRect)
    private data class Request(val id: Long, val key: Key, val startedAt: Long)
    private data class Result(val request: Request, val text: String, val completedAt: Long)
    private data class Completion(val id: Long, val durationMillis: Long, val textLength: Int, val status: String)
    data class Lookup(val text: String?, val diagnostics: LabyrinthEventOcrCacheDiagnostics)

    private var sequence = 0L
    private var pending: Request? = null
    private var cached: Result? = null
    private var lastCompletion: Completion? = null

    fun read(key: Key, now: Long): String? = lookup(key, now).text

    fun lookup(key: Key, now: Long): Lookup {
        val result = cached
        val age = result?.let { now - it.completedAt }
        val distance = result?.let { java.lang.Long.bitCount(it.request.key.fingerprint xor key.fingerprint) }
        val status = when {
            result == null -> "NO_CACHED_TEXT"
            requireNotNull(age) < 0 -> "CLOCK_ROLLBACK"
            age >= CACHE_LIFETIME_MILLIS -> "EXPIRED"
            result.request.key.rect != key.rect -> "CROP_CHANGED"
            requireNotNull(distance) > 8 -> "FINGERPRINT_CHANGED"
            else -> "HIT"
        }
        return Lookup(result?.text?.takeIf { status == "HIT" }, LabyrinthEventOcrCacheDiagnostics(
            status = status,
            ageMillis = age,
            fingerprintDistance = distance,
            cachedCropRect = result?.request?.key?.rect,
            cachedRequestId = result?.request?.id,
            pendingRequestId = pending?.id,
            pendingAgeMillis = pending?.let { now - it.startedAt },
            lastCompletedRequestId = lastCompletion?.id,
            lastRequestDurationMillis = lastCompletion?.durationMillis,
            lastResultTextLength = lastCompletion?.textLength,
            lastResultStatus = lastCompletion?.status,
            cacheLifetimeMillis = CACHE_LIFETIME_MILLIS,
        ))
    }

    fun begin(key: Key, now: Long): Long? {
        if (pending?.let { now - it.startedAt in 0 until REQUEST_TIMEOUT_MILLIS } == true) return null
        return (++sequence).also { pending = Request(it, key, now) }
    }

    fun complete(id: Long, text: String?, now: Long): Boolean {
        val request = pending?.takeIf { it.id == id } ?: return false
        pending = null
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        lastCompletion = Completion(id, (now - request.startedAt).coerceAtLeast(0L), normalized?.length ?: 0,
            when {
                text == null -> "FAILED_OR_UNAVAILABLE"
                normalized == null -> "EMPTY_TEXT"
                else -> "TEXT"
            },
        )
        cached = normalized?.let { Result(request, it, now) }
        return true
    }

    fun clear() {
        pending = null
        cached = null
        lastCompletion = null
    }

    private companion object {
        const val CACHE_LIFETIME_MILLIS = 5_000L
        const val REQUEST_TIMEOUT_MILLIS = 5_000L
    }
}
