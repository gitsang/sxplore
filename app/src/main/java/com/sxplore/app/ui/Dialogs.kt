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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
        title = { Text("New tab") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogRow(Icons.Filled.Storage, "Local storage") {
                    vm.showNewTab = false
                    vm.addLocalTab()
                }
                vm.savedConnections.forEach { info ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            vm.openSmbTab(info, save = false) { err ->
                                if (err == null) vm.showNewTab = false
                                else vm.snackbar = "Connection failed: $err"
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
                        IconButton(onClick = {
                            vm.showNewTab = false
                            vm.connectInitial = info
                        }) {
                            Icon(Icons.Filled.Edit, "Edit connection", Modifier.size(18.dp))
                        }
                        IconButton(onClick = { vm.deleteConnection(info.id) }) {
                            Icon(Icons.Filled.Delete, "Delete connection", Modifier.size(18.dp))
                        }
                    }
                }
                DialogRow(Icons.Filled.Dns, "New SMB connection…") {
                    vm.showNewTab = false
                    vm.connectInitial = SmbConnectionInfo(name = "", host = "", share = "")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.showNewTab = false }) { Text("Cancel") } },
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
        title = { Text(if (initial.id.isBlank()) "New SMB connection" else "Edit SMB connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(host, { host = it }, label = { Text("Host / IP *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(port, { port = it }, label = { Text("Port (default 445)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(share, { share = it }, label = { Text("Share name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(username, { username = it }, label = { Text("Username (blank = anonymous)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(save, { save = it })
                    Text("Save connection (credentials stored encrypted)", fontSize = 13.sp)
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
            ) { Text(if (connecting) "Connecting…" else "Connect") }
        },
        dismissButton = {
            TextButton(enabled = !connecting, onClick = onDismiss) { Text("Cancel") }
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
    onInstall: () -> Unit,
) {
    val kind = StreamProxy.kindOf(entry.name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, fontSize = 16.sp, maxLines = 2) },
        text = {
            Column {
                when (kind) {
                    "video", "audio" -> {
                        ActionItem("▶ Play built-in (streaming)", onPlayInternal)
                        ActionItem("↗ External player (streaming)", onPlayExternal)
                    }
                    "image" -> {
                        ActionItem("Preview built-in", onPlayInternal)
                        ActionItem("↗ Open with another app", onPlayExternal)
                    }
                    else -> {
                        if (entry.name.substringAfterLast('.', "").equals("apk", ignoreCase = true)) {
                            ActionItem("Install", onInstall)
                        }
                        ActionItem("↗ Open with another app", onPlayExternal)
                    }
                }
                ActionItem("Copy", onCopy)
                ActionItem("Cut (move)", onCut)
                ActionItem("Rename", onRename)
                ActionItem("Delete", onDelete)
                ActionItem("Properties", onInfo)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    onInfo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, fontSize = 16.sp, maxLines = 2) },
        text = {
            Column {
                ActionItem("New subfolder", onNewFolder)
                ActionItem("Refresh", onRefresh)
                ActionItem("Rename", onRename)
                ActionItem("Delete", onDelete)
                ActionItem("Properties", onInfo)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BatchDeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete confirmation") },
        text = {
            Text(
                "Delete $count selected item(s)? Folders are deleted recursively. " +
                    "This cannot be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Rename") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name != entry.name, onClick = { onConfirm(name.trim()) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteConfirmDialog(entry: VfsEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete confirmation") },
        text = {
            Text("Delete \"${entry.name}\"? ${if (entry.isDir) "The folder and all its contents will be deleted recursively. " else ""}This cannot be undone.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(entry: VfsEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties", fontSize = 18.sp) },
        text = {
            Column {
                InfoLine("Name", entry.name)
                InfoLine("Path", entry.path)
                InfoLine("Type", if (entry.isDir) "Folder" else StreamProxy.mimeOf(entry.name))
                if (!entry.isDir) InfoLine("Size", "${formatSize(entry.size)} (${entry.size} bytes)")
                InfoLine("Modified", formatDate(entry.lastModified))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
