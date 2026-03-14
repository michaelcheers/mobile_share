package com.mobileshare

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.Surface
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
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    var onQrDecoded: ((String) -> Unit)? = null

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
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
        startBackgroundThread()
        openCamera()
    }

    override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        shutdown()
    }

    fun shutdown() {
        scanning = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("QrScanner").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        try { bgThread?.join() } catch (_: Exception) {}
        bgThread = null
        bgHandler = null
    }

    private fun findBackCamera(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun openCamera() {
        val camId = findBackCamera() ?: return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        val chars = cameraManager.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes
            .filter { it.width <= 1280 && it.height <= 960 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: return

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader!!.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!scanning) { image.close(); return@setOnImageAvailableListener }
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val w = image.width
                val h = image.height
                val buffer = plane.buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                val source = PlanarYUVLuminanceSource(data, rowStride, h, 0, 0, w, h, false)
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decode(bitmap)
                if (result.text.isNotEmpty()) {
                    scanning = false
                    post { onQrDecoded?.invoke(result.text) }
                }
            } catch (_: NotFoundException) {
                // No QR in this frame
            } catch (_: Exception) {
                // Other decode errors
            } finally {
                image.close()
            }
        }, bgHandler)

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview(camera)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, bgHandler)
    }

    @Suppress("DEPRECATION")
    private fun startPreview(camera: CameraDevice) {
        val previewSurface = holder.surface
        val readerSurface = imageReader?.surface ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            addTarget(readerSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        camera.createCaptureSession(
            listOf(previewSurface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, bgHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            bgHandler
        )
    }
}
