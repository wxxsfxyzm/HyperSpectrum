// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

/** Retarget from the current presentation values; no second attack/release filter. */
final class AppleSpectrumMotion extends SpectrumMotion {
    private final float[] from = new float[6], to = new float[6];
    private final float[] heightFrom = new float[6], heightTo = new float[6], heightPresentation = new float[6];
    private float floor;
    private boolean playing = true;
    // Stop-at-volume-0 settles with the pause curve but keeps the playing state.
    private boolean settling;
    private float elapsed = AppleWaveform.SAMPLE_MS;
    @Override boolean isPlaying() { return playing; }
    @Override boolean recovering() { return false; }
    void geometry(float width, float height) {
        floor = height <= 0 ? 0 : Math.min(1, AppleWaveform.baseWidth(width) / height);
        for (int i = 0; i < 6; i++) {
            heightFrom[i] = Math.max(floor, heightFrom[i]);
            heightTo[i] = Math.max(floor, heightTo[i]);
            heightPresentation[i] = Math.max(floor, heightPresentation[i]);
        }
    }
    float barHeight(int index, float available) { return heightPresentation[index] * available; }
    void inheritPresentation(AppleSpectrumMotion source) {
        System.arraycopy(source.heights, 0, heights, 0, 6);
        for (int i = 0; i < 6; i++) heightPresentation[i] = Math.max(floor, source.heightPresentation[i]);
        // A holder handoff inherits the visible shape, never an old target or
        // elapsed animation time. Only fresh measured frames retarget it.
        hidden();
    }
    @Override boolean playing(boolean value) {
        if (playing == value) return false;
        playing = value; settling = false;
        System.arraycopy(heights, 0, from, 0, 6);
        System.arraycopy(heightPresentation, 0, heightFrom, 0, 6);
        if (value) {
            // Resume has no measured target yet. Hold the presentation until
            // frame() supplies fresh audio instead of inserting a zero target.
            System.arraycopy(heights, 0, to, 0, 6);
            System.arraycopy(heightPresentation, 0, heightTo, 0, 6);
            elapsed = AppleWaveform.SAMPLE_MS;
        } else {
            java.util.Arrays.fill(heightTo, floor);
            java.util.Arrays.fill(to, 0); elapsed = 0;
        }
        return true;
    }
    @Override void frame(float[] levels) {
        if (!playing) return;
        settling = false;
        System.arraycopy(heights, 0, from, 0, 6);
        System.arraycopy(heightPresentation, 0, heightFrom, 0, 6);
        for (int i = 0; i < 6; i++) {
            to[i] = Float.isFinite(levels[i]) ? Math.max(0, Math.min(1, levels[i])) : 0;
            heightTo[i] = Math.max(floor, to[i]);
        }
        elapsed = 0;
    }
    @Override void silence() {
        if (!playing) return;
        System.arraycopy(heights, 0, from, 0, 6);
        System.arraycopy(heightPresentation, 0, heightFrom, 0, 6);
        java.util.Arrays.fill(to, 0); java.util.Arrays.fill(heightTo, floor);
        elapsed = 0; settling = true;
    }
    private float duration() { return playing && !settling ? AppleWaveform.SAMPLE_MS : AppleWaveform.PAUSE_MS; }
    @Override boolean advance(float ms) {
        float duration = duration();
        elapsed = Math.min(duration, elapsed + Math.max(0, ms));
        float progress = AppleWaveform.ease(elapsed / duration, duration == AppleWaveform.PAUSE_MS);
        for (int i = 0; i < 6; i++) {
            heights[i] = from[i] + (to[i] - from[i]) * progress;
            heightPresentation[i] = heightFrom[i] + (heightTo[i] - heightFrom[i]) * progress;
        }
        return elapsed < duration;
    }
    @Override void hidden() {
        elapsed = duration();
        if (playing) {
            // Keep the last presentation across an app/Home transition, but
            // discard the old target so it cannot advance off screen or replay.
            System.arraycopy(heights, 0, from, 0, 6);
            System.arraycopy(heights, 0, to, 0, 6);
            System.arraycopy(heightPresentation, 0, heightFrom, 0, 6);
            System.arraycopy(heightPresentation, 0, heightTo, 0, 6);
            return;
        }
        java.util.Arrays.fill(heights, 0);
        java.util.Arrays.fill(from, 0); java.util.Arrays.fill(to, 0);
        java.util.Arrays.fill(heightPresentation, floor);
        java.util.Arrays.fill(heightFrom, floor); java.util.Arrays.fill(heightTo, floor);
    }
}
