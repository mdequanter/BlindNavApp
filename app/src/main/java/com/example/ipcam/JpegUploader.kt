package com.example.ipcam

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock
import okhttp3.Response
import okhttp3.WebSocket

class JpegUploader(
    private val url: String,
    private val minIntervalMs: Long = 200L // ~5 fps
) {
    private val client = OkHttpClient.Builder().build()
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var lastUploadAt = 0L

    fun tryUpload(jpeg: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUploadAt < minIntervalMs) return
        if (!busy.compareAndSet(false, true)) return

        exec.execute {
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
                    .build()
                client.newCall(req).execute().use { /* response body ignoreren */ }
                lastUploadAt = now
            } catch (_: Exception) {
                // optioneel: loggen
            } finally {
                busy.set(false)
            }
        }
    }

    fun shutdown() {
        exec.shutdownNow()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }
}
