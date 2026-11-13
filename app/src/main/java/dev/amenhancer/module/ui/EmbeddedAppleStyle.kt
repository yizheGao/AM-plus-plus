package dev.amenhancer.module.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import top.yukonga.miuix.kmp.basic.Text

/**
 * Apple-style settings surface: inset grouped cards on a grouped background,
 * 17/13 pt text, iOS switches and a single accent colour.
 *
 * Colours follow the system light/dark appearance the host is already in, so
 * the injected settings page does not fight Apple Music's own appearance.
 */
internal object AppleStyle {
    private val accentLight = Color(0xFFFA233B)
    private val accentDark = Color(0xFFFF4A5D)
    private val trackOffLight = Color(0xFFE9E9EA)
    private val trackOffDark = Color(0xFF39393D)

    @Composable
    fun groupedBackground(): Color = if (isSystemInDarkTheme()) Color(0xFF000000) else Color(0xFFF2F2F7)

    @Composable
    fun card(): Color = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    @Composable
    fun label(): Color = if (isSystemInDarkTheme()) Color(0xFFFFFFFF) else Color(0xFF000000)

    @Composable
    fun secondaryLabel(): Color = if (isSystemInDarkTheme()) Color(0xFF98989F) else Color(0xFF8E8E93)

    @Composable
    fun chevron(): Color = if (isSystemInDarkTheme()) Color(0xFF5C5C60) else Color(0xFFC4C4C6)

    @Composable
    fun accent(): Color = if (isSystemInDarkTheme()) accentDark else accentLight

    @Composable
    fun track(checked: Boolean, enabled: Boolean): Color {
        val off = if (isSystemInDarkTheme()) trackOffDark else trackOffLight
        val on = accent()
        return when {
            !enabled -> off.copy(alpha = 0.5f)
            checked -> on
            else -> off
        }
    }
}

@Composable
internal fun AppleGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppleStyle.card()),
        content = content,
    )
}

@Composable
internal fun AppleSection(title: String, summary: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 7.dp)) {
        Text(
            text = title,
            color = AppleStyle.secondaryLabel(),
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )
        if (summary != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                color = AppleStyle.secondaryLabel(),
                fontSize = 12.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun AppleRow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interaction = MutableInteractionSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun AppleRowText(title: String, summary: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title,
            color = if (enabled) AppleStyle.label() else AppleStyle.secondaryLabel(),
            fontSize = 17.sp,
            lineHeight = 21.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary != null) {
            Text(
                text = summary,
                color = AppleStyle.secondaryLabel(),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** iOS-style switch: 51x31 track, 27 pt knob, animated slide. */
@Composable
internal fun AppleSwitch(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = if (checked) 180 else 220),
    )
    val accent = AppleStyle.accent()
    val trackColor = if (checked) {
        lerp(accent.copy(alpha = 0.45f), accent, progress)
    } else {
        AppleStyle.track(checked = false, enabled = enabled)
    }
    Box(
        modifier = Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) trackColor else trackColor.copy(alpha = 0.5f))
            .semantics { }
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = MutableInteractionSource(),
                role = Role.Switch,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .offset(x = (progress * 20f).dp)
                .padding(2.dp)
                .size(27.dp)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
internal fun AppleSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    icon: EmbeddedSvgIcon?,
    onCheckedChange: (Boolean) -> Unit,
) {
    AppleRow(enabled = enabled) {
        if (icon != null) {
            EmbeddedSettingsIcon(
                icon = icon,
                modifier = Modifier.size(26.dp),
                tint = AppleStyle.secondaryLabel(),
            )
            Spacer(Modifier.width(12.dp))
        }
        AppleRowText(title, summary, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        AppleSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun AppleLinkRow(
    title: String,
    summary: String?,
    icon: EmbeddedSvgIcon?,
    enabled: Boolean,
    onClick: () -> Unit,
    trailingText: String? = null,
) {
    AppleRow(enabled = enabled, onClick = onClick) {
        if (icon != null) {
            EmbeddedSettingsIcon(
                icon = icon,
                modifier = Modifier.size(26.dp),
                tint = AppleStyle.secondaryLabel(),
            )
            Spacer(Modifier.width(12.dp))
        }
        AppleRowText(title, summary, enabled, Modifier.weight(1f))
        if (trailingText != null) {
            Spacer(Modifier.width(8.dp))
            top.yukonga.miuix.kmp.basic.Text(
                text = trailingText,
                color = AppleStyle.secondaryLabel(),
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        EmbeddedSettingsIcon(
            icon = EmbeddedSvgIcon.ChevronRight,
            modifier = Modifier.size(16.dp),
            tint = AppleStyle.chevron(),
        )
    }
}
