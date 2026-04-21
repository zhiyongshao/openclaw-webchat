package com.openclaw.webchat.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.openclaw.webchat.upload.FileUploadManager
import com.openclaw.webchat.util.PreferencesManager
import com.openclaw.webchat.voice.VoiceInputManager
import com.openclaw.webchat.web.ChatWebViewCallback
import com.openclaw.webchat.web.ChatWebViewClient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fileUploadManager: FileUploadManager
    private lateinit var voiceInputManager: VoiceInputManager

    @SuppressLint("WARN")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesManager = PreferencesManager(this)
        fileUploadManager = FileUploadManager()
        voiceInputManager = VoiceInputManager(this)

        setContent {
            var isLoggedIn by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var webViewRef by remember { mutableStateOf<WebView?>(null) }

            // Check if already has token
            LaunchedEffect(Unit) {
                isLoggedIn = preferencesManager.getToken().isNotEmpty()
            }

            if (showSettings) {
                SettingsScreen(
                    preferencesManager = preferencesManager,
                    onDismiss = { showSettings = false },
                    onLogout = {
                        showSettings = false
                        isLoggedIn = false
                    }
                )
            } else if (!isLoggedIn) {
                LoginScreen(
                    serverUrl = preferencesManager.getServerUrl(),
                    onLogin = { url, token ->
                        preferencesManager.saveServerUrl(url)
                        preferencesManager.saveToken(token)
                        isLoggedIn = true
                    },
                    onOpenSettings = { showSettings = true }
                )
            } else {
                MainScreen(
                    preferencesManager = preferencesManager,
                    fileUploadManager = fileUploadManager,
                    voiceInputManager = voiceInputManager,
                    onOpenSettings = { showSettings = true },
                    onLogout = {
                        isLoggedIn = false
                    }
                )
            }
        }
    }

    override fun onBackPressed() {
        // Let WebView handle back first
        super.onBackPressed()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    serverUrl: String,
    onLogin: (String, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var url by remember { mutableStateOf(serverUrl.ifEmpty { "http://172.16.3.16:18789" }) }
    var token by remember { mutableStateOf("") }
    var showUrlError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw 登录") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "连接到 OpenClaw 服务器",
                style = MaterialTheme.typography.headlineSmall
            )

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    showUrlError = false
                },
                label = { Text("服务器地址") },
                placeholder = { Text("http://172.16.3.16:18789") },
                singleLine = true,
                isError = showUrlError,
                supportingText = if (showUrlError) {{ Text("请输入有效的服务器地址") }} else null,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                placeholder = { Text("输入你的访问令牌") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        onLogin(url, token)
                    } else {
                        showUrlError = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Login, null)
                Spacer(Modifier.width(8.dp))
                Text("连接")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferencesManager: PreferencesManager,
    onDismiss: () -> Unit,
    onLogout: () -> Unit
) {
    var url by remember { mutableStateOf(preferencesManager.getServerUrl()) }
    var sshHost by remember { mutableStateOf(preferencesManager.getSSHHost()) }
    var sshPort by remember { mutableStateOf(preferencesManager.getSSHPort().toString()) }
    var sshUser by remember { mutableStateOf(preferencesManager.getSSHUser()) }
    var sshPass by remember { mutableStateOf(preferencesManager.getSSHPassword()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("服务器", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text("文件上传 (SCP)", style = MaterialTheme.typography.titleMedium)
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
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("退出登录")
                }
                Button(
                    onClick = {
                        preferencesManager.saveServerUrl(url)
                        preferencesManager.saveSSHConfig(
                            sshHost,
                            sshPort.toIntOrNull() ?: 22,
                            sshUser,
                            sshPass
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    preferencesManager: PreferencesManager,
    fileUploadManager: FileUploadManager,
    voiceInputManager: VoiceInputManager,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(preferencesManager.getServerUrl()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPageLoaded by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var jsErrorLog by remember { mutableStateOf("") }
    var showDebugDialog by remember { mutableStateOf(false) }

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
                        onProgress = { progress -> uploadProgress = progress }
                    )
                    if (result.isSuccess) {
                        Toast.makeText(context, "上传成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "上传失败", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    isUploading = false
                    uploadProgress = ""
                }
            }
        }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw") },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                    IconButton(onClick = onOpenSettings) {
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
                    onClick = { filePickerLauncher.launch("*/*") },
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
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = false
                            displayZoomControls = false
                            // Desktop-like UA
                            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }
                            setCacheMode(WebSettings.LOAD_DEFAULT)
                        }

                        // Enable cookies for session persistence
                        CookieManager.getInstance().setAcceptCookie(true)

                        val chatClient = ChatWebViewClient(object : ChatWebViewCallback {
                            override fun onPageLoaded(success: Boolean) {
                                isPageLoaded = success
                                isLoading = false
                                errorMessage = null
                                // Inject token into localStorage on page load
                                val token = preferencesManager.getToken()
                                if (success && token.isNotEmpty() && webViewRef != null) {
                                    webViewRef?.evaluateJavascript(
                                        "try { localStorage.setItem('token', '$token'); localStorage.setItem('openclaw_token', '$token'); } catch(e) { console.log('token inject err: ' + e); } "
                                    ) { Log.d("MainActivity", "Token injected: $it") }
                                }
                            }
                            override fun onError(message: String) {
                                errorMessage = message
                                isLoading = false
                            }
                            override fun onPageStarted(url: String) {
                                isLoading = true
                                currentUrl = url
                            }
                        })
                        webViewClient = chatClient.webViewClient

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                pageTitle = title ?: ""
                            }
                        }

                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun setToken(token: String) {
                                preferencesManager.saveToken(token)
                            }
                            @android.webkit.JavascriptInterface
                            fun getToken(): String = preferencesManager.getToken()
                            @android.webkit.JavascriptInterface
                            fun log(msg: String) {
                                Log.d("OpenClawApp", msg)
                            }
                            @android.webkit.JavascriptInterface
                            fun onJsError(msg: String) {
                                Log.e("OpenClawApp", "JS Error: $msg")
                            }
                        }, "OpenClawApp")

                        // Load URL (without token in URL params)
                        loadUrl(serverUrl)
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Debug info bar (click to show page info)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(Modifier.clickable { showDebugDialog = true }),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = if (isLoading) "加载中: $currentUrl"
                               else if (isPageLoaded) "已加载: ${pageTitle.ifEmpty { currentUrl }}"
                               else "等待加载: $serverUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (jsErrorLog.isNotEmpty()) {
                        Text(
                            text = "JS错误: $jsErrorLog",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = "点击查看页面信息",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // Debug dialog
            if (showDebugDialog) {
                AlertDialog(
                    onDismissRequest = { showDebugDialog = false },
                    title = { Text("页面调试信息") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("标题: $pageTitle", style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider()
                            Text("URL: $currentUrl", style = MaterialTheme.typography.bodySmall)
                            Text("页面已加载: $isPageLoaded", style = MaterialTheme.typography.bodySmall)
                            if (jsErrorLog.isNotEmpty()) {
                                Text("JS错误: $jsErrorLog", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            HorizontalDivider()
                            Button(
                                onClick = {
                                    showDebugDialog = false
                                    webViewRef?.reload()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text("刷新页面")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDebugDialog = false }) {
                            Text("关闭")
                        }
                    }
                )
            }

            // Loading overlay
            if (!isPageLoaded && !errorMessage.isNullOrEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Error overlay
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

            // Upload progress
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
        }
    }
}
