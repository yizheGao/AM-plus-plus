package dev.amenhancer.module.ui

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amenhancer.module.i18n.ModuleText
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val EmbeddedPreferencePadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)

@Composable
internal fun EmbeddedSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = AppleGroup(modifier = modifier, content = content)

@Composable
internal fun EmbeddedSettingsSection(
    title: String,
    summary: String? = null,
    endAction: (@Composable () -> Unit)? = null,
) {
    if (endAction == null) {
        AppleSection(title = title, summary = summary)
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AppleSection(title = title, summary = summary) }
            Box(Modifier.padding(end = 16.dp)) { endAction() }
        }
    }
}

@Composable
internal fun EmbeddedSettingSwitch(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    icon: EmbeddedSvgIcon? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    AppleSwitchRow(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        icon = icon,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun EmbeddedSettingLink(
    title: String,
    summary: String? = null,
    icon: EmbeddedSvgIcon? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AppleLinkRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun EmbeddedSettingsNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        color = AppleStyle.secondaryLabel(),
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )
}

@Composable
internal fun EmbeddedSettingsIcon(
    icon: EmbeddedSvgIcon,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = AppleStyle.secondaryLabel(),
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirror = rtl && (icon == EmbeddedSvgIcon.Back || icon == EmbeddedSvgIcon.ChevronRight)
    Icon(
        imageVector = EmbeddedSettingsSvgAssets.vector(icon),
        contentDescription = contentDescription,
        modifier = modifier.then(if (mirror) Modifier.graphicsLayer { scaleX = -1f } else Modifier),
        tint = tint,
    )
}

@Composable
internal fun EmbeddedSettingsIconAction(
    icon: EmbeddedSvgIcon,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = label
            role = Role.Button
            if (!enabled) disabled()
        },
    ) {
        EmbeddedSettingsIcon(
            icon = icon,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) AppleStyle.accent() else AppleStyle.secondaryLabel(),
        )
    }
}

@Composable
internal fun EmbeddedDialogActions(
    dismissText: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
) {
    val confirm = onConfirm
    if (confirmText != null && confirm != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(dismissText, onDismiss, Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                confirmText,
                confirm,
                Modifier.weight(1f),
                enabled = confirmEnabled,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    } else {
        TextButton(dismissText, onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun EmbeddedSettingsHost.EmbeddedSettingRange(
    title: String,
    value: Int,
    range: IntRange,
    unit: String,
    summary: String? = null,
    defaultValue: Int? = null,
    onChanged: (Int) -> Unit,
) {
    AppleSliderRow(
        title = title,
        summary = summary,
        value = value,
        range = range,
        unit = unit,
        defaultValue = defaultValue,
        onChanged = onChanged,
    )
}

// The legacy host entry is still a framework View.
internal fun EmbeddedSettingsHost.dp(activity: Activity, value: Int): Int =
    (value * activity.resources.displayMetrics.density).toInt()

internal fun EmbeddedSettingsHost.matchWidthWrapContent(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
