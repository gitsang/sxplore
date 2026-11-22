package com.sxplore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sxplore.app.vfs.VfsPaths
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    if (vm.showSettings) {
        SettingsScreen(vm)
        return
    }

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
                        IconButton(onClick = { vm.showSettings = true }) {
                            Icon(Icons.Filled.Settings, "Settings")
                        }
                        Box {
                            IconButton(onClick = { vm.showOverflow = true }) {
                                Icon(Icons.Filled.MoreVert, "More actions")
                            }
                            DropdownMenu(
                                expanded = vm.showOverflow,
                                onDismissRequest = { vm.showOverflow = false },
                            ) {
                                val n = vm.tabs.getOrNull(vm.activeIndex)?.selected?.size ?: 0
                                if (n > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Copy ($n)") },
                                        onClick = {
                                            vm.showOverflow = false
                                            vm.setClipboardSelected(vm.activeIndex, move = false)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Cut ($n)") },
                                        onClick = {
                                            vm.showOverflow = false
                                            vm.setClipboardSelected(vm.activeIndex, move = true)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete ($n)") },
                                        onClick = {
                                            vm.showOverflow = false
                                            vm.batchDeleteConfirm = vm.activeIndex
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear selection") },
                                        onClick = {
                                            vm.showOverflow = false
                                            vm.clearSelection(vm.activeIndex)
                                        },
                                    )
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text("Select all in current folder") },
                                    onClick = {
                                        vm.showOverflow = false
                                        vm.selectAll(vm.activeIndex)
                                    },
                                )
                            }
                        }
                    },
                )
                SxploreTabRow(vm)
                HorizontalDivider(thickness = 0.5.dp)
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
                                "${if (op.move) "Move" else "Copy"}: ${VfsPaths.name(op.sources.first().path)}" +
                                    if (op.sources.size > 1) " +${op.sources.size - 1}" else "",
                                Modifier.weight(1f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { vm.paste() }) { Text("Paste here") }
                            IconButton(onClick = { vm.clipboard = null }) {
                                Icon(Icons.Filled.Close, "Clear clipboard", Modifier.size(16.dp))
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

    // ---------- Dialogs ----------

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
            onPlayInternal = { vm.fileAction = null; vm.openInternalChosen(idx, e) },
            onPlayExternal = { vm.fileAction = null; vm.openExternalChosen(idx, e) },
            onCopy = { vm.fileAction = null; vm.setClipboard(idx, e, move = false) },
            onCut = { vm.fileAction = null; vm.setClipboard(idx, e, move = true) },
            onRename = { vm.fileAction = null; vm.renameTarget = idx to e },
            onDelete = { vm.fileAction = null; vm.deleteTarget = idx to e },
            onInfo = { vm.fileAction = null; vm.infoTarget = e },
            onInstall = { vm.fileAction = null; vm.installApk(idx, e) },
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
            onInfo = { vm.dirAction = null; vm.infoTarget = e },
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
    vm.batchDeleteConfirm?.let { idx ->
        BatchDeleteDialog(
            count = vm.tabs.getOrNull(idx)?.selected?.size ?: 0,
            onDismiss = { vm.batchDeleteConfirm = null },
            onConfirm = { vm.batchDeleteConfirm = null; vm.batchDelete(idx) },
        )
    }
    vm.infoTarget?.let { e ->
        InfoDialog(entry = e, onDismiss = { vm.infoTarget = null })
    }
    vm.transfer?.let { t ->
        TransferDialog(t)
    }
}

/**
 * Custom scrollable tab strip.
 *
 * Replaces Material3 ScrollableTabRow, which crashes with
 * IndexOutOfBoundsException (TabRow.kt:502) when the tab count changes:
 * its indicator reads tabPositions[selectedTabIndex] before the position
 * list is re-measured for the new count. Here each tab draws its own
 * indicator from its own selected flag, so no shared list can go stale.
 */
@Composable
private fun SxploreTabRow(vm: AppViewModel) {
    val scrollState = rememberScrollState()
    val tabOffsets = remember { mutableStateMapOf<Int, Int>() }

    // Keep the active tab visible when selection changes or tabs are added/removed.
    LaunchedEffect(vm.activeIndex, vm.tabs.size, tabOffsets[vm.activeIndex]) {
        tabOffsets[vm.activeIndex]?.let { x ->
            scrollState.animateScrollTo(x.coerceAtLeast(0))
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
        ) {
            vm.tabs.forEachIndexed { i, tab ->
            val selected = i == vm.activeIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        tabOffsets[i] = coords.positionInParent().x.roundToInt()
                    }
                    .clickable { vm.activeIndex = i }
                    .width(IntrinsicSize.Max),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        tab.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Close, "Close tab",
                        Modifier
                            .size(14.dp)
                            .clickable { vm.closeTab(i) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                        ),
                )
            }
            }
        }
        IconButton(onClick = {
            vm.refreshConnections()
            vm.showNewTab = true
        }) {
            Icon(Icons.Filled.Add, "New tab")
        }
    }
}
