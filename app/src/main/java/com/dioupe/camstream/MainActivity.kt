package com.dioupe.camstream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.dioupe.camstream.ui.theme.DioupeCamTheme
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DioupeCamTheme {
                MainScreen()
            }
        }
    }
}

data class CameraOption(val id: String, val label: String)

/**
 * Enumera câmeras disponíveis usando Camera2 CameraManager.
 * Filtra sensores auxiliares (< 1MP) e câmeras externas.
 * Labels são gerados por resolução e distância focal.
 */
fun enumerateCameras(context: Context): List<CameraOption> {
    return try {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mgr.cameraIdList.mapNotNull { id ->
            val chars = mgr.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return@mapNotNull null

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@mapNotNull null
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return@mapNotNull null
            val maxSize = sizes.maxByOrNull { it.width * it.height } ?: return@mapNotNull null
            val mpx = maxSize.width * maxSize.height / 1_000_000
            if (mpx < 1) return@mapNotNull null // descarta TOF/depth sensors

            val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f

            val label = when {
                facing == CameraCharacteristics.LENS_FACING_FRONT -> "Frontal · ${mpx}MP"
                mpx >= 20 -> "Principal · ${mpx}MP"   // sensor grande = câmera principal
                fl < 2.5f -> "Ultra-wide · ${mpx}MP"  // focal curta = grande angular
                mpx <= 3  -> return@mapNotNull null    // descarta macro/profundidade
                else      -> "Traseira · ${mpx}MP"
            }
            CameraOption(id, label)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val isRunning by CamStreamService.isRunning.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) CamStreamService.start(context)
    }

    val ip = remember { getLocalIpAddress() }

    val cameraOptions = remember { enumerateCameras(context) }

    // Inicializa com o valor atual do serviço (mantém seleção entre recomposições)
    var selectedCameraId by remember { mutableStateOf(CamStreamService.selectedCameraId) }
    var selectedFps by remember { mutableIntStateOf(CamStreamService.targetFps) }

    val previewView = remember {
        PreviewView(context).also { pv ->
            pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            CamStreamService.uiSurfaceProvider = pv.surfaceProvider
        }
    }

    val previewAlpha by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = tween(400),
        label = "preview_alpha"
    )
    val infoAlpha by animateFloatAsState(
        targetValue = if (!isRunning) 1f else 0f,
        animationSpec = tween(300),
        label = "info_alpha"
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "DioupeCam",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .alpha(previewAlpha)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(infoAlpha),
                    contentAlignment = Alignment.Center
                ) {
                    ConnectionCards(ip)
                }
            }

            AnimatedVisibility(
                visible = isRunning,
                enter = slideInVertically(tween(350)) { -it } + fadeIn(tween(350)),
                exit  = slideOutVertically(tween(250)) { -it } + fadeOut(tween(250))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConnectionCard("WiFi", "http://$ip:8080/video", "Abrir no browser ou Unity")
                    ConnectionCard("USB", "adb forward tcp:8554 tcp:8554", "Alta qualidade via cabo")
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (isRunning) {
                        CamStreamService.stop(context)
                    } else {
                        if (hasCameraPermission) {
                            CamStreamService.start(context)
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "Parar Stream" else "Iniciar Stream")
            }

            // Configurações de câmera e FPS — visíveis apenas quando parado
            AnimatedVisibility(
                visible = !isRunning,
                enter = fadeIn(tween(250)),
                exit  = fadeOut(tween(200))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Seleção de câmera
                    if (cameraOptions.size > 1) {
                        Text(
                            "Câmera",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            cameraOptions.forEach { option ->
                                FilterChip(
                                    selected = selectedCameraId == option.id,
                                    onClick = {
                                        selectedCameraId = option.id
                                        CamStreamService.restart(context, option.id, selectedFps)
                                    },
                                    label = { Text(option.label, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    // Seleção de FPS
                    Text(
                        "FPS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60).forEach { fps ->
                            FilterChip(
                                selected = selectedFps == fps,
                                onClick = {
                                    selectedFps = fps
                                    CamStreamService.restart(context, selectedCameraId, fps)
                                },
                                label = { Text("${fps}fps") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCards(ip: String) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("webcam_animation.lottie"))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier.size(160.dp)
        )
        ConnectionCard("WiFi", "http://$ip:8080/video", "Abrir no browser ou Unity")
        ConnectionCard("USB", "adb forward tcp:8554 tcp:8554", "Alta qualidade via cabo")
    }
}

@Composable
private fun ConnectionCard(label: String, primary: String, secondary: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

fun getLocalIpAddress(): String {
    return try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "IP desconhecido"
    } catch (_: Exception) {
        "IP desconhecido"
    }
}
