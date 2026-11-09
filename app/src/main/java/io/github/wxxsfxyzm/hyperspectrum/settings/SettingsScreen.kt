// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wxxsfxyzm.hyperspectrum.BuildConfig
import io.github.wxxsfxyzm.hyperspectrum.R
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassStyles
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun SettingsScreen(
    state: SettingsState,
    onEnabledChange: (Boolean) -> Unit,
    onColorSchemeChange: (String) -> Unit,
    onCaptureBackendChange: (String) -> Unit,
    onStopWhenMutedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val contentScrolled by remember { derivedStateOf { listState.canScrollBackward } }
    val backdrop = rememberLayerBackdrop()
    val dark = isSystemInDarkTheme()
    val horizontalPadding = GlassTopAppBarDefaults.HorizontalPadding
    val layoutDirection = LocalLayoutDirection.current
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var colorBounds by remember { mutableStateOf(Rect.Zero) }
    val colorAnchor = rememberGlassPopupAnchor()
    var captureMenu by remember { mutableStateOf(false) }
    var captureBounds by remember { mutableStateOf(Rect.Zero) }
    val captureAnchor = rememberGlassPopupAnchor()
    val optionsEnabled = state.enabled && state.connected && state.systemUiInScope && !state.loading && state.error != SettingsError.Load

    Scaffold(
        modifier = modifier,
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.app_name),
                largeTitle = stringResource(R.string.settings_title),
                isContentScrolled = contentScrolled,
                backdrop = if (isRuntimeShaderSupported()) backdrop else null,
                style = if (dark) GlassStyles.CommonMediumRegularDark else GlassStyles.CommonMediumRegularLowLight,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(insets)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = insets.calculateLeftPadding(layoutDirection),
                        end = insets.calculateRightPadding(layoutDirection),
                        top = insets.calculateTopPadding(),
                        bottom = insets.calculateBottomPadding(),
                    ),
                ) {
                    item(key = "status") {
                        ModuleStatusCard(
                            state = state,
                            onRefresh = onRefresh,
                            modifier = Modifier
                                .padding(horizontal = horizontalPadding)
                                .padding(top = 12.dp, bottom = 12.dp),
                        )
                    }
                    item(key = "spectrum-title") {
                        SmallTitle(text = stringResource(R.string.spectrum_title))
                    }
                    item(key = "spectrum") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = horizontalPadding)
                                .padding(bottom = 12.dp),
                        ) {
                            SwitchPreference(
                                checked = state.enabled,
                                onCheckedChange = {
                                    colorMenu = false
                                    onEnabledChange(it)
                                },
                                title = stringResource(R.string.enable_spectrum),
                                summary = stringResource(R.string.enable_spectrum_summary),
                                enabled = state.connected && state.systemUiInScope && !state.loading && state.error != SettingsError.Load,
                            )
                            if (BuildConfig.NATIVE_CAPTURE) {
                                GlassDropdownRow(
                                    title = stringResource(R.string.capture_backend),
                                    value = stringResource(if (state.captureBackend == SpectrumPreferences.NATIVE)
                                        R.string.capture_native else R.string.capture_visualizer),
                                    anchor = captureAnchor,
                                    enabled = optionsEnabled,
                                    onClick = { captureMenu = true; colorMenu = false },
                                    onBounds = { captureBounds = it },
                                )
                            }
                            GlassDropdownRow(
                                title = stringResource(R.string.color_scheme),
                                value = stringResource(
                                    if (BuildConfig.NATIVE_CAPTURE) R.string.color_macos else when (state.colorScheme) {
                                        SpectrumPreferences.ORIGINAL -> R.string.color_original
                                        SpectrumPreferences.HYPEROS -> R.string.color_hyperos
                                        else -> R.string.color_ios
                                    },
                                ),
                                anchor = colorAnchor,
                                enabled = optionsEnabled && !BuildConfig.NATIVE_CAPTURE,
                                onClick = { colorMenu = true },
                                onBounds = { colorBounds = it },
                            )
                            if (BuildConfig.NATIVE_CAPTURE && state.nativeAvailable) {
                                SwitchPreference(
                                    checked = state.stopWhenMuted,
                                    onCheckedChange = onStopWhenMutedChange,
                                    title = stringResource(R.string.stop_when_muted),
                                    summary = stringResource(R.string.stop_when_muted_summary),
                                    enabled = optionsEnabled,
                                )
                            }
                        }
                    }
                    item(key = "about-title") {
                        SmallTitle(text = stringResource(R.string.about_title))
                    }
                    item(key = "about") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = horizontalPadding)
                                .padding(bottom = 12.dp),
                        ) {
                            ArrowPreference(
                                title = stringResource(R.string.about_app),
                                summary = stringResource(R.string.version, BuildConfig.VERSION_NAME),
                                onClick = { aboutVisible = true },
                            )
                        }
                    }
                }
            }
            // The popup must be outside the layer it samples to avoid a render-node cycle.
            GlassDropdownPopup(
                show = colorMenu && optionsEnabled && !BuildConfig.NATIVE_CAPTURE,
                onDismissRequest = { colorMenu = false },
                anchorBounds = colorBounds,
                anchor = colorAnchor,
                backdrop = backdrop,
                visuals = GlassPopupDefaults.visuals(),
            ) {
                listOf(
                    SpectrumPreferences.ORIGINAL to R.string.color_original,
                    SpectrumPreferences.HYPEROS to R.string.color_hyperos,
                    SpectrumPreferences.IOS to R.string.color_ios,
                ).forEach { (value, label) ->
                    GlassPopupItem(
                        text = stringResource(label),
                        selected = state.colorScheme == value,
                        onClick = {
                            onColorSchemeChange(value)
                            colorMenu = false
                        },
                    )
                }
            }
            GlassDropdownPopup(
                show = captureMenu && optionsEnabled && BuildConfig.NATIVE_CAPTURE && state.nativeAvailable,
                onDismissRequest = { captureMenu = false },
                anchorBounds = captureBounds,
                anchor = captureAnchor,
                backdrop = backdrop,
                visuals = GlassPopupDefaults.visuals(),
            ) {
                listOf(
                    SpectrumPreferences.VISUALIZER to R.string.capture_visualizer,
                    SpectrumPreferences.NATIVE to R.string.capture_native,
                ).forEach { (value, label) ->
                    GlassPopupItem(
                        text = stringResource(label),
                        selected = state.captureBackend == value,
                        onClick = {
                            onCaptureBackendChange(value)
                            captureMenu = false
                        },
                    )
                }
            }
        }
        OverlayDialog(
            show = aboutVisible,
            title = stringResource(R.string.app_name),
            summary = stringResource(R.string.about_description, BuildConfig.VERSION_NAME),
            onDismissRequest = { aboutVisible = false },
        ) {
            TextButton(
                text = stringResource(R.string.got_it),
                onClick = { aboutVisible = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Matches gesture's large status card, including its clipped icon and tilt feedback. */
@Composable
private fun ModuleStatusCard(
    state: SettingsState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state.connected && state.systemUiInScope && !state.loading && state.error == null
    val warning = state.loading || (state.connected && !state.systemUiInScope && state.error == null)
    val dark = isSystemInDarkTheme()
    val title = stringResource(
        when {
            ready -> R.string.status_working
            state.connected -> R.string.status_connected
            else -> R.string.status_disconnected
        },
    )
    val summary = when {
        state.loading -> stringResource(R.string.loading)
        state.error == SettingsError.Load -> stringResource(R.string.load_error)
        state.error == SettingsError.Save -> stringResource(R.string.save_error)
        !state.connected -> stringResource(R.string.activate_hint)
        !state.systemUiInScope -> stringResource(R.string.scope_hint)
        else -> stringResource(R.string.status_ready)
    } + if (BuildConfig.NATIVE_CAPTURE) "\n" + stringResource(
        if (state.nativeAvailable) R.string.native_status_connected else R.string.native_status_disconnected,
    ) else ""
    val cardColor = when {
        ready && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        ready && dark -> Color(0xFF1A3825)
        ready -> Color(0xFFDFFAE4)
        warning && dark -> Color(0xFF3D3215)
        warning -> Color(0xFFFFF3CD)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        dark -> Color(0xFF3A1E22)
        else -> Color(0xFFFFE4E1)
    }
    val iconTint = when {
        ready && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        ready -> Color(0xFF36D167)
        warning && dark -> Color(0xFFFFC107)
        warning -> Color(0xFFFFB300)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        dark -> Color(0xFFFF8A80)
        else -> Color(0xFFD32F2F)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cardColor),
            onClick = onRefresh,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = when {
                            ready -> Icons.Rounded.CheckCircleOutline
                            warning -> Icons.Rounded.WarningAmber
                            else -> Icons.Rounded.ErrorOutline
                        },
                        tint = iconTint,
                        contentDescription = null,
                    )
                }
                if (ready) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp, 10.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(text = state.framework, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(1.dp))
                        Text(text = summary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassDropdownRow(
    title: String,
    value: String,
    anchor: GlassPopupAnchor,
    enabled: Boolean,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.4f
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Row(Modifier.glassPopupAnchorValue(anchor), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            Icon(
                imageVector = MiuixIcons.Basic.ArrowUpDown,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
