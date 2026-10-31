package com.saathi.app.core.net

import okhttp3.Interceptor
import okhttp3.Request
import java.security.SecureRandom
import java.util.Base64

/**
 * The install secret: 32 random bytes made on first launch, sent as `X-Saathi-Key` with every backend call and
 * WebSocket handshake (never in a URL, which servers log). The backend keeps only its SHA-256 hash and refuses a
 * device id that comes without it, so knowing a device id is not enough to read that phone's data.
 */
object DeviceAuth {
    const val HEADER = "X-Saathi-Key"

    /** Set once by AppContainer; reads the key from Settings. */
    @Volatile var key: () -> String = { "" }

    fun newKey(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun sign(builder: Request.Builder): Request.Builder = key().let { if (it.isEmpty()) builder else builder.header(HEADER, it) }

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        chain.proceed(if (req.header(HEADER) != null) req else sign(req.newBuilder()).build())
    }
}
