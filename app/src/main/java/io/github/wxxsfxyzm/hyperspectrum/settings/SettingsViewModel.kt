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
    val captureBackend: String,
)

data class SettingsState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val enabled: Boolean = SpectrumPreferences.DEFAULT_ENABLED,
    val colorScheme: String = SpectrumPreferences.DEFAULT_COLOR_SCHEME,
    val stopWhenMuted: Boolean = SpectrumPreferences.DEFAULT_STOP_WHEN_MUTED,
    val captureBackend: String = SpectrumPreferences.DEFAULT_CAPTURE_BACKEND,
    val nativeAvailable: Boolean = false,
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
    private val availabilityPreferences = application.getSharedPreferences(SpectrumPreferences.GROUP, Application.MODE_PRIVATE)
    private val availabilityListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SpectrumPreferences.NATIVE_AVAILABLE) {
            state = state.copy(nativeAvailable = availabilityPreferences.getBoolean(SpectrumPreferences.NATIVE_AVAILABLE, false))
        }
    }

    init {
        availabilityPreferences.registerOnSharedPreferenceChangeListener(availabilityListener)
        state = state.copy(nativeAvailable = availabilityPreferences.getBoolean(SpectrumPreferences.NATIVE_AVAILABLE, false))
    }

    private val remotePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == SpectrumPreferences.CAPTURE_BACKEND) {
            viewModelScope.launch {
                if (preferences === prefs) {
                    state = state.copy(captureBackend = SpectrumPreferences.captureBackend(prefs.getString(
                        SpectrumPreferences.CAPTURE_BACKEND, SpectrumPreferences.DEFAULT_CAPTURE_BACKEND)))
                }
            }
        }
    }

    override fun onCleared() {
        availabilityPreferences.unregisterOnSharedPreferenceChangeListener(availabilityListener)
        detachPreferences()
        super.onCleared()
    }

    private fun detachPreferences() {
        preferences?.unregisterOnSharedPreferenceChangeListener(remotePreferenceListener)
        preferences = null
    }

    fun connect(next: XposedService?) {
        if (service === next && (state.loading || preferences != null)) return
        if (service !== next) {
            detachPreferences()
            state = SettingsState()
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
        state = SettingsState(connected = current != null, loading = current != null)
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
                        SpectrumPreferences.captureBackend(prefs.getString(SpectrumPreferences.CAPTURE_BACKEND,
                            SpectrumPreferences.DEFAULT_CAPTURE_BACKEND)),
                    )
                }
                if (service !== current) return@launch
                preferences = loaded.preferences
                loaded.preferences.registerOnSharedPreferenceChangeListener(remotePreferenceListener)
                confirmedEnabled = loaded.enabled
                confirmedOptions[SpectrumPreferences.COLOR_SCHEME] = loaded.colorScheme
                confirmedOptions[SpectrumPreferences.STOP_WHEN_MUTED] = loaded.stopWhenMuted
                confirmedOptions[SpectrumPreferences.CAPTURE_BACKEND] = loaded.captureBackend
                state = SettingsState(
                    connected = true,
                    enabled = loaded.enabled,
                    colorScheme = loaded.colorScheme,
                    stopWhenMuted = loaded.stopWhenMuted,
                    captureBackend = loaded.captureBackend,
                    nativeAvailable = availabilityPreferences.getBoolean(SpectrumPreferences.NATIVE_AVAILABLE, false),
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
    fun setCaptureBackend(value: String) = setOption(SpectrumPreferences.CAPTURE_BACKEND,
        SpectrumPreferences.captureBackend(value))

    fun nativeAvailability(value: Boolean) {
        state = state.copy(nativeAvailable = value)
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
        SpectrumPreferences.CAPTURE_BACKEND -> previous.copy(captureBackend = value as String)
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
