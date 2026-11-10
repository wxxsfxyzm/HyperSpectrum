// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.audio;

import io.github.wxxsfxyzm.hyperspectrum.BuildConfig;
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences;

import android.content.Context;
import android.os.Handler;

public interface SpectrumCapture {
    static SpectrumCapture create(CaptureProbe.Reporter reporter, Handler main, Sink sink) {
        return BuildConfig.NATIVE_CAPTURE
                ? new SelectableSpectrum(reporter, main, sink) : new LiveSpectrum(reporter, main, sink);
    }
    interface Sink {
        void frame(float[] levels, long uptime, boolean healthy);
        /** Main looper: stop-at-volume-0 intentionally paused sampling, not a failure. */
        default void silenced(boolean value) { }
    }
    void execute(Runnable action);
    void audio(Context context);
    void media(String packageName, int userId);
    void stopWhenMuted(boolean value);
    void demand(boolean enabled, int count);
    /** The source currently feeding the views: SpectrumPreferences.MODE_*. */
    default String captureMode() { return SpectrumPreferences.MODE_VISUALIZER; }
    void close();
}
