// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.render;

import io.github.wxxsfxyzm.hyperspectrum.BuildConfig;
import io.github.wxxsfxyzm.hyperspectrum.audio.CaptureProbe;
import io.github.wxxsfxyzm.hyperspectrum.audio.SpectrumDsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Method;
import java.util.Arrays;

/** The same six bars persist through pause/resume, with a cover-scoped palette. */
final class SpectrumView extends View {
    interface Lifecycle { void active(SpectrumView view, boolean active); }
    private final Lifecycle lifecycle;
    private final CaptureProbe.Reporter reporter;
    private View original;
    private Method cancelAnimation, playAnimation;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SpectrumMotion motion = BuildConfig.NATIVE_CAPTURE ? new AppleSpectrumMotion() : new SpectrumMotion();
    private final RenderNode artworkNode = new RenderNode("HyperSpectrumArtwork");
    private final Path barMask = new Path();
    private boolean allowed, aggregatedVisible, subscribed, ownsOriginal;
    private boolean disposed, drew, fallback = true, pauseSettled;
    private boolean signalUnavailable, silenced;
    private long lastDraw, lastData, awaitingSince;
    private final int fieldSize = BuildConfig.NATIVE_CAPTURE ? AppleArtwork.SIZE : CoverColors.SIZE;
    private final int[] colors = BuildConfig.NATIVE_CAPTURE ? AppleArtwork.fallback() : CoverColors.fallback();
    private final int[] fromColors = colors.clone(), targetColors = colors.clone();
    private final Bitmap colorBitmap = Bitmap.createBitmap(fieldSize, fieldSize, Bitmap.Config.ARGB_8888);
    private Shader colorShader;
    private boolean originalColors;
    private final Matrix originalBounds = new Matrix();
    private final Matrix colorBounds = new Matrix();
    private boolean paletteDirty = true;
    private long colorStart;
    private final Runnable stale = this::stale;

