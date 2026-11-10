// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.config;

/** Shared contract between the settings app and the SystemUI module. */
public final class SpectrumPreferences {
    public static final String GROUP = "spectrum";
    public static final String ENABLED = "enabled";
    public static final boolean DEFAULT_ENABLED = true;
    public static final String COLOR_SCHEME = "color_scheme";
    public static final String HYPEROS = "hyperos";
    public static final String IOS = "ios";
    public static final String ORIGINAL = "original";
    public static final String DEFAULT_COLOR_SCHEME = ORIGINAL;
    public static final String STOP_WHEN_MUTED = "stop_when_muted";
    public static final boolean DEFAULT_STOP_WHEN_MUTED = true;
    // ZN is preferred; Visualizer only runs as a fallback. Pref key and extra share this name.
    public static final String CAPTURE_MODE = "capture_mode";
    public static final String MODE_NATIVE = "native";
    public static final String MODE_VISUALIZER = "visualizer";
    // Action strings are unchanged from the earlier availability protocol.
    public static final String STATUS_ACTION = "io.github.wxxsfxyzm.hyperspectrum.NATIVE_AVAILABILITY";
    public static final String STATUS_QUERY_ACTION = "io.github.wxxsfxyzm.hyperspectrum.QUERY_NATIVE_AVAILABILITY";
    public static final String STATUS_NONCE = "native_availability_nonce";

    public static String colorScheme(String value) {
        return HYPEROS.equals(value) ? HYPEROS : IOS.equals(value) ? IOS : DEFAULT_COLOR_SCHEME;
    }

    /** Returns null for a missing or unknown mode. */
    public static String captureMode(String value) {
        return MODE_NATIVE.equals(value) ? MODE_NATIVE : MODE_VISUALIZER.equals(value) ? MODE_VISUALIZER : null;
    }

    private SpectrumPreferences() { }
}
