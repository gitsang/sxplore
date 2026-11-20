package com.sxplore.app.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sxplore.app.SxploreApp
import com.sxplore.app.caption.ModelDownloader

/** Full settings page (replaces the old dialog). */
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
            // ---------- General ----------
            SectionHeader("General")
            SwitchRow(
                title = "Remember tabs and paths",
                subtitle = "Restore open tabs, expanded folders and current paths on launch",
                checked = settings.rememberTabs,
                onChange = { settings.rememberTabs = it },
            )
            SwitchRow(
                title = "Real-time network speed",
                subtitle = "Show live download speed while playing",
                checked = vm.showLiveSpeed,
                onChange = { vm.setLiveSpeedEnabled(it) },
            )

            // ---------- AI live captions ----------
            SectionHeader("AI live subtitles (Whisper)")
            Text(
                "Recognizes speech from the playing video via an OpenAI-compatible " +
                    "/audio/transcriptions endpoint (OpenAI or a self-hosted Whisper server). " +
                    "Uses extra bandwidth: audio is read ahead of playback.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "Enable by default",
                subtitle = "Start AI subtitles automatically when a video plays",
                checked = settings.aiSubtitlesEnabled,
                onChange = { settings.aiSubtitlesEnabled = it },
            )
            SettingField("API base URL", settings.whisperBaseUrl, "https://api.openai.com") {
                settings.whisperBaseUrl = it
            }
            SettingField("API key", settings.whisperApiKey, "sk-…", password = true) {
                settings.whisperApiKey = it
            }
            SettingField("Model", settings.whisperModel, "whisper-1") {
                settings.whisperModel = it
            }
            SettingField("Language hint (blank = auto)", settings.whisperLanguage, "en / zh / ja …") {
                settings.whisperLanguage = it
            }

            // ---------- Local Whisper ----------
            SectionHeader("Local Whisper (on-device)")
            var useLocal by remember { mutableStateOf(settings.whisperEngine == "local") }
            SwitchRow(
                title = "Use local model",
                subtitle = "Recognize on-device with whisper.cpp (arm64); no API needed",
                checked = useLocal,
                onChange = {
                    useLocal = it
                    settings.whisperEngine = if (it) "local" else "api"
                },
            )
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
            SettingField("Model download source", settings.modelDownloadSource, "https://hf-mirror.com") {
                settings.modelDownloadSource = it
            }
            Text(
                "Models are ggml files from ggerganov/whisper.cpp. Default mirror hf-mirror.com " +
                    "works in China; use https://huggingface.co elsewhere. Tiny is fastest, Small is most accurate.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------- Translation ----------
            SectionHeader("Translation (bilingual subtitles)")
            SwitchRow(
                title = "Translate subtitles",
                subtitle = "Show original + translated lines together",
                checked = settings.translationEnabled,
                onChange = { settings.translationEnabled = it },
            )
            SettingField("Target language", settings.translationTargetLang, "Chinese") {
                settings.translationTargetLang = it
            }
            Text(
                "AI engine via OpenAI-compatible /chat/completions. " +
                    "Leave base URL / key blank to reuse the Whisper settings.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SettingField("API base URL (blank = reuse Whisper)", settings.translationBaseUrl, "https://api.openai.com") {
                settings.translationBaseUrl = it
            }
            SettingField("API key (blank = reuse Whisper)", settings.translationApiKey, "sk-…", password = true) {
                settings.translationApiKey = it
            }
            SettingField("Model", settings.translationModel, "gpt-4o-mini") {
                settings.translationModel = it
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

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
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
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
