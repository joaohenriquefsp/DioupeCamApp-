package com.dioupe.camstream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "H264Encoder"

/**
 * Encoder H.264 em modo buffer (NV12 → MediaCodec → Annex B → TCP).
 * Recebe frames já extraídos como ByteArray via onNv12() — sem conversão interna.
 * O servidor TCP sobe no construtor; o encoder MediaCodec inicia no primeiro frame.
 */
class H264Encoder(val port: Int = 8554, val fps: Int = 30) {

    private var encoder: MediaCodec? = null
    private var serverSocket: ServerSocket? = null

    private data class Client(val socket: Socket, val out: OutputStream)
    private val clients = CopyOnWriteArrayList<Client>()

    private val outputThread = AtomicReference<Thread?>(null)
    private val acceptThread = AtomicReference<Thread?>(null)

    @Volatile private var running = true
    @Volatile private var encoderReady = false
    @Volatile private var configData: ByteArray? = null

    init {
        startAcceptLoop()
    }

    /** Recebe um frame NV12 já extraído. Chamado pelo executor dedicado do CamStreamService. */
    fun onNv12(nv12: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!running) return

        if (!encoderReady) {
            startEncoder(width, height)
            encoderReady = true
        }

        val enc = encoder ?: return
        val inputIdx = enc.dequeueInputBuffer(10_000) // 10ms — dá tempo ao encoder
        if (inputIdx < 0) return

        val buf = enc.getInputBuffer(inputIdx) ?: return
        buf.clear()
        buf.put(nv12)

        enc.queueInputBuffer(inputIdx, 0, nv12.size, timestampNs / 1000L, 0)
    }

    private fun startEncoder(width: Int, height: Int) {
        val bitrate = if (fps >= 60) 12_000_000 else 8_000_000
        Log.i(TAG, "encoder iniciado ${width}x${height} @ $fps fps / ${bitrate / 1_000_000} Mbps")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        encoder = enc

        Thread { readEncoderOutput(enc) }.also {
            it.isDaemon = true
            it.name = "H264-output"
            it.start()
            outputThread.set(it)
        }
    }

    private fun readEncoderOutput(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = enc.dequeueOutputBuffer(info, 10_000)
            if (idx < 0) continue
            val buf = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val data = ByteArray(info.size)
                buf.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    configData = data
                }
                broadcast(data)
            }
            enc.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    private fun broadcast(data: ByteArray) {
        val dead = mutableListOf<Client>()
        clients.forEach { client ->
            try { client.out.write(data); client.out.flush() }
            catch (_: IOException) { dead.add(client) }
        }
        dead.forEach { client ->
            runCatching { client.socket.close() }
            clients.remove(client)
        }
    }

    private fun startAcceptLoop() {
        val server = ServerSocket(port)
        serverSocket = server
        Log.i(TAG, "TCP server ouvindo na porta $port")

        Thread {
            while (!Thread.interrupted()) {
                try {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    val out = socket.getOutputStream()
                    configData?.let { out.write(it); out.flush() }
                    clients.add(Client(socket, out))
                    Log.i(TAG, "cliente conectado: ${socket.remoteSocketAddress}")
                } catch (_: IOException) { break }
            }
        }.also {
            it.isDaemon = true
            it.name = "H264-accept"
            it.start()
            acceptThread.set(it)
        }
    }

    fun stop() {
        running = false
        acceptThread.get()?.interrupt()
        serverSocket?.close()
        serverSocket = null
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        configData = null
        outputThread.get()?.join(2000)
        encoder?.stop()
        encoder?.release()
        encoder = null
    }
}
