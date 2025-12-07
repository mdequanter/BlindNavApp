package com.example.ipcam

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class ArrowOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 20f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL

    }

    @Volatile private var headingDeg: Float = 90f // 0°=rechts, 90°=omhoog (jouw conventie)

    fun setHeading(deg: Double) {
        headingDeg = deg.toFloat()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Start onderaan in het midden (wil je midden-midden? zet startY = h/2f)
        val startX = w / 2f
        val startY = h * 0.95f  // <-- correct (geen deling!)

        // Pijllengte
        val extra = linePaint.strokeWidth / 2f
        val L = (min(w, h) * 0.35f) + extra

        // Jouw heading: 0°=rechts, 90°=omhoog; Y naar beneden -> dy negatief bij omhoog
        val rad = Math.toRadians(headingDeg.toDouble())
        val dx = (cos(rad) * L).toFloat()
        val dy = (-sin(rad) * L).toFloat()

        val endX = startX + dx
        val endY = startY + dy

        // 1) Lijn tekenen
        canvas.drawLine(startX, startY, endX, endY, linePaint)

        // 2) Schermhoek van de lijn berekenen (in graden, 0°=rechts, 90°=omlaag)
        val angleDegScreen = Math.toDegrees(atan2((endY - startY), (endX - startX)).toDouble()).toFloat()

        // 3) Pijlkop roteren volgens die schermhoek
        drawArrowHead(canvas, endX, endY, angleDegScreen)
    }

    private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, angleDegScreen: Float) {
        val size = min(width, height) * 0.05f
        val half = size / 2f

        // Lokale driehoek: TIP op (0,0), wijzend naar +X (rechts)
        val path = Path().apply {
            moveTo(0f, 0f)          // tip
            lineTo(-size, -half)    // achter-boven
            lineTo(-size, +half)    // achter-onder
            close()
        }

        val m = Matrix().apply {
            // Roteren met de **schermhoek** van de lijn (atan2 boven)
            postRotate(angleDegScreen)   // pivot op (0,0) => tip blijft op origine
            postTranslate(tipX, tipY)    // tip naar het lijn-eindpunt
        }

        path.transform(m)
        canvas.drawPath(path, headPaint)
    }
}
