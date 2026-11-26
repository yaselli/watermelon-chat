/*
 * 西瓜瓣 Watermelon Chat
 * Derived from RikkaHub, licensed under GNU AGPL v3. See LICENSE.
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

/** The sleepy loaf kitten stays still; only its eyes open, glance, and close. */
@Composable
fun CatLoadingIndicator(modifier: Modifier = Modifier) {
    if (!LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator) {
        ContainedLoadingIndicator(modifier = modifier)
        return
    }

    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.notification_live_update_thinking)
    val transition = rememberInfiniteTransition(label = "sleepyKittenWaiting")
    val eyes = transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5600
                0f at 0
                0f at 700
                1f at 900
                1f at 3500
                0f at 3720
                0f at 5600
            },
        ),
        label = "kittenEyesOpen",
    )
    val gaze = transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5600
                0f at 0
                0f at 1200
                -1.4f at 1500
                -1.4f at 1950
                1.4f at 2450
                1.4f at 2950
                0f at 3300
                0f at 5600
            },
        ),
        label = "kittenEyeGlance",
    )
    val head = remember {
        Path().apply {
            // Leave a gap in the lower outline for the single resting paw.
            moveTo(7.5f, 35.5f)
            cubicTo(3f, 34.8f, 3f, 30f, 5.5f, 25.5f)
            quadraticTo(6.5f, 23.5f, 8.2f, 20.8f)
            cubicTo(7.5f, 13.5f, 12f, 12.3f, 17.8f, 15.5f)
            quadraticTo(23f, 13.7f, 29.7f, 15.2f)
            cubicTo(31.8f, 10.3f, 37.8f, 10.5f, 39.2f, 17.2f)
            cubicTo(43.7f, 21.3f, 46.3f, 27.8f, 44.5f, 33.5f)
            cubicTo(43.2f, 38f, 38f, 38.5f, 31f, 38.5f)
            lineTo(15.2f, 38.5f)
        }
    }
    val mouth = remember {
        Path().apply {
            moveTo(23.5f, 29.5f)
            quadraticTo(25f, 31f, 26.5f, 29.5f)
        }
    }
    val paw = remember {
        Path().apply {
            moveTo(9f, 38f)
            cubicTo(5f, 38f, 5f, 31f, 10f, 31f)
            cubicTo(15f, 31f, 17f, 34f, 15f, 38f)
            quadraticTo(12f, 40f, 9f, 38f)
            close()
            moveTo(10.4f, 37f)
            lineTo(10.4f, 38.8f)
        }
    }
    val markings = remember {
        Path().apply {
            moveTo(19f, 15.5f)
            lineTo(20f, 18.2f)
            moveTo(24f, 15f)
            lineTo(24.7f, 18f)
            moveTo(28.5f, 15.5f)
            lineTo(29f, 18f)
            moveTo(8.7f, 27f)
            quadraticTo(10.3f, 26.8f, 12f, 27.5f)
            moveTo(8f, 30f)
            lineTo(11.3f, 30.6f)
            moveTo(37.4f, 26.5f)
            quadraticTo(39f, 26.3f, 40.3f, 27f)
            moveTo(38f, 29.5f)
            lineTo(41f, 30.3f)
        }
    }
    val eye = remember { Path() }

    Canvas(modifier.size(28.dp).semantics { contentDescription = description }) {
        // Animation is read only in drawing. Body geometry, layout, and opacity remain fixed.
        val openness = eyes.value
        val look = gaze.value
        val scale = size.minDimension / 48f
        val outline = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val detail = Stroke(width = 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({
            translate((size.width - 48f * scale) / 2f, (size.height - 48f * scale) / 2f)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(head, color, style = outline)
            drawPath(paw, color, style = outline)
            drawPath(markings, color, style = detail)
            drawPath(mouth, color, style = detail)
            // A closed curved eyelid becomes a little bean eye, then closes along the same curve.
            repeat(2) { index ->
                val x = (if (index == 0) 18f else 31.5f) + look
                val y = 25.5f
                val halfWidth = 2.2f - 0.9f * openness
                eye.reset()
                eye.moveTo(x - halfWidth, y)
                eye.cubicTo(x - 1f, y + 1.2f, x + 1f, y + 1.2f, x + halfWidth, y - 0.2f)
                eye.cubicTo(
                    x + 1f, y + 1.2f - 3.8f * openness,
                    x - 1f, y + 1.2f - 3.8f * openness,
                    x - halfWidth, y,
                )
                eye.close()
                drawPath(eye, color)
                drawPath(eye, color, style = detail)
            }
        }
    }
}
