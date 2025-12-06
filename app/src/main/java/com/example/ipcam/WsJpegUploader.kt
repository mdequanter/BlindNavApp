package com.example.ipcam

import android.R
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class WsJpegUploader(
    private val url: String,
    minIntervalMs: Long = 200L,
    headingTolerance_pct: Int = 10,
    selectedModel_txt : String = "unrealsim.pt",
    private val maxFrameBytes: Int = 1024 * 1024,
    // heading callback: (heading in graden, optionele frameId als Long?)
    private val onHeading: ((Double, Long?) -> Unit)? = null,
    // verzonden-callback: (werkelijk verzend-timestamp (elapsedRealtime ms), frameId)
    private val onSent: ((Long, Long) -> Unit)? = null
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming
        .build()

    private var ws: WebSocket? = null
    private val connectingOrOpen = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val latest = AtomicReference<ByteArray?>(null)
    @Volatile private var lastSendSpacingAt = 0L
    private val exec = Executors.newSingleThreadExecutor()

    private val frameCounter = AtomicLong(0L)

    @Volatile private var minIntervalMs: Long = minIntervalMs

    @Volatile private var headingTolerance_pct: Int = headingTolerance_pct

    @Volatile private var selectedModel : String = selectedModel_txt

    fun updateMinIntervalMs(value: Long) {
        if (value >= 0) this.minIntervalMs = value
    }
    fun updateHeadingTolerance(value: Int) {
        if (value >= 0) this.headingTolerance_pct = value
    }
    fun updateModel(value: String) {
        this.selectedModel = value.toString()
        val json = """{
                  "type":"Setting",
                  "selectedModel":${this.selectedModel}
                }""".trimIndent()
        this.sendJson(json)
    }

    fun sendStats(latency: String, latitude: String, longitude: String){
        val json = """{
                  "type":"stats",
                  "latency":${latency},
                  "longitude":${longitude},
                  "latitude":${latitude},
                }""".trimIndent()
        this.sendJson(json)
    }

    private fun ensureConnected() {
        if (connectingOrOpen.get()) return
        connectingOrOpen.set(true)
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("WsJpegUploader", "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Verwachten feedback zoals {"heading": 87.12, "frame_id": 123}
                try {
                    val obj = JSONObject(text)
                    if (obj.has("heading")) {
                        val h = obj.getDouble("heading")
                        val fid = if (obj.has("frame_id")) obj.optLong("frame_id") else null
                        onHeading?.invoke(h, fid)
                    }
                } catch (_: Exception) {
                    // andere berichten (bv. telemetry echo) negeren
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // geen binaire terugweg verwacht
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectingOrOpen.set(false)
                Log.w("WsJpegUploader", "WS failure: ${t.message}")
                exec.execute {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                    ensureConnected()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectingOrOpen.set(false)
                Log.i("WsJpegUploader", "WS closed: $reason")
            }
        })
    }

    /**
     * Zend arbitrary JSON-text (bijv. locatie-telemetrie).
     */
    fun sendJson(json: String) {
        ensureConnected()
        ws?.send(json)
    }

    /**
     * Verzend een JPEG-frame. Stuurt eerst een klein JSON met frame_id & tijdstempel,
     * daarna de binaire JPEG. Roept onSent(sentAt, frameId) zodra het frame echt is verzonden.
     */
    fun trySend(jpeg: ByteArray, latency: String, longitude: String, latitude: String) {
        if (jpeg.isEmpty() || jpeg.size > maxFrameBytes) return
        latest.set(jpeg)

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSendSpacingAt < minIntervalMs) return

        ensureConnected()
        val socket = ws ?: return
        if (!sending.compareAndSet(false, true)) return

        exec.execute {
            try {
                val frame = latest.get() ?: return@execute
                val id = frameCounter.incrementAndGet()

                // 1) meta JSON met frame_id uitsturen
                val metaJson = """{"type":"frame_meta","frame_id":$id,"ts":${System.currentTimeMillis()},"latency_ms":"${latency}","longitude":"${longitude}","latitude":"${latitude}","selectedModel":"${selectedModel}"}"""
                socket.send(metaJson)

                // 2) binaire JPEG uitsturen
                val ok = socket.send(frame.toByteString(0, min(frame.size, maxFrameBytes)))
                if (ok) {
                    val sentAt = android.os.SystemClock.elapsedRealtime()
                    lastSendSpacingAt = sentAt
                    onSent?.invoke(sentAt, id)
                }
            } catch (e: Exception) {
                Log.w("WsJpegUploader", "send error: ${e.message}")
            } finally {
                sending.set(false)
            }
        }
    }

    fun shutdown() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        exec.shutdownNow()
    }
}
