// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.settings

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.libxposed.service.XposedService
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SettingsError { Load, Save }

private data class LoadedSettings(
    val preferences: SharedPreferences,
    val framework: String,
    val systemUiInScope: Boolean,
    val enabled: Boolean,
    val colorScheme: String,
    val stopWhenMuted: Boolean,
)

data class SettingsState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val enabled: Boolean = SpectrumPreferences.DEFAULT_ENABLED,
    val colorScheme: String = SpectrumPreferences.DEFAULT_COLOR_SCHEME,
    val stopWhenMuted: Boolean = SpectrumPreferences.DEFAULT_STOP_WHEN_MUTED,
    /** SpectrumPreferences.MODE_*, or null while SystemUI has not reported one. */
    val captureMode: String? = null,
    val framework: String = "",
    val systemUiInScope: Boolean = false,
    val error: SettingsError? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(SettingsState())
        private set
    private var service: XposedService? = null
    private var preferences: SharedPreferences? = null
    private var loadJob: Job? = null
    private val writeMutex = Mutex()
    private var confirmedEnabled = SpectrumPreferences.DEFAULT_ENABLED
    private val confirmedOptions = mutableMapOf<String, Any>()
    private val optionRevisions = mutableMapOf<String, Long>()
    private var revision = 0L
    // Last mode reported by SystemUI (pushed or queried), shown until a live query answers.
    private val modePreferences = application.getSharedPreferences(SpectrumPreferences.GROUP, Application.MODE_PRIVATE)
    private val modeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SpectrumPreferences.CAPTURE_MODE) state = state.copy(captureMode = storedMode())
    }

    init {
        modePreferences.registerOnSharedPreferenceChangeListener(modeListener)
        state = state.copy(captureMode = storedMode())
    }

    private fun storedMode(): String? =
        SpectrumPreferences.captureMode(modePreferences.getString(SpectrumPreferences.CAPTURE_MODE, null))

    override fun onCleared() {
        modePreferences.unregisterOnSharedPreferenceChangeListener(modeListener)
        detachPreferences()
    }

    private fun detachPreferences() {
        preferences = null
    }

    fun connect(next: XposedService?) {
        if (service === next && (state.loading || preferences != null)) return
        if (service !== next) {
            detachPreferences()
            // The mode comes from SystemUI, not from the LSPosed service: keep it across binds.
            state = SettingsState(captureMode = state.captureMode)
        }
        service = next
        refresh()
    }

    fun refresh() {
        if (writeMutex.isLocked && preferences != null) return
        loadJob?.cancel()
        revision++
        detachPreferences()
        val current = service
        state = SettingsState(
            connected = current != null,
            loading = current != null,
            captureMode = state.captureMode,
        )
        if (current == null) return
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val prefs = current.getRemotePreferences(SpectrumPreferences.GROUP)
                    LoadedSettings(
                        prefs,
                        "${current.frameworkName} ${current.frameworkVersion}",
                        current.scope.contains("com.android.systemui"),
                        prefs.getBoolean(SpectrumPreferences.ENABLED, SpectrumPreferences.DEFAULT_ENABLED),
                        SpectrumPreferences.colorScheme(prefs.getString(SpectrumPreferences.COLOR_SCHEME, SpectrumPreferences.DEFAULT_COLOR_SCHEME)),
                        prefs.getBoolean(SpectrumPreferences.STOP_WHEN_MUTED, SpectrumPreferences.DEFAULT_STOP_WHEN_MUTED),
                    )
                }
                if (service !== current) return@launch
                preferences = loaded.preferences
                confirmedEnabled = loaded.enabled
                confirmedOptions[SpectrumPreferences.COLOR_SCHEME] = loaded.colorScheme
                confirmedOptions[SpectrumPreferences.STOP_WHEN_MUTED] = loaded.stopWhenMuted
                state = SettingsState(
                    connected = true,
                    enabled = loaded.enabled,
                    colorScheme = loaded.colorScheme,
                    stopWhenMuted = loaded.stopWhenMuted,
                    captureMode = state.captureMode,
                    framework = loaded.framework,
                    systemUiInScope = loaded.systemUiInScope,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                if (service === current) state = state.copy(loading = false, error = SettingsError.Load)
            }
        }
    }

    // KTX edit discards commit's Boolean result; keep confirmation and run on IO.
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun setEnabled(value: Boolean) {
        val prefs = preferences ?: return
        val current = service
        if (state.loading || state.enabled == value) return
        val request = ++revision
        state = state.copy(enabled = value, error = null)
        viewModelScope.launch {
            writeMutex.withLock {
                if (service !== current || preferences !== prefs) return@withLock
                val fallback = confirmedEnabled
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val succeeded = prefs.edit().putBoolean(SpectrumPreferences.ENABLED, value).commit()
                        if (!succeeded) restorePreference(prefs, fallback)
                        succeeded
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        restorePreference(prefs, fallback)
                        false
                    }
                }
                if (service !== current || preferences !== prefs) return@withLock
                if (saved) confirmedEnabled = value
                else if (revision == request) state = state.copy(enabled = confirmedEnabled, error = SettingsError.Save)
            }
        }
    }

    fun setColorScheme(value: String) = setOption(SpectrumPreferences.COLOR_SCHEME, SpectrumPreferences.colorScheme(value))
    fun setStopWhenMuted(value: Boolean) = setOption(SpectrumPreferences.STOP_WHEN_MUTED, value)
    fun captureMode(value: String?) {
        state = state.copy(captureMode = value)
        // Remember the live answer so the next launch shows it immediately.
        if (value != null) modePreferences.edit().putString(SpectrumPreferences.CAPTURE_MODE, value).apply()
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun setOption(key: String, value: Any) {
        val prefs = preferences ?: return
        val current = service ?: return
        if (state.loading) return
        val request = (optionRevisions[key] ?: 0) + 1
        optionRevisions[key] = request
        state = withOption(state, key, value).copy(error = null)
        viewModelScope.launch {
            writeMutex.withLock {
                if (service !== current || preferences !== prefs) return@withLock
                val fallback = confirmedOptions.getValue(key)
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val succeeded = commitOption(prefs, key, value)
                        if (!succeeded) restoreOption(prefs, key, fallback)
                        succeeded
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        restoreOption(prefs, key, fallback)
                        false
                    }
                }
                if (service !== current || preferences !== prefs) return@withLock
                if (saved) confirmedOptions[key] = value
                else if (optionRevisions[key] == request) {
                    state = withOption(state, key, fallback).copy(error = SettingsError.Save)
                }
            }
        }
    }

    private fun withOption(previous: SettingsState, key: String, value: Any): SettingsState = when (key) {
        SpectrumPreferences.COLOR_SCHEME -> previous.copy(colorScheme = value as String)
        else -> previous.copy(stopWhenMuted = value as Boolean)
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun commitOption(prefs: SharedPreferences, key: String, value: Any): Boolean {
        val editor = prefs.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            else -> error("Unsupported setting")
        }
        return editor.commit()
    }

    private fun restoreOption(prefs: SharedPreferences, key: String, value: Any) {
        try { commitOption(prefs, key, value) }
        catch (_: RuntimeException) { /* Keep the last confirmed UI state if the service is unavailable. */ }
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun restorePreference(prefs: SharedPreferences, value: Boolean) {
        try {
            prefs.edit().putBoolean(SpectrumPreferences.ENABLED, value).commit()
        } catch (_: RuntimeException) {
            // The UI keeps the last confirmed value if the remote service is unavailable.
        }
    }
}
