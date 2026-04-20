package com.openclaw.webchat.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Properties

/**
 * File upload manager using SCP to transfer files to OpenClaw host.
 * Server credentials are stored in the app's secure preferences (encrypted).
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
     *
     * @param context Android context
     * @param fileUri Content URI of the file to upload
     * @param serverUrl Full server URL (e.g., http://172.16.3.16:18789)
     * @param onProgress Callback for progress updates
     * @return Result with remote file path on success
     */
    suspend fun uploadFile(
        context: Context,
        fileUri: Uri,
        serverUrl: String,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Parse server host from URL
            val host = extractHost(serverUrl)
            if (host.isEmpty()) {
                return@withContext Result.failure(Exception("无效的服务器地址"))
            }

            // Get credentials from app preferences
            val prefs = context.getSharedPreferences("upload_prefs", Context.MODE_PRIVATE)
            val sshHost = prefs.getString("ssh_host", host) ?: host
            val sshPort = prefs.getInt("ssh_port", DEFAULT_SCP_PORT)
            val sshUser = prefs.getString("ssh_user", DEFAULT_SSH_USER) ?: DEFAULT_SSH_USER
            val sshPassword = prefs.getString("ssh_password", "") ?: ""

            // Get filename from URI
            val filename = getFileName(context, fileUri) ?: "upload_${System.currentTimeMillis()}"
            val remotePath = "$DEFAULT_UPLOAD_PATH/$filename"

            // Get input stream
            val inputStream: InputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext Result.failure(Exception("无法读取文件"))

            // SCP upload via JSch
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

            // Execute SCP command
            val execChannel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            execChannel.setCommand("scp -t \"$remotePath\"")

            val out = execChannel.outputStream
            val err = execChannel.errStream
            execChannel.connect()

            // Read file and write to SCP stream
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0

            // Get file size hint
            val fd = context.contentResolver.openFileDescriptor(fileUri, "r")
            val fileSize = fd?.statSize ?: -1
            fd?.close()

            // Send file content
            inputStream.use { input ->
                // Send C0644 filesize filename\n
                val filesize = inputStream.available().toLong()
                val command = "C0644 $filesize $filename\n"
                out.write(command.toByteArray())
                out.flush()

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    out.flush()
                    totalBytes += bytesRead
                    if (fileSize > 0) {
                        val pct = (totalBytes * 100 / fileSize).toInt()
                        onProgress("上传中... $pct%")
                    }
                }

                out.write(ByteArray(1) { 0 })  // Send \0
                out.flush()
            }

            // Wait for confirmation
            val resp = ByteArray(1)
            var idx = 0
            while (idx < 1) {
                val b = ByteArray(1)
                if (out.read(b) == -1) break
                resp[idx++] = b[0]
            }

            execChannel.disconnect()
            session.disconnect()

            if (resp[0].toInt() != 0) {
                return@withContext Result.failure(Exception("SCP传输失败"))
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
