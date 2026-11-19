package com.sxplore.app.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sxplore.app.SxploreApp
import com.sxplore.app.player.PlayerActivity
import com.sxplore.app.proxy.StreamProxy
import com.sxplore.app.vfs.LocalVfs
import com.sxplore.app.vfs.SmbConnectionInfo
import com.sxplore.app.vfs.SmbVfs
import com.sxplore.app.vfs.TransferEngine
import com.sxplore.app.vfs.Vfs
import com.sxplore.app.vfs.VfsEntry
import com.sxplore.app.vfs.VfsPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TabUiState(
    val id: Long,
    val title: String,
    val vfs: Vfs,
    val rootEntries: List<VfsEntry> = emptyList(),
    val children: Map<String, List<VfsEntry>> = emptyMap(),
    val expanded: Set<String> = emptySet(),
    val loading: Set<String> = emptySet(),
    val currentDir: String = "/",
    val rootLoaded: Boolean = false,
    val rootError: String? = null,
)

data class TreeRow(val entry: VfsEntry, val depth: Int)

data class ClipboardOp(val srcVfs: Vfs, val srcPath: String, val move: Boolean)

data class TransferProgress(val label: String, val done: Long, val total: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as SxploreApp).container

    val tabs = mutableStateListOf<TabUiState>()
    var activeIndex by mutableStateOf(0)

    var clipboard by mutableStateOf<ClipboardOp?>(null)
    var transfer by mutableStateOf<TransferProgress?>(null)
    var snackbar by mutableStateOf<String?>(null)
    var savedConnections by mutableStateOf<List<SmbConnectionInfo>>(emptyList())

    // 对话框状态
    var showNewTab by mutableStateOf(false)
    var connectInitial by mutableStateOf<SmbConnectionInfo?>(null)
    var fileAction by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var dirAction by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var renameTarget by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var newFolderDir by mutableStateOf<Pair<Int, String>?>(null)
    var deleteTarget by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var infoTarget by mutableStateOf<VfsEntry?>(null)

    private var nextId = 1L

    init {
        refreshConnections()
        addTab(container.newLocalVfs(), "本地存储")
    }

    // ---------- Tab 管理 ----------

    private fun addTab(vfs: Vfs, title: String) {
        tabs.add(TabUiState(id = nextId++, title = title, vfs = vfs))
        activeIndex = tabs.lastIndex
        loadRoot(tabs.lastIndex)
    }

    fun addLocalTab() = addTab(container.newLocalVfs(), "本地存储")

    fun openSmbTab(info: SmbConnectionInfo, save: Boolean, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            val vfs = container.newSmbVfs(info)
            try {
                val entries = withContext(Dispatchers.IO) { vfs.list("/") }
                val finalInfo = if (save || info.id.isNotBlank()) {
                    container.connectionStore.upsert(info)
                } else info
                refreshConnections()
                tabs.add(
                    TabUiState(
                        id = nextId++, title = finalInfo.name, vfs = vfs,
                        rootEntries = entries, rootLoaded = true,
                    )
                )
                activeIndex = tabs.lastIndex
                onDone(null)
            } catch (e: Exception) {
                runCatching { vfs.close() }
                onDone(e.message ?: "连接失败")
            }
        }
    }

    fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        runCatching { tab.vfs.close() }
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            addLocalTab()
            return
        }
        activeIndex = activeIndex.coerceAtMost(tabs.lastIndex)
    }

    fun refreshConnections() {
        savedConnections = container.connectionStore.list()
    }

    fun deleteConnection(id: String) {
        container.connectionStore.remove(id)
        refreshConnections()
    }

    // ---------- 树加载 ----------

    private fun update(index: Int, f: (TabUiState) -> TabUiState) {
        if (index in tabs.indices) tabs[index] = f(tabs[index])
    }

    fun loadRoot(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        update(index) { it.copy(loading = it.loading + "/", rootError = null) }
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { tab.vfs.list("/") }
                update(index) {
                    it.copy(rootEntries = entries, rootLoaded = true, loading = it.loading - "/")
                }
            } catch (e: Exception) {
                update(index) {
                    it.copy(rootLoaded = true, rootError = e.message ?: "加载失败", loading = it.loading - "/")
                }
            }
        }
    }

    fun toggleDir(index: Int, entry: VfsEntry) {
        val tab = tabs.getOrNull(index) ?: return
        if (entry.path in tab.expanded) {
            update(index) { it.copy(expanded = it.expanded - entry.path) }
        } else {
            update(index) { it.copy(expanded = it.expanded + entry.path, currentDir = entry.path) }
            if (entry.path !in tab.children) loadChildren(index, entry.path)
        }
    }

    fun loadChildren(index: Int, path: String) {
        val tab = tabs.getOrNull(index) ?: return
        update(index) { it.copy(loading = it.loading + path) }
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { tab.vfs.list(path) }
                update(index) {
                    it.copy(children = it.children + (path to entries), loading = it.loading - path)
                }
            } catch (e: Exception) {
                update(index) { it.copy(loading = it.loading - path, expanded = it.expanded - path) }
                snackbar = "加载失败: ${e.message}"
            }
        }
    }

    fun refreshDir(index: Int, path: String) {
        if (path == "/") loadRoot(index) else loadChildren(index, path)
    }

    fun refreshAll(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        loadRoot(index)
        tab.expanded.forEach { loadChildren(index, it) }
    }

    fun flatten(tab: TabUiState): List<TreeRow> {
        val out = ArrayList<TreeRow>()
        fun walk(list: List<VfsEntry>, depth: Int) {
            val sorted = list.sortedWith(
                compareByDescending<VfsEntry> { it.isDir }.thenBy { it.name.lowercase() }
            )
            for (e in sorted) {
                out.add(TreeRow(e, depth))
                if (e.isDir && e.path in tab.expanded) {
                    tab.children[e.path]?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(tab.rootEntries, 0)
        return out
    }

    // ---------- 播放 ----------

    private fun streamUrl(index: Int, e: VfsEntry): String {
        val tab = tabs[index]
        return when (val vfs = tab.vfs) {
            is SmbVfs -> container.proxy.register(vfs, e.path, e.size, e.name)
            is LocalVfs -> Uri.fromFile(vfs.realFile(e.path)).toString()
            else -> throw IllegalStateException("不支持的文件源")
        }
    }

    fun playInternal(index: Int, e: VfsEntry) {
        try {
            val url = streamUrl(index, e)
            val ctx = getApplication<Application>()
            ctx.startActivity(
                PlayerActivity.makeIntent(ctx, url, e.name, StreamProxy.mimeOf(e.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ex: Exception) {
            snackbar = "无法播放: ${ex.message}"
        }
    }

    fun playExternal(index: Int, e: VfsEntry) {
        val ctx = getApplication<Application>()
        val tab = tabs.getOrNull(index) ?: return
        val mime = StreamProxy.mimeOf(e.name)
        val uri = when (val vfs = tab.vfs) {
            is SmbVfs -> Uri.parse(container.proxy.register(vfs, e.path, e.size, e.name))
            is LocalVfs -> FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", vfs.realFile(e.path)
            )
            else -> return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(
                Intent.createChooser(intent, "选择播放器").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ex: ActivityNotFoundException) {
            snackbar = "未找到可用的播放器"
        }
    }

    // ---------- 文件操作 ----------

    fun setClipboard(index: Int, e: VfsEntry, move: Boolean) {
        clipboard = ClipboardOp(tabs[index].vfs, e.path, move)
        snackbar = if (move) "已剪切: ${e.name}，到目标目录后点粘贴" else "已复制: ${e.name}，到目标目录后点粘贴"
    }

    fun paste() {
        val op = clipboard ?: return
        val idx = activeIndex
        val tab = tabs.getOrNull(idx) ?: return
        val destDir = tab.currentDir
        if (op.srcVfs.id == tab.vfs.id && VfsPaths.parent(op.srcPath) == destDir) {
            snackbar = "源目录与目标目录相同"
            return
        }
        viewModelScope.launch {
            transfer = TransferProgress("准备中…", 0, 0)
            try {
                withContext(Dispatchers.IO) {
                    TransferEngine.copy(op.srcVfs, op.srcPath, tab.vfs, destDir) { done, total ->
                        transfer = TransferProgress(if (op.move) "移动中" else "复制中", done, total)
                    }
                }
                if (op.move) {
                    withContext(Dispatchers.IO) { op.srcVfs.delete(op.srcPath) }
                    clipboard = null
                    tabs.forEachIndexed { i, t -> if (t.vfs.id == op.srcVfs.id) refreshAll(i) }
                }
                refreshDir(idx, destDir)
                snackbar = "传输完成"
            } catch (e: Exception) {
                snackbar = "传输失败: ${e.message}"
            } finally {
                transfer = null
            }
        }
    }

    fun delete(index: Int, e: VfsEntry) {
        val tab = tabs.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { tab.vfs.delete(e.path) }
                update(index) {
                    it.copy(expanded = it.expanded - e.path, children = it.children - e.path)
                }
                refreshDir(index, VfsPaths.parent(e.path))
                snackbar = "已删除: ${e.name}"
            } catch (ex: Exception) {
                snackbar = "删除失败: ${ex.message}"
            }
        }
    }

    fun rename(index: Int, e: VfsEntry, newName: String) {
        val tab = tabs.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { tab.vfs.rename(e.path, newName) }
                update(index) {
                    it.copy(expanded = it.expanded - e.path, children = it.children - e.path)
                }
                refreshDir(index, VfsPaths.parent(e.path))
                snackbar = "已重命名"
            } catch (ex: Exception) {
                snackbar = "重命名失败: ${ex.message}"
            }
        }
    }

    fun mkdir(index: Int, dir: String, name: String) {
        val tab = tabs.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { tab.vfs.mkdir(VfsPaths.join(dir, name)) }
                refreshDir(index, dir)
                snackbar = "已创建文件夹: $name"
            } catch (ex: Exception) {
                snackbar = "创建失败: ${ex.message}"
            }
        }
    }
}
