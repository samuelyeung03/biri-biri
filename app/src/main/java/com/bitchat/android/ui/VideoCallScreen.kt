package com.bitchat.android.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

/**
 * Full-screen video call UI.
 *
 * Uses a SurfaceView for remote video rendering.
 */
@Composable
fun VideoCallScreen(
    modifier: Modifier = Modifier,
    videoCallStats: VideoCallStats? = null,
    onHangUp: () -> Unit,
    onRemoteSurfaceAvailable: (Surface) -> Unit,
    onRemoteSurfaceDestroyed: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val holderCallback = remember {
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    onRemoteSurfaceAvailable(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    // no-op
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    onRemoteSurfaceDestroyed()
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                SurfaceView(context).apply {
                    // Best-effort: keep screen on during call
                    keepScreenOn = true
                    holder.addCallback(holderCallback)
                }
            },
            update = { /* no-op */ }
        )

        if (videoCallStats != null) {
            val bitrateKbps = (videoCallStats.bitrateBps / 1000).coerceAtLeast(0)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "${videoCallStats.width}x${videoCallStats.height} • ${videoCallStats.codec} • ${bitrateKbps} kbps",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Ensure callback is removed when composable disposes.
        DisposableEffect(Unit) {
            onDispose {
                onRemoteSurfaceDestroyed()
            }
        }

        FloatingActionButton(
            onClick = onHangUp,
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Icon(imageVector = Icons.Filled.CallEnd, contentDescription = "Hang up")
        }
    }
}
