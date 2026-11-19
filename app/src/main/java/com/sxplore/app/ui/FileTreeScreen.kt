package com.sxplore.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sxplore.app.proxy.StreamProxy
import com.sxplore.app.util.formatDate
import com.sxplore.app.util.formatSize
import com.sxplore.app.vfs.VfsEntry

@Composable
fun FileTreeScreen(vm: AppViewModel, index: Int) {
    val tab = vm.tabs.getOrNull(index) ?: return
    val rows = remember(tab) { vm.flatten(tab) }

    Column(Modifier.fillMaxSize()) {
        // 当前目录栏（粘贴目标）
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Folder, null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    tab.currentDir,
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { vm.refreshAll(index) }) {
                    Icon(Icons.Filled.Refresh, "刷新", Modifier.size(18.dp))
                }
            }
        }

        when {
            !tab.rootLoaded -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            tab.rootError != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败", color = MaterialTheme.colorScheme.error)
                    Text(
                        tab.rootError ?: "",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.loadRoot(index) }) { Text("重试") }
                }
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.entry.path }) { row ->
                    TreeRowItem(vm, index, tab, row)
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(vm: AppViewModel, index: Int, tab: TabUiState, row: TreeRow) {
    val e = row.entry
    val expanded = e.path in tab.expanded
    val loading = e.path in tab.loading

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (e.isDir) vm.toggleDir(index, e) else vm.fileAction = index to e
                },
                onLongClick = {
                    if (e.isDir) vm.dirAction = index to e else vm.fileAction = index to e
                },
            )
            .padding(start = (4 + row.depth * 18).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.isDir) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp).padding(2.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Filled.ChevronRight, null,
                    Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = when {
                e.isDir -> Icons.Filled.Folder
                StreamProxy.isMedia(e.name) && isVideo(e.name) -> Icons.Filled.Movie
                StreamProxy.isMedia(e.name) -> Icons.Filled.Audiotrack
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (e.isDir) Color(0xFFE8A33D) else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!e.isDir) {
                Text(
                    "${formatSize(e.size)} · ${formatDate(e.lastModified)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun isVideo(name: String): Boolean {
    val mime = StreamProxy.mimeOf(name)
    return mime.startsWith("video/")
}
