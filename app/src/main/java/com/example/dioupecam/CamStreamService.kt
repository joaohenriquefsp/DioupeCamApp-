package com.dioupe.camstream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CamStreamService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var mjpegServer: MjpegServer
    private lateinit var h264Server: H264Server
    private lateinit var cameraCapture: CameraCapture

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CamStreamService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CamStreamService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startAsForeground()

        mjpegServer = MjpegServer().also { it.start() }
        h264Server = H264Server(this, port = 8554)

        cameraCapture = CameraCapture { jpeg ->
            mjpegServer.updateFrame(jpeg)
        }

        val imageAnalysis = cameraCapture.buildUseCase()
        val h264Preview = h264Server.buildPreviewUseCase()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageAnalysis,
                h264Preview
            )
        }, ContextCompat.getMainExecutor(this))

        _isRunning.value = true
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        cameraCapture.shutdown()
        h264Server.stop()
        mjpegServer.stop()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val channelId = "cam_stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Camera Stream", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DioupeCam")
            .setContentText("MJPEG :8080 · H.264 :8554")
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
