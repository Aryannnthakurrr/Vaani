package com.saathi.app.core.net

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bytes and requests for one kind of traffic since the app started. */
data class TrafficStats(val requests: Long, val bytesUp: Long, val bytesDown: Long)

/**
 * How much data Saathi uses, per feature, since the app started (Settings → Developer → Data used). Counts only:
 * no URLs with ids, no bodies. Keys are short feature names; the backend's `GET /usage` gives the server's view.
 */
object UsageMeter {
    private class Counter {
        val requests = AtomicLong()
        val up = AtomicLong()
        val down = AtomicLong()
    }

    private val counters = ConcurrentHashMap<String, Counter>()

    fun record(key: String, up: Long, down: Long, request: Boolean = true) {
        val c = counters.getOrPut(key) { Counter() }
        if (request) c.requests.incrementAndGet()
        c.up.addAndGet(up.coerceAtLeast(0))
        c.down.addAndGet(down.coerceAtLeast(0))
    }

    fun snapshot(): Map<String, TrafficStats> =
        counters.toSortedMap().mapValues { (_, c) -> TrafficStats(c.requests.get(), c.up.get(), c.down.get()) }

    fun total(): TrafficStats = snapshot().values.fold(TrafficStats(0, 0, 0)) { a, b ->
        TrafficStats(a.requests + b.requests, a.bytesUp + b.bytesUp, a.bytesDown + b.bytesDown)
    }

    fun reset() = counters.clear()

    /** "/care/s_ab12/events" → "care": the first path segment names the feature; ids never become keys. */
    fun keyFor(path: String): String = path.trim('/').substringBefore('/').ifBlank { "root" }

    /** Counts every HTTP call made through the clients that add it. */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val resp: Response = chain.proceed(req)
        val up = (req.body?.contentLength() ?: 0L) + req.headers.byteCount()
        val down = (resp.body?.contentLength()?.takeIf { it >= 0 } ?: 0L) + resp.headers.byteCount()
        record(keyFor(req.url.encodedPath), up, down)
        resp
    }
}