    SpectrumView(Context context, View original, boolean animated, Lifecycle lifecycle,
                 CaptureProbe.Reporter reporter) throws ReflectiveOperationException {
        super(context);
        this.lifecycle = lifecycle; this.reporter = reporter;
        if (BuildConfig.NATIVE_CAPTURE) fallback = false;
        bindOriginal(original, animated);
        awaitingSince = SystemClock.uptimeMillis();
        setTag("miui-island-spectrum-v2");
        setClickable(false); setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    boolean bindOriginal(View next, boolean animated) throws ReflectiveOperationException {
        if (original == next) return false;
        Method cancel = animated ? next.getClass().getMethod("cancelAnimation") : null;
        Method play = animated ? next.getClass().getMethod("playAnimation") : null;
        // The completed host bind owns visibility of its previous source.
        // Restoring it here would resurrect a now-hidden device/Lottie icon.
        ownsOriginal = false;
        original = next; cancelAnimation = cancel; playAnimation = play;
        return true;
    }

    boolean wantsAudio() { return subscribed && motion.isPlaying(); }

    void inheritPresentation(SpectrumView source) {
        if (motion instanceof AppleSpectrumMotion apple && source.motion instanceof AppleSpectrumMotion other
                && motion.isPlaying() && other.isPlaying()) {
            apple.inheritPresentation(other);
            lastDraw = 0;
        }
    }

    void playing(boolean value) {
        if (BuildConfig.NATIVE_CAPTURE && lastDraw != 0) {
            long now = SystemClock.uptimeMillis();
            motion.advance(Math.max(0, now - lastDraw)); lastDraw = now;
        }
        if (!motion.playing(value)) return;
        removeCallbacks(stale); lastDraw = 0;
        if (value) {
            awaitingSince = SystemClock.uptimeMillis();
            lastData = 0; signalUnavailable = false; scheduleStale();
        } else {
            fallback = false; pauseSettled = false;
        }
        suppressOriginal(); invalidate();
        lifecycle.active(this, subscribed); // Re-evaluate audio demand without replacing this View.
        reporter.report("spectrum_motion mode=" + (value ? "playing" : "pause_decay")
                + " view=" + System.identityHashCode(this), null);
    }

    void colors(int[] field, boolean original) {
        if (field.length != colors.length) throw new IllegalArgumentException("Invalid color field");
        if (originalColors == original && Arrays.equals(field, targetColors)) return;
        palette(SystemClock.uptimeMillis());
        originalColors = original;
        System.arraycopy(colors, 0, fromColors, 0, colors.length);
        System.arraycopy(field, 0, targetColors, 0, colors.length);
        paletteDirty = true;
        colorStart = SystemClock.uptimeMillis(); invalidate();
    }

    void allow(boolean value) {
        allowed = value; reconcile();
    }

    void disable() {
        // Resume the stock animation while this visible holder still owns its slot.
        restoreOriginal(true);
        allow(false);
        invalidate();
    }

    private void reconcile() {
        boolean active = !disposed && allowed && isAttachedToWindow() && aggregatedVisible
                && isShown() && getWindowVisibility() == VISIBLE;
        if (active == subscribed) return;
        subscribed = active; lastDraw = 0;
        if (!active) {
            removeCallbacks(stale); restoreOriginal(false); motion.hidden();
        } else {
            if (BuildConfig.NATIVE_CAPTURE) { fallback = false; signalUnavailable = false; }
            if (motion.isPlaying()) {
                awaitingSince = SystemClock.uptimeMillis(); lastData = 0; scheduleStale();
            }
            suppressOriginal(); invalidate();
        }
        lifecycle.active(this, active);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); reconcile(); }
    @Override protected void onDetachedFromWindow() {
        aggregatedVisible = false; reconcile(); super.onDetachedFromWindow();
    }
    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible); aggregatedVisible = visible; reconcile();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (lifecycle != null) reconcile();
    }

    /** Stop-at-volume-0: settle smoothly now and suspend the missing-data watchdog. */
    void silenced(boolean value) {
        if (silenced == value || disposed) return;
        silenced = value;
        if (value) {
            removeCallbacks(stale);
            if (!subscribed || !motion.isPlaying()) return;
            if (lastDraw != 0) motion.advance(Math.max(0, SystemClock.uptimeMillis() - lastDraw));
            lastDraw = 0; signalUnavailable = false;
            motion.silence(); suppressOriginal(); invalidate();
        } else {
            // Fresh audio must arrive within the usual window once sampling resumes.
            awaitingSince = SystemClock.uptimeMillis(); lastData = 0; scheduleStale();
        }
        reporter.report("spectrum_silenced value=" + value + " view=" + System.identityHashCode(this), null);
    }

    void frame(float[] levels, long time, boolean available) {
        if (disposed || !subscribed || !motion.isPlaying() || silenced) return;
        // Pending stop deliveries during a holder switch are not an audio failure.
        // A sustained failure is handled by the same 750 ms watchdog as missing FFT.
        if (!available) return;
        removeCallbacks(stale);
        boolean recovering = motion.recovering();
        long now = SystemClock.uptimeMillis();
        if (BuildConfig.NATIVE_CAPTURE && lastDraw != 0) {
            motion.advance(Math.max(0, now - lastDraw)); lastDraw = now;
        }
        fallback = false; signalUnavailable = false; motion.frame(levels); lastData = time;
        if (!recovering && motion.recovering()) reporter.report("spectrum_silence_recovery event=start"
                + " durationMs=" + SpectrumMotion.RECOVERY_MS + " view=" + System.identityHashCode(this), null);
        suppressOriginal(); invalidate(); scheduleStale();
    }

    private void scheduleStale() {
        if (subscribed && motion.isPlaying() && !silenced) postDelayed(stale, 800);
    }

    void suppressOriginal() {
        if (disposed || fallback || !subscribed) return;
        try {
            if (cancelAnimation != null && (!ownsOriginal || original.getVisibility() == VISIBLE)) cancelAnimation.invoke(original);
            original.setVisibility(INVISIBLE);
            ownsOriginal = true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            fallback = true; reporter.report("spectrum_view_error stage=suppress", failure);
            restoreOriginal(true);
        }
    }

    private void restoreOriginal(boolean animate) {
        if (!ownsOriginal) return;
        ownsOriginal = false; original.setVisibility(VISIBLE);
        if (animate && allowed && original.isShown() && playAnimation != null) {
            try { playAnimation.invoke(original); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                reporter.report("spectrum_view_error stage=restore", failure);
            }
        }
    }

    private void stale() {
        if (disposed || !subscribed || !motion.isPlaying() || silenced) return;
        if (BuildConfig.NATIVE_CAPTURE && signalUnavailable) return;
        if (SystemClock.uptimeMillis() - Math.max(lastData, awaitingSince) >= 750) {
            if (BuildConfig.NATIVE_CAPTURE) {
                // Keep the independently drawn resting capsules. No vendor or
                // canned waveform is used to conceal missing measured data.
                signalUnavailable = true; motion.frame(new float[6]);
                fallback = false; suppressOriginal(); invalidate();
                reporter.report("spectrum_view_idle reason=stale_fft renderer=mac_waveform", null);
                return;
            }
            fallback = true; restoreOriginal(true); invalidate();
            reporter.report("spectrum_view_fallback reason=stale_fft", null);
        }
    }

    void dispose(boolean restore) {
        allowed = false; disposed = true; removeCallbacks(stale);
        paint.setShader(null);
        artworkNode.discardDisplayList();
        if (subscribed) { subscribed = false; lifecycle.active(this, false); }
        fallback = true;
        if (restore) {
            allowed = true; restoreOriginal(true); allowed = false;
        } else ownsOriginal = false;
    }

    private boolean palette(long now) {
        if (colorStart == 0 && !paletteDirty) return false;
        float colorDuration = BuildConfig.NATIVE_CAPTURE ? (motion.isPlaying() ? 150f : 900f) : 240f;
        float t = colorStart == 0 ? 1 : Math.min(1, (now - colorStart) / colorDuration);
        boolean changed = paletteDirty;
        for (int i = 0; i < colors.length; i++) {
            int color = CoverColors.blend(fromColors[i], targetColors[i], t);
            changed |= color != colors[i]; colors[i] = color;
        }
        if (changed) {
            if (originalColors) {
                // Stock musicWave moves its two-stop vertical gradient with each bar's height.
                // Use the host's ARGB endpoints directly: no gray veil, saturation or alpha changes.
                colorShader = new LinearGradient(0, 0, 0, 1, colors[0], colors[colors.length - 1], Shader.TileMode.CLAMP);
            } else {
                colorBitmap.setPixels(colors, 0, fieldSize, 0, 0, fieldSize, fieldSize);
                // BitmapShader can retain a native snapshot of a mutable bitmap. Refresh it only
                // during a cover transition; steady audio frames reuse the completed shader.
                BitmapShader bitmapShader = new BitmapShader(colorBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                colorShader = bitmapShader;
                colorShader.setLocalMatrix(colorBounds);
            }
            paint.setShader(colorShader);
            if (BuildConfig.NATIVE_CAPTURE) recordArtwork();
        }
        paletteDirty = false;
        if (t >= 1) colorStart = 0;
        return t < 1;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        float density = getResources().getDisplayMetrics().density;
        float width = BuildConfig.NATIVE_CAPTURE ? AppleWaveform.compactExtent(w, h) : Math.max(1, Math.min(w * 0.82f, 22 * density));
        float height = BuildConfig.NATIVE_CAPTURE ? width : Math.max(1, h);
        // One fixed field spans the rectangle when all six bars are fully extended.
        // A shorter bar clips this field; its current height never stretches the colors.
        colorBounds.setScale(width / fieldSize, height / fieldSize);
        colorBounds.postTranslate((w - width) * 0.5f, (h - height) * 0.5f);
        if (colorShader != null && !originalColors) colorShader.setLocalMatrix(colorBounds);
        if (motion instanceof AppleSpectrumMotion apple) apple.geometry(width, height);
        if (BuildConfig.NATIVE_CAPTURE) recordArtwork();
        super.onSizeChanged(w, h, oldW, oldH);
    }

    private void recordArtwork() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        artworkNode.setPosition(0, 0, getWidth(), getHeight());
        float blur = AppleWaveform.artworkBlurRadius(AppleWaveform.compactExtent(getWidth(), getHeight()));
        artworkNode.setRenderEffect(blur > 0
                ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null);
        Canvas recording = artworkNode.beginRecording();
        // Extend the compact image's edge colors through the host slot before
        // blurring; the shader clamps, so the surrounding margin adds no black.
        recording.drawRect(0, 0, getWidth(), getHeight(), paint);
        artworkNode.endRecording();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (fallback || !subscribed || disposed) return;
        long now = SystemClock.uptimeMillis();
        if (motion.isPlaying() && !signalUnavailable && !silenced && now - Math.max(lastData, awaitingSince) >= 750) { stale(); return; }
        boolean colorMoving = palette(now);
        float elapsed = lastDraw == 0 ? 16 : BuildConfig.NATIVE_CAPTURE ? now - lastDraw : Math.min(64, now - lastDraw); lastDraw = now;
        boolean recovering = motion.recovering();
        boolean moving = motion.advance(elapsed);
        if (recovering && !motion.recovering()) reporter.report("spectrum_silence_recovery event=finished"
                + " view=" + System.identityHashCode(this), null);
        float density = getResources().getDisplayMetrics().density;
        float width = BuildConfig.NATIVE_CAPTURE ? AppleWaveform.compactExtent(getWidth(), getHeight()) : Math.min(getWidth() * 0.82f, 22 * density);
        float bar = width / (SpectrumDsp.BARS + (SpectrumDsp.BARS - 1) * 0.65f);
        float gap = bar * 0.65f;
        float minHeight = Math.min(bar, 1.8f * density);
        float maxHeight = BuildConfig.NATIVE_CAPTURE ? width : Math.max(minHeight, getHeight());
        float left = (getWidth() - width) * 0.5f;
        barMask.reset();
        for (int index = 0; index < motion.heights.length; index++) {
            float level = motion.heights[index];
            if (BuildConfig.NATIVE_CAPTURE) {
                float base = AppleWaveform.baseWidth(width);
                bar = AppleWaveform.width(base, level);
                left = (getWidth() - width) / 2 + AppleWaveform.left(width, index, bar);
                float height = ((AppleSpectrumMotion) motion).barHeight(index, width);
                float y = (getHeight() - height) / 2;
                float radius = AppleWaveform.radius(bar);
                barMask.addRoundRect(left, y, left + bar, y + height, radius, radius, Path.Direction.CW);
                continue;
            }
            float height = minHeight + (maxHeight - minHeight) * level;
            float y = (getHeight() - height) * 0.5f;
            if (originalColors && colorShader != null) {
                originalBounds.setScale(1, height);
                originalBounds.postTranslate(0, y);
                colorShader.setLocalMatrix(originalBounds);
            }
            canvas.drawRoundRect(left, y, left + bar, y + height, bar * 0.5f, bar * 0.5f, paint);
            left += bar + gap;
        }
        if (BuildConfig.NATIVE_CAPTURE) {
            int saved = canvas.save();
            canvas.clipPath(barMask);
            if (canvas.isHardwareAccelerated()) {
                // HWUI may discard a node's display list while this holder is
                // hidden. Artwork pixels and palette can still be unchanged:
                // the palette cache is not a validity check for GPU commands.
                if (!artworkNode.hasDisplayList()) {
                    recordArtwork();
                    reporter.report("spectrum_artwork_rebuilt reason=discarded_display_list"
                            + " view=" + System.identityHashCode(this), null);
                }
                canvas.drawRenderNode(artworkNode);
            } else canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            canvas.restoreToCount(saved);
        }
        if (!drew) {
            drew = true; int[] xy = new int[2]; getLocationOnScreen(xy);
            reporter.report("spectrum_view_draw x=" + xy[0] + " y=" + xy[1]
                    + " width=" + getWidth() + " height=" + getHeight()
                    + (BuildConfig.NATIVE_CAPTURE ? " contentWidth=" + width + " contentHeight=" + width + " layout=compact_fit" : "")
                    + " maxBarHeight=" + maxHeight + " renderer=" + (BuildConfig.NATIVE_CAPTURE ? "mac_waveform" : "legacy")
                    + " colorMapping=full_spectrum", null);
        }
        if (!motion.isPlaying() && !moving && !pauseSettled) {
            pauseSettled = true;
            reporter.report("spectrum_pause_settled view=" + System.identityHashCode(this), null);
        }
        if (moving || colorMoving) postInvalidateOnAnimation();
    }
}
