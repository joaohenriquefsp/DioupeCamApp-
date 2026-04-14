# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status do projeto

### Concluído
- [x] Todos os arquivos Kotlin implementados (`MainActivity`, `CamStreamService`, `CameraCapture`, `H264Server`, `MjpegServer`)
- [x] `build.gradle.kts` — namespace, applicationId, minSdk e dependências corretos
- [x] `AndroidManifest.xml` — permissões e service declarados
- [x] `libs.versions.toml` — CameraX 1.3.4, Ktor 2.3.12, slf4j adicionados

### Concluído em 2026-04-13
- [x] **Repositório GitHub criado** — `https://github.com/joaohenriquefsp/DioupeCamApp-` (branch `main`)
- [x] **Todos os arquivos commitados e pushed** — 43 arquivos, `.idea/` excluído corretamente do repo
- [x] **Arquitetura revisada** — estrutura atual adequada para o escopo do app (sem necessidade de Clean Architecture)

### Pendente (retomar)
- [ ] **Gradle Sync** — Android Studio precisa baixar o Gradle 8.13 (~130MB). Se falhar por timeout, baixar manualmente e colocar em `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.13-bin\<hash>\`
- [ ] **Build e install no dispositivo** — rodar `./gradlew installDebug` ou usar o botão Run no Android Studio
- [ ] **Testar MJPEG** — abrir `http://IP:8080/video` no browser
- [ ] **Testar H.264** — rodar `ffplay -f h264 tcp://IP:8554` no PC
- [ ] **Verificar uso simultâneo** de `ImageAnalysis` + `Preview` no dispositivo físico (alguns aparelhos têm limitações)
- [ ] **Melhorar UI** — adicionar animação Lottie (personagem filmando/webcam), layout com cards e melhor tipografia

## O que é este projeto

**DioupeCam** é um app Android que captura a câmera do celular e transmite em dois formatos:

- **MJPEG over HTTP** (porta 8080) — para Unity via polling de `/jpeg`
- **H.264 over TCP** (porta 8554) — para o **DioupeCamDesktop** criar câmera virtual no Windows

Substitui o DroidCam sem precisar de driver DirectShow.

## Por que foi criado

O Unity usa DirectShow (API legada do Windows) para webcams. Câmeras virtuais como DroidCam Source 2 não registram capabilities no DirectShow. A solução: servir frames via HTTP (Unity consome direto) e H.264 raw TCP (desktop converte em câmera virtual via Unity Capture).

## Stack

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| UI | Jetpack Compose |
| Captura | CameraX (ImageAnalysis + Preview simultâneos) |
| Encode H.264 | MediaCodec (hardware encoder) |
| Servidor HTTP | Ktor (Netty engine) |
| Servidor TCP | `ServerSocket` Kotlin stdlib |
| Protocolo MJPEG | `multipart/x-mixed-replace` |
| Protocolo H.264 | H.264 Annex B raw over TCP |
| Min SDK | API 26 (Android 8.0) |
| Package | `com.dioupe.camstream` |

## Arquitetura

```
CameraX
  ├─ ImageAnalysis → JPEG bytes → MjpegServer (Ktor :8080)
  │                                    GET /video  (MJPEG stream)
  │                                    GET /jpeg   (frame único)
  │                                    GET /status (JSON)
  │
  └─ Preview (Surface) → MediaCodec H.264 HW → H264Server (TCP :8554)
                                                    ← DioupeCamDesktop conecta aqui
```

- `CamStreamService` faz o binding único das duas use cases no CameraX
- `CameraCapture` cria e configura o `ImageAnalysis` (não faz binding)
- `H264Server` cria e configura o `Preview` via `setSurfaceProvider` (não faz binding)
- Binding de ambos acontece em `CamStreamService.onCreate()`

## Qualidade e latência

| Conexão | Protocolo | Latência estimada | Qualidade |
|---|---|---|---|
| WiFi | H.264 TCP | ~30–60ms | ★★★★★ |
| USB (adb forward) | H.264 TCP | ~5–15ms | ★★★★★ |
| WiFi | MJPEG HTTP | ~80ms | ★★★☆☆ |

**H.264 encoder config:** 4 Mbps, 30fps, I-frame a cada 1s, hardware MediaCodec.

## Arquivos principais

```
app/src/main/java/com/dioupe/camstream/
  MainActivity.kt       — UI Compose + permissões + controle do serviço
  CamStreamService.kt   — ForegroundService: LifecycleOwner + binding câmera + start servers
  CameraCapture.kt      — Cria ImageAnalysis use case → JPEG bytes via callback
  H264Server.kt         — Cria Preview use case + MediaCodec encoder + TCP server
  MjpegServer.kt        — Ktor HTTP: /video /jpeg /status
```

## Endpoints e portas

| Porta | Protocolo | Uso |
|---|---|---|
| 8080 | HTTP | MJPEG stream + frame único + status |
| 8554 | TCP raw | H.264 Annex B stream (para DioupeCamDesktop) |

### Permissões no Manifest

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
```

### Dependências (app/build.gradle.kts)

```kotlin
implementation("androidx.camera:camera-camera2:1.3.4")
implementation("androidx.camera:camera-lifecycle:1.3.4")
implementation("androidx.camera:camera-view:1.3.4")
implementation("io.ktor:ktor-server-netty:2.3.12")
implementation("io.ktor:ktor-server-core:2.3.12")
```

## Como testar

### MJPEG (browser)
1. Abrir o app → ver IP na tela (ex: `192.168.0.105`)
2. Acessar `http://192.168.0.105:8080/video` no browser

### H.264 com FFplay (WiFi)
```bash
ffplay -f h264 tcp://192.168.0.105:8554
```

### H.264 com FFplay (USB)
```bash
adb forward tcp:8080 tcp:8080
adb forward tcp:8554 tcp:8554
ffplay -f h264 tcp://localhost:8554
```

## Comandos de build

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
./gradlew connectedAndroidTest
./gradlew test --tests "com.dioupe.camstream.ExampleUnitTest"
./gradlew clean
```

## Como o Unity consome

Para o projeto **DioupeFilter**, usar o componente `DroidCamCapture.cs`:
- Polling de `http://<ip>:8080/jpeg` via `UnityWebRequestTexture`
- Atualiza `RawImage` a cada frame
- Não precisa do DioupeCamDesktop — consome HTTP diretamente

## Analogias .NET para conceitos Android

| Android | .NET equivalente |
|---|---|
| `Activity` | `Program.cs` / entry point |
| `ForegroundService` | `IHostedService` em background |
| `Compose` | Blazor/Razor (declarativo) |
| `ViewModel` | Controller/state manager |
| `MediaCodec` | `System.IO.Pipelines` + codec nativo |
