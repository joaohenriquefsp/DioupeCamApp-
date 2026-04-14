package com.dioupe.camstream

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraCapture(private val onFrame: (ByteArray) -> Unit) {

    private val executor = Executors.newSingleThreadExecutor()

    fun buildUseCase(): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { image ->
            val bitmap = image.toBitmap()
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            onFrame(out.toByteArray())
            image.close()
        }

        return analysis
    }

    fun shutdown() {
        executor.shutdown()
    }
}
