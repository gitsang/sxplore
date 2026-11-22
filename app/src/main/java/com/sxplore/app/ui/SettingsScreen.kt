package com.sxplore.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sxplore.app.SxploreApp
import com.sxplore.app.caption.ModelDownloader
import com.sxplore.app.data.ProviderDef
import com.sxplore.app.data.Providers
import com.sxplore.app.util.DebugLog
import com.sxplore.app.util.formatSize
import java.io.File

/**
 * Settings page, organized as:
 *   General | Video (ASR / Translation / Network) | Developer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val container = (vm.getApplication<SxploreApp>()).container
    val settings = container.settings
    val downloader = container.modelDownloader
    val ctx = LocalContext.current
    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    BackHandler { vm.showSettings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.showSettings = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ================= General =================
            SectionHeader("General")
            SwitchRow(
                title = "Remember tabs and paths",
                subtitle = "Restore open tabs, expanded folders and current paths on launch",
                checked = settings.rememberTabs,
                onChange = { settings.rememberTabs = it },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // ================= Video =================
            SectionHeader("Video")

            // ---------- ASR ----------
            SubHeader("ASR (speech recognition)")
            SwitchRow(
                title = "Enable by default",
                subtitle = "Start AI subtitles automatically when a video plays",
                checked = settings.aiSubtitlesEnabled,
                onChange = { settings.aiSubtitlesEnabled = it },
            )
            SettingField("Language hint (blank = auto)", settings.whisperLanguage, "en / zh / ja …") {
                settings.whisperLanguage = it
            }

            var asrProvider by remember { mutableStateOf(settings.asrProvider) }
            ProviderPicker("Provider", Providers.asr, asrProvider) {
                asrProvider = it
                settings.asrProvider = it
            }
            if (asrProvider == "local") {
                SettingField("Download source", settings.modelDownloadSource, "https://hf-mirror.com") {
                    settings.modelDownloadSource = it
                }
                var selectedModel by remember { mutableStateOf(settings.whisperLocalModel) }
                downloader.models.forEach { m ->
                    val st = downloader.stateOf(m.name)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedModel == m.name,
                            onClick = {
                                selectedModel = m.name
                                settings.whisperLocalModel = m.name
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${m.label} (~${m.sizeMb} MB)", fontSize = 14.sp)
                            when (st.status) {
                                ModelDownloader.Status.READY ->
                                    Text("Ready", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                ModelDownloader.Status.DOWNLOADING -> {
                                    Text("Downloading… ${st.progress}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    LinearProgressIndicator(
                                        progress = { st.progress / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                ModelDownloader.Status.ERROR ->
                                    Text(st.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 2)
                                else ->
                                    Text("Not downloaded", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when (st.status) {
                            ModelDownloader.Status.READY ->
                                TextButton(onClick = { downloader.delete(m.name) }) { Text("Delete") }
                            ModelDownloader.Status.DOWNLOADING ->
                                TextButton(onClick = { downloader.cancel(m.name) }) { Text("Cancel") }
                            else ->
                                TextButton(onClick = {
                                    downloader.download(m.name, settings.modelDownloadSource)
                                }) { Text(if (st.status == ModelDownloader.Status.ERROR) "Retry" else "Download") }
                        }
                    }
                }
                Text(
                    "ggml models from ggerganov/whisper.cpp. hf-mirror.com works in China; " +
                        "use https://huggingface.co elsewhere. Tiny is fastest, Small is most accurate.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ProviderFields("asr", asrProvider, settings)
            }

            // ---------- Translation ----------
            SubHeader("Translation")
            SwitchRow(
                title = "Translate subtitles",
                subtitle = "Show original + translated lines together (bilingual)",
                checked = settings.translationEnabled,
                onChange = { settings.translationEnabled = it },
            )
            var transProvider by remember { mutableStateOf(settings.transProvider) }
            ProviderPicker("Provider", Providers.translation, transProvider) {
                transProvider = it
                settings.transProvider = it
            }
            ProviderFields("trans", transProvider, settings)
            SettingField("Target language", settings.translationTargetLang, "Chinese") {
                settings.translationTargetLang = it
            }

            // ---------- Network ----------
            SubHeader("Network")
            SwitchRow(
                title = "Real-time network speed",
                subtitle = "Show live download speed while playing",
                checked = vm.showLiveSpeed,
                onChange = { vm.setLiveSpeedEnabled(it) },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // ================= Developer =================
            SectionHeader("Developer")
            var debugOn by remember { mutableStateOf(settings.debugMode) }
            SwitchRow(
                title = "Debug mode",
                subtitle = "Write diagnostic logs to a file (proxy / SMB / player / captions)",
                checked = debugOn,
                onChange = {
                    debugOn = it
                    settings.debugMode = it
                    DebugLog.enabled = it
                    if (it) DebugLog.d("App", "debug mode enabled")
                },
            )
            SwitchRow(
                title = "Clean on close",
                subtitle = "Clear diagnostic logs when the app exits",
                checked = settings.cleanLogsOnClose,
                onChange = { settings.cleanLogsOnClose = it },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val zip = File(ctx.cacheDir, "sxplore-logs.zip")
                    if (!DebugLog.exportZip(zip)) {
                        Toast.makeText(
                            ctx,
                            "No logs yet — enable Debug mode and reproduce the issue first",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", zip)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Export logs"))
                    }
                }) { Text("Export logs") }
                TextButton(onClick = {
                    DebugLog.clear()
                    Toast.makeText(ctx, "Logs cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear logs") }
                Text(
                    formatSize(DebugLog.totalSize()),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "Version $versionName",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Base URL / API key / model fields for an API provider; blanks use preset defaults. */
@Composable
private fun ProviderFields(scope: String, provider: String, settings: com.sxplore.app.data.SettingsStore) {
    val def = Providers.byId(provider)
    key(provider) {
        SettingField(
            "Base URL (blank = default)",
            settings.providerBase(scope, provider),
            def?.defaultBase?.ifBlank { "https://your-endpoint/v1" } ?: "https://your-endpoint/v1",
        ) { settings.setProviderBase(scope, provider, it) }
        SettingField("API key", settings.providerKey(scope, provider), "sk-…", password = true) {
            settings.setProviderKey(scope, provider, it)
        }
        SettingField(
            "Model (blank = default)",
            settings.providerModel(scope, provider),
            Providers.defaultModel(scope, provider).ifBlank { "model name" },
        ) { settings.setProviderModel(scope, provider, it) }
    }
    if (provider != "custom") {
        Text(
            "Leave base URL / model blank to use the ${def?.label ?: ""} defaults.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Any OpenAI-compatible endpoint: /audio/transcriptions for ASR, /chat/completions for translation.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(label: String, providers: List<ProviderDef>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = providers.firstOrNull { it.id == selected } ?: providers.first()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 13.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = {
                        onSelect(p.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SubHeader(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var value by remember { mutableStateOf(checked) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = value,
            onCheckedChange = {
                value = it
                onChange(it)
            },
        )
    }
}

@Composable
private fun SettingField(label: String, initial: String, placeholder: String, password: Boolean = false, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it)
        },
        label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
