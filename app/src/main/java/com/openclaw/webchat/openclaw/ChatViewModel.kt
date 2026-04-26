package com.openclaw.webchat.openclaw

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * ViewModel for the OpenClaw WebSocket chat.
 * Manages connection state, message history, and chat operations.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // ─────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────

    data class UiState(
        val connectionState: ConnectionState = ConnectionState.Disconnected,
        val messages: List<ChatMessageUi> = emptyList(),
        val currentText: String = "",
        val streamingSessionKey: String? = null,
        val sessions: List<OpenClawWsClient.SessionInfo> = emptyList(),
        val pairingUrl: String? = null,
        val pairingDeviceId: String? = null,
        val error: String? = null,
        val showWorkingProcess: Boolean = false
    )

    enum class ConnectionState {
        Disconnected,
        Connecting,
        WaitingForPairing,
        Connected
    }

    data class ChatMessageUi(
        val id: String,
        val role: String,  // "user", "assistant", "system"
        val content: String,
        val timestamp: String = "",
        val isStreaming: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var wsClient: OpenClawWsClient? = null
    private lateinit var identityManager: DeviceIdentityManager

    // ─────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────

    fun initialize(serverUrl: String, token: String) {
        identityManager = DeviceIdentityManager(getApplication())
        wsClient = OpenClawWsClient(serverUrl, token, identityManager)

        // Fallback: if hello-ok already fired before listeners were registered, sync state
        wsClient?.let { client ->
            if (client.isConnectedSynchronized) {
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.Connected,
                    pairingUrl = null,
                    pairingDeviceId = null,
                    error = null
                )
            }
        }

        setupListeners()
    }

    private fun setupListeners() {
        val client = wsClient ?: return

        client.on("connected") { payload ->
            android.util.Log.d(TAG, "[DEBUG] connected listener FIRED! payload=$payload")
            Log.d(TAG, "Connected to OpenClaw")
            _state.value = _state.value.copy(
                connectionState = ConnectionState.Connected,
                pairingUrl = null,
                pairingDeviceId = null,
                error = null
            )
            android.util.Log.d(TAG, "[DEBUG] _state.connectionState is now: ${_state.value.connectionState}")
            _state.value = _state.value.copy(
                connectionState = ConnectionState.Connected,
                pairingUrl = null,
                pairingDeviceId = null,
                error = null
            )
            // Load history
            loadHistory()
            // List sessions
            listSessions()
        }

        client.on("disconnected") {
            _state.value = _state.value.copy(connectionState = ConnectionState.Disconnected)
        }

        client.on("error") { payload ->
            val msg = payload.optString("message", "Unknown error")
            Log.e(TAG, "WS Error: $msg")
            _state.value = _state.value.copy(error = msg)
        }

        client.on("pairingRequired") { payload ->
            val deviceId = payload.optString("deviceId")
            Log.d(TAG, "Pairing required! Device ID: $deviceId")
            _state.value = _state.value.copy(
                connectionState = ConnectionState.WaitingForPairing,
                pairingDeviceId = deviceId
            )
        }

        client.on("streamStart") { payload ->
            val sk = payload.optString("sessionKey")
            Log.d(TAG, "Stream start: $sk")
            _state.value = _state.value.copy(streamingSessionKey = sk)
        }

        client.on("streamChunk") { payload ->
            val text = payload.optString("text")
            val sk = payload.optString("sessionKey")
            appendToLastMessage(text, isStreaming = true)
        }

        client.on("streamEnd") { payload ->
            val sk = payload.optString("sessionKey")
            Log.d(TAG, "Stream end: $sk")
            finishStreamingMessage(sk)
            _state.value = _state.value.copy(streamingSessionKey = null)
        }

        client.on("message") { payload ->
            val msg = ChatMessageUi(
                id = payload.optString("id"),
                role = payload.optString("role", "assistant"),
                content = payload.optString("content"),
                timestamp = payload.optString("timestamp"),
                isStreaming = false
            )
            addMessage(msg)
            _state.value = _state.value.copy(streamingSessionKey = null)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Connection
    // ─────────────────────────────────────────────────────────────

    fun connect() {
        val client = wsClient ?: return
        _state.value = _state.value.copy(
            connectionState = ConnectionState.Connecting,
            error = null
        )
        client.connect()
    }

    fun disconnect() {
        wsClient?.disconnect()
        _state.value = _state.value.copy(
            connectionState = ConnectionState.Disconnected,
            messages = emptyList()
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Chat operations
    // ─────────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val client = wsClient ?: return

        // Add user message immediately
        addMessage(ChatMessageUi(
            id = "user-${System.currentTimeMillis()}",
            role = "user",
            content = text,
            timestamp = java.time.Instant.now().toString(),
            isStreaming = false
        ))

        // Clear current text
        _state.value = _state.value.copy(currentText = "")

        // Send via WS
        client.sendMessage(text)
    }

    fun sendImage(file: File, mimeType: String = "image/jpeg") {
        val client = wsClient ?: return

        val bytes = FileInputStream(file).use { it.readBytes() }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val filename = file.name

        // Add user message immediately
        addMessage(ChatMessageUi(
            id = "user-img-${System.currentTimeMillis()}",
            role = "user",
            content = "[📎 Image: $filename]",
            timestamp = java.time.Instant.now().toString(),
            isStreaming = false
        ))

        client.sendImage(base64, mimeType, filename)
    }

    fun abort() {
        wsClient?.abort()
    }

    private fun loadHistory() {
        val client = wsClient ?: return
        client.getHistory(client.defaultSessionKey, object : (Result<List<OpenClawWsClient.ChatMessage>>) -> Unit {
            override fun invoke(r: Result<List<OpenClawWsClient.ChatMessage>>) {
                r.fold(
                    onSuccess = { msgs -> 
                        val uiMsgs = msgs.map { 
                            ChatMessageUi(it.id, it.role, it.content, it.timestamp) 
                        }
                        _state.value = _state.value.copy(messages = uiMsgs)
                    },
                    onFailure = { e -> Log.e(TAG, "History error: ${e.message}") }
                )
            }
        })
    }

    private fun listSessions() {
        val client = wsClient ?: return
        client.listSessions(object : (Result<List<OpenClawWsClient.SessionInfo>>) -> Unit {
            override fun invoke(r: Result<List<OpenClawWsClient.SessionInfo>>) {
                r.fold(
                    onSuccess = { sessions ->
                        _state.value = _state.value.copy(sessions = sessions)
                    },
                    onFailure = { }
                )
            }
        })
    }

    // ─────────────────────────────────────────────────────────────
    // State helpers
    // ─────────────────────────────────────────────────────────────

    private fun addMessage(msg: ChatMessageUi) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + msg
        )
    }

    private fun appendToLastMessage(text: String, isStreaming: Boolean) {
        val msgs = _state.value.messages.toMutableList()
        if (msgs.isNotEmpty() && msgs.last().role == "assistant" && isStreaming) {
            msgs[msgs.lastIndex] = msgs.last().copy(
                content = msgs.last().content + text,
                isStreaming = true
            )
        } else {
            msgs.add(ChatMessageUi(
                id = "streaming-${System.currentTimeMillis()}",
                role = "assistant",
                content = text,
                isStreaming = true
            ))
        }
        _state.value = _state.value.copy(messages = msgs)
    }

    private fun finishStreamingMessage(sessionKey: String) {
        val msgs = _state.value.messages.toMutableList()
        if (msgs.isNotEmpty() && msgs.last().isStreaming) {
            msgs[msgs.lastIndex] = msgs.last().copy(isStreaming = false)
        }
        _state.value = _state.value.copy(messages = msgs)
    }

    fun setCurrentText(text: String) {
        _state.value = _state.value.copy(currentText = text)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(messages = emptyList())
    }

    fun toggleWorkingProcess() {
        _state.value = _state.value.copy(showWorkingProcess = !_state.value.showWorkingProcess)
    }

    fun switchSession(sessionKey: String) {
        wsClient?.defaultSessionKey = sessionKey
        _state.value = _state.value.copy(messages = emptyList())
        loadHistory()
    }

    override fun onCleared() {
        super.onCleared()
        wsClient?.disconnect()
    }
}
