package com.sxplore.app.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.GifDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Built-in image viewer with folder playlist.
 * Swipe left/right for previous/next image; optional slideshow auto-advance
 * with adjustable interval and looping. Pinch zoom / pan / double-tap zoom
 * per image. SMB images stream through the local proxy (zero download).
 */
class ImageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val urls = intent.getStringArrayListExtra(EXTRA_URLS)
        if (urls.isNullOrEmpty()) {
            finish()
            return
        }
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.lastIndex)

        // OkHttp defaults to a 10s read timeout, which is far too tight for
        // slow remote SMB links (~10KB/s): the first bytes can legitimately
        // take longer. Match the video player's generous timeouts.
        val okHttp = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val loader = coil.ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val pagerState = rememberPagerState(
                    initialPage = startIndex,
                    pageCount = { urls.size },
                )
                var playing by remember { mutableStateOf(false) }
                var intervalSec by remember { mutableIntStateOf(3) }
                var loop by remember { mutableStateOf(true) }

                // Slideshow auto-advance; restarts whenever the page changes
                // (manual swipe included).
                LaunchedEffect(playing, pagerState.currentPage, intervalSec, loop) {
                    if (!playing) return@LaunchedEffect
                    delay(intervalSec * 1000L)
                    val next = pagerState.currentPage + 1
                    when {
                        next < urls.size -> pagerState.animateScrollToPage(next)
                        loop -> pagerState.animateScrollToPage(0)
                        else -> playing = false
                    }
                }

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        ZoomableImage(
                            url = urls[page],
                            title = titles.getOrElse(page) { "" },
                            loader = loader,
                        )
                    }

                    // Top bar: back + title + counter.
                    Row(
                        Modifier.align(Alignment.TopStart).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Text(
                            titles.getOrElse(pagerState.currentPage) { "" },
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            "  ${pagerState.currentPage + 1} / ${urls.size}",
                            color = Color(0xFFB0BEC5),
                            fontSize = 12.sp,
                        )
                    }

                    // Slideshow controls: play/pause, interval, loop.
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            intervalSec = when (intervalSec) {
                                2 -> 3
                                3 -> 5
                                5 -> 10
                                else -> 2
                            }
                        }) {
                            Text("${intervalSec}s", color = Color.White, fontSize = 12.sp)
                        }
                        IconButton(onClick = { loop = !loop }) {
                            Icon(
                                Icons.Filled.Repeat, "Loop slideshow",
                                Modifier.size(20.dp),
                                tint = if (loop) Color.White else Color(0xFF666666),
                            )
                        }
                        IconButton(onClick = { playing = !playing }) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playing) "Pause slideshow" else "Start slideshow",
                                Modifier.size(24.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"

        fun makeIntent(
            ctx: Context,
            urls: ArrayList<String>,
            titles: ArrayList<String>,
            index: Int,
        ): Intent = Intent(ctx, ImageActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_URLS, urls)
            putStringArrayListExtra(EXTRA_TITLES, titles)
            putExtra(EXTRA_INDEX, index)
        }
    }
}

@Composable
private fun ZoomableImage(url: String, title: String, loader: coil.ImageLoader) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var retryTick by remember { mutableIntStateOf(0) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(url)
            .setParameter("retry", retryTick)
            .build(),
        contentDescription = title,
        imageLoader = loader,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(transformState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
            is AsyncImagePainter.State.Error -> {
                val err = (painter.state as AsyncImagePainter.State.Error).result.throwable
                android.util.Log.e("SxploreImage", "Load failed: $url", err)
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .fillMaxSize()
                        .clickable { retryTick++ },
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Failed to load image", color = Color.White, fontSize = 14.sp)
                    Text(
                        err.message ?: err.javaClass.simpleName,
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Text("Tap to retry", color = Color.White, fontSize = 12.sp)
                }
            }
            else -> SubcomposeAsyncImageContent()
        }
    }
}
