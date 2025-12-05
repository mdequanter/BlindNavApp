package com.example.ipcam
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

class YuvToRgbConverter(@Suppress("unused") private val context: android.content.Context) {

    fun yuvToRgb(image: Image, output: Bitmap) {
        require(image.format == ImageFormat.YUV_420_888)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val width = image.width
        val height = image.height
        val outPixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIndex = yRowStride * y + x * yPixelStride
                val uvRow = (y / 2)
                val uvCol = (x / 2)

                val uIndex = uRowStride * uvRow + uvCol * uPixelStride
                val vIndex = vRowStride * uvRow + uvCol * vPixelStride

                val Y = (yBuf.getUByte(yIndex) - 16).coerceAtLeast(0)
                val U = uBuf.getUByte(uIndex) - 128
                val V = vBuf.getUByte(vIndex) - 128

                val r = (1.164f * Y + 1.596f * V).toInt()
                val g = (1.164f * Y - 0.813f * V - 0.391f * U).toInt()
                val b = (1.164f * Y + 2.018f * U).toInt()

                outPixels[y * width + x] = (0xFF shl 24) or
                        (r.coerceIn(0, 255) shl 16) or
                        (g.coerceIn(0, 255) shl 8) or
                        (b.coerceIn(0, 255))
            }
        }
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
    }

    private fun ByteBuffer.getUByte(index: Int): Int =
        (this.get(index).toInt() and 0xFF)
}
