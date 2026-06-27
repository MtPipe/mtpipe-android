package org.mtpipe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mtpipe.proxy.MtpipeService
import org.mtpipe.ui.theme.MtpipeTheme

class MainActivity : ComponentActivity() {

    private val _service = mutableStateOf<MtpipeService?>(null)
    private val _resumeCount = mutableIntStateOf(0)
    private var bound = false
    private var onServiceStatusChanged: ((String) -> Unit)? = null
    private var onServiceClientCountChanged: ((Int) -> Unit)? = null
    private var onServiceProxyStopped: (() -> Unit)? = null
    private var _lastFormValues = Triple("", "", "")

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MtpipeService.LocalBinder
            _service.value = localBinder.getService()
            bound = true
            _service.value?.onStatusChanged = { status -> onServiceStatusChanged?.invoke(status) }
            _service.value?.onClientCountChanged = { count -> onServiceClientCountChanged?.invoke(count) }
            _service.value?.onProxyStopped = { onServiceProxyStopped?.invoke() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val svc by _service
            MtpipeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MtpipeScreen(
                        isRunning = { svc?.isProxyRunning == true },
                        onStart = { s, p, sec -> startProxy(s, p, sec) },
                        onStop = { stopProxy() },
                        onServiceStatus = { cb -> onServiceStatusChanged = cb },
                        onServiceClientCount = { cb -> onServiceClientCountChanged = cb },
                        onServiceProxyStopped = { cb -> onServiceProxyStopped = cb },
                        resumeCount = _resumeCount.intValue,
                        onRequestNotifPermission = { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                        onFormChanged = { s, p, sec -> _lastFormValues = Triple(s, p, sec) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MtpipeService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        _resumeCount.intValue++
    }

    override fun onStop() {
        super.onStop()
        val (s, p, sec) = _lastFormValues
        if (s.isNotEmpty()) {
            getSharedPreferences("mtpipe_prefs", MODE_PRIVATE).edit()
                .putString("server", s).putString("port", p).putString("secret", sec).apply()
        }
        if (bound) {
            _service.value?.onStatusChanged = null
            _service.value?.onClientCountChanged = null
            _service.value?.onProxyStopped = null
            unbindService(connection)
            bound = false
        }
    }

    private fun startProxy(server: String, port: Int, secret: String) {
        val prefs = getSharedPreferences("mtpipe_prefs", MODE_PRIVATE)
        val listenPort = prefs.getInt("listen_port", -1)
        val intent = Intent(this, MtpipeService::class.java).apply {
            action = MtpipeService.ACTION_START
            putExtra(MtpipeService.EXTRA_SERVER, server)
            putExtra(MtpipeService.EXTRA_PORT, port)
            putExtra(MtpipeService.EXTRA_SECRET, secret)
            putExtra(MtpipeService.EXTRA_LISTEN_PORT, listenPort)
        }
        startForegroundService(intent)
    }

    private fun stopProxy() {
        val intent = Intent(this, MtpipeService::class.java).apply {
            action = MtpipeService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MtpipeScreen(
    isRunning: () -> Boolean = { false },
    onStart: (String, Int, String) -> Unit = { _, _, _ -> },
    onStop: () -> Unit = {},
    onServiceStatus: ((String) -> Unit) -> Unit = {},
    onServiceClientCount: ((Int) -> Unit) -> Unit = {},
    onServiceProxyStopped: (() -> Unit) -> Unit = {},
    resumeCount: Int = 0,
    onRequestNotifPermission: () -> Unit = {},
    onFormChanged: (String, String, String) -> Unit = { _, _, _ -> }
) {
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var clientCount by remember { mutableIntStateOf(0) }
    var listenPort by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showSecretError by remember { mutableStateOf(false) }
    var showBattery by remember { mutableStateOf(false) }
    var showNotifPerm by remember { mutableStateOf(false) }
    var settingsPort by remember { mutableStateOf("") }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("mtpipe_prefs", android.content.Context.MODE_PRIVATE) }

    if (!prefs.contains("server")) {
        prefs.edit()
            .putString("server", "db.knigacat.space")
            .putString("port", "5432")
            .putString("secret", "ee53c66714243934ba7c679268437fe956706574726f766963682e7275")
            .apply()
    }

    LaunchedEffect(Unit) {
        statusText = context.getString(R.string.disconnected)
        server = prefs.getString("server", "db.knigacat.space") ?: "db.knigacat.space"
        port = prefs.getString("port", "5432") ?: "5432"
        secret = prefs.getString("secret", "ee53c66714243934ba7c679268437fe956706574726f766963682e7275") ?: "ee53c66714243934ba7c679268437fe956706574726f766963682e7275"
        val saved = prefs.getInt("listen_port", 0)
        if (saved == 0) {
            val random = (20000..60000).random()
            prefs.edit().putInt("listen_port", random).apply()
            listenPort = random
        } else {
            listenPort = saved
        }
        if (!prefs.getBoolean("battery_asked", false)) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                showBattery = true
            }
            prefs.edit().putBoolean("battery_asked", true).apply()
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false)) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showNotifPerm = true
            }
            prefs.edit().putBoolean("notif_asked", true).apply()
        }
    }

