package com.phonemirror.sender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phonemirror.sender.net.*

class MainActivity : ComponentActivity() {

    private val streamClient = StreamClient()
    private lateinit var nsdDiscovery: NsdDiscovery

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdDiscovery = NsdDiscovery(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        streamClient = streamClient,
                        nsdDiscovery = nsdDiscovery,
                        onConnect = { host, port, pin, name ->
                            streamClient.start(lifecycleScope, host, port, pin, name)
                        },
                        onDisconnect = {
                            streamClient.stop()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    streamClient: StreamClient,
    nsdDiscovery: NsdDiscovery,
    onConnect: (String, Int, String, String) -> Unit,
    onDisconnect: () -> Unit
) {
    val clientState by streamClient.state.collectAsState()
    val rttMs by streamClient.rttMs.collectAsState()

    val discoveredDevices by produceState(initialValue = emptyList<DiscoveredDevice>()) {
        nsdDiscovery.discover().collect { value = it }
    }

    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("47700") }
    var pin by remember { mutableStateOf("") }
    var selectedDeviceName by remember { mutableStateOf("Android TV") }

    val lastEndpoint = remember { streamClient.endpointStore.loadEndpoint() }

    LaunchedEffect(lastEndpoint) {
        lastEndpoint?.let {
            if (manualIp.isEmpty()) {
                manualIp = it.host
                manualPort = it.port.toString()
                selectedDeviceName = it.name
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "PhoneMirror",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status: ${formatState(clientState)}",
                    fontWeight = FontWeight.SemiBold
                )
                if (clientState is ClientState.Connected) {
                    Text(text = "RTT: ${rttMs}ms | Gen: ${streamClient.sessionPolicy.gen}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }

        // Quick connect button if last endpoint exists
        if (lastEndpoint != null && clientState !is ClientState.Connected) {
            Button(
                onClick = {
                    onConnect(lastEndpoint.host, lastEndpoint.port, pin, lastEndpoint.name)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Quick connect to ${lastEndpoint.name}")
            }
        }

        // Discovered Devices Section
        Text(text = "Discovered TVs:", fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (discoveredDevices.isEmpty()) {
                item {
                    Text("Searching for TVs on Wi-Fi...", color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                items(discoveredDevices) { dev ->
                    ListItem(
                        headlineContent = { Text(dev.name) },
                        supportingContent = { Text("${dev.host}:${dev.port}") },
                        modifier = Modifier.clickable {
                            manualIp = dev.host
                            manualPort = dev.port.toString()
                            selectedDeviceName = dev.name
                        }
                    )
                }
            }
        }

        // Manual Connection Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Connect to TV", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text("TV IP Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("4-digit PIN (from TV)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val port = manualPort.toIntOrNull() ?: 47700
                        if (manualIp.isNotBlank()) {
                            onConnect(manualIp.trim(), port, pin.trim(), selectedDeviceName)
                        }
                    },
                    enabled = clientState !is ClientState.Connected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

fun formatState(state: ClientState): String = when (state) {
    is ClientState.Idle -> "Idle"
    is ClientState.Connecting -> "Connecting to ${state.host}:${state.port}..."
    is ClientState.Connected -> "Connected to ${state.host}:${state.port}"
    is ClientState.PairingFailure -> "Pairing Failed: ${state.reason}"
    is ClientState.Reconnecting -> "Reconnecting (${state.attempt}/${state.maxAttempts})..."
    is ClientState.Failed -> "Connection Failed (unreachable)"
}