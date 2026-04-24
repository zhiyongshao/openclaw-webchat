package com.openclaw.webchat.openclaw

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OpenClaw Gateway WebSocket Client
 * 
 * Implements the OpenClaw v3 WebSocket protocol:
 * - Device pairing (Ed25519 challenge signing)
 * - Chat streaming (chat.send with delta/final events)
 * - Session management
 * 
 * Based on ClawControl (TypeScript) protocol implementation.
 */
class OpenClawWsClient(
    private val serverUrl: String,
    private val authToken: String,
    private val identityManager: DeviceIdentityManager
) {
    companion object {
        private const val TAG = "OpenClawWsClient"
        
        // Protocol version
        private const val MIN_PROTOCOL = 3
        private const val MAX_PROTOCOL = 3
        
        // Operator role scopes
        private val OPERATOR_SCOPES = listOf(
            "operator.read", "operator.write", "operator.admin", "operator.approvals"
        )
        
        // Timeouts (seconds)
        private const val CONNECT_TIMEOUT_SEC = 15L
        private const val REQUEST_TIMEOUT_SEC = 30L
        
        // Health check
        private const val HEALTH_CHECK_INTERVAL_MS = 15000L
    }

    // OkHttp
    private var webSocket: WebSocket? = null
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // State
    @Volatile var isAuthenticated = false
        private set
    @Volatile var isConnected = false
        private set

    @Volatile var defaultSessionKey = "main"
    
    // Server info
    @Volatile var serverVersion: String? = null

    // Pending RPC requests
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    // Event listeners
    private val listeners = ConcurrentHashMap<String, MutableList<(JSONObject) -> Unit>>()

    // Session stream state
    private val sessionStreams = ConcurrentHashMap<String, SessionStream>()

    // Reconnection
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 20
    private val handler = Handler(Looper.getMainLooper())

    // Data class for pending request
    private data class PendingRequest(
        val resolve: (JSONObject?) -> Unit,
        val reject: (Throwable) -> Unit,
        val timeoutRunnable: Runnable
    )

    // Data class for session stream state
    private data class SessionStream(
        var text: String = "",
        var started: Boolean = false,
        var finalized: Boolean = false,
        var runId: String? = null
    )

    // ─────────────────────────────────────────────────────────────
    // Connection lifecycle
    // ─────────────────────────────────────────────────────────────

    fun connect() {
        disconnectInternal(reconnect = false)

        val wsUrl = buildWsUrl(serverUrl)
        android.util.Log.d(TAG, "Connecting to $wsUrl")

        val originHeader = "http://172.16.3.16:18789"
        val request = Request.Builder().url(wsUrl).addHeader("Origin", originHeader).addHeader("Host", "172.16.3.16:18789").build()
        android.util.Log.d(TAG, "WS Request: url=$wsUrl origin=$originHeader")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.d(TAG, "WebSocket opened")
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e(TAG, "WebSocket failure: ${t.message}")
                t.printStackTrace()
                handleDisconnect()
                emitToListener("error", JSONObject().put("message", t.message))
            }
        })
    }

    fun disconnect() {
        disconnectInternal(reconnect = false)
    }

    private fun disconnectInternal(reconnect: Boolean) {
        reconnectAttempts = if (reconnect) reconnectAttempts else maxReconnectAttempts
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        isAuthenticated = false
        rejectAllPending("Connection closed")
    }

    private fun handleDisconnect() {
        isConnected = false
        isAuthenticated = false
        rejectAllPending("Connection lost")
        emitToListener("disconnected", JSONObject())
        sessionStreams.clear()

        if (reconnectAttempts < maxReconnectAttempts) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        val baseDelay = minOf(1000L * (1 shl (reconnectAttempts - 1)), 30000L)
        val jitter = (baseDelay * 0.25 * Math.random()).toLong()
        val delay = baseDelay + jitter

        handler.postDelayed({
            if (reconnectAttempts < maxReconnectAttempts && !isAuthenticated) {
                android.util.Log.d(TAG, "Reconnect attempt $reconnectAttempts")
                connect()
            }
        }, delay)
    }

    private fun buildWsUrl(url: String): String {
        val normalized = url
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/')
        return if (normalized.contains(":18789")) {
            normalized
        } else {
            "$normalized:18789"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Message handling
    // ─────────────────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "event" -> handleEvent(json)
                "res" -> handleResponse(json)
                else -> android.util.Log.w(TAG, "Unknown msg type: ${json.optString("type")}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun handleEvent(json: JSONObject) {
        val event = json.optString("event")
        val payload = json.optJSONObject("payload") ?: JSONObject()

        when (event) {
            "connect.challenge" -> {
                val nonce = payload.optString("nonce")
                android.util.Log.d(TAG, "Got challenge nonce: $nonce")
                performHandshake(nonce)
            }
            "chat" -> {
                handleChatEvent(payload)
            }
            "tick" -> {
                // Server tick - connection is alive
            }
            "hello-ok" -> {
                // Successful connect event (not an RPC response)
                val wasAuth = isAuthenticated
                isAuthenticated = true
                reconnectAttempts = 0
                serverVersion = payload.optString("runtimeVersion", null)
                android.util.Log.d(TAG, "hello-ok! wasAuth=$wasAuth Server: $serverVersion")
                // Always emit connected (even on reconnect, for UI state update)
                emitToListener("connected", payload)
            }
            else -> {
                emitToListener(event, payload)
            }
        }
    }

    private fun handleResponse(json: JSONObject) {
        val id = json.optString("id")
        val pending = pendingRequests.remove(id) ?: return

        // Cancel timeout
        handler.removeCallbacks(pending.timeoutRunnable)

        if (json.optBoolean("ok")) {
            val payload = json.optJSONObject("payload")
            android.util.Log.d(TAG, "handleResponse OK: payload=" + payload)

            // Check for hello-ok (successful connect)
            if (payload?.optString("type") == "hello-ok") {
                val wasAuth = isAuthenticated
                isAuthenticated = true
                reconnectAttempts = 0
                serverVersion = payload.optString("runtimeVersion", null)
                android.util.Log.d(TAG, "hello-ok! wasAuth=$wasAuth Server: $serverVersion")
                // Always emit connected, even on reconnect (for UI state update)
                emitToListener("connected", payload)
                if (wasAuth) {
                    pending.resolve(payload)
                }
            } else {
                pending.resolve(payload)
            }
        } else {
            val errorCode = json.optJSONObject("error")?.optString("code") ?: ""
            val errorMsg = json.optJSONObject("error")?.optString("message") ?: "Unknown error"
            android.util.Log.e(TAG, "handleResponse ERROR: code=" + errorCode + " msg=" + errorMsg)

            if (errorCode == "NOT_PAIRED") {
                emitToListener("pairingRequired", JSONObject()
                    .put("deviceId", identityManager.getOrCreateDeviceIdentity()?.id))
            }

            pending.reject(Exception(errorMsg))
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Handshake
    // ─────────────────────────────────────────────────────────────

    private fun performHandshake(nonce: String) {
        val scopes = OPERATOR_SCOPES

        val deviceField = if (nonce.isNotEmpty() && authToken.isNotEmpty()) {
            identityManager.signChallenge(nonce, authToken, scopes)
        } else null

        val params = JSONObject().apply {
            put("minProtocol", MIN_PROTOCOL)
            put("maxProtocol", MAX_PROTOCOL)
            put("role", "operator")
            put("scopes", JSONArray(scopes))
            put("client", JSONObject().apply {
                put("id", DeviceIdentityManager.OPENCLAW_CLIENT_ID)
                put("displayName", "OpenClaw WebChat")
                put("version", "1.0.0")
                put("platform", "android")
                put("mode", DeviceIdentityManager.OPENCLAW_CLIENT_MODE)
            })
            put("caps", JSONArray(listOf("tool-events", "thinking-events", "plugin-approvals")))
            put("auth", JSONObject().apply {
                put("token", authToken)
                deviceField?.let { dev ->
                    put("deviceId", dev.id)
                    put("publicKey", dev.publicKey)
                    put("signature", dev.signature)
                    put("signedAt", dev.signedAt)
                    put("nonce", dev.nonce)
                }
            })
        }

        android.util.Log.d(TAG, "performHandshake auth: ${params.optJSONObject("auth")}")
        sendRpc("connect", params)
    }

    // ─────────────────────────────────────────────────────────────
    // Chat events
    // ─────────────────────────────────────────────────────────────

    private fun handleChatEvent(payload: JSONObject) {
        val state = payload.optString("state")
        val sessionKey = payload.optString("sessionKey", defaultSessionKey)

        val ss = sessionStreams.getOrPut(sessionKey) { SessionStream() }

        when (state) {
            "delta" -> {
                if (!ss.started) {
                    ss.started = true
                    emitToListener("streamStart", JSONObject().put("sessionKey", sessionKey))
                }

                val rawText = extractText(payload.optJSONObject("message")?.opt("content"))
                if (rawText.isNotBlank() && !isNoise(rawText)) {
                    ss.text += rawText
                    emitToListener("streamChunk", JSONObject()
                        .put("text", rawText)
                        .put("fullText", ss.text)
                        .put("sessionKey", sessionKey))
                }
            }
            "final" -> {
                ss.finalized = true
                val finalText = extractText(payload.optJSONObject("message")?.opt("content")).ifEmpty { ss.text }
                val runId = payload.optString("runId")

                emitToListener("message", JSONObject().apply {
                    put("id", payload.optString("messageId", UUID.randomUUID().toString()))
                    put("role", "assistant")
                    put("content", finalText)
                    put("timestamp", payload.optString("timestamp", java.time.Instant.now().toString()))
                    put("sessionKey", sessionKey)
                    put("runId", runId)
                })
                emitToListener("streamEnd", JSONObject().put("sessionKey", sessionKey))
                sessionStreams.remove(sessionKey)
            }
            "error" -> {
                val errorMsg = payload.optString("errorMessage", "Server error")
                emitToListener("message", JSONObject().apply {
                    put("id", "error-${System.currentTimeMillis()}")
                    put("role", "system")
                    put("content", "Server error: $errorMsg")
                    put("sessionKey", sessionKey)
                })
                emitToListener("streamEnd", JSONObject().put("sessionKey", sessionKey))
                sessionStreams.remove(sessionKey)
            }
        }
    }

    private fun extractText(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val obj = content.optJSONObject(i) ?: continue
                    val type = obj.optString("type")
                    if (type == "text" || type == "input_text" || type == "output_text") {
                        append(obj.optString("text", ""))
                    }
                }
            }
            is JSONObject -> {
                val type = content.optString("type")
                if (type == "text" || type == "input_text" || type == "output_text") {
                    content.optString("text", "")
                } else ""
            }
            else -> ""
        }
    }

    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || t == "..." || t == "…" || t.length < 2
    }

    // ─────────────────────────────────────────────────────────────
    // RPC calls
    // ─────────────────────────────────────────────────────────────

    private fun sendRpc(method: String, params: JSONObject? = null): String {
        val id = UUID.randomUUID().toString()
        val request = JSONObject().apply {
            put("type", "req")
            put("id", id)
            put("method", method)
            if (params != null) {
                put("params", params)
            }
        }
        android.util.Log.d(TAG, "sendRpc: ${request.toString().take(300)}")
        webSocket?.send(request.toString())
        return id
    }

    /**
     * Make an async RPC call. Callback is called on main thread.
     */
    fun call(method: String, params: JSONObject? = null, callback: (Result<JSONObject>) -> Unit) {
        val id = sendRpc(method, params)
        val timeout = Runnable {
            if (pendingRequests.remove(id) != null) {
                callback(Result.failure(Exception("Request timeout: $method")))
            }
        }
        handler.postDelayed(timeout, REQUEST_TIMEOUT_SEC * 1000)
        pendingRequests[id] = PendingRequest(
            resolve = { payload -> callback(Result.success(payload ?: JSONObject())) },
            reject = { e -> callback(Result.failure(e)) },
            timeoutRunnable = timeout
        )
    }

    /**
     * Send a text message. Responses come via event listeners.
     */
    fun sendMessage(content: String, sessionKey: String? = null) {
        val sk = sessionKey ?: defaultSessionKey
        defaultSessionKey = sk
        sessionStreams.remove(sk)

        val params = JSONObject().apply {
            put("sessionKey", sk)
            put("message", JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }
        sendRpc("chat.send", params)
    }

    /**
     * Send an image message.
     */
    fun sendImage(imageBase64: String, mimeType: String, filename: String, sessionKey: String? = null) {
        val sk = sessionKey ?: defaultSessionKey
        defaultSessionKey = sk
        sessionStreams.remove(sk)

        val params = JSONObject().apply {
            put("sessionKey", sk)
            put("message", JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("mimeType", mimeType)
                        put("fileName", filename)
                        put("content", imageBase64)
                    })
                })
            })
        }
        sendRpc("chat.send", params)
    }

    /**
     * Get chat history for a session.
     */
    fun getHistory(sessionKey: String, callback: (Result<List<ChatMessage>>) -> Unit) {
        val params = JSONObject().apply { put("sessionKey", sessionKey) }
        call("chat.history", params, object : (Result<JSONObject>) -> Unit {
            override fun invoke(r: Result<JSONObject>) {
                r.fold(
                    onSuccess = { payload ->
                        val msgs = parseMessages(payload)
                        callback(Result.success(msgs))
                    },
                    onFailure = { e -> callback(Result.failure(e)) }
                )
            }
        })
    }

    private fun parseMessages(payload: JSONObject): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val arr = payload.optJSONArray("messages")
            ?: payload.optJSONArray("history")
            ?: payload.optJSONArray("entries")
            ?: return messages

        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val msg = m.optJSONObject("message") ?: m
            val role = msg.optString("role", "assistant")
            val content = extractText(msg.opt("content"))
            if (content.isNotBlank()) {
                val thinking = (msg.opt("content") as? JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        if (item != null && item.optString("type") == "thinking") {
                            return@let item.optString("thinking")
                        }
                    }
                    null
                }
                messages.add(ChatMessage(
                    id = msg.optString("id", UUID.randomUUID().toString()),
                    role = role,
                    content = content,
                    timestamp = msg.optString("timestamp", ""),
                    thinking = thinking
                ))
            }
        }
        return messages
    }

    /**
     * List all sessions.
     */
    fun listSessions(callback: (Result<List<SessionInfo>>) -> Unit) {
        call("sessions.list", null, object : (Result<JSONObject>) -> Unit {
            override fun invoke(r: Result<JSONObject>) {
                r.fold(
                    onSuccess = { payload ->
                        val sessions = mutableListOf<SessionInfo>()
                        val arr = payload.optJSONArray("sessions") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            sessions.add(SessionInfo(
                                key = s.optString("key"),
                                title = s.optString("title", "Chat"),
                                agentId = s.optString("agentId", null),
                                updatedAt = s.optString("updatedAt", "")
                            ))
                        }
                        callback(Result.success(sessions))
                    },
                    onFailure = { e -> callback(Result.failure(e)) }
                )
            }
        })
    }

    /**
     * Abort in-progress chat.
     */
    fun abort(sessionKey: String? = null) {
        val params = JSONObject().apply {
            put("sessionKey", sessionKey ?: defaultSessionKey)
        }
        sendRpc("chat.abort", params)
    }

    // ─────────────────────────────────────────────────────────────
    // Event listeners
    // ─────────────────────────────────────────────────────────────

    fun on(event: String, listener: (JSONObject) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(listener)
    }

    fun off(event: String, listener: ((JSONObject) -> Unit)? = null) {
        if (listener == null) {
            listeners.remove(event)
        } else {
            listeners[event]?.remove(listener)
        }
    }

    private fun emitToListener(event: String, payload: JSONObject) {
        handler.post {
            listeners[event]?.forEach { listener ->
                try { listener(payload) } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun rejectAllPending(reason: String) {
        pendingRequests.forEach { (_, pending) ->
            handler.post {
                pending.reject(Exception(reason))
                handler.removeCallbacks(pending.timeoutRunnable)
            }
        }
        pendingRequests.clear()
    }

    // ─────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────

    data class ChatMessage(
        val id: String,
        val role: String,  // "user", "assistant", "system"
        val content: String,
        val timestamp: String = "",
        val thinking: String? = null
    )

    data class SessionInfo(
        val key: String,
        val title: String,
        val agentId: String?,
        val updatedAt: String
    )

    data class DeviceConnectField(
        val id: String,
        val publicKey: String,
        val signature: String,
        val signedAt: Long,
        val nonce: String
    )
}
