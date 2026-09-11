package com.phonemirror.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phonemirror.sender.data.MirrorSettings
import com.phonemirror.sender.data.SettingsRepository
import com.phonemirror.sender.data.SharedPreferencesSettingsStore
import com.phonemirror.sender.net.ClientState
import com.phonemirror.sender.net.DiscoveredDevice
import com.phonemirror.sender.net.NsdDiscovery
import com.phonemirror.sender.net.StreamClient
import com.phonemirror.sender.service.MirrorService
import com.phonemirror.sender.service.MirrorTileService
import com.phonemirror.sender.service.ProjectionHolder
import com.phonemirror.sender.ui.*

class MainActivity : ComponentActivity() {

    private lateinit var windowController: ActivityWindowController
    private lateinit var viewModel: MainViewModel
    private lateinit var nsdDiscovery: NsdDiscovery

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val projectionConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val tvName = viewModel.lastEndpoint?.name ?: "Android TV"
            val serviceIntent = Intent(this, MirrorService::class.java).apply {
                putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MirrorService.EXTRA_RESULT_DATA, result.data)
                putExtra(MirrorService.EXTRA_TV_NAME, tvName)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            ProjectionHolder.setStopped()
            Toast.makeText(this, "Разрешение на запись экрана не получено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowController = ActivityWindowController(this)
        val settingsRepo = SettingsRepository(SharedPreferencesSettingsStore(this))
        val streamClient = StreamClient()
        viewModel = MainViewModel(
            streamClient = streamClient,
            settingsRepository = settingsRepo,
            projectionHolder = ProjectionHolder,
            windowController = windowController,
            screenMetricsProvider = {
                val dm = resources.displayMetrics
                ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.densityDpi)
            },
            coroutineScope = lifecycleScope
        )
        nsdDiscovery = NsdDiscovery(this)

