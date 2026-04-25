package com.openclaw.webchat.openclaw

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenClaw Gateway WebSocket Client using Java-WebSocket library.
 * 
 * Replaces OkHttp WebSocket with org.java-websocket for better
 * WebSocket frame handling and more reliable onMessage callback.
 * 
 * Implements OpenClaw v3 WebSocket protocol:
 * - Device pairing (Ed25519 challenge signing)
 * - Chat streaming (chat.send with delta/final events)
 * - Session management
 */
class JavaWebSocketClient(
    private val serverUrl: String,
    private val authToken: String,
    private val identityManager: DeviceIdentityManager
) {
    companion object {
        private const val TAG = "JavaWsClient"
        
        // Protocol version
        private const val MIN_PROTOCOL = 3
        private const val MAX_PROTOCOL = 3
        
        // Operator role scopes
        private val OPERATOR_SCOPES = listOf(
            "operator.read", "operator.write", "operator.admin", "operator.approvals"
        )
        
        // Timeouts (seconds)
        private const val CONNECT_TIMEOUT_MS = 15000L
        private const val REQUEST_TIMEOUT_MS = 30000L
    }

    // Java-WebSocket
    private var wsClient: WebSocketClient? = null

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

    // Handler for UI thread
    private val handler = Handler(Looper.getMainLooper())

    // Reconnection
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 20

    // Request counter for unique IDs
    private var requestCounter = 0L

    // Data class for pending request
    private data class PendingRequest(
        val resolve: (JSONObject?) -> Unit,
        val reject: (Throwable) -> Unit,
        val timeoutRunnable: Runnable
    )

    // ─────────────────────────────────────────────────────────────
    // Connection lifecycle
    // ─────────────────────────────────────────────────────────────

    fun connect() {
        disconnectInternal(reconnect = false)

        val wsUrl = buildWsUrl(serverUrl)
        android.util.Log.d(TAG, "Connecting to $wsUrl")

        try {
            val uri = URI(wsUrl)
            wsClient = object : WebSocketClient(uri) {

                override fun onOpen(handshakedata: ServerHandshake?) {
                    android.util.Log.d(TAG, "WebSocket opened, protocol=${handshakedata?.httpStatus}")
                    isConnected = true
                }

                override fun onMessage(message: String?) {
                    android.util.Log.d(TAG, "onMessage received, len=${message?.length ?: 0}")
                    if (message != null) {
                        handleMessage(message)
                    }
                }

                override fun onMessage(message: ByteBuffer?) {
                    android.util.Log.d(TAG, "onMessage BINARY received, len=${message?.remaining() ?: 0}")
                    // Binary frames - should not happen in normal protocol
                    val bytes = message?.array() ?: return
                    android.util.Log.d(TAG, "Binary frame: ${bytes.size} bytes, hex=${bytes.take(32).toHexString()}")
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    android.util.Log.d(TAG, "WebSocket closed: code=$code reason=$reason remote=$remote")
                    handleDisconnect()
                }

                override fun onError(ex: Exception?) {
                    android.util.Log.e(TAG, "WebSocket error: ${ex?.message}")
                    ex?.printStackTrace()
                    emitToListener("error", JSONObject().put("message", ex?.message ?: "Unknown error"))
                    handleDisconnect()
                }
            }

            // Connect with timeout
            wsClient!!.connect()
            
            // Wait for connection with timeout
            val startTime = System.currentTimeMillis()
            while (!isConnected && System.currentTimeMillis() - startTime < CONNECT_TIMEOUT_MS) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
            }

            if (!isConnected) {
                android.util.Log.w(TAG, "Connection timeout, but continuing...")
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create WebSocket: ${e.message}")
            e.printStackTrace()
            emitToListener("error", JSONObject().put("message", e.message))
        }
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

    fun disconnect() {
        disconnectInternal(reconnect = false)
    }

    private fun disconnectInternal(reconnect: Boolean) {
        reconnectAttempts = if (reconnect) reconnectAttempts else maxReconnectAttempts
        wsClient?.close()
        wsClient = null
        isConnected = false
        isAuthenticated = false
        rejectAllPending("Connection closed")
    }

    private fun handleDisconnect() {
        isConnected = false
        isAuthenticated = false
        rejectAllPending("Connection lost")
        emitToListener("disconnected", JSONObject())

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

    // ─────────────────────────────────────────────────────────────
    // Message handling
    // ─────────────────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        android.util.Log.d(TAG, "handleMessage: len=${text.length}, first=${text.take(200)}")
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            android.util.Log.d(TAG, "handleMessage: type=$type, keys=${json.keys().asSequence().joinToString()}")
            
            when (type) {
                "event" -> {
                    val event = json.optString("event")
                    val payload = json.optJSONObject("payload") ?: JSONObject()
                    android.util.Log.d(TAG, "Event: $event, payload keys=${payload.keys().asSequence().joinToString()}")
                    handleEvent(event, payload)
                }
                "res" -> {
                    handleResponse(json)
                }
                else -> {
                    android.util.Log.w(TAG, "Unknown msg type: $type, full=$text")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Parse error: ${e.message}, text=${text.take(100)}")
            e.printStackTrace()
        }
    }

    private fun handleEvent(event: String, payload: JSONObject) {
        android.util.Log.d(TAG, "handleEvent: $event")
        
        when (event) {
            "connect.challenge" -> {
                val nonce = payload.optString("nonce")
                android.util.Log.d(TAG, "Got challenge nonce: $nonce")
                performHandshake(nonce)
            }
            "hello-ok" -> {
                val wasAuth = isAuthenticated
                isAuthenticated = true
                reconnectAttempts = 0
                serverVersion = payload.optString("runtimeVersion", null)
                android.util.Log.d(TAG, "hello-ok! wasAuth=$wasAuth, serverVersion=$serverVersion")
                emitToListener("connected", payload)
            }
            "chat" -> {
                handleChatEvent(payload)
            }
            "tick" -> {
                // Server tick - connection alive
            }
            else -> {
                android.util.Log.d(TAG, "Unhandled event: $event")
                emitToListener(event, payload)
            }
        }
    }

    private fun handleResponse(json: JSONObject) {
        val id = json.optString("id")
        android.util.Log.d(TAG, "handleResponse: id=$id, ok=${json.optBoolean("ok")}")
        
        val pending = pendingRequests.remove(id)
        if (pending == null) {
            android.util.Log.w(TAG, "No pending request for id: $id")
            return
        }

        // Cancel timeout
        handler.removeCallbacks(pending.timeoutRunnable)

        if (json.optBoolean("ok")) {
            val payload = json.optJSONObject("payload")
            android.util.Log.d(TAG, "RPC OK: $id, payload type=${payload?.optString("type")}")
            
            // Check for hello-ok
            if (payload?.optString("type") == "hello-ok") {
                val wasAuth = isAuthenticated
                isAuthenticated = true
                reconnectAttempts = 0
                serverVersion = payload.optString("runtimeVersion", null)
                android.util.Log.d(TAG, "hello-ok via RPC! wasAuth=$wasAuth")
                emitToListener("connected", payload)
            }
            
            pending.resolve(payload)
        } else {
            val errorCode = json.optJSONObject("error")?.optString("code") ?: ""
            val errorMsg = json.optJSONObject("error")?.optString("message") ?: "Unknown error"
            android.util.Log.e(TAG, "RPC ERROR: $errorCode - $errorMsg")
            
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
        android.util.Log.d(TAG, "performHandshake called with nonce: ${nonce.take(16)}")
        
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
            })
            deviceField?.let { dev ->
                put("device", JSONObject().apply {
                    put("id", dev.id)
                    put("publicKey", dev.publicKey)
                    put("signature", dev.signature)
                    put("signedAt", dev.signedAt)
                    put("nonce", dev.nonce)
                })
            }
        }

        val requestId = generateRequestId()
        val request = JSONObject().apply {
            put("type", "req")
            put("id", requestId)
            put("method", "connect")
            put("params", params)
        }

        android.util.Log.d(TAG, "Sending connect RPC: $requestId, params keys=${params.keys().asSequence().joinToString()}")
        sendRaw(request.toString())
        
        // Set timeout for connect response
        handler.postDelayed({
            val pending = pendingRequests.remove(requestId)
            if (pending != null) {
                android.util.Log.w(TAG, "Connect RPC timed out")
                pending.reject(Exception("Connect timeout"))
            }
        }, REQUEST_TIMEOUT_MS)
    }

    // ─────────────────────────────────────────────────────────────
    // RPC calls
    // ─────────────────────────────────────────────────────────────

    private fun generateRequestId(): String {
        return "req-${System.currentTimeMillis()}-${++requestCounter}"
    }

    private fun sendRpc(method: String, params: JSONObject?, callback: ((Result<JSONObject>) -> Unit)? = null) {
        val requestId = generateRequestId()
        val request = JSONObject().apply {
            put("type", "req")
            put("id", requestId)
            put("method", method)
            params?.let { put("params", it) }
        }

        android.util.Log.d(TAG, "sendRpc: method=$method id=$requestId")
        
        if (callback != null) {
            val timeoutRunnable = Runnable {
                val pending = pendingRequests.remove(requestId)
                if (pending != null) {
                    android.util.Log.w(TAG, "RPC timed out: $method $requestId")
                    pending.reject(Exception("Request timeout"))
                }
            }
            handler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS)
            
            pendingRequests[requestId] = PendingRequest(
                resolve = { payload -> handler.post { callback(Result.success(payload ?: JSONObject())) } },
                reject = { e -> handler.post { callback(Result.failure(e)) } },
                timeoutRunnable = timeoutRunnable
            )
        }

        sendRaw(request.toString())
    }

    fun sendMessage(
        content: String,
        sessionKey: String? = null,
        agentId: String? = null,
        thinking: Boolean = false,
        thinkingLevel: String? = null,
        attachments: List<Map<String, Any>>? = null
    ) {
        val sk = sessionKey ?: defaultSessionKey
        val params = JSONObject().apply {
            put("sessionKey", sk)
            put("content", content)
            put("streaming", true)
            if (agentId != null) put("agentId", agentId)
            if (thinking) put("thinking", true)
            if (thinkingLevel != null) put("thinkingLevel", thinkingLevel)
            if (attachments != null && attachments.isNotEmpty()) {
                val arr = JSONArray()
                for (att in attachments) {
                    arr.put(JSONObject(att))
                }
                put("attachments", arr)
            }
        }
        sendRpc("chat.send", params)
    }

    private fun sendRaw(text: String) {
        if (wsClient == null || !wsClient!!.isOpen) {
            android.util.Log.w(TAG, "sendRaw: WebSocket not open")
            return
        }
        try {
            android.util.Log.d(TAG, "sendRaw: ${text.take(200)}")
            wsClient!!.send(text)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "sendRaw failed: ${e.message}")
            e.printStackTrace()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Chat events
    // ─────────────────────────────────────────────────────────────

    private fun handleChatEvent(payload: JSONObject) {
        val sessionKey = payload.optString("sessionKey")
        val type = payload.optString("type")

        android.util.Log.d(TAG, "chat event: type=$type, session=$sessionKey")

        when (type) {
            "stream-start" -> {
                emitToListener("streamStart", payload)
            }
            "stream-delta" -> {
                emitToListener("streamChunk", payload)
            }
            "stream-end" -> {
                emitToListener("streamEnd", payload)
            }
            "final" -> {
                val msg = payload.optJSONObject("message") ?: payload
                emitToListener("message", msg)
            }
            else -> {
                android.util.Log.d(TAG, "Unknown chat event type: $type")
                emitToListener("chat", payload)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Query methods
    // ─────────────────────────────────────────────────────────────

    fun getSessionMessages(sessionKey: String, callback: (Result<List<ChatMessage>>) -> Unit) {
        val params = JSONObject().apply {
            put("sessionKey", sessionKey)
            put("limit", 100)
        }
        sendRpc("sessions.get", params, object : (Result<JSONObject>) -> Unit {
            override fun invoke(r: Result<JSONObject>) {
                r.fold(
                    onSuccess = { payload ->
                        val messages = mutableListOf<ChatMessage>()
                        val arr = payload.optJSONArray("messages") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val m = arr.optJSONObject(i) ?: continue
                            messages.add(ChatMessage(
                                id = m.optString("id"),
                                role = m.optString("role"),
                                content = m.optString("content"),
                                timestamp = m.optString("timestamp"),
                                thinking = m.optString("thinking", null)
                            ))
                        }
                        callback(Result.success(messages))
                    },
                    onFailure = { e -> callback(Result.failure(e)) }
                )
            }
        })
    }

    fun listSessions(callback: (Result<List<SessionInfo>>) -> Unit) {
        sendRpc("sessions.list", null, object : (Result<JSONObject>) -> Unit {
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

    fun createSession(agentId: String?, callback: (Result<SessionInfo>) -> Unit) {
        val params = JSONObject()
        if (agentId != null) params.put("agentId", agentId)
        sendRpc("sessions.create", params, object : (Result<JSONObject>) -> Unit {
            override fun invoke(r: Result<JSONObject>) {
                r.fold(
                    onSuccess = { payload ->
                        val s = payload.optJSONObject("session") ?: payload
                        callback(Result.success(SessionInfo(
                            key = s.optString("key"),
                            title = s.optString("title", "Chat"),
                            agentId = s.optString("agentId", null),
                            updatedAt = s.optString("updatedAt", "")
                        )))
                    },
                    onFailure = { e -> callback(Result.failure(e)) }
                )
            }
        })
    }

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
        android.util.Log.d(TAG, "emitToListener: event=$event listenersCount=${listeners[event]?.size ?: 0}")
        handler.post {
            listeners[event]?.forEach { listener ->
                try {
                    listener(payload)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Listener exception: ${e.message}")
                    e.printStackTrace()
                }
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
        val role: String,
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

    // Extension to convert byte array to hex string
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}