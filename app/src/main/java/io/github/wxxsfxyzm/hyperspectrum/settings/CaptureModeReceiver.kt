// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences

/** Receives the working capture mode only from senders holding the platform DUMP permission. */
class CaptureModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SpectrumPreferences.STATUS_ACTION) return
        val mode = SpectrumPreferences.captureMode(intent.getStringExtra(SpectrumPreferences.CAPTURE_MODE)) ?: return
        context.getSharedPreferences(SpectrumPreferences.GROUP, Context.MODE_PRIVATE).edit()
            .putString(SpectrumPreferences.CAPTURE_MODE, mode)
            .apply()
    }
}
