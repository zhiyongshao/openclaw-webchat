package com.openclaw.webchat.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Properties

/**
 * File upload manager using SCP via JSch to transfer files to OpenClaw host.
 * Credentials are stored encrypted in SharedPreferences.
 */
class FileUploadManager {

    companion object {
        private const val TAG = "FileUploadManager"
        private const val DEFAULT_SCP_PORT = 22
        private const val DEFAULT_SSH_USER = "root"
        private const val DEFAULT_UPLOAD_PATH = "/root/.openclaw/workspace/temp"
    }

    /**
     * Upload a file from a content URI to the OpenClaw host via SCP.
     */
    suspend fun uploadFile(
        context: Context,
        fileUri: Uri,
        serverUrl: String,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val host = extractHost(serverUrl)
            if (host.isEmpty()) {
                return@withContext Result.failure(Exception("无效的服务器地址"))
            }

            val prefs = context.getSharedPreferences("upload_prefs", Context.MODE_PRIVATE)
            val sshHost = prefs.getString("ssh_host", host) ?: host
            val sshPort = prefs.getInt("ssh_port", DEFAULT_SCP_PORT)
            val sshUser = prefs.getString("ssh_user", DEFAULT_SSH_USER) ?: DEFAULT_SSH_USER
            val sshPassword = prefs.getString("ssh_password", "") ?: ""

            val filename = getFileName(context, fileUri) ?: "upload_${System.currentTimeMillis()}"
            val remotePath = "$DEFAULT_UPLOAD_PATH/$filename"

            val inputStream: InputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext Result.failure(Exception("无法读取文件"))

            // Get file size
            val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
            val fileSize = pfd?.statSize ?: 0L
            pfd?.close()

            onProgress("连接中...")

            val jsch = JSch()
            val session: Session = jsch.getSession(sshUser, sshHost, sshPort)
            session.setPassword(sshPassword)
            session.setConfig(Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password")
            })
            session.connect(30000)

            onProgress("上传中...")

            // Use exec channel for scp
            val execChannel = session.openChannel("exec") as ChannelExec
            execChannel.setCommand("scp -t -d \"$remotePath\"")

            val out = execChannel.outputStream
            val inp = execChannel.inputStream
            execChannel.connect()

            // Send file header: C0644 filesize filename\n
            val header = "C0644 $fileSize $filename\n"
            out.write(header.toByteArray())
            out.flush()

            // Read single byte response from remote
            val ack = inp.read()
            if (ack != 0) {
                execChannel.disconnect()
                session.disconnect()
                return@withContext Result.failure(Exception("SCP被拒绝，代码: $ack"))
            }

            // Send file content in chunks
            val buffer = ByteArray(8192)
            var totalSent = 0L

            inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    out.flush()
                    totalSent += bytesRead
                    if (fileSize > 0) {
                        val pct = (totalSent * 100 / fileSize).toInt()
                        onProgress("上传中... $pct%")
                    }
                }
            }

            // Send terminal zero byte
            out.write(ByteArray(1) { 0 })
            out.flush()

            // Read final acknowledgement
            val resp = inp.read()
            execChannel.disconnect()
            session.disconnect()

            if (resp != 0) {
                return@withContext Result.failure(Exception("SCP传输失败: $resp"))
            }

            onProgress("完成")
            Result.success(remotePath)

        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.failure(e)
        }
    }

    private fun extractHost(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host ?: url.substringAfter("://").substringBefore(":").substringBefore("/")
        } catch (e: Exception) {
            url.substringAfter("://").substringBefore(":").substringBefore("/")
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    fun saveCredentials(context: Context, host: String, port: Int, user: String, password: String) {
        context.getSharedPreferences("upload_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("ssh_host", host)
            putInt("ssh_port", port)
            putString("ssh_user", user)
            putString("ssh_password", password)
            apply()
        }
    }
}
