package com.openclaw.webchat.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.core.content.ContextCompat
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
    private var isPageLoaded by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    // Exposed to JavaScript via addJavascriptInterface
    @Suppress("unused")
    inner class WebAppInterface {
        fun setToken(token: String) {
            preferencesManager.saveToken(token)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Token 已保存", Toast.LENGTH_SHORT).show()
            }
        }

        fun getToken(): String = preferencesManager.getToken()

        fun showSettings() {
            runOnUiThread {
                // Signal to show native settings
            }
        }

        fun isDarkMode(): Boolean {
            return resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        fun getServerUrl(): String = serverUrl

        fun notify(name: String, data: String) {
            when (name) {
                "file-uploaded" -> {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "文件上传成功: $data", Toast.LENGTH_LONG).show()
                    }
                }
                "ready" -> {
                    isPageLoaded = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesManager = PreferencesManager(this)
        fileUploadManager = FileUploadManager()
        voiceInputManager = VoiceInputManager(this)

        setContent {
            var showSettingsDialog by remember { mutableStateOf(false) }
            var webViewRef by remember { mutableStateOf<WebView?>(null) }
            var isUploading by remember { mutableStateOf(false) }
            var uploadProgress by remember { mutableStateOf("") }
            var isInitializing by remember { mutableStateOf(true) }
            var tokenInput by remember { mutableStateOf("") }

            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            // Load saved server URL
            LaunchedEffect(Unit) {
                serverUrl = preferencesManager.getServerUrl()
                if (serverUrl.isEmpty()) {
                    serverUrl = "http://172.16.3.16:18789"
                }
                isInitializing = false
            }

            // Permission launcher
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

            if (isInitializing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
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
                        // Token input banner (shown when needed)
                        if (tokenInput.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shadowElevation = 4.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        "请在下方输入 Token 登录",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    OutlinedTextField(
                                        value = tokenInput,
                                        onValueChange = { tokenInput = it },
                                        label = { Text("Token") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            // Inject token into WebView
                                            webViewRef?.evaluateJavascript(
                                                """
                                                (function() {
                                                    var input = document.querySelector('input[type="text"], input[name="token"], input[id*="token"]');
                                                    if (input) { input.value = '$tokenInput'; input.dispatchEvent(new Event('input', {bubbles: true})); }
                                                    var btn = document.querySelector('button[type="submit"], button');
                                                    if (btn) btn.click();
                                                })();
                                                """.trimIndent(),
                                                null
                                            )
                                            tokenInput = ""
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("确认")
                                    }
                                }
                            }
                        }

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
                                        // Allow mixed content (HTTP on HTTPS page if needed)
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        }
                                        // Enable caching
                                        setCacheMode(WebSettings.LOAD_DEFAULT)
                                        // User agent for mobile
                                        userAgentString = "$userAgentString OpenClawApp/1.0 Android/${android.os.Build.VERSION.SDK_INT}"
                                    }

                                    // Clear cookies
                                    CookieManager.getInstance().apply {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                            flush()
                                        } else {
                                            @Suppress("DEPRECATION")
                                            acceptThirdPartyCookies(webView)
                                        }
                                    }

                                    webViewClient = ChatWebViewClient(
                                        onPageLoaded = { loaded ->
                                            isPageLoaded = loaded
                                            errorMessage = null
                                        },
                                        onError = { err ->
                                            errorMessage = err
                                        },
                                        onTokenNeeded = {
                                            tokenInput = "pending"
                                        }
                                    )

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                            Log.d("WebView", "Console: ${msg?.message()}")
                                            return super.onConsoleMessage(msg)
                                        }
                                    }

                                    // Add JavaScript interface
                                    addJavascriptInterface(WebAppInterface(), "OpenClawApp")

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

                        // Error display
                        errorMessage?.let { err ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = { webViewRef?.reload() }) {
                                        Text("重试")
                                    }
                                }
                            }
                        }

                        // Loading indicator
                        if (!isPageLoaded && errorMessage == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }

                // Settings dialog
                if (showSettingsDialog) {
                    var urlInput by remember { mutableStateOf(serverUrl) }
                    var sshHost by remember { mutableStateOf(preferencesManager.getSSHHost()) }
                    var sshPort by remember { mutableStateOf(preferencesManager.getSSHPort().toString()) }
                    var sshUser by remember { mutableStateOf(preferencesManager.getSSHUser()) }
                    var sshPass by remember { mutableStateOf(preferencesManager.getSSHPassword()) }

                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("设置") },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    label = { Text("服务器地址") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                HorizontalDivider()
                                Text("文件上传 (SCP)", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = sshHost,
                                    onValueChange = { sshHost = it },
                                    label = { Text("SSH 服务器") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = sshPort,
                                        onValueChange = { sshPort = it.filter { c -> c.isDigit() } },
                                        label = { Text("端口") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = sshUser,
                                        onValueChange = { sshUser = it },
                                        label = { Text("用户名") },
                                        singleLine = true,
                                        modifier = Modifier.weight(2f)
                                    )
                                }
                                OutlinedTextField(
                                    value = sshPass,
                                    onValueChange = { sshPass = it },
                                    label = { Text("SSH 密码") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                serverUrl = urlInput
                                preferencesManager.saveServerUrl(urlInput)
                                preferencesManager.saveSSHConfig(sshHost, sshPort.toIntOrNull() ?: 22, sshUser, sshPass)
                                webViewRef?.loadUrl(urlInput)
                                fileUploadManager.saveCredentials(
                                    this@MainActivity, sshHost,
                                    sshPort.toIntOrNull() ?: 22, sshUser, sshPass
                                )
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

    override fun onBackPressed() {
        // If WebView can go back, do it
        val webView = findViewById<WebView>(android.R.id.content)
        if (webView?.canGoBack() == true) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
