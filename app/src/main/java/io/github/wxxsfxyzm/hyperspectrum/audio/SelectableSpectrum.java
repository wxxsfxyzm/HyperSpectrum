// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.audio;

import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences;

import android.os.Handler;

/** Keeps the ZN handshake observable while Visualizer remains the default source. */
final class SelectableSpectrum implements SpectrumCapture {
    private final CaptureProbe.Reporter reporter;
    private final LiveSpectrum visualizer;
    private final NativeSpectrum nativeSpectrum;
    private volatile boolean nativeAvailable;
    private volatile boolean nativeSelected;
    private volatile boolean initialized;
    private volatile boolean demanded;
    private volatile int subscribers;

    SelectableSpectrum(CaptureProbe.Reporter reporter, Handler main, Sink sink) {
        this.reporter = reporter;
        visualizer = new LiveSpectrum(reporter, main, (levels, time, healthy) -> {
            if (!nativeSelected) sink.frame(levels, time, healthy);
        });
        nativeSpectrum = new NativeSpectrum((message, failure) -> {
            if (message.startsWith("native_results_connected ")) available(true);
            else if (message.startsWith("native_results_disconnected")
                    || message.startsWith("native_results_unavailable")) available(false);
            reporter.report(message, failure);
        }, main, (levels, time, healthy) -> {
            if (nativeActive()) sink.frame(levels, time, healthy);
        });
        initialized = true;
        applyDemand();
        reporter.report("spectrum_backend type=visualizer nativeMonitor=true", null);
    }

    private boolean nativeActive() { return nativeSelected && nativeAvailable; }

    private void available(boolean value) {
        if (nativeAvailable == value) return;
        nativeAvailable = value;
        reporter.report("native_backend_available=" + value, null);
        // Runs on the native I/O thread: never touch the main-looper sink here.
        // Views fall back through their own stale watchdog once frames stop.
        if (initialized) applyDemand();
    }

    private void applyDemand() {
        boolean useNative = nativeActive();
        visualizer.demand(demanded && !nativeSelected, subscribers);
        // Keep its authenticated status connection alive while Visualizer is selected.
        nativeSpectrum.demand(demanded && useNative, subscribers);
        reporter.report("spectrum_backend selected=" + (nativeSelected ? "native" : "visualizer")
                + " available=" + nativeAvailable + " active=" + useNative, null);
    }

    // One fixed worker keeps artwork decoding single-threaded across backend switches.
    @Override public void execute(Runnable action) { visualizer.execute(action); }
    @Override public void audio(android.content.Context context) {
        visualizer.audio(context); nativeSpectrum.audio(context);
    }
    @Override public void media(String packageName, int userId) {
        visualizer.media(packageName, userId); nativeSpectrum.media(packageName, userId);
    }
    @Override public void stopWhenMuted(boolean value) {
        visualizer.stopWhenMuted(value); nativeSpectrum.stopWhenMuted(value);
    }
    @Override public void demand(boolean enabled, int count) {
        demanded = enabled; subscribers = count; applyDemand();
    }
    @Override public void captureBackend(boolean value) {
        nativeSelected = value;
        applyDemand();
        if (value && !nativeAvailable) {
            reporter.report("spectrum_backend_waiting reason=zygisk_next_unavailable", null);
        }
    }
    @Override public boolean nativeAvailable() { return nativeAvailable; }
    @Override public void close() {
        demanded = false;
        available(false);
        nativeSpectrum.close();
        visualizer.close();
    }
}
