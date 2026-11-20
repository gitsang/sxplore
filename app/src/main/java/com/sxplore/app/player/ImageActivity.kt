package com.sxplore.app.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/**
 * Built-in image preview. SMB images load through the local streaming proxy
 * (same zero-download path as playback); local images load via file://.
 * Pinch to zoom, drag to pan, double-tap to toggle zoom. Animated GIF on API 28+.
 */
class ImageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        val loader = coil.ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }

                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 8f)
                    offset = if (scale > 1f) offset + panChange else Offset.Zero
                }

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(this@ImageActivity)
                            .data(url)
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
                            is AsyncImagePainter.State.Error -> Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Failed to load image",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                            }
                            else -> SubcomposeAsyncImageContent()
                        }
                    }
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
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun makeIntent(ctx: Context, url: String, title: String): Intent =
            Intent(ctx, ImageActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
