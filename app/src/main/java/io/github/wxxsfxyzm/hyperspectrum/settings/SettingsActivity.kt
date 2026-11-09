// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.ViewModelProvider
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class SettingsActivity : ComponentActivity() {
    private lateinit var model: SettingsViewModel
    private val serviceListener: (XposedService?) -> Unit = { model.connect(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[SettingsViewModel::class.java]
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsScreen(
                    state = model.state,
                    onEnabledChange = model::setEnabled,
                    onColorSchemeChange = model::setColorScheme,
                    onCaptureBackendChange = model::setCaptureBackend,
                    onStopWhenMutedChange = model::setStopWhenMuted,
                    onRefresh = model::refresh,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as ModuleApplication).observe(serviceListener)
    }

    override fun onStop() {
        (application as ModuleApplication).removeObserver(serviceListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refresh()
    }
}