    onServiceStatus { status -> statusText = status }
    onServiceClientCount { count -> clientCount = count }
    onServiceProxyStopped {
        isConnected = false
        statusText = context.getString(R.string.disconnected)
    }

    LaunchedEffect(isRunning()) {
        if (isRunning()) {
            isConnected = true
            statusText = context.getString(R.string.running)
        }
    }

    SideEffect {
        onFormChanged(server, port, secret)
    }

    val connectionColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF00C853) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(600),
        label = "connectionColor"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (isConnected) 1.1f else 1f,
        animationSpec = tween(300),
        label = "buttonScale"
    )

    val proxyLink = "https://t.me/proxy?server=127.0.0.1&port=$listenPort&secret=$secret"
    val tgLink = "tg://proxy?server=127.0.0.1&port=$listenPort&secret=$secret"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Mtpipe",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = context.getString(R.string.menu))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.settings)) },
                            onClick = {
                                showMenu = false
                                settingsPort = listenPort.toString()
                                showSettings = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.logs)) },
                            onClick = {
                                showMenu = false
                                showLogs = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.about)) },
                            onClick = {
                                showMenu = false
                                showAbout = true
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MtpipeTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = context.getString(R.string.server_label),
                        placeholder = context.getString(R.string.server_placeholder),
                        icon = Icons.Default.Dns,
                        enabled = !isConnected
                    )

                    MtpipeTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = context.getString(R.string.port_label),
                        placeholder = context.getString(R.string.port_placeholder),
                        icon = Icons.Default.Router,
                        enabled = !isConnected
                    )

                    MtpipeTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = context.getString(R.string.secret_label),
                        placeholder = context.getString(R.string.secret_placeholder),
                        icon = Icons.Default.Key,
                        enabled = !isConnected
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    try {
                        val clip = clipboardManager.getText()?.toString() ?: ""
                        val uri = android.net.Uri.parse(clip.trim())
                        val s = uri.getQueryParameter("server")
                        val p = uri.getQueryParameter("port")
                        val sec = uri.getQueryParameter("secret")
                        if (s != null && p != null && sec != null) {
                            server = s
                            port = p
                            secret = sec
                            Toast.makeText(context, context.getString(R.string.fields_filled), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.no_valid_link), Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.no_valid_link), Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isConnected,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.wrapContentWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(context.getString(R.string.paste_from_clipboard), fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(40.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(100.dp)
            ) {
                Button(
                    onClick = {
                        if (isConnected) {
                            onStop()
                            isConnected = false
                            statusText = context.getString(R.string.disconnected)
                        } else {
                            if (server.isBlank() || port.isBlank() || secret.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val portInt = port.toIntOrNull()
                            if (portInt == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (!secret.trim().lowercase().startsWith("ee")) {
                                showSecretError = true
                                return@Button
                            }
                        onStart(server, portInt, secret)
                        prefs.edit().putString("server", server).putString("port", port).putString("secret", secret).apply()
                        isConnected = true
                            statusText = context.getString(R.string.starting)
                        }
                    },
                    modifier = Modifier
                        .size(88.dp)
                        .scale(buttonScale),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = connectionColor
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = context.getString(R.string.connect),
                        modifier = Modifier.size(36.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = connectionColor,
                letterSpacing = 1.sp
            )

            if (isConnected && clientCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = context.getString(R.string.clients_connected, clientCount),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            if (isConnected) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = proxyLink,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(proxyLink))
                        context.startActivity(intent)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(proxyLink))
                            Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(context.getString(R.string.copy_link), fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(tgLink))
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(context.getString(R.string.open_telegram), fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showSettings) {
        var batteryIgnored by remember { mutableStateOf(false) }
        var notifGranted by remember { mutableStateOf(true) }
        LaunchedEffect(resumeCount) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                notifGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(context.getString(R.string.settings_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = settingsPort,
                        onValueChange = { settingsPort = it.filter { c -> c.isDigit() } },
                        label = { Text(context.getString(R.string.listen_port_label)) },
                        placeholder = { Text(context.getString(R.string.listen_port_placeholder)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (batteryIgnored) context.getString(R.string.battery_status_granted) else context.getString(R.string.battery_status_not_granted),
                            fontSize = 14.sp,
                            color = if (batteryIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        if (!batteryIgnored) {
                            OutlinedButton(
                                onClick = {
                                    val intent = android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                Text(context.getString(R.string.allow), fontSize = 12.sp)
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (notifGranted) context.getString(R.string.notif_status_granted) else context.getString(R.string.notif_status_not_granted),
                            fontSize = 14.sp,
                            color = if (notifGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        if (!notifGranted && android.os.Build.VERSION.SDK_INT >= 33) {
                            OutlinedButton(
                                onClick = { onRequestNotifPermission() },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                Text(context.getString(R.string.grant), fontSize = 12.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedButton(
                        onClick = {
                            prefs.edit()
                                .putString("server", "db.knigacat.space")
                                .putString("port", "5432")
                                .putString("secret", "ee53c66714243934ba7c679268437fe956706574726f766963682e7275")
                                .apply()
                            server = "db.knigacat.space"
                            port = "5432"
                            secret = "ee53c66714243934ba7c679268437fe956706574726f766963682e7275"
                            settingsPort = "19796"
                            showSettings = false
                            Toast.makeText(context, context.getString(R.string.reset_confirm), Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(context.getString(R.string.reset_settings))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = settingsPort.toIntOrNull()
                    if (p != null && p in 1..65535) {
                        prefs.edit().putInt("listen_port", p).apply()
                        listenPort = p
                        showSettings = false
                    } else {
                        Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(context.getString(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text(context.getString(R.string.cancel)) }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(context.getString(R.string.about_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.about_description))
                    Text(
                        text = "https://github.com/MtPipe/mtpipe-android",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/MtPipe/mtpipe-android"))
                            context.startActivity(intent)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text(context.getString(R.string.ok)) }
            }
        )
    }

    if (showSecretError) {
        AlertDialog(
            onDismissRequest = { showSecretError = false },
            title = { Text(context.getString(R.string.error)) },
            text = { Text(context.getString(R.string.invalid_secret)) },
            confirmButton = {
                TextButton(onClick = { showSecretError = false }) { Text(context.getString(R.string.ok)) }
            }
        )
    }

    if (showBattery) {
        AlertDialog(
            onDismissRequest = { showBattery = false },
            title = { Text(context.getString(R.string.battery_title)) },
            text = { Text(context.getString(R.string.battery_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showBattery = false
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) { Text(context.getString(R.string.allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showBattery = false }) { Text(context.getString(R.string.later)) }
            }
        )
    }

    if (showNotifPerm) {
        AlertDialog(
            onDismissRequest = { showNotifPerm = false },
            title = { Text(context.getString(R.string.notif_permission_title)) },
            text = { Text(context.getString(R.string.notif_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotifPerm = false
                    onRequestNotifPermission()
                }) { Text(context.getString(R.string.allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotifPerm = false }) { Text(context.getString(R.string.later)) }
            }
        )
    }

    if (showLogs) {
        LogcatViewerScreen(onBack = { showLogs = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MtpipeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var logLines by remember { mutableStateOf(listOf<String>()) }
    val listState = rememberLazyListState()

    androidx.activity.compose.BackHandler { onBack() }

    DisposableEffect(Unit) {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-s", "MtpipeProxy:*", "MtpipeService:*"))
        val thread = Thread {
            val reader = process.inputStream.bufferedReader()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    logLines = logLines + line
                }
            } catch (_: Exception) {}
        }
        thread.start()
        onDispose {
            thread.interrupt()
            process.destroy()
        }
    }

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.logs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp)
        ) {
            items(logLines.size) { i ->
                Text(
                    text = logLines[i],
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MtpipeScreenPreview() {
    MtpipeTheme(darkTheme = true) {
        MtpipeScreen()
    }
}
