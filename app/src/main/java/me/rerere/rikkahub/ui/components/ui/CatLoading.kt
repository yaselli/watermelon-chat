/*
 * 西瓜瓣 Watermelon Chat
 * Derived from RikkaHub, licensed under GNU AGPL v3. See LICENSE.
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

/** A fixed-size, theme-tinted kitten for the pending chat response. */
@Composable
fun CatLoadingIndicator(modifier: Modifier = Modifier) {
    if (!LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator) {
        ContainedLoadingIndicator(modifier = modifier)
        return
    }

    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.notification_live_update_thinking)
    val transition = rememberInfiniteTransition(label = "kittenWaiting")
    val tilt = transition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "kittenHeadTilt",
    )
    val eyes = transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4600
                1f at 0
                1f at 2300
                0.12f at 2400
                1f at 2520
                1f at 4600
            },
        ),
        label = "kittenBlink",
    )
    val head = remember {
        Path().apply {
            moveTo(10f, 21f)
            lineTo(10f, 8f)
            quadraticTo(10f, 5f, 12.5f, 7f)
            lineTo(20f, 13f)
            quadraticTo(24f, 11.5f, 28f, 13f)
            lineTo(35.5f, 7f)
            quadraticTo(38f, 5f, 38f, 8f)
            lineTo(38f, 21f)
            cubicTo(43f, 31f, 37f, 38f, 24f, 38f)
            cubicTo(11f, 38f, 5f, 31f, 10f, 21f)
            close()
        }
    }
    val mouth = remember {
        Path().apply {
            moveTo(24f, 28f)
            lineTo(24f, 30f)
            cubicTo(23f, 33f, 20f, 33f, 19f, 31f)
            moveTo(24f, 30f)
            cubicTo(25f, 33f, 28f, 33f, 29f, 31f)
        }
    }
    val paws = remember {
        Path().apply {
            moveTo(12f, 42f)
            lineTo(12f, 40f)
            cubicTo(12f, 36f, 20f, 36f, 20f, 40f)
            lineTo(20f, 42f)
            quadraticTo(16f, 45f, 12f, 42f)
            close()
            moveTo(28f, 42f)
            lineTo(28f, 40f)
            cubicTo(28f, 36f, 36f, 36f, 36f, 40f)
            lineTo(36f, 42f)
            quadraticTo(32f, 45f, 28f, 42f)
            close()
        }
    }

    Canvas(modifier.size(28.dp).semantics { contentDescription = description }) {
        // Read animation states only while drawing; chat layout and messages do not recompose per frame.
        val scale = size.minDimension / 48f
        val outline = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({
            translate((size.width - 48f * scale) / 2f, (size.height - 48f * scale) / 2f)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            rotate(tilt.value, pivot = Offset(24f, 33f)) {
                drawPath(head, color, style = outline)
                drawLine(
                    color, Offset(13.5f, 12f), Offset(13.5f, 17f),
                    strokeWidth = 1.5f, cap = StrokeCap.Round,
                )
                drawLine(
                    color, Offset(34.5f, 12f), Offset(34.5f, 17f),
                    strokeWidth = 1.5f, cap = StrokeCap.Round,
                )
                val eyeHeight = 4f * eyes.value
                drawOval(color, Offset(16.25f, 24f - eyeHeight / 2f), Size(2.5f, eyeHeight))
                drawOval(color, Offset(29.25f, 24f - eyeHeight / 2f), Size(2.5f, eyeHeight))
                drawLine(
                    color, Offset(23f, 27.5f), Offset(25f, 27.5f),
                    strokeWidth = 2f, cap = StrokeCap.Round,
                )
                drawPath(mouth, color, style = Stroke(1.6f, cap = StrokeCap.Round))
                drawLine(
                    color, Offset(7f, 27f), Offset(12f, 28f),
                    strokeWidth = 1.5f, cap = StrokeCap.Round,
                )
                drawLine(
                    color, Offset(36f, 28f), Offset(41f, 27f),
                    strokeWidth = 1.5f, cap = StrokeCap.Round,
                )
            }
            // Stationary paws make the little head tilt feel like peeking over a ledge.
            drawPath(paws, color, style = outline)
        }
    }
}
