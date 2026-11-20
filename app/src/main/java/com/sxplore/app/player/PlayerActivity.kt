package com.sxplore.app.player

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.sxplore.app.SxploreApp
import com.sxplore.app.util.formatSpeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Built-in player: Media3/ExoPlayer.
 * SMB files go through the local proxy URL (http://127.0.0.1), local files use file://.
 * Both support seeking; fully streamed, nothing is written to disk.
 *
 * Shows buffering progress + live network speed while loading, and an optional
 * persistent speed badge (Settings -> Real-time network speed).
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var errorMsg by mutableStateOf<String?>(null)

    private var isBuffering by mutableStateOf(true)
    private var isPlaying by mutableStateOf(false)
    private var startPct by mutableStateOf(0)
    private var bufferedAheadMs by mutableStateOf(0L)
    private var speedBps by mutableStateOf(0L)
    private var showLiveSpeed = true

    // True once playback has actually started; a seek resets it because the
    // start gate drops back to the (smaller) initial buffer requirement.
    private var everPlayed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val container = (application as SxploreApp).container
        showLiveSpeed = container.settings.showLiveSpeed
        val speedMonitor = container.proxy.speedMonitor

        // Keep Wi-Fi out of power-save while streaming: power-save adds
        // latency spikes that cause constant re-buffering on SMB playback.
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

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "video/*"

        // Read timeout generous enough for slow remote links; the adaptive
        // prefetch keeps bytes trickling, so this only fires on real stalls.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(DefaultDataSource.Factory(this, httpFactory))
                    // More retries for flaky/slow networks before surfacing an error.
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            )
            // Explicit start gates so the UI can show "distance to playback start".
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
        p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mime).build())
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
            // Poll start progress and network speed for the overlays.
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
                                setShowSubtitleButton(true)
                                controllerShowTimeoutMs = 3000
                                // We render our own buffering overlay with % and speed.
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
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
                            title,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Buffering overlay: progress + live speed.
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
                            Text(
                                "Buffering… $startPct%",
                                color = Color.White,
                                fontSize = 14.sp,
                            )
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
                            Text(
                                formatSpeed(speedBps),
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp,
                            )
                        }
                    }

                    // Optional persistent live-speed badge.
                    if (showLiveSpeed && (isBuffering || isPlaying)) {
                        Text(
                            formatSpeed(speedBps),
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .background(Color(0x66000000), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
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
        }
    }

    /** Buffer requirement before playback may (re)start, matching LoadControl. */
    private fun startTargetMs(): Int = if (everPlayed) REBUFFER_BUFFER_MS else START_BUFFER_MS

    /** Progress toward playback start: buffered-ahead media vs the start gate. */
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
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MIME = "mime"

        // LoadControl buffer durations (ms). The last two are the gates the
        // buffering overlay measures progress against.
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 50_000
        private const val START_BUFFER_MS = 2_500
        private const val REBUFFER_BUFFER_MS = 5_000

        fun makeIntent(ctx: Context, url: String, title: String, mime: String): Intent =
            Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MIME, mime)
            }
    }
}
