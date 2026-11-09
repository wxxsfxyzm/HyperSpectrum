// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences

/** Receives availability only from senders holding the platform DUMP permission. */
class NativeAvailabilityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SpectrumPreferences.NATIVE_AVAILABILITY_ACTION ||
            !intent.hasExtra(SpectrumPreferences.NATIVE_AVAILABILITY_EXTRA)) return
        context.getSharedPreferences(SpectrumPreferences.GROUP, Context.MODE_PRIVATE).edit()
            .putBoolean(SpectrumPreferences.NATIVE_AVAILABLE,
                intent.getBooleanExtra(SpectrumPreferences.NATIVE_AVAILABILITY_EXTRA, false))
            .apply()
    }
}
