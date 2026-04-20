package com.openclaw.webchat.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.openclaw.webchat.notification.ConnectionService
import com.openclaw.webchat.R
import com.openclaw.webchat.upload.FileUploadManager
import com.openclaw.webchat.util.PreferencesManager
import com.openclaw.webchat.voice.VoiceInputManager
import com.openclaw.webchat.web.ChatWebViewClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fileUploadManager: FileUploadManager
    private lateinit var voiceInputManager: VoiceInputManager

    private var serverUrl by mutableStateOf("")
    private var isConnected by mutableStateOf(false)

    private val notificationChannelId = "openclaw_messages"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesManager = PreferencesManager(this)
        fileUploadManager = FileUploadManager()
        voiceInputManager = VoiceInputManager(this)

        createNotificationChannel()
        startConnectionService()

        setContent {
            var showSettings by remember { mutableStateOf(serverUrl.isEmpty()) }
            var webViewRef by remember { mutableStateOf<WebView?>(null) }
            var isUploading by remember { mutableStateOf(false) }
            var uploadProgress by remember { mutableStateOf("") }

            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
            val notificationPermission = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
            val storagePermission = rememberPermissionState(Manifest.permission.READ_EXTERNAL_STORAGE)

            // Load saved server URL
            LaunchedEffect(Unit) {
                serverUrl = preferencesManager.getServerUrl()
                if (serverUrl.isEmpty()) {
                    showSettings = true
                }
            }

            // File picker launcher
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    scope.launch {
                        isUploading = true
                        try {
                            val result = fileUploadManager.uploadFile(
                                context = context,
                                fileUri = it,
                                serverUrl = serverUrl,
                                onProgress = { progress ->
                                    uploadProgress = progress
                                }
                            )
                            if (result.isSuccess) {
                                // Notify via WebView JavaScript
                                webViewRef?.evaluateJavascript(
                                    "window.dispatchEvent(new CustomEvent('file-uploaded', {detail: {path: '${result.getOrNull()}'}}))",
                                    null
                                )
                                Toast.makeText(context, "上传成功", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "上传失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            isUploading = false
                            uploadProgress = ""
                        }
                    }
                }
            }

            // Voice input launcher
            val voicePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    voiceInputManager.startListening { text ->
                        webViewRef?.evaluateJavascript(
                            "window.dispatchEvent(new CustomEvent('voice-input', {detail: {text: '$text'}}))",
                            null
                        )
                    }
                }
            }

            if (showSettings && serverUrl.isEmpty()) {
                // First time setup - server URL entry
                SettingsScreen(
                    onSave = { url ->
                        serverUrl = url
                        preferencesManager.saveServerUrl(url)
                        showSettings = false
                    }
                )
            } else {
                // Main chat screen
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("OpenClaw") },
                            actions = {
                                IconButton(onClick = {
                                    webViewRef?.reload()
                                }) {
                                    Icon(Icons.Default.Refresh, "刷新")
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, "设置")
                                }
                            }
                        )
                    },
                    floatingActionButton = {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Voice input FAB
                            FloatingActionButton(
                                onClick = {
                                    if (micPermission.status.isGranted) {
                                        voiceInputManager.startListening { text ->
                                            webViewRef?.evaluateJavascript(
                                                "window.dispatchEvent(new CustomEvent('voice-input', {detail: {text: '$text'}}))",
                                                null
                                            )
                                        }
                                    } else {
                                        voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Icon(Icons.Default.Mic, "语音输入")
                            }

                            // File upload FAB
                            FloatingActionButton(
                                onClick = {
                                    filePickerLauncher.launch("*/*")
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Icon(Icons.Default.Upload, "上传文件")
                            }
                        }
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        // WebView
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        mediaPlaybackRequiresUserGesture = false
                                        allowFileAccess = true
                                        loadWithOverviewMode = true
                                        useWideViewPort = true
                                        builtInZoomControls = false
                                        displayZoomControls = false
                                        // Mobile optimized
                                        userAgentString = "$userAgentString OpenClawApp/1.0"
                                    }

                                    webViewClient = ChatWebViewClient { loaded ->
                                        isConnected = loaded
                                    }

                                    if (serverUrl.isNotEmpty()) {
                                        loadUrl(serverUrl)
                                    }

                                    webViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { webView ->
                                if (webView.url != serverUrl && serverUrl.isNotEmpty()) {
                                    webView.loadUrl(serverUrl)
                                }
                            }
                        )

                        // Upload progress overlay
                        if (isUploading) {
                            Surface(
                                modifier = Modifier.align(Alignment.Center),
                                shape = MaterialTheme.shapes.medium,
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Text(uploadProgress.ifEmpty { "上传中..." })
                                }
                            }
                        }

                        // Connection status
                        if (!isConnected && serverUrl.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopCenter),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    "连接中...",
                                    modifier = Modifier.padding(8.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Settings dialog
                if (showSettings && serverUrl.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = { showSettings = false },
                        title = { Text("设置") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = serverUrl,
                                    onValueChange = { serverUrl = it },
                                    label = { Text("服务器地址") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                preferencesManager.saveServerUrl(serverUrl)
                                webViewRef?.loadUrl(serverUrl)
                                showSettings = false
                            }) {
                                Text("保存")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSettings = false }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "新消息通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "接收 OpenClaw 新消息提醒"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startConnectionService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, ConnectionService::class.java))
        } else {
            startService(Intent(this, ConnectionService::class.java))
        }
    }

    fun showNotification(title: String, body: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(System.currentTimeMillis().toInt(), notification)
            }
        }
    }
}
