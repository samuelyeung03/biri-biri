package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Full-screen video call UI.
 *
 * Rendering is intentionally not wired yet, so the video surface is a placeholder.
 */
@Composable
fun VideoCallScreen(
    modifier: Modifier = Modifier,
    onHangUp: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Placeholder for video rendering (to be implemented later)
        Box(
            modifier = Modifier
                .fillMaxSize()
        )

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

