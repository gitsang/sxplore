package com.sxplore.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sxplore.app.proxy.StreamProxy
import com.sxplore.app.util.formatDate
import com.sxplore.app.util.formatSize
import com.sxplore.app.vfs.SmbConnectionInfo
import com.sxplore.app.vfs.VfsEntry

@Composable
fun NewTabDialog(vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.showNewTab = false },
        title = { Text("新建标签页") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogRow(Icons.Filled.Storage, "本地存储") {
                    vm.showNewTab = false
                    vm.addLocalTab()
                }
                vm.savedConnections.forEach { info ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            vm.openSmbTab(info, save = false) { err ->
                                if (err == null) vm.showNewTab = false
                                else vm.snackbar = "连接失败: $err"
                            }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Dns, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(info.name, fontSize = 15.sp)
                            Text(
                                "${info.displayHost} / ${info.share}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.deleteConnection(info.id) }) {
                            Icon(Icons.Filled.Delete, "删除连接", Modifier.size(18.dp))
                        }
                    }
                }
                DialogRow(Icons.Filled.Dns, "新建 SMB 连接…") {
                    vm.showNewTab = false
                    vm.connectInitial = SmbConnectionInfo(name = "", host = "", share = "")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.showNewTab = false }) { Text("取消") } },
    )
}

@Composable
private fun DialogRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
fun ConnectDialog(
    initial: SmbConnectionInfo,
    onDismiss: () -> Unit,
    onConnect: (SmbConnectionInfo, Boolean, (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var share by remember { mutableStateOf(initial.share) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var domain by remember { mutableStateOf(initial.domain) }
    var save by remember { mutableStateOf(true) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text(if (initial.id.isBlank()) "新建 SMB 连接" else "编辑 SMB 连接") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(host, { host = it }, label = { Text("主机 / IP *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(port, { port = it }, label = { Text("端口（默认 445）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(share, { share = it }, label = { Text("共享名 *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(username, { username = it }, label = { Text("用户名（留空 = 匿名）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(domain, { domain = it }, label = { Text("域（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(save, { save = it })
                    Text("保存连接（凭据加密存储）", fontSize = 13.sp)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !connecting && host.isNotBlank() && share.isNotBlank(),
                onClick = {
                    connecting = true
                    error = null
                    val info = SmbConnectionInfo(
                        id = initial.id,
                        name = name.ifBlank { "${host.trim()}/${share.trim()}" },
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 445,
                        share = share.trim(),
                        username = username.trim(),
                        password = password,
                        domain = domain.trim(),
                    )
                    onConnect(info, save) { err ->
                        if (err == null) onDismiss()
                        else {
                            connecting = false
                            error = err
                        }
                    }
                },
            ) { Text(if (connecting) "连接中…" else "连接") }
        },
        dismissButton = {
            TextButton(enabled = !connecting, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun FileActionsDialog(
    entry: VfsEntry,
    onDismiss: () -> Unit,
    onPlayInternal: () -> Unit,
    onPlayExternal: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
) {
    val media = StreamProxy.isMedia(entry.name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, fontSize = 16.sp, maxLines = 2) },
        text = {
            Column {
                if (media) {
                    ActionItem("▶ 内置播放（流式）", onPlayInternal)
                    ActionItem("↗ 外部播放器（流式）", onPlayExternal)
                } else {
                    ActionItem("↗ 用其他应用打开", onPlayExternal)
                }
                ActionItem("复制", onCopy)
                ActionItem("剪切（移动）", onCut)
                ActionItem("重命名", onRename)
                ActionItem("删除", onDelete)
                ActionItem("属性", onInfo)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DirActionsDialog(
    entry: VfsEntry,
    onDismiss: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, fontSize = 16.sp, maxLines = 2) },
        text = {
            Column {
                ActionItem("新建子文件夹", onNewFolder)
                ActionItem("刷新", onRefresh)
                ActionItem("重命名", onRename)
                ActionItem("删除", onDelete)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ActionItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        fontSize = 15.sp,
    )
}

@Composable
fun RenameDialog(entry: VfsEntry, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(entry.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name != entry.name, onClick = { onConfirm(name.trim()) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DeleteConfirmDialog(entry: VfsEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除确认") },
        text = {
            Text("确定删除「${entry.name}」？${if (entry.isDir) "文件夹及其全部内容将被递归删除，" else ""}此操作不可恢复。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun InfoDialog(entry: VfsEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("属性", fontSize = 18.sp) },
        text = {
            Column {
                InfoLine("名称", entry.name)
                InfoLine("路径", entry.path)
                InfoLine("类型", if (entry.isDir) "文件夹" else StreamProxy.mimeOf(entry.name))
                if (!entry.isDir) InfoLine("大小", "${formatSize(entry.size)} (${entry.size} 字节)")
                InfoLine("修改时间", formatDate(entry.lastModified))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(k, Modifier.width(72.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 13.sp)
    }
}

@Composable
fun TransferDialog(t: TransferProgress) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(t.label) },
        text = {
            Column {
                if (t.total > 0) {
                    LinearProgressIndicator(
                        progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${formatSize(t.done)} / ${formatSize(t.total)}", fontSize = 13.sp)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}
