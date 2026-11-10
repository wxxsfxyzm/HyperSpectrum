// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.settings

import android.content.Intent
import android.content.BroadcastReceiver
import android.app.BroadcastOptions
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.ViewModelProvider
import io.github.libxposed.service.XposedService
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class SettingsActivity : ComponentActivity() {
    private lateinit var model: SettingsViewModel
    private val serviceListener: (XposedService?) -> Unit = { model.connect(it) }
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusNonce = 0L
    private var statusReceiverRegistered = false
    private val statusTimeout = Runnable {
        if (statusNonce != 0L) {
            statusNonce = 0L
            model.captureMode(null)
        }
    }
    private val pollCaptureMode = object : Runnable {
        override fun run() {
            requestCaptureMode()
            statusHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != SpectrumPreferences.STATUS_ACTION) return
            val senderUid = sentFromUid
            val senderPackage = sentFromPackage
            if (senderUid == Process.INVALID_UID || senderPackage != SYSTEM_UI_PACKAGE ||
                !isUidOwner(senderUid, SYSTEM_UI_PACKAGE)) return
            val nonce = intent.getLongExtra(SpectrumPreferences.STATUS_NONCE, 0L)
            if (nonce == 0L || nonce != statusNonce) return
            statusHandler.removeCallbacks(statusTimeout)
            statusNonce = 0L
            model.captureMode(SpectrumPreferences.captureMode(intent.getStringExtra(SpectrumPreferences.CAPTURE_MODE)))
        }
    }

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
                    onStopWhenMutedChange = model::setStopWhenMuted,
                    onRefresh = { model.refresh(); requestCaptureMode() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as ModuleApplication).observe(serviceListener)
        if (!statusReceiverRegistered) {
            registerReceiver(statusReceiver, android.content.IntentFilter(SpectrumPreferences.STATUS_ACTION), RECEIVER_EXPORTED)
            statusReceiverRegistered = true
        }
        requestCaptureMode()
        statusHandler.postDelayed(pollCaptureMode, STATUS_POLL_MS)
    }

    override fun onStop() {
        statusHandler.removeCallbacks(pollCaptureMode)
        statusHandler.removeCallbacks(statusTimeout)
        statusNonce = 0L
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        (application as ModuleApplication).removeObserver(serviceListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) { model.refresh(); requestCaptureMode() }
    }

    private fun requestCaptureMode() {
        if (!statusReceiverRegistered || !::model.isInitialized) return
        val nonce = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        statusNonce = nonce
        statusHandler.removeCallbacks(statusTimeout)
        statusHandler.postDelayed(statusTimeout, STATUS_TIMEOUT_MS)
        val query = Intent(SpectrumPreferences.STATUS_QUERY_ACTION)
            .setPackage(SYSTEM_UI_PACKAGE)
            .putExtra(SpectrumPreferences.STATUS_NONCE, nonce)
            .putExtra("sender_uid", Process.myUid())
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        sendBroadcast(query, null, options)
    }

    private fun isUidOwner(uid: Int, packageName: String): Boolean =
        packageManager.getPackagesForUid(uid)?.contains(packageName) == true

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val STATUS_TIMEOUT_MS = 1200L
        private const val STATUS_POLL_MS = 2500L
    }
}
