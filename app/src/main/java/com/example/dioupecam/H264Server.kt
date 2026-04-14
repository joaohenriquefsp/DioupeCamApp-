package com.dioupe.camstream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class H264Server(
    private val context: Context,
    val port: Int = 8554
) {
    private var encoder: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val outputThread = AtomicReference<Thread?>(null)
    private val acceptThread = AtomicReference<Thread?>(null)

    // SPS+PPS cached to send to clients that connect mid-stream
    @Volatile private var configData: ByteArray? = null

    fun buildPreviewUseCase(): Preview {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request ->
            val width = request.resolution.width
            val height = request.resolution.height

            val enc = createEncoder(width, height)
            val surface = enc.createInputSurface()
            enc.start()
            encoder = enc

            request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }

            val thread = Thread { readEncoderOutput(enc) }.also {
                it.isDaemon = true
                it.name = "H264-output"
                it.start()
            }
            outputThread.set(thread)

            startAcceptLoop()
        }
        return preview
    }

    private fun createEncoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun readEncoderOutput(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!Thread.interrupted()) {
            val idx = enc.dequeueOutputBuffer(info, 10_000)
            if (idx < 0) continue

            val buf = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val data = ByteArray(info.size)
                buf.get(data)

                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (isConfig) {
                    configData = data
                }

                broadcast(data)
            }
            enc.releaseOutputBuffer(idx, false)

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    private fun broadcast(data: ByteArray) {
        val dead = mutableListOf<OutputStream>()
        clients.forEach { out ->
            try {
                out.write(data)
                out.flush()
            } catch (_: IOException) {
                dead.add(out)
            }
        }
        clients.removeAll(dead.toSet())
    }

    private fun startAcceptLoop() {
        if (serverSocket != null) return
        val server = ServerSocket(port)
        serverSocket = server

        val thread = Thread {
            while (!Thread.interrupted()) {
                try {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    val out = socket.getOutputStream()
                    // Send cached SPS/PPS so decoder can start immediately
                    configData?.let { out.write(it); out.flush() }
                    clients.add(out)
                } catch (_: IOException) {
                    break
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "H264-accept"
            it.start()
        }
        acceptThread.set(thread)
    }

    fun stop() {
        outputThread.get()?.interrupt()
        acceptThread.get()?.interrupt()
        serverSocket?.close()
        serverSocket = null
        clients.clear()
        configData = null
        encoder?.stop()
        encoder?.release()
        encoder = null
    }
}
