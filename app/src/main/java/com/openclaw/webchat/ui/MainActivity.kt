package com.openclaw.webchat.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.openclaw.webchat.notification.ConnectionService
import com.openclaw.webchat.upload.FileUploadManager
import com.openclaw.webchat.util.PreferencesManager
import com.openclaw.webchat.voice.VoiceInputManager
import com.openclaw.webchat.web.ChatWebViewClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fileUploadManager: FileUploadManager
    private lateinit var voiceInputManager: VoiceInputManager

    private var serverUrl by mutableStateOf("")
    private var isConnected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesManager = PreferencesManager(this)
        fileUploadManager = FileUploadManager()
        voiceInputManager = VoiceInputManager(this)

        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var webViewRef by remember { mutableStateOf<WebView?>(null) }
            var isUploading by remember { mutableStateOf(false) }
            var uploadProgress by remember { mutableStateOf("") }
            var showSettingsDialog by remember { mutableStateOf(false) }

            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            // Load saved server URL on first composition
            LaunchedEffect(Unit) {
                serverUrl = preferencesManager.getServerUrl()
                if (serverUrl.isEmpty()) {
                    showSettings = true
                }
            }

            // Permission launchers
            val micPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    voiceInputManager.startListening { text ->
                        webViewRef?.evaluateJavascript(
                            "window.dispatchEvent(new CustomEvent('voice-input', {detail: {text: '${text.replace("'", "\\'")}'}}))",
                            null
                        )
                    }
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

            // Show settings on first launch
            if (showSettings && serverUrl.isEmpty()) {
                SettingsScreen(
                    onSave = { url ->
                        serverUrl = url
                        preferencesManager.saveServerUrl(url)
                        showSettings = false
                    }
                )
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("OpenClaw") },
                            actions = {
                                IconButton(onClick = { webViewRef?.reload() }) {
                                    Icon(Icons.Default.Refresh, "刷新")
                                }
                                IconButton(onClick = { showSettingsDialog = true }) {
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
                            FloatingActionButton(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                        == PackageManager.PERMISSION_GRANTED) {
                                        voiceInputManager.startListening { text ->
                                            webViewRef?.evaluateJavascript(
                                                "window.dispatchEvent(new CustomEvent('voice-input', {detail: {text: '${text.replace("'", "\\'")}'}}))",
                                                null
                                            )
                                        }
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Icon(Icons.Default.Mic, "语音输入")
                            }

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

                if (showSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
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
                                showSettingsDialog = false
                            }) {
                                Text("保存")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSettingsDialog = false }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }
}
