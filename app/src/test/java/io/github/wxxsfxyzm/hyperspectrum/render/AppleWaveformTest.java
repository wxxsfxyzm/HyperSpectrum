// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppleWaveformTest {
    @Test public void holderHandoffInheritsVisibleShapeWithoutReplayingEitherOldTarget() {
        AppleSpectrumMotion visible = new AppleSpectrumMotion(); visible.geometry(60, 60);
        AppleSpectrumMotion returning = new AppleSpectrumMotion(); returning.geometry(60, 60);
        visible.frame(new float[]{1, .8f, .6f, .4f, .2f, 0}); visible.advance(40);
        returning.frame(new float[]{.1f, .1f, .1f, .1f, .1f, .1f}); returning.advance(80);
        returning.inheritPresentation(visible);
        for (int i = 0; i < 6; i++) assertEquals(visible.barHeight(i, 60), returning.barHeight(i, 60), .00001);
        assertFalse(returning.advance(5000));
        for (int i = 0; i < 6; i++) assertEquals(visible.barHeight(i, 60), returning.barHeight(i, 60), .00001);
        visible.advance(40);
        assertTrue(visible.barHeight(0, 60) > returning.barHeight(0, 60));
        returning.frame(new float[]{.3f, .3f, .3f, .3f, .3f, .3f}); returning.advance(80);
        assertEquals(18, returning.barHeight(0, 60), .00001);
    }
    @Test public void hidingPlayingViewHoldsPresentationAndDiscardsPendingTarget() {
        AppleSpectrumMotion m = new AppleSpectrumMotion(); m.geometry(60, 60);
        m.frame(new float[]{1, 1, 1, 1, 1, 1}); m.advance(40);
        assertTrue(m.barHeight(0, 60) > 30);
        float height = m.barHeight(0, 60);
        float[] presentation = m.heights.clone();
        m.hidden();
        assertArrayEquals(presentation, m.heights, 0);
        for (int i = 0; i < 6; i++) assertEquals(height, m.barHeight(i, 60), 0.00001);
        assertFalse(m.advance(5000));
        for (int i = 0; i < 6; i++) assertEquals(height, m.barHeight(i, 60), 0.00001);
    }

    @Test public void resumeHoldsCurrentPresentationUntilFreshAudioRatherThanAnimatingToZero() {
        AppleSpectrumMotion m = new AppleSpectrumMotion(); m.geometry(60, 60);
        m.frame(new float[]{1, 0, 0, 0, 0, 0}); m.advance(80);
        m.playing(false); m.advance(200);
        float pausedHeight = m.barHeight(0, 60), pausedLevel = m.heights[0];
        m.playing(true);
        assertFalse(m.advance(40));
        assertEquals(pausedHeight, m.barHeight(0, 60), 0.00001);
        assertEquals(pausedLevel, m.heights[0], 0.00001);
        m.frame(new float[]{0.4f, 0, 0, 0, 0, 0}); m.advance(40);
        assertEquals(pausedHeight + (24 - pausedHeight) * AppleWaveform.ease(0.5f, false),
                m.barHeight(0, 60), 0.00001);
    }

    @Test public void hiddenTrackSwitchWaitsForFreshAudioWithoutAnIntermediateZero() {
        AppleSpectrumMotion m = new AppleSpectrumMotion(); m.geometry(60, 60);
        m.frame(new float[]{1, 1, 1, 1, 1, 1}); m.advance(80);
        m.hidden(); m.playing(false); m.playing(true);
        assertFalse(m.advance(16));
        assertEquals(60, m.barHeight(0, 60), 0.00001);
        m.frame(new float[]{0.2f, 0, 0, 0, 0, 0}); m.advance(40);
        assertEquals(60 + (12 - 60) * AppleWaveform.ease(0.5f, false), m.barHeight(0, 60), 0.00001);
        m.advance(40); assertEquals(12, m.barHeight(0, 60), 0.00001);
    }

    @Test public void hidingAnActuallyPausedViewStillSettlesToCapsules() {
        AppleSpectrumMotion m = new AppleSpectrumMotion(); m.geometry(60, 60);
        m.frame(new float[]{1, 1, 1, 1, 1, 1}); m.advance(80);
        m.playing(false); m.advance(200); m.hidden();
        assertFalse(m.advance(5000));
        assertArrayEquals(new float[6], m.heights, 0);
        for (int i = 0; i < 6; i++) assertEquals(AppleWaveform.baseWidth(60), m.barHeight(i, 60), 0.00001);
    }

    @Test public void compactFullHeightReachesHostSlotAndScalesArtworkWithGeometry() {
        float extent = AppleWaveform.compactExtent(60, 60);
        AppleSpectrumMotion motion = new AppleSpectrumMotion(); motion.geometry(extent, extent);
        motion.frame(new float[]{1, 1, 1, 1, 1, 1}); motion.advance(80);
        // The former16dp cap left6.1px at each edge on the tested60px slot.
        for (int i = 0; i < 6; i++) assertEquals(60, motion.barHeight(i, extent), 0);
        assertEquals(6f / 16, AppleWaveform.artworkBlur(extent) / extent, 0);
        assertEquals(6, AppleWaveform.artworkBlur(AppleWaveform.compactExtent(16, 16)), 0);
        assertEquals(20, AppleWaveform.compactExtent(20, 20), 0);
        assertEquals(36, AppleWaveform.compactExtent(60, 36), 0);
        assertEquals(36, AppleWaveform.compactExtent(36, 60), 0);
        assertEquals(0, AppleWaveform.compactExtent(0, 60), 0);
        motion.playing(false); motion.advance(900);
        assertEquals(AppleWaveform.baseWidth(extent), motion.barHeight(0, extent), 0.00001);
    }
    @Test public void liveTimingUsesTheBezierTimeAxisAndReachesTargetIn80ms() {
        assertEquals(0.684643, AppleWaveform.ease(0.5f, false), 0.00001);
        AppleSpectrumMotion m = new AppleSpectrumMotion();
        m.frame(new float[]{1, 0.8f, 0.6f, 0.4f, 0.2f, 0});
        assertTrue(m.advance(40)); assertEquals(0.684643, m.heights[0], 0.00001);
        assertFalse(m.advance(40)); assertEquals(1, m.heights[0], 0);
    }
    @Test public void newTargetStartsAtPresentationAndPauseUses900ms() {
        AppleSpectrumMotion m = new AppleSpectrumMotion();
        m.frame(new float[]{1, 1, 1, 1, 1, 1}); m.advance(40);
        float visible = m.heights[0];
        m.frame(new float[6]); assertEquals(visible, m.heights[0], 0);
        m.advance(20); assertTrue(m.heights[0] < visible);
        m.playing(false); float pausedFrom = m.heights[0];
        assertTrue(m.advance(450));
        assertEquals(pausedFrom * (1 - AppleWaveform.ease(0.5f, true)), m.heights[0], 0.000001);
        assertFalse(m.playing(false));
        m.frame(new float[]{1, 1, 1, 1, 1, 1}); // Late audio cannot revive pause.
        assertFalse(m.advance(450)); assertArrayEquals(new float[6], m.heights, 0);
    }
    @Test public void geometryUsesMaxWidthSlotsWithSymmetricEdgesAndDotFloor() {
        float base = AppleWaveform.baseWidth(48);
        assertEquals(5, base, 0);
        for (float level : new float[]{0, 0.5f, 1}) {
            float w = AppleWaveform.width(base, level);
            float left = AppleWaveform.left(48, 0, w), right = AppleWaveform.left(48, 5, w) + w;
            assertEquals(left, 48 - right, 0.00001);
            for (int i = 0; i < 5; i++) assertTrue(AppleWaveform.left(48, i + 1, w) > AppleWaveform.left(48, i, w) + w);
        }
        assertEquals(5, AppleWaveform.height(base, 32, 0), 0);
        assertEquals(16, AppleWaveform.height(base, 32, 0.5f), 0);
        assertEquals(32, AppleWaveform.height(base, 32, 1), 0);
        assertEquals(3, AppleWaveform.radius(5.6f), 0);
    }
    @Test public void animationInterpolatesGeometryAboveDotFloorRatherThanClippingAnAnimatedLevel() {
        AppleSpectrumMotion m = new AppleSpectrumMotion(); m.geometry(48, 32);
        assertEquals(5, m.barHeight(0, 32), 0);
        m.frame(new float[]{1, 0, 0, 0, 0, 0}); m.advance(40);
        assertEquals(5 + 27 * AppleWaveform.ease(0.5f, false), m.barHeight(0, 32), 0.00001);
        assertEquals(5, m.barHeight(1, 32), 0);
        m.advance(40); m.playing(false); m.advance(900);
        assertEquals(5, m.barHeight(0, 32), 0);
    }
    @Test public void stopAtVolumeZeroSettlesWithPauseCurveWhileStillPlaying() {
        AppleSpectrumMotion m = new AppleSpectrumMotion(); m.geometry(48, 32);
        m.frame(new float[]{1, 1, 1, 1, 1, 1}); m.advance(80);
        assertEquals(32, m.barHeight(0, 32), 0.00001);
        m.silence();
        // Not the 80 ms sample snap: halfway through the 900 ms pause curve.
        assertTrue(m.advance(80));
        assertTrue(m.barHeight(0, 32) > 5);
        m.advance(370);
        assertEquals(32 + (5 - 32) * AppleWaveform.ease(0.5f, true), m.barHeight(0, 32), 0.00001);
        assertFalse(m.advance(900));
        assertEquals(5, m.barHeight(0, 32), 0);
        assertTrue(m.isPlaying());
        // Fresh audio after unmute uses the ordinary sample animation again.
        m.frame(new float[]{1, 0, 0, 0, 0, 0}); m.advance(40);
        assertEquals(5 + 27 * AppleWaveform.ease(0.5f, false), m.barHeight(0, 32), 0.00001);
    }
}
