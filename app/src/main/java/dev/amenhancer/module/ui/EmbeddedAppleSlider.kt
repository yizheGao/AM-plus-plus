package dev.amenhancer.module.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text

/**
 * Apple-style slider row: 4 dp track, accent fill, white knob, value on the
 * trailing edge. Replaces the Miuix slider so the row shares one accent colour
 * with the switch instead of the framework's own palette.
 */
@Composable
internal fun AppleSliderRow(
    title: String,
    summary: String?,
    value: Int,
    range: IntRange,
    unit: String,
    defaultValue: Int?,
    onChanged: (Int) -> Unit,
) {
    var preview by remember(value, range) { mutableIntStateOf(value.coerceIn(range)) }
    var trackWidth by remember { mutableStateOf(0) }
    val span = (range.last - range.first).coerceAtLeast(1)

    fun commitAt(x: Float) {
        if (trackWidth <= 0) return
        val fraction = (x / trackWidth.toFloat()).coerceIn(0f, 1f)
        preview = (range.first + (span * fraction).roundToInt()).coerceIn(range)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = AppleStyle.label(),
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
            )
            if (defaultValue != null) {
                EmbeddedSettingsIconAction(
                    icon = EmbeddedSvgIcon.RestoreDefault,
                    label = title,
                ) {
                    preview = defaultValue
                    onChanged(defaultValue)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = "$preview$unit",
                color = if (preview == defaultValue || defaultValue == null) {
                    AppleStyle.secondaryLabel()
                } else {
                    AppleStyle.accent()
                },
                fontSize = 15.sp,
            )
        }
        if (summary != null) {
            Text(
                text = summary,
                color = AppleStyle.secondaryLabel(),
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        val accentColor = AppleStyle.accent()
        val trackColor = AppleStyle.track(checked = false, enabled = true)
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { trackWidth = it.width }
                .pointerInput(range, trackWidth) {
                    detectTapGestures { offset -> commitAt(offset.x); onChanged(preview) }
                }
                .pointerInput(range, trackWidth) {
                    detectHorizontalDragGestures(
                        onDragEnd = { onChanged(preview) },
                        onDragCancel = { onChanged(preview) },
                    ) { change, _ -> commitAt(change.position.x) }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val centerY = size.height / 2f
                val trackHeight = 4.dp.toPx()
                val knobRadius = 13.dp.toPx()
                val left = knobRadius
                val right = size.width - knobRadius
                val fraction = (preview - range.first).toFloat() / span
                val knobX = left + (right - left) * fraction
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, centerY - trackHeight / 2f),
                    size = Size(right - left, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(left, centerY - trackHeight / 2f),
                    size = Size((knobX - left).coerceAtLeast(0f), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.10f),
                    radius = knobRadius + 1.dp.toPx(),
                    center = Offset(knobX, centerY + 1.dp.toPx()),
                )
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = knobRadius,
                    center = Offset(knobX, centerY),
                )
            }
        }
    }
}
