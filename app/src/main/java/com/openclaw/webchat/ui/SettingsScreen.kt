package com.openclaw.webchat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    onSave: (serverUrl: String) -> Unit
) {
    var serverUrl by remember { mutableStateOf("http://172.16.3.16:18789") }
    var sshHost by remember { mutableStateOf("172.16.3.16") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("root") }
    var sshPassword by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "OpenClaw WebChat",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "首次配置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("OpenClaw 服务器地址") },
            placeholder = { Text("http://172.16.3.16:18789") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "文件上传配置（SCP）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = sshHost,
            onValueChange = { sshHost = it },
            label = { Text("SSH 服务器") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = sshPort,
                onValueChange = { sshPort = it.filter { c -> c.isDigit() } },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = sshUser,
                onValueChange = { sshUser = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.weight(2f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = sshPassword,
            onValueChange = { sshPassword = it },
            label = { Text("SSH 密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (serverUrl.isNotBlank()) {
                    onSave(serverUrl)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开始使用")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "服务器地址用于加载 WebChat 界面\nSSH 配置用于文件上传功能",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
