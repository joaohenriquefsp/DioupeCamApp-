package com.dioupe.camstream

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

class MjpegServer {
    private val _frameFlow = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val frameFlow = _frameFlow.asSharedFlow()

    private var engine: ApplicationEngine? = null

    fun updateFrame(jpeg: ByteArray) {
        _frameFlow.tryEmit(jpeg)
    }

    fun start() {
        engine = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            routing {
                get("/video") {
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondBytesWriter(
                        contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")
                    ) {
                        try {
                            frameFlow.collect { jpeg ->
                                val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                                writeFully(header.toByteArray())
                                writeFully(jpeg)
                                writeFully("\r\n".toByteArray())
                                flush()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Client disconnected
                        }
                    }
                }

                get("/jpeg") {
                    val frame = frameFlow.replayCache.firstOrNull()
                    if (frame != null) {
                        call.respondBytes(frame, ContentType.Image.JPEG)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                    }
                }

                get("/status") {
                    val streaming = frameFlow.replayCache.isNotEmpty()
                    call.respondText(
                        """{"streaming":$streaming,"port":8080}""",
                        ContentType.Application.Json
                    )
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        engine = null
    }
}
