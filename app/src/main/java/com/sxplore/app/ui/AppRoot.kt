package com.sxplore.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sxplore.app.vfs.VfsPaths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val pagerState = rememberPagerState(pageCount = { vm.tabs.size })
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(pagerState.currentPage) {
        vm.activeIndex = pagerState.currentPage
    }
    LaunchedEffect(vm.activeIndex) {
        if (vm.activeIndex in vm.tabs.indices && pagerState.currentPage != vm.activeIndex) {
            pagerState.animateScrollToPage(vm.activeIndex)
        }
    }
    LaunchedEffect(vm.snackbar) {
        vm.snackbar?.let {
            snackbarHost.showSnackbar(it)
            vm.snackbar = null
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Sxplore", fontSize = 20.sp) },
                    actions = {
                        IconButton(onClick = {
                            vm.refreshConnections()
                            vm.showNewTab = true
                        }) {
                            Icon(Icons.Filled.Add, "新建标签页")
                        }
                    },
                )
                ScrollableTabRow(
                    selectedTabIndex = vm.activeIndex.coerceIn(0, (vm.tabs.size - 1).coerceAtLeast(0)),
                    edgePadding = 0.dp,
                ) {
                    vm.tabs.forEachIndexed { i, tab ->
                        Tab(
                            selected = i == vm.activeIndex,
                            onClick = { vm.activeIndex = i },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        tab.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 140.dp),
                                        fontSize = 13.sp,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Filled.Close, "关闭标签页",
                                        Modifier
                                            .size(14.dp)
                                            .clickable { vm.closeTab(i) },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                vm.clipboard?.let { op ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.ContentPaste, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${if (op.move) "移动" else "复制"}: ${VfsPaths.name(op.srcPath)}",
                                Modifier.weight(1f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { vm.paste() }) { Text("粘贴到当前目录") }
                            IconButton(onClick = { vm.clipboard = null }) {
                                Icon(Icons.Filled.Close, "清除剪贴板", Modifier.size(16.dp))
                            }
                        }
                    }
                }
                SnackbarHost(snackbarHost)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                FileTreeScreen(vm, page)
            }
        }
    }

    // ---------- 对话框 ----------

    if (vm.showNewTab) {
        NewTabDialog(vm)
    }
    vm.connectInitial?.let { initial ->
        ConnectDialog(
            initial = initial,
            onDismiss = { vm.connectInitial = null },
            onConnect = { info, save, cb -> vm.openSmbTab(info, save, cb) },
        )
    }
    vm.fileAction?.let { (idx, e) ->
        FileActionsDialog(
            entry = e,
            onDismiss = { vm.fileAction = null },
            onPlayInternal = { vm.fileAction = null; vm.playInternal(idx, e) },
            onPlayExternal = { vm.fileAction = null; vm.playExternal(idx, e) },
            onCopy = { vm.fileAction = null; vm.setClipboard(idx, e, move = false) },
            onCut = { vm.fileAction = null; vm.setClipboard(idx, e, move = true) },
            onRename = { vm.fileAction = null; vm.renameTarget = idx to e },
            onDelete = { vm.fileAction = null; vm.deleteTarget = idx to e },
            onInfo = { vm.fileAction = null; vm.infoTarget = e },
        )
    }
    vm.dirAction?.let { (idx, e) ->
        DirActionsDialog(
            entry = e,
            onDismiss = { vm.dirAction = null },
            onNewFolder = { vm.dirAction = null; vm.newFolderDir = idx to e.path },
            onRefresh = { vm.dirAction = null; vm.refreshDir(idx, e.path) },
            onRename = { vm.dirAction = null; vm.renameTarget = idx to e },
            onDelete = { vm.dirAction = null; vm.deleteTarget = idx to e },
        )
    }
    vm.renameTarget?.let { (idx, e) ->
        RenameDialog(
            entry = e,
            onDismiss = { vm.renameTarget = null },
            onConfirm = { newName -> vm.renameTarget = null; vm.rename(idx, e, newName) },
        )
    }
    vm.newFolderDir?.let { (idx, dir) ->
        NewFolderDialog(
            onDismiss = { vm.newFolderDir = null },
            onConfirm = { name -> vm.newFolderDir = null; vm.mkdir(idx, dir, name) },
        )
    }
    vm.deleteTarget?.let { (idx, e) ->
        DeleteConfirmDialog(
            entry = e,
            onDismiss = { vm.deleteTarget = null },
            onConfirm = { vm.deleteTarget = null; vm.delete(idx, e) },
        )
    }
    vm.infoTarget?.let { e ->
        InfoDialog(entry = e, onDismiss = { vm.infoTarget = null })
    }
    vm.transfer?.let { t ->
        TransferDialog(t)
    }
}
