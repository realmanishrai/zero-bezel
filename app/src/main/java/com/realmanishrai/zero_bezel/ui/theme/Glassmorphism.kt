package com.realmanishrai.zero_bezel.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun Modifier.glassCard(
    cornerRadius: Int = 16
): Modifier = this.composed {
    this.shadow(
        elevation = 12.dp,
        shape = RoundedCornerShape(cornerRadius.dp),
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.04f),
        spotColor = Color.Black.copy(alpha = 0.08f)
    )
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(Color.White.copy(alpha = 0.75f)) // alpha 0.8 / 0.75 for semi-transparency
    .border(
        BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(cornerRadius.dp)
    )
}

@Composable
fun GlassyBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC)) // Pure light slate background
    ) {
        // Cyan glass mesh circle
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(8.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF0EA5E9).copy(alpha = 0.15f), Color.Transparent),
                        center = Offset(200f, 300f),
                        radius = 700f
                    )
                )
        )

        // Pink/Indigo glass mesh circle
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(8.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFD946EF).copy(alpha = 0.15f), Color.Transparent),
                        center = Offset(800f, 1500f),
                        radius = 800f
                    )
                )
        )

        // Yellow glass mesh circle
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(8.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFF59E0B).copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(100f, 1200f),
                        radius = 600f
                    )
                )
        )

        content()
    }
}
