@file:Suppress("DEPRECATION")
package com.mobileshare

import android.content.Context
import android.hardware.Camera
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class QrScannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Camera.PreviewCallback {

    var onQrDecoded: ((String) -> Unit)? = null

    private var camera: Camera? = null
    private var scanning = true
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ))
    }

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        try {
            camera = Camera.open()?.apply {
                setDisplayOrientation(0) // landscape activity
                parameters = parameters.apply {
                    val focusModes = supportedFocusModes
                    focusMode = when {
                        focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                        focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                            Camera.Parameters.FOCUS_MODE_AUTO
                        else -> focusMode
                    }
                }
                setPreviewDisplay(h)
                setPreviewCallback(this@QrScannerView)
                startPreview()
            }
        } catch (_: Exception) {}
    }

    override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        shutdown()
    }

    fun shutdown() {
        scanning = false
        camera?.apply {
            setPreviewCallback(null)
            stopPreview()
            release()
        }
        camera = null
    }

    override fun onPreviewFrame(data: ByteArray, cam: Camera) {
        if (!scanning) return
        try {
            val size = cam.parameters.previewSize
            val source = PlanarYUVLuminanceSource(
                data, size.width, size.height,
                0, 0, size.width, size.height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(bitmap)
            if (result.text.isNotEmpty()) {
                scanning = false
                post { onQrDecoded?.invoke(result.text) }
            }
        } catch (_: NotFoundException) {
            // No QR in this frame, keep scanning
        } catch (_: Exception) {
            // Other decode errors
        }
    }
}
