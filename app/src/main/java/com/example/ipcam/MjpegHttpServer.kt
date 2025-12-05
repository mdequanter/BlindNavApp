package com.example.ipcam

import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MjpegHttpServer(
    port: Int,
    private val latestJpegProvider: () -> ByteArray?
) : NanoHTTPD(port) {

    private val executor = Executors.newSingleThreadExecutor()

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/video" -> serveMjpegStream()
            "/snapshot.jpg" -> serveSingleJpeg()
            else -> newFixedLengthResponse(
                Response.Status.OK, "text/html",
                "<html><body>" +
                        "<h3>Android IP Camera</h3>" +
                        "<a href='/video'>/video</a> (MJPEG stream)<br/>" +
                        "<a href='/snapshot.jpg'>/snapshot.jpg</a>" +
                        "</body></html>"
            )
        }
    }

    private fun serveSingleJpeg(): Response {
        val bytes = latestJpegProvider() ?: return notReady()
        val bais = ByteArrayInputStream(bytes)
        val resp = newFixedLengthResponse(Response.Status.OK, "image/jpeg", bais, bytes.size.toLong())
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        return resp
    }

    private fun serveMjpegStream(): Response {
        val boundary = "frame"
        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos, 64 * 1024)

        // Background writer that pushes multipart JPEG frames
        executor.execute {
            val out = BufferedOutputStream(pos)
            try {
                while (true) {
                    val bytes = latestJpegProvider()
                    if (bytes != null) {
                        out.write(("--$boundary\r\n").toByteArray())
                        out.write("Content-Type: image/jpeg\r\n".toByteArray())
                        out.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
                        out.write(bytes)
                        out.write("\r\n".toByteArray())
                        out.flush()
                    }
                    TimeUnit.MILLISECONDS.sleep(60) // ~16 fps
                }
            } catch (_: Exception) {
                // client disconnected or server stopping
            } finally {
                try { out.close() } catch (_: Exception) {}
            }
        }

        val resp = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            pis
        )
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        return resp
    }

    private fun notReady(): Response =
        newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Camera initializing...")
}
