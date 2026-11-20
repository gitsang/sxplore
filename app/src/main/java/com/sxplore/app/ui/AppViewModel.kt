package com.sxplore.app.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sxplore.app.SxploreApp
import com.sxplore.app.data.SavedTab
import com.sxplore.app.data.SavedTabs
import com.sxplore.app.player.ImageActivity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
    val selected: Set<String> = emptySet(),
    val rootLoaded: Boolean = false,
    val rootError: String? = null,
)

data class TreeRow(val entry: VfsEntry, val depth: Int)

data class ClipboardSource(val vfs: Vfs, val path: String)

data class ClipboardOp(val sources: List<ClipboardSource>, val move: Boolean)

data class TransferProgress(val label: String, val done: Long, val total: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as SxploreApp).container

    val tabs = mutableStateListOf<TabUiState>()
    var activeIndex by mutableStateOf(0)

    var clipboard by mutableStateOf<ClipboardOp?>(null)
    var transfer by mutableStateOf<TransferProgress?>(null)
    var snackbar by mutableStateOf<String?>(null)
    var savedConnections by mutableStateOf<List<SmbConnectionInfo>>(emptyList())

    // Settings
    var showLiveSpeed by mutableStateOf(container.settings.showLiveSpeed)
    var showSettings by mutableStateOf(false)

    fun setLiveSpeedEnabled(on: Boolean) {
        showLiveSpeed = on
        container.settings.showLiveSpeed = on
    }

    // Dialog state
    var showNewTab by mutableStateOf(false)
    var connectInitial by mutableStateOf<SmbConnectionInfo?>(null)
    var fileAction by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var dirAction by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var renameTarget by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var newFolderDir by mutableStateOf<Pair<Int, String>?>(null)
    var deleteTarget by mutableStateOf<Pair<Int, VfsEntry>?>(null)
    var infoTarget by mutableStateOf<VfsEntry?>(null)
    var showOverflow by mutableStateOf(false)
    var batchDeleteConfirm by mutableStateOf<Int?>(null)

    private var nextId = 1L

    init {
        refreshConnections()
        if (!restoreTabs()) {
            addTab(container.newLocalVfs(), "Local storage")
        }
        // Persist tab layout (debounced) so it can be restored on next launch.
        viewModelScope.launch {
            snapshotFlow { tabs.toList() to activeIndex }
                .collectLatest {
                    delay(800)
                    if (container.settings.rememberTabs) persistTabs()
                }
        }
    }

    private fun restoreTabs(): Boolean {
        if (!container.settings.rememberTabs) return false
        val saved = container.settings.loadTabs() ?: return false
        if (saved.tabs.isEmpty()) return false
        val connections = container.connectionStore.list()
        saved.tabs.forEach { st ->
            val vfs = when (st.kind) {
                "local" -> container.newLocalVfs()
                "smb" -> {
                    val info = connections.firstOrNull { it.id == st.connectionId } ?: return@forEach
                    container.newSmbVfs(info)
                }
                else -> return@forEach
            }
            tabs.add(
                TabUiState(
                    id = nextId++, title = st.title, vfs = vfs,
                    expanded = st.expanded.toSet(), currentDir = st.currentDir,
                )
            )
            val idx = tabs.lastIndex
            loadRoot(idx)
            st.expanded.forEach { loadChildren(idx, it) }
        }
        if (tabs.isEmpty()) return false
        activeIndex = saved.activeIndex.coerceIn(0, tabs.lastIndex)
        return true
    }

    private fun persistTabs() {
        val dto = SavedTabs(
            tabs = tabs.map { t ->
                SavedTab(
                    kind = if (t.vfs is SmbVfs) "smb" else "local",
                    connectionId = (t.vfs as? SmbVfs)?.info?.id ?: "",
                    title = t.title,
                    expanded = t.expanded.toList(),
                    currentDir = t.currentDir,
                )
            },
            activeIndex = activeIndex,
        )
        container.settings.saveTabs(dto)
    }

    // ---------- Tab management ----------

    private fun addTab(vfs: Vfs, title: String) {
        tabs.add(TabUiState(id = nextId++, title = title, vfs = vfs))
        activeIndex = tabs.lastIndex
        loadRoot(tabs.lastIndex)
    }

    fun addLocalTab() = addTab(container.newLocalVfs(), "Local storage")

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
                onDone(e.message ?: "Connection failed")
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

    // ---------- Tree loading ----------

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
                    it.copy(rootLoaded = true, rootError = e.message ?: "Failed to load", loading = it.loading - "/")
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
                snackbar = "Load failed: ${e.message}"
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

    // ---------- Selection ----------

    fun toggleSelect(index: Int, path: String) {
        update(index) {
            it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
        }
    }

    fun clearSelection(index: Int) {
        update(index) { it.copy(selected = emptySet()) }
    }

    fun selectAll(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val dir = tab.currentDir
        val cached = if (dir == "/") tab.rootEntries else tab.children[dir]
        if (cached != null) {
            update(index) { it.copy(selected = cached.map { e -> e.path }.toSet()) }
        } else {
            viewModelScope.launch {
                try {
                    val entries = withContext(Dispatchers.IO) { tab.vfs.list(dir) }
                    update(index) {
                        it.copy(
                            children = it.children + (dir to entries),
                            selected = entries.map { e -> e.path }.toSet(),
                        )
                    }
                } catch (e: Exception) {
                    snackbar = "Load failed: ${e.message}"
                }
            }
        }
    }

    /** Human-readable full path of the tab's current folder (for copying). */
    fun currentPathText(index: Int): String {
        val tab = tabs.getOrNull(index) ?: return ""
        return when (val vfs = tab.vfs) {
            is SmbVfs -> "smb://${vfs.info.displayHost}/${vfs.info.share}${tab.currentDir}"
            is LocalVfs -> vfs.realFile(tab.currentDir).absolutePath
            else -> tab.currentDir
        }
    }

    // ---------- Playback / opening ----------

    /**
     * Default click behavior: open with the last-used method for this media
     * kind (built-in vs external, remembered per kind). Long-press menu is
     * where the user switches methods.
     */
    fun openFile(index: Int, e: VfsEntry) {
        val kind = StreamProxy.kindOf(e.name)
        val internal = container.settings.openInternally(kind)
        when (kind) {
            "video", "audio" -> if (internal) playInternal(index, e) else playExternal(index, e)
            "image" -> if (internal) previewImage(index, e) else playExternal(index, e)
            else -> playExternal(index, e)
        }
    }

    /** Chosen from the long-press menu: remember the choice, then open. */
    fun openInternalChosen(index: Int, e: VfsEntry) {
        val kind = StreamProxy.kindOf(e.name)
        container.settings.setOpenMode(kind, true)
        if (kind == "image") previewImage(index, e) else playInternal(index, e)
    }

    fun openExternalChosen(index: Int, e: VfsEntry) {
        container.settings.setOpenMode(StreamProxy.kindOf(e.name), false)
        playExternal(index, e)
    }

    private fun previewImage(index: Int, e: VfsEntry) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val tab = tabs[index]
                val parent = VfsPaths.parent(e.path)
                val siblings = (if (parent == "/") tab.rootEntries else tab.children[parent])
                    ?: withContext(Dispatchers.IO) { tab.vfs.list(parent) }
                // Image playlist: swipe left/right between sibling images,
                // optional auto-advance slideshow.
                val images = siblings
                    .filter { StreamProxy.kindOf(it.name) == "image" }
                    .sortedBy { it.name.lowercase() }
                val startIdx = images.indexOfFirst { it.path == e.path }.coerceAtLeast(0)
                val urls = ArrayList<String>()
                val titles = ArrayList<String>()
                withContext(Dispatchers.IO) {
                    images.forEach {
                        urls += streamUrl(index, it)
                        titles += it.name
                    }
                }
                ctx.startActivity(
                    ImageActivity.makeIntent(ctx, urls, titles, startIdx)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ex: Exception) {
                snackbar = "Cannot preview: ${ex.message}"
            }
        }
    }

    private fun streamUrl(index: Int, e: VfsEntry): String {
        val tab = tabs[index]
        return when (val vfs = tab.vfs) {
            is SmbVfs -> container.proxy.register(vfs, e.path, e.size, e.name)
            is LocalVfs -> Uri.fromFile(vfs.realFile(e.path)).toString()
            else -> throw IllegalStateException("Unsupported file source")
        }
    }

    fun playInternal(index: Int, e: VfsEntry) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val tab = tabs[index]
                val parent = VfsPaths.parent(e.path)
                val siblings = (if (parent == "/") tab.rootEntries else tab.children[parent])
                    ?: withContext(Dispatchers.IO) { tab.vfs.list(parent) }
                // Playlist = sibling video/audio files in the same folder, so
                // playback can continue to the next item and loop.
                val media = siblings
                    .filter { StreamProxy.kindOf(it.name) == "video" || StreamProxy.kindOf(it.name) == "audio" }
                    .sortedBy { it.name.lowercase() }
                val startIdx = media.indexOfFirst { it.path == e.path }.coerceAtLeast(0)
                val urls = ArrayList<String>()
                val titles = ArrayList<String>()
                val subUrls = ArrayList<String>()
                val subTitles = ArrayList<String>()
                withContext(Dispatchers.IO) {
                    media.forEach {
                        urls += streamUrl(index, it)
                        titles += it.name
                    }
                    siblings.filter { isSubtitleFile(it.name) }
                        .sortedBy { it.name.lowercase() }
                        .forEach {
                            subUrls += streamUrl(index, it)
                            subTitles += it.name
                        }
                }
                ctx.startActivity(
                    PlayerActivity.makeIntent(ctx, urls, titles, startIdx, subUrls, subTitles)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ex: Exception) {
                snackbar = "Cannot play: ${ex.message}"
            }
        }
    }

    private fun isSubtitleFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("srt", "ass", "ssa", "vtt")

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
                Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ex: ActivityNotFoundException) {
            snackbar = "No player app found"
        }
    }

    // ---------- File operations ----------

    fun setClipboard(index: Int, e: VfsEntry, move: Boolean) {
        clipboard = ClipboardOp(listOf(ClipboardSource(tabs[index].vfs, e.path)), move)
        snackbar = if (move) "Cut: ${e.name} — open a destination folder and tap Paste" else "Copied: ${e.name} — open a destination folder and tap Paste"
    }

    fun setClipboardSelected(index: Int, move: Boolean) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab.selected.isEmpty()) return
        clipboard = ClipboardOp(tab.selected.map { ClipboardSource(tab.vfs, it) }, move)
        snackbar = "${if (move) "Cut" else "Copied"} ${tab.selected.size} item(s) — open a destination folder and tap Paste"
    }

    fun paste() {
        val op = clipboard ?: return
        val idx = activeIndex
        val tab = tabs.getOrNull(idx) ?: return
        val destDir = tab.currentDir
        val sources = op.sources.filterNot {
            it.vfs.id == tab.vfs.id && VfsPaths.parent(it.path) == destDir
        }
        if (sources.isEmpty()) {
            snackbar = "Source and destination folders are the same"
            return
        }
        viewModelScope.launch {
            transfer = TransferProgress("Preparing…", 0, 0)
            try {
                sources.forEachIndexed { i, src ->
                    val label = "${if (op.move) "Moving" else "Copying"} (${i + 1}/${sources.size})"
                    withContext(Dispatchers.IO) {
                        TransferEngine.copy(src.vfs, src.path, tab.vfs, destDir) { done, total ->
                            transfer = TransferProgress(label, done, total)
                        }
                    }
                    if (op.move) {
                        withContext(Dispatchers.IO) { src.vfs.delete(src.path) }
                    }
                }
                if (op.move) {
                    clipboard = null
                    val ids = sources.map { it.vfs.id }.toSet()
                    tabs.forEachIndexed { i, t -> if (t.vfs.id in ids) refreshAll(i) }
                }
                refreshDir(idx, destDir)
                snackbar = "Transfer complete"
            } catch (e: Exception) {
                snackbar = "Transfer failed: ${e.message}"
            } finally {
                transfer = null
            }
        }
    }

    fun batchDelete(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val paths = tab.selected.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            withContext(Dispatchers.IO) {
                paths.forEach { p ->
                    runCatching { tab.vfs.delete(p) }.onFailure { failed++ }
                }
            }
            val gone = paths.toSet()
            update(index) {
                it.copy(selected = emptySet(), expanded = it.expanded - gone, children = it.children - gone)
            }
            refreshAll(index)
            snackbar = if (failed == 0) {
                "Deleted ${paths.size} item(s)"
            } else {
                "Deleted ${paths.size - failed} item(s), $failed failed"
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
                snackbar = "Deleted: ${e.name}"
            } catch (ex: Exception) {
                snackbar = "Delete failed: ${ex.message}"
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
                snackbar = "Renamed"
            } catch (ex: Exception) {
                snackbar = "Rename failed: ${ex.message}"
            }
        }
    }

    fun mkdir(index: Int, dir: String, name: String) {
        val tab = tabs.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { tab.vfs.mkdir(VfsPaths.join(dir, name)) }
                refreshDir(index, dir)
                snackbar = "Folder created: $name"
            } catch (ex: Exception) {
                snackbar = "Create failed: ${ex.message}"
            }
        }
    }
}
