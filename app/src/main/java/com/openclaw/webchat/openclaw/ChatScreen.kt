package com.openclaw.webchat.openclaw

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Main chat screen using WebSocket connection to OpenClaw Gateway.
 * Replaces the WebView-based Control UI with direct WS communication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    serverUrl: String,
    token: String,
    onLogout: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    // Save to temp file for ViewModel
                    val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
                    tempFile.writeBytes(bytes)
                    viewModel.sendImage(tempFile)
                    tempFile.deleteOnExit()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Initialize ViewModel once
    LaunchedEffect(serverUrl, token) {
        viewModel.initialize(serverUrl, token)
        viewModel.connect()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenClaw", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when (state.connectionState) {
                                ChatViewModel.ConnectionState.Disconnected -> "未连接"
                                ChatViewModel.ConnectionState.Connecting -> "连接中..."
                                ChatViewModel.ConnectionState.WaitingForPairing -> "等待配对"
                                ChatViewModel.ConnectionState.Connected -> "已连接"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (state.connectionState) {
                                ChatViewModel.ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                                ChatViewModel.ConnectionState.WaitingForPairing -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                },
                actions = {
                    // Sessions menu
                    if (state.sessions.isNotEmpty()) {
                        var sessionsExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { sessionsExpanded = true }) {
                            Icon(Icons.Default.SwitchAccount, "切换会话")
                        }
                        DropdownMenu(
                            expanded = sessionsExpanded,
                            onDismissRequest = { sessionsExpanded = false }
                        ) {
                            state.sessions.forEach { session ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(session.title, style = MaterialTheme.typography.bodyMedium)
                                            if (session.key != "main") {
                                                Text(
                                                    session.key,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.switchSession(session.key)
                                        sessionsExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Chat, null, modifier = Modifier.size(20.dp))
                                    }
                                )
                            }
                        }
                    }

                    // Connection status indicator
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                when (state.connectionState) {
                                    ChatViewModel.ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                                    ChatViewModel.ConnectionState.WaitingForPairing -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )

                    IconButton(onClick = {
                        viewModel.disconnect()
                        viewModel.connect()
                    }) {
                        Icon(Icons.Default.Refresh, "重连")
                    }

                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, "退出")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Pairing required view
            if (state.connectionState == ChatViewModel.ConnectionState.WaitingForPairing) {
                PairingCard(
                    deviceId = state.pairingDeviceId ?: "",
                    serverUrl = serverUrl,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Error banner
            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Messages list
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.messages.isEmpty() && state.connectionState == ChatViewModel.ConnectionState.Connected) {
                    item {
                        EmptyChatPlaceholder(modifier = Modifier.fillMaxWidth())
                    }
                }

                items(state.messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Streaming indicator
                if (state.streamingSessionKey != null) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "正在输入...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Input area
            ChatInputBar(
                currentText = state.currentText,
                onTextChange = { viewModel.setCurrentText(it) },
                onSend = { viewModel.sendMessage(state.currentText) },
                onAbort = { viewModel.abort() },
                onAttach = { imagePickerLauncher.launch("image/*") },
                isStreaming = state.streamingSessionKey != null,
                isConnected = state.connectionState == ChatViewModel.ConnectionState.Connected,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.disconnect()
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatViewModel.ChatMessageUi,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"

    Column(
        modifier = modifier,
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isSystem) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.horizontalScroll(state = rememberScrollState())
            ) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        } else {
            Surface(
                color = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (message.isStreaming) {
                        // Streaming cursor blink
                        Text(
                            "▊",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (message.timestamp.isNotEmpty()) {
                Text(
                    formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Chat,
            null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "开始对话吧",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "已连接到 OpenClaw Gateway",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun ChatInputBar(
    currentText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onAttach: () -> Unit,
    isStreaming: Boolean,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Attachment button
            IconButton(
                onClick = onAttach,
                enabled = isConnected && !isStreaming,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    "附件",
                    modifier = Modifier.size(20.dp),
                    tint = if (isConnected && !isStreaming) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }

            if (isStreaming) {
                // Show abort button when streaming
                FilledTonalIconButton(
                    onClick = onAbort,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Stop, "停止", modifier = Modifier.size(20.dp))
                }
            }

            OutlinedTextField(
                value = currentText,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        if (!isConnected) "等待连接..." else "输入消息",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                enabled = isConnected && !isStreaming,
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                trailingIcon = {
                    if (currentText.isNotEmpty()) {
                        IconButton(
                            onClick = { onTextChange("") },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.Clear, "清空", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            )

            FilledIconButton(
                onClick = onSend,
                enabled = isConnected && currentText.isNotBlank() && !isStreaming,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Send, "发送", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PairingCard(
    deviceId: String,
    serverUrl: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Link,
                null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "设备配对",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "请在 Control UI 中批准此设备",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    deviceId.take(16) + "...",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun formatTimestamp(ts: String): String {
    return try {
        val instant = Instant.parse(ts)
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        ""
    }
}