        // Handle BACK during streaming: app backgrounds, streaming continues!
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isStreaming.value) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Handle quick-connect extra from Quick Settings Tile
        handleQuickConnectIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        nsdDiscovery = nsdDiscovery,
                        onRequestProjectionConsent = {
                            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionConsentLauncher.launch(mgr.createScreenCaptureIntent())
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleQuickConnectIntent(intent)
    }

    private fun handleQuickConnectIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(MirrorTileService.EXTRA_QUICK_CONNECT, false) == true) {
            viewModel.lastEndpoint?.let { ep ->
                viewModel.connect(ep.host, ep.port, "", ep.name)
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    nsdDiscovery: NsdDiscovery,
    onRequestProjectionConsent: () -> Unit
) {
    val clientState by viewModel.clientState.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val sessionStats by viewModel.sessionStats.collectAsState()
    val uiError by viewModel.uiError.collectAsState()
    val abrNoteVisible by viewModel.abrNoteVisible.collectAsState()

    val discoveredDevices by produceState(initialValue = emptyList<DiscoveredDevice>()) {
        nsdDiscovery.discover().collect { value = it }
    }

    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("47700") }
    var pin by remember { mutableStateOf("") }
    var selectedDeviceName by remember { mutableStateOf("Android TV") }

    val lastEndpoint = viewModel.lastEndpoint

    LaunchedEffect(lastEndpoint) {
        lastEndpoint?.let {
            if (manualIp.isEmpty()) {
                manualIp = it.host
                manualPort = it.port.toString()
                selectedDeviceName = it.name
            }
        }
    }

    // Error Dialogs & Surfaces
    uiError?.let { err ->
        when (err) {
            is UiError.PinRejected -> {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissError() },
                    title = { Text("Ошибка PIN-кода") },
                    text = { Text(err.message) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK")
                        }
                    }
                )
            }
            is UiError.Busy -> {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissError() },
                    title = { Text("Телевизор занят") },
                    text = { Text(err.message) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK")
                        }
                    }
                )
            }
            is UiError.Revoked -> {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissError() },
                    title = { Text("Трансляция остановлена") },
                    text = { Text(err.message) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK")
                        }
                    }
                )
            }
            is UiError.Failed -> {
                Snackbar(
                    action = {
                        TextButton(onClick = { viewModel.retryConnection() }) {
                            Text("Повторить")
                        }
                    },
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(err.message)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PhoneMirror",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

        // STATUS CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Статус: ${formatClientState(clientState, isStreaming)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                if (isStreaming) {
                    Text("Время: ${sessionStats.formattedDuration} | RTT: ${sessionStats.rttMs} мс | Gen: ${sessionStats.gen}")
                    Text("Видео: ${"%.1f".format(sessionStats.videoFps)} кадр/с | ${sessionStats.videoKbps} кбит/с")
                    if (settings.audioEnabled) {
                        Text("Аудио: ${sessionStats.audioKbps} кбит/с")
                        Text(
                            text = "Некоторые приложения (DRM, звонки) могут глушить звук - это ограничение Android",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (abrNoteVisible) {
                        Text(
                            text = "Слабая сеть - качество снижено автоматически",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.stopMirroring() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Остановить трансляцию")
                    }
                } else if (clientState is ClientState.Connected) {
                    Text("Подключено к телевизору")
                    Button(
                        onClick = onRequestProjectionConsent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Начать трансляцию экрана")
                    }
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отключиться")
                    }
                }
            }
        }

        // SETTINGS CARD
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "Настройки трансляции", fontWeight = FontWeight.Bold, fontSize = 18.sp)

                // Bitrate slider (live)
                Text(text = "Битрейт видео: ${settings.bitrateMbps} Мбит/с")
                Slider(
                    value = settings.bitrateMbps.toFloat(),
                    onValueChange = { viewModel.updateBitrate(it.toInt()) },
                    valueRange = 2f..16f,
                    steps = 13
                )

                // Resolution cap
                Text(text = "Максимальное разрешение (применится при следующем запуске):")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1080p", "720p").forEach { res ->
                        FilterChip(
                            selected = settings.resolutionCap == res,
                            onClick = { viewModel.updateResolutionCap(res) },
                            label = { Text(res) }
                        )
                    }
                }

                // FPS
                Text(text = "Частота кадров (применится при следующем запуске):")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 30).forEach { f ->
                        FilterChip(
                            selected = settings.fps == f,
                            onClick = { viewModel.updateFps(f) },
                            label = { Text("$f кадр/с") }
                        )
                    }
                }

                // Audio toggle (hidden on SDK < 29)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Трансляция звука:")
                        Switch(
                            checked = settings.audioEnabled,
                            onCheckedChange = { viewModel.updateAudioEnabled(it) }
                        )
                    }
                } else {
                    Text(
                        text = "Захват внутреннего звука поддерживается только на Android 10+",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp
                    )
                }

                // Dim screen toggle (live)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Затемнять экран при трансляции:")
                    Switch(
                        checked = settings.dimScreen,
                        onCheckedChange = { viewModel.updateDimScreen(it) }
                    )
                }
            }
        }

        // QUICK CONNECT & DISCOVERY (when not streaming or connected)
        if (!isStreaming && clientState !is ClientState.Connected) {
            lastEndpoint?.let { ep ->
                Button(
                    onClick = { viewModel.connect(ep.host, ep.port, pin, ep.name) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Быстрое подключение к ${ep.name}")
                }
            }

            Text(text = "Найденные телевизоры в Wi-Fi:", fontWeight = FontWeight.Bold)
            if (discoveredDevices.isEmpty()) {
                Text("Поиск телевизоров...", color = MaterialTheme.colorScheme.secondary)
            } else {
                discoveredDevices.forEach { dev ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                manualIp = dev.host
                                manualPort = dev.port.toString()
                                selectedDeviceName = dev.name
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(dev.name, fontWeight = FontWeight.SemiBold)
                            Text("${dev.host}:${dev.port}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            // Manual Connection Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Ручное подключение", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("IP-адрес ТВ") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it },
                        label = { Text("Порт") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("4-значный PIN (с экрана ТВ)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val port = manualPort.toIntOrNull() ?: 47700
                            if (manualIp.isNotBlank()) {
                                viewModel.connect(manualIp.trim(), port, pin.trim(), selectedDeviceName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Подключиться")
                    }
                }
            }
        }
    }
}
}

fun formatClientState(state: ClientState, isStreaming: Boolean): String = when {
    isStreaming -> "Трансляция активна"
    state is ClientState.Idle -> "Ожидание"
    state is ClientState.Connecting -> "Подключение к ${state.host}:${state.port}..."
    state is ClientState.Connected -> "Подключено к ${state.host}:${state.port}"
    state is ClientState.PairingFailure -> "Ошибка сопряжения: ${state.reason}"
    state is ClientState.Reconnecting -> "Переподключение (${state.attempt}/${state.maxAttempts})..."
    state is ClientState.Failed -> "Не удалось подключиться"
    else -> "Ожидание"
}
