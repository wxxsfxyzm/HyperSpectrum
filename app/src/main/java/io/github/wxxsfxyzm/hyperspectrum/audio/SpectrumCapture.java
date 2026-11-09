// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.audio;

import io.github.wxxsfxyzm.hyperspectrum.BuildConfig;

import android.content.Context;
import android.os.Handler;

public interface SpectrumCapture {
    static SpectrumCapture create(CaptureProbe.Reporter reporter, Handler main, Sink sink) {
        return BuildConfig.NATIVE_CAPTURE
                ? new SelectableSpectrum(reporter, main, sink) : new LiveSpectrum(reporter, main, sink);
    }
    interface Sink { void frame(float[] levels, long uptime, boolean healthy); }
    void execute(Runnable action);
    void audio(Context context);
    void media(String packageName, int userId);
    void stopWhenMuted(boolean value);
    void demand(boolean enabled, int count);
    default void captureBackend(boolean nativeBackend) { }
    default boolean nativeAvailable() { return false; }
    void close();
}
