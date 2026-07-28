package com.viwa.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun HsvColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    wheelSize: Dp = 220.dp,
    enabled: Boolean = true,
) {
    val argb = composeColorToArgb(color)
    val hsv = remember(argb) { argbToHsv(argb) }
    var hue by remember(argb) { mutableFloatStateOf(hsv.hue) }
    var saturation by remember(argb) { mutableFloatStateOf(hsv.saturation) }
    var value by remember(argb) { mutableFloatStateOf(hsv.value) }

    fun emitColor() {
        val newArgb = hsvToArgb(hue, saturation, value)
        onColorChange(argbToComposeColor(newArgb))
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Canvas(
            modifier =
                Modifier
                    .size(wheelSize)
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        fun updateFromOffset(offset: Offset) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val dx = offset.x - center.x
                            val dy = offset.y - center.y
                            val radius = min(size.width, size.height) / 2f
                            val distance = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
                            val angle = atan2(dy, dx)
                            hue = ((angle * 180f / PI.toFloat()) + 360f) % 360f
                            saturation = (distance / radius).coerceIn(0f, 1f)
                            emitColor()
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            updateFromOffset(down.position)
                            down.consume()
                            drag(down.id) { change ->
                                updateFromOffset(change.position)
                                change.consume()
                            }
                        }
                    },
        ) {
            val radius = min(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val hueColors =
                (0..6).map { step ->
                    Color(hsvToArgb(step * 60f, 1f, value))
                }
            drawCircle(
                brush = Brush.sweepGradient(colors = hueColors, center = center),
                radius = radius,
                center = center,
            )
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(Color.White, Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                radius = radius,
                center = center,
            )
            if (value < 1f) {
                drawCircle(
                    color = Color.Black.copy(alpha = 1f - value),
                    radius = radius,
                    center = center,
                )
            }
            val angle = hue / 180f * PI.toFloat()
            val pointerRadius = saturation * radius
            val pointer =
                Offset(
                    x = center.x + cos(angle) * pointerRadius,
                    y = center.y + sin(angle) * pointerRadius,
                )
            drawCircle(
                color = Color.White,
                radius = 11f,
                center = pointer,
            )
            drawCircle(
                color = Color(hsvToArgb(hue, saturation, value)),
                radius = 8f,
                center = pointer,
            )
        }
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        fun updateFromOffset(offset: Offset) {
                            value = (offset.x / size.width).coerceIn(0f, 1f)
                            emitColor()
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            updateFromOffset(down.position)
                            down.consume()
                            drag(down.id) { change ->
                                updateFromOffset(change.position)
                                change.consume()
                            }
                        }
                    },
        ) {
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        colors =
                            listOf(
                                Color.Black,
                                Color(hsvToArgb(hue, saturation, 1f)),
                            ),
                    ),
            )
            val pointerX = value * size.width
            drawCircle(
                color = Color.White,
                radius = max(6f, min(size.height / 2f - 2f, 10f)),
                center = Offset(pointerX, size.height / 2f),
            )
        }
        Text(
            text = colorToRgbHex(color),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
