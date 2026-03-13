package com.mobileshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that efficiently renders received bitmaps.
 * Scales the frame to fit the view while maintaining aspect ratio.
 */
class FrameDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var currentBitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun updateFrame(bitmap: Bitmap) {
        val old = currentBitmap
        currentBitmap = bitmap
        updateMatrix(bitmap)
        invalidate()
        // Recycle previous bitmap after replacement
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
    }

    private fun updateMatrix(bitmap: Bitmap) {
        if (width == 0 || height == 0) return

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()

        val scale: Float
        val dx: Float
        val dy: Float

        // Fit inside view, centered
        if (bw / bh > vw / vh) {
            // Frame is wider — fit to width
            scale = vw / bw
            dx = 0f
            dy = (vh - bh * scale) / 2f
        } else {
            // Frame is taller — fit to height
            scale = vh / bh
            dx = (vw - bw * scale) / 2f
            dy = 0f
        }

        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        currentBitmap?.let { updateMatrix(it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = currentBitmap ?: return
        if (bmp.isRecycled) return
        canvas.drawBitmap(bmp, drawMatrix, paint)
    }
}
