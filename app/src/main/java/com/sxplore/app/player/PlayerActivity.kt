package com.sxplore.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.sxplore.app.SxploreApp
import com.sxplore.app.proxy.StreamProxy
import com.sxplore.app.util.formatSpeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Built-in player: Media3/ExoPlayer with folder playlist.
 * SMB files stream through the local proxy (zero download); local files use file://.
 *
 * Features: 0.5-5x speed, audio/subtitle track selection, external subtitles
 * (.srt/.ass/.ssa/.vtt found next to the video), repeat modes (order / loop
 * playlist / loop one) with automatic next-item playback.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var errorMsg by mutableStateOf<String?>(null)

    private var isBuffering by mutableStateOf(true)
    private var isPlaying by mutableStateOf(false)
    private var startPct by mutableIntStateOf(0)
    private var bufferedAheadMs by mutableStateOf(0L)
    private var speedBps by mutableStateOf(0L)
    private var showLiveSpeed = true
    private var everPlayed = false

    private var playbackSpeed by mutableFloatStateOf(1f)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_ALL)
    private var currentTitle by mutableStateOf("")
    private var showSpeedDialog by mutableStateOf(false)
    private var showTracksDialog by mutableStateOf(false)
    private var showExtSubDialog by mutableStateOf(false)

    private var titles = arrayListOf<String>()
    private var subUrls = arrayListOf<String>()
    private var subTitles = arrayListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val urls = intent.getStringArrayListExtra(EXTRA_URLS)
        if (urls.isNullOrEmpty()) {
            finish()
            return
        }
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        subUrls = intent.getStringArrayListExtra(EXTRA_SUB_URLS) ?: arrayListOf()
        subTitles = intent.getStringArrayListExtra(EXTRA_SUB_TITLES) ?: arrayListOf()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.lastIndex)
        currentTitle = titles.getOrElse(startIndex) { "" }

        val container = (application as SxploreApp).container
        showLiveSpeed = container.settings.showLiveSpeed
        val speedMonitor = container.proxy.speedMonitor

        // Keep Wi-Fi out of power-save while streaming.
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "sxplore:playback").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(DefaultDataSource.Factory(this, httpFactory))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        START_BUFFER_MS,
                        REBUFFER_BUFFER_MS,
                    )
                    .build()
            )
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        val items = urls.mapIndexed { i, u ->
            MediaItem.Builder()
                .setUri(u)
                .setMimeType(StreamProxy.mimeOf(titles.getOrElse(i) { "" }))
                .build()
        }
        p.setMediaItems(items, startIndex, C.TIME_UNSET)
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.playWhenReady = true
        p.prepare()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (isBuffering) updateStartProgress(p)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) everPlayed = true
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = titles.getOrElse(p.currentMediaItemIndex) { "" }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    everPlayed = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMsg = "Playback failed: ${error.errorCodeName}\n${error.message}"
            }
        })
        player = p

        setContent {
            LaunchedEffect(Unit) {
                while (isActive) {
                    speedBps = speedMonitor.bytesPerSecond()
                    if (isBuffering) {
                        player?.let { updateStartProgress(it) }
                    }
                    delay(500)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = p
                                useController = true
                                controllerShowTimeoutMs = 3000
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                // Settings live in our top-right controls (speed /
                                // tracks+external subs / repeat). Hide the native
                                // track-selection entry points to avoid two competing
                                // menus; keep native transport (play/seek/next/prev).
                                setShowSubtitleButton(false)
                                findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
                                    ?.visibility = android.view.View.GONE
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Row(
                        Modifier.align(Alignment.TopStart).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Text(
                            currentTitle,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Top-right controls: speed / tracks / repeat + speed badge.
                    Column(
                        Modifier.align(Alignment.TopEnd).padding(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showSpeedDialog = true }) {
                                Text("${playbackSpeed}×", color = Color.White, fontSize = 12.sp)
                            }
                            IconButton(onClick = { showTracksDialog = true }) {
                                Icon(
                                    Icons.Filled.Subtitles, "Tracks and subtitles",
                                    Modifier.size(20.dp), tint = Color.White,
                                )
                            }
                            IconButton(onClick = { cycleRepeat(p) }) {
                                Icon(
                                    when (repeatMode) {
                                        Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                                        Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat
                                        else -> Icons.Filled.PlaylistPlay
                                    },
                                    "Repeat mode",
                                    Modifier.size(20.dp),
                                    tint = Color.White,
                                )
                            }
                        }
                        if (showLiveSpeed && (isBuffering || isPlaying)) {
                            Text(
                                formatSpeed(speedBps),
                                Modifier
                                    .background(Color(0x66000000), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    // Buffering overlay: progress toward playback start + speed.
                    if (isBuffering && errorMsg == null) {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .background(Color(0x99000000), RoundedCornerShape(12.dp))
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("Buffering… $startPct%", color = Color.White, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "%.1fs / %.1fs to play".format(
                                    bufferedAheadMs / 1000.0,
                                    startTargetMs() / 1000.0,
                                ),
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(formatSpeed(speedBps), color = Color(0xFFB0BEC5), fontSize = 12.sp)
                        }
                    }

                    errorMsg?.let { msg ->
                        Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(msg, color = Color.White, fontSize = 13.sp)
                            Button(onClick = {
                                errorMsg = null
                                p.prepare()
                            }, modifier = Modifier.padding(top = 12.dp)) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            if (showSpeedDialog) {
                SpeedDialog(
                    current = playbackSpeed,
                    onPick = {
                        playbackSpeed = it
                        p.setPlaybackSpeed(it)
                        showSpeedDialog = false
                    },
                    onDismiss = { showSpeedDialog = false },
                )
            }
            if (showTracksDialog) {
                TracksDialog(
                    player = p,
                    onDismiss = { showTracksDialog = false },
                    onLoadExternal = {
                        showTracksDialog = false
                        showExtSubDialog = true
                    },
                )
            }
            if (showExtSubDialog) {
                ExternalSubtitleDialog(
                    names = subTitles,
                    onPick = { i ->
                        showExtSubDialog = false
                        applyExternalSubtitle(p, subUrls[i], subTitles[i])
                    },
                    onDismiss = { showExtSubDialog = false },
                )
            }
        }
    }

    private fun cycleRepeat(p: ExoPlayer) {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        p.repeatMode = repeatMode
        val label = when (repeatMode) {
            Player.REPEAT_MODE_ALL -> "Loop playlist"
            Player.REPEAT_MODE_ONE -> "Loop current"
            else -> "Play in order (no loop)"
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    /** Attach an external subtitle file to the current item, keeping the playlist. */
    private fun applyExternalSubtitle(p: ExoPlayer, url: String, name: String) {
        val idx = p.currentMediaItemIndex
        val pos = p.currentPosition
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setMimeType(mime)
            .setLabel(name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val item = p.getMediaItemAt(idx).buildUpon()
            .setSubtitleConfigurations(listOf(sub))
            .build()
        p.replaceMediaItem(idx, item)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        p.seekTo(pos)
        p.prepare()
    }

    private fun startTargetMs(): Int = if (everPlayed) REBUFFER_BUFFER_MS else START_BUFFER_MS

    private fun updateStartProgress(p: ExoPlayer) {
        val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        bufferedAheadMs = ahead
        startPct = (ahead * 100 / startTargetMs()).toInt().coerceIn(0, 100)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_SUB_URLS = "sub_urls"
        private const val EXTRA_SUB_TITLES = "sub_titles"

        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 50_000
        private const val START_BUFFER_MS = 2_500
        private const val REBUFFER_BUFFER_MS = 5_000

        fun makeIntent(
            ctx: Context,
            urls: ArrayList<String>,
            titles: ArrayList<String>,
            index: Int,
            subUrls: ArrayList<String>,
            subTitles: ArrayList<String>,
        ): Intent = Intent(ctx, PlayerActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_URLS, urls)
            putStringArrayListExtra(EXTRA_TITLES, titles)
            putExtra(EXTRA_INDEX, index)
            putStringArrayListExtra(EXTRA_SUB_URLS, subUrls)
            putStringArrayListExtra(EXTRA_SUB_TITLES, subTitles)
        }
    }
}

@Composable
private fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f).forEach { s ->
                    Text(
                        "${s}×" + if (s == current) "  ✓" else "",
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(s) }
                            .padding(vertical = 10.dp),
                        fontSize = 15.sp,
                        fontWeight = if (s == current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TracksDialog(player: ExoPlayer, onDismiss: () -> Unit, onLoadExternal: () -> Unit) {
    val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
    val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tracks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Audio", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                if (audioGroups.isEmpty()) {
                    Text("No audio tracks", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                audioGroups.forEach { g ->
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        val label = f.label ?: f.language ?: "Track ${i + 1}"
                        TrackRow(label) { selectTrack(player, g, i) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Subtitles", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                TrackRow("Off") {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
                textGroups.forEach { g ->
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        val label = f.label ?: f.language ?: "Subtitle ${i + 1}"
                        TrackRow(label) { selectTrack(player, g, i) }
                    }
                }
                TrackRow("Load external subtitle…", onLoadExternal)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TrackRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun selectTrack(player: ExoPlayer, group: Tracks.Group, trackIndex: Int) {
    val builder = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
    if (group.type == C.TRACK_TYPE_TEXT) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    }
    player.trackSelectionParameters = builder.build()
}

@Composable
private fun ExternalSubtitleDialog(names: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("External subtitle") },
        text = {
            if (names.isEmpty()) {
                Text(
                    "No subtitle files (.srt / .ass / .ssa / .vtt) found next to this video.",
                    fontSize = 13.sp,
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    names.forEachIndexed { i, n -> TrackRow(n) { onPick(i) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
