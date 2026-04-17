package com.dioupe.camstream

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * Converte frames NV21 para JPEG e entrega ao MjpegServer.
 * Recebe bytes já extraídos via onNv21() — sem conversão interna.
 */
class CameraCapture(private val onJpeg: (ByteArray) -> Unit) {

    fun onNv21(nv21: ByteArray, width: Int, height: Int) {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        onJpeg(out.toByteArray())
    }
}
