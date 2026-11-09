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
    public static final String CAPTURE_BACKEND = "capture_backend";
    public static final String VISUALIZER = "visualizer";
    public static final String NATIVE = "native";
    public static final String NATIVE_AVAILABLE = "native_available";
    public static final String NATIVE_AVAILABILITY_ACTION = "io.github.wxxsfxyzm.hyperspectrum.NATIVE_AVAILABILITY";
    public static final String NATIVE_AVAILABILITY_EXTRA = "available";
    public static final String DEFAULT_CAPTURE_BACKEND = VISUALIZER;

    public static String colorScheme(String value) {
        return HYPEROS.equals(value) ? HYPEROS : IOS.equals(value) ? IOS : DEFAULT_COLOR_SCHEME;
    }

    public static String captureBackend(String value) {
        return NATIVE.equals(value) ? NATIVE : VISUALIZER;
    }

    private SpectrumPreferences() { }
}
