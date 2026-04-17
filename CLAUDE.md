# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status do projeto

### Concluído
- [x] Todos os arquivos Kotlin implementados e funcionando
- [x] `build.gradle.kts` — namespace, applicationId, minSdk e dependências corretos
- [x] `AndroidManifest.xml` — permissões e service declarados
- [x] **Arquitetura desacoplada** — `H264Encoder` e `CameraCapture` independentes; `CamStreamService` orquestra
- [x] **ImageAnalysis único** alimenta H264 e MJPEG em paralelo (3 executors)
- [x] **YUV bulk copy** — cópia por linha inteira quando `pixelStride==2` (NV12/NV21), eliminando 172.800 chamadas JNI por frame
- [x] **Aspect ratio correto** — resolução 960×720 não é distorcida para 1280×720 (FFmpeg faz o scale no desktop)
- [x] **ResolutionSelector** com `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER` substituindo `setTargetResolution` deprecated
- [x] Repositório GitHub: `https://github.com/joaohenriquefsp/DioupeCamApp-` (branch `main`)
- [x] **Animação Lottie** na tela principal (`app/src/main/assets/webcam_animation.lottie`):
  - `lottie-compose 6.4.0` adicionado em `libs.versions.toml` + `build.gradle.kts`
  - `MainActivity.kt`: `ConnectionCards` exibe a animação acima dos cards WiFi/USB quando o stream está parado

### Pendente
- [ ] Testar em outros dispositivos (alguns têm limitações de surface combination no CameraX)

## O que é este projeto

**DioupeCam** é um app Android que captura a câmera do celular e transmite em dois formatos:

- **MJPEG over HTTP** (porta 8080) — preview e consumo por outras ferramentas
- **H.264 over TCP** (porta 8554) — para o **DioupeCamDesktop** criar câmera virtual no Windows

## Stack

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| UI | Jetpack Compose |
| Captura | CameraX (ImageAnalysis + Preview simultâneos) |
| Encode H.264 | MediaCodec buffer mode (COLOR_FormatYUV420SemiPlanar / NV12) |
| Servidor HTTP | Ktor (Netty engine) |
| Servidor TCP | `ServerSocket` Kotlin stdlib |
| Protocolo MJPEG | `multipart/x-mixed-replace` |
| Protocolo H.264 | H.264 Annex B raw over TCP |
| Min SDK | API 26 (Android 8.0) |
| Package | `com.example.dioupecam` |

## Arquitetura

```
CameraX
  └─ ImageAnalysis (único)
         │
         │  extractExecutor: extrai NV12 + NV21 (bulk copy), fecha ImageProxy
         │
         ├─ h264Executor  → H264Encoder.onNv12(nv12, w, h, ts)
         │                       │
         │                  MediaCodec HW encoder (buffer mode)
         │                       │
         │                  H.264 Annex B → TCP :8554
         │                       └─── DioupeCamDesktop conecta aqui
         │
         └─ mjpegExecutor → CameraCapture.onNv21(nv21, w, h)
                                 │
                            YuvImage → JPEG bytes
                                 │
                            MjpegServer (Ktor :8080)
                                 GET /video  (MJPEG stream)
                                 GET /jpeg   (frame único)
                                 GET /status (JSON)

Preview (separado, só para UI do app)
```

## Arquivos principais

```
app/src/main/java/com/example/dioupecam/
  MainActivity.kt       — UI Compose + permissões + controle do serviço
  CamStreamService.kt   — ForegroundService: binding CameraX, 3 executors, YUV extraction
  CameraCapture.kt      — Recebe NV21 bytes → JPEG → callback (sem use case building)
  H264Encoder.kt        — Recebe NV12 bytes → MediaCodec buffer mode → TCP server
  MjpegServer.kt        — Ktor HTTP: /video /jpeg /status
```

## Detalhes de implementação críticos

### CamStreamService — 3 executors paralelos

```kotlin
// Extrai NV12 e NV21 (bulk copy), fecha o ImageProxy imediatamente,
// depois despacha para H264 e MJPEG em paralelo.
analysis.setAnalyzer(extractExecutor) { imageProxy ->
    val nv12 = imageProxy.toNV12()
    val nv21 = imageProxy.toNV21()
    val w = imageProxy.width; val h = imageProxy.height
    val ts = imageProxy.imageInfo.timestamp
    imageProxy.close()                                    // libera ASAP
    h264Executor.execute  { h264Encoder.onNv12(nv12, w, h, ts) }
    mjpegExecutor.execute { cameraCapture.onNv21(nv21, w, h) }
}
```

### YUV bulk copy (fast path)

```kotlin
// Para NV12 (UV interleaved, pixelStride==2): copia linha a linha
// Evita 172.800 chamadas JNI individuais (1280×720 / 2 × 2 bytes)
if (planes[1].pixelStride == 2) {
    for (row in 0 until h / 2) {
        uvBuf.position(row * uvStride)
        uvBuf.get(out, pos, w)    // ~640 bytes por chamada em vez de 1 por pixel
        pos += w
    }
}
```

### H264Encoder — buffer mode MediaCodec

- `COLOR_FormatYUV420SemiPlanar` (NV12)
- Inicialização lazy no primeiro frame (dimensões vindas do ImageAnalysis)
- `dequeueInputBuffer(10_000)` — timeout 10ms
- TCP accept loop inicia no construtor
- Não usa Preview use case — evita crash "No supported surface combination" com dois Preview

### ResolutionSelector

```kotlin
ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(1280, 720),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
        )
    ).build()
```

## Qualidade e latência

| Conexão | Protocolo | Latência estimada |
|---|---|---|
| USB (adb forward) | H.264 TCP | ~5–15ms |
| WiFi | H.264 TCP | ~30–60ms |
| WiFi | MJPEG HTTP | ~80ms |

**H.264 encoder config:** 4 Mbps, 30fps, I-frame a cada 1s, hardware MediaCodec.

## Endpoints e portas

| Porta | Protocolo | Uso |
|---|---|---|
| 8080 | HTTP | MJPEG stream + frame único + status |
| 8554 | TCP raw | H.264 Annex B stream (para DioupeCamDesktop) |

## Permissões no Manifest

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
```

## Como testar

### H.264 com FFplay (WiFi)
```bash
ffplay -f h264 tcp://192.168.0.105:8554
```

### H.264 com FFplay (USB)
```bash
adb forward tcp:8554 tcp:8554
ffplay -f h264 tcp://localhost:8554
```

### MJPEG (browser)
Abrir `http://192.168.0.105:8080/video` no browser.

## Comandos de build

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew clean assembleDebug
```

## Analogias .NET para conceitos Android

| Android | .NET equivalente |
|---|---|
| `Activity` | entry point / `Program.cs` |
| `ForegroundService` | `IHostedService` em background |
| `Compose` | Blazor/Razor (declarativo) |
| `MediaCodec` | codec nativo via P/Invoke |
| `ImageAnalysis.Analyzer` | producer de um `Channel<T>` |
| `Executor` | `TaskScheduler` / thread pool dedicado |
