package com.dioupe.camstream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

class CamStreamService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var mjpegServer: MjpegServer
    private lateinit var h264Encoder: H264Encoder
    private lateinit var cameraCapture: CameraCapture

    private val extractExecutor = Executors.newSingleThreadExecutor()
    private val mjpegExecutor   = Executors.newSingleThreadExecutor()
    private val h264Executor    = Executors.newSingleThreadExecutor()

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        var uiSurfaceProvider: Preview.SurfaceProvider? = null

        /** null = DEFAULT_BACK_CAMERA (câmera traseira padrão do sistema) */
        var selectedCameraId: String? = null
        var targetFps: Int = 30

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CamStreamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CamStreamService::class.java))
        }

        /**
         * Atualiza câmera e FPS. Se o stream estiver rodando, reinicia automaticamente.
         * Aguarda 600ms para o service anterior terminar antes de subir o novo.
         */
        fun restart(context: Context, cameraId: String?, fps: Int) {
            selectedCameraId = cameraId
            targetFps = fps
            if (_isRunning.value) {
                stop(context)
                Handler(Looper.getMainLooper()).postDelayed({ start(context) }, 600)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startAsForeground()

        mjpegServer   = MjpegServer().also { it.start() }
        h264Encoder   = H264Encoder(port = 8554, fps = targetFps)
        cameraCapture = CameraCapture(onJpeg = { jpeg -> mjpegServer.updateFrame(jpeg) })

        // Configura resolução + FPS via Camera2Interop
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        // AE_TARGET_FPS_RANGE força o hardware a operar na faixa escolhida
        Camera2Interop.Extender(analysisBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(targetFps, targetFps)
            )

        val imageAnalysis = analysisBuilder.build().also { analysis ->
            analysis.setAnalyzer(extractExecutor) { imageProxy ->
                val w  = imageProxy.width
                val h  = imageProxy.height
                val ts = imageProxy.imageInfo.timestamp
                val nv12 = imageProxy.toNV12()
                val nv21 = imageProxy.toNV21()
                imageProxy.close()

                h264Executor.execute  { h264Encoder.onNv12(nv12, w, h, ts) }
                mjpegExecutor.execute { cameraCapture.onNv21(nv21, w, h) }
            }
        }

        val uiPreview = Preview.Builder().build().also { preview ->
            uiSurfaceProvider?.let { preview.setSurfaceProvider(it) }
        }

        val cameraSelector = buildCameraSelector(selectedCameraId)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, imageAnalysis, uiPreview)
        }, ContextCompat.getMainExecutor(this))

        _isRunning.value = true
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        extractExecutor.shutdown()
        mjpegExecutor.shutdown()
        h264Executor.shutdown()
        h264Encoder.stop()
        mjpegServer.stop()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val channelId = "cam_stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Camera Stream", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DioupeCam")
            .setContentText("${targetFps}fps · H.264 :8554 · MJPEG :8080")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }
}

/**
 * Constrói um CameraSelector para um ID específico de câmera (Camera2Interop).
 * Se cameraId for null, usa DEFAULT_BACK_CAMERA.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun buildCameraSelector(cameraId: String?): CameraSelector {
    if (cameraId == null) return CameraSelector.DEFAULT_BACK_CAMERA
    return CameraSelector.Builder()
        .addCameraFilter { infos ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
        }
        .build()
}

// ---------------------------------------------------------------------------
// Extração YUV
// ---------------------------------------------------------------------------

private fun ImageProxy.toNV12(): ByteArray {
    val w = width; val h = height
    val out = ByteArray(w * h * 3 / 2)
    var pos = 0

    val yBuf = planes[0].buffer; val yStride = planes[0].rowStride
    for (row in 0 until h) {
        yBuf.position(row * yStride)
        yBuf.get(out, pos, w); pos += w
    }

    val uvBuf    = planes[1].buffer
    val uvStride = planes[1].rowStride
    if (planes[1].pixelStride == 2) {
        for (row in 0 until h / 2) {
            val start = row * uvStride
            if (start + w > uvBuf.limit()) break
            uvBuf.position(start); uvBuf.get(out, pos, w); pos += w
        }
    } else {
        val vBuf = planes[2].buffer; val vStride = planes[2].rowStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[pos++] = uvBuf.get(row * uvStride + col)
                out[pos++] = vBuf.get(row * vStride + col)
            }
        }
    }
    return out
}

private fun ImageProxy.toNV21(): ByteArray {
    val w = width; val h = height
    val out = ByteArray(w * h * 3 / 2)
    var pos = 0

    val yBuf = planes[0].buffer; val yStride = planes[0].rowStride
    for (row in 0 until h) {
        yBuf.position(row * yStride)
        yBuf.get(out, pos, w); pos += w
    }

    val vBuf    = planes[2].buffer
    val vStride = planes[2].rowStride
    if (planes[2].pixelStride == 2) {
        for (row in 0 until h / 2) {
            val start = row * vStride
            if (start + w > vBuf.limit()) break
            vBuf.position(start); vBuf.get(out, pos, w); pos += w
        }
    } else {
        val uBuf = planes[1].buffer; val uStride = planes[1].rowStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[pos++] = vBuf.get(row * vStride + col)
                out[pos++] = uBuf.get(row * uStride + col)
            }
        }
    }
    return out
}
