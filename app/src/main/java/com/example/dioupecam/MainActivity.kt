package com.dioupe.camstream

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("DioupeCam", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))

            if (isRunning) {
                EndpointRow(label = "MJPEG", address = "$ip:8080", detail = "/video  /jpeg")
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                EndpointRow(label = "H.264 TCP", address = "$ip:8554", detail = "DioupeCamDesktop")
                Spacer(Modifier.height(16.dp))
                Text(
                    "USB: adb forward tcp:8080 tcp:8080\n     adb forward tcp:8554 tcp:8554",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Text("Stream parado", style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(32.dp))

            Button(onClick = {
                if (isRunning) {
                    CamStreamService.stop(context)
                } else {
                    if (hasCameraPermission) {
                        CamStreamService.start(context)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }) {
                Text(if (isRunning) "Parar Stream" else "Iniciar Stream")
            }
        }
    }
}

@Composable
private fun EndpointRow(label: String, address: String, detail: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            address,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(detail, style = MaterialTheme.typography.bodySmall)
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
