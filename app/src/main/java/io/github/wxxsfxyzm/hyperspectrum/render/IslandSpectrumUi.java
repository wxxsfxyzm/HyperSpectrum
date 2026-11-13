// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.render;

import io.github.wxxsfxyzm.hyperspectrum.BuildConfig;
import io.github.wxxsfxyzm.hyperspectrum.audio.CaptureProbe;
import io.github.wxxsfxyzm.hyperspectrum.audio.MediaRoute;
import io.github.wxxsfxyzm.hyperspectrum.audio.SpectrumCapture;
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Main-looper owner of every replacement View and subscription. */
public final class IslandSpectrumUi {
    private static final class Slot {
        // Holder slots key a host IslandIconViewHolder; layout slots key a found island_icon container.
        final Object holder; final SpectrumView view; final boolean layout;
        boolean shown = true;
        Slot(Object holder, SpectrumView view, boolean layout) {
            this.holder = holder; this.view = view; this.layout = layout;
        }
    }
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String ISLAND_WINDOW = "DynamicIslandWindow";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CaptureProbe.Reporter reporter;
    private final Map<Object, Slot> slots = new IdentityHashMap<>();
    private final Set<SpectrumView> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private SpectrumView lastPresentation;
    private final SpectrumCapture engine;
    private final ArtworkPalette palette;
    private volatile boolean closed;
    private volatile Object[][] reloadState;
    private boolean playing, interactive = true, silenced;
    private boolean enabled = SpectrumPreferences.DEFAULT_ENABLED;
    private String key;
    private String route = "";
    private Context context;
    private boolean screenRegistered;
    private int[] coverColors = BuildConfig.NATIVE_CAPTURE ? AppleArtwork.fallback() : CoverColors.fallback();
    private boolean originalColors, originalSeeded;
    // Layout fallback for island modules drawn without host holders (for example a lyric module
    // that rebuilds the big island with the host resource IDs). Holder slots always take priority.
    private boolean compatibility = SpectrumPreferences.DEFAULT_COMPATIBILITY_MODE;
    private String appLabel;
    private View islandRoot;
    private int iconId;
    private boolean layoutUnavailableReported;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutScan = this::scanLayout;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            interactive = Intent.ACTION_SCREEN_ON.equals(intent.getAction()); demand();
        }
    };

    public IslandSpectrumUi(CaptureProbe.Reporter reporter) {
        this.reporter = reporter;
        SpectrumCapture.Sink sink = new SpectrumCapture.Sink() {
            @Override public void frame(float[] levels, long time, boolean healthy) {
                if (!closed) for (SpectrumView view : new ArrayList<>(active)) view.frame(levels, time, healthy);
            }
            @Override public void silenced(boolean value) {
                if (closed || silenced == value) return;
                silenced = value;
                for (Slot slot : slots.values()) slot.view.silenced(value);
            }
        };
        engine = SpectrumCapture.create(reporter, main, sink);
        reporter.report("spectrum_backend type=" + (BuildConfig.NATIVE_CAPTURE ? "native" : "visualizer"), null);
        palette = new ArtworkPalette(engine::execute, main, (colors, original) -> {
            coverColors = colors;
            originalColors = original;
            for (Slot slot : slots.values()) slot.view.colors(colors, original);
        }, reporter);
    }

    private void main(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!closed) action.run(); }
        else main.post(() -> { if (!closed) action.run(); });
    }

    public void enabled(boolean value) {
        main(() -> {
            if (enabled == value) return;
            enabled = value;
            for (Slot slot : slots.values()) {
                if (value) slot.view.allow(slot.shown);
                else slot.view.disable();
            }
            scanLayout();
            demand();
            reporter.report("spectrum_enabled value=" + value + " slots=" + slots.size(), null);
        });
    }

    public void colorScheme(String value) { main(() -> palette.scheme(value)); }

    public void originalColors(long cover, int top, int bottom) {
        main(() -> palette.original(cover, top, bottom));
    }

    public void stopWhenMuted(boolean value) { engine.stopWhenMuted(value); }

    public void compatibilityMode(boolean value) {
        main(() -> {
            if (compatibility == value) return;
            compatibility = value;
            if (value) watchLayout(); else unwatchLayout();
            scanLayout(); // Off removes every layout slot and restores the foreign animation.
            demand();
        });
    }

    public String captureMode() { return engine.captureMode(); }

    public void media(Context ctx, String notificationKey, Boolean isPlaying, String route,
               String packageName, int userId, String track, Icon artwork, String app) {
        main(() -> {
            if (context == null) {
                context = ctx;
                engine.audio(ctx);
                PowerManager power = ctx.getSystemService(PowerManager.class);
                interactive = power == null || power.isInteractive();
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                try {
                    ctx.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
                    screenRegistered = true;
                } catch (RuntimeException failure) {
                    reporter.report("spectrum_screen_receiver_unavailable", failure);
                }
            }
            key = notificationKey;
            this.route = route;
            playing = Boolean.TRUE.equals(isPlaying) && MediaRoute.isLocal(route);
            engine.media(packageName, userId);
            palette.artwork(ctx, track, artwork);
            appLabel = app;
            if (compatibility) watchLayout();
            scanLayout();
            demand();
        });
    }

    public void removed(String removedKey) {
        main(() -> {
            if (!java.util.Objects.equals(key, removedKey)) return;
            playing = false; key = null;
            scanLayout(); // Without a media island no layout slot remains.
            demand();
        });
    }

    public void holder(Object holder, String event) {
        main(() -> apply(holder, event));
    }

    private void apply(Object holder, String event) {
        try {
            seedOriginalColors(holder);
            Slot slot = slots.get(holder);
            if (event.equals("onDetach")) { remove(holder, false); return; }
            if (event.equals("onHidden")) {
                if (slot != null) { slot.shown = false; slot.view.allow(false); }
                return;
            }
            Object info = field(holder, "picInfo");
            String pic = info == null ? "" : String.valueOf(info.getClass().getMethod("getPic").invoke(info));
            if (!MediaIcon.replace(pic, route)) { remove(holder, false); return; }
            if (slot == null && event.equals("animation")) return;
            boolean wiredIcon = MediaIcon.wiredDevice(pic, route);
            FrameLayout container = (FrameLayout) field(holder, "iconContainer");
            View original = (View) field(holder, wiredIcon ? "fixIcon" : "lottieView");
            if (container == null || original == null || original.getParent() != container) return;
            if (slot == null) {
                Slot found = slots.get(container);
                if (found != null && found.layout) remove(container, true); // The host holder path wins.
                SpectrumView view = createView(container, original, !wiredIcon, MediaIcon.playing(pic, route, playing));
                slot = new Slot(holder, view, false);
                slots.put(holder, slot);
                reporter.report("spectrum_view_install holder=" + System.identityHashCode(holder)
                        + " slots=" + slots.size() + " source=" + (wiredIcon ? "wired_device" : "waveform"), null);
            } else if (slot.view.bindOriginal(original, !wiredIcon)) {
                // Pause switches the host back to Lottie. Preserve the same
                // spectrum view, presentation heights and artwork throughout.
                slot.view.setLayoutParams(new FrameLayout.LayoutParams((FrameLayout.LayoutParams) original.getLayoutParams()));
            }
            if (event.equals("onShow")) slot.shown = true;
            slot.view.playing(MediaIcon.playing(pic, route, playing));
            slot.view.allow(enabled && slot.shown);
            slot.view.suppressOriginal(); // Also catches the end of asynchronous Lottie composition.
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reporter.report("spectrum_view_error stage=bind", failure);
            remove(holder, true);
        }
    }

    private SpectrumView createView(FrameLayout container, View original, boolean animated, boolean isPlaying)
            throws ReflectiveOperationException {
        SpectrumView view = new SpectrumView(container.getContext(), original, animated,
                (v, enabled) -> {
                    if (enabled) {
                        if (!active.contains(v) && lastPresentation != null && lastPresentation != v)
                            v.inheritPresentation(lastPresentation);
                        active.add(v); lastPresentation = v;
                    } else {
                        if (active.remove(v)) lastPresentation = v;
                    }
                    demand();
                }, reporter);
        view.colors(coverColors, originalColors);
        view.playing(isPlaying);
        view.silenced(silenced);
        container.addView(view, new FrameLayout.LayoutParams((FrameLayout.LayoutParams) original.getLayoutParams()));
        return view;
    }

    private void watchLayout() {
        if (islandRoot != null && islandRoot.isAttachedToWindow()) return;
        unwatchLayout();
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            Field rootsField = global.getDeclaredField("mRoots"), lockField = global.getDeclaredField("mLock");
            rootsField.setAccessible(true); lockField.setAccessible(true);
            List<Object> roots;
            synchronized (lockField.get(instance)) { roots = new ArrayList<>((List<?>) rootsField.get(instance)); }
            for (Object root : roots) {
                View view = (View) root.getClass().getMethod("getView").invoke(root);
                if (view != null && view.getLayoutParams() instanceof WindowManager.LayoutParams params
                        && ISLAND_WINDOW.contentEquals(params.getTitle())) {
                    islandRoot = view;
                    view.getViewTreeObserver().addOnGlobalLayoutListener(layoutScan);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!layoutUnavailableReported) reporter.report("spectrum_layout_unavailable", failure);
            layoutUnavailableReported = true;
        }
    }

    private void unwatchLayout() {
        if (islandRoot == null) return;
        try { islandRoot.getViewTreeObserver().removeOnGlobalLayoutListener(layoutScan); }
        catch (IllegalStateException ignored) { } // The detached observer is already dead.
        islandRoot = null;
    }

    /** Keep layout slots in step with the island tree; runs on every island-window layout. */
    private void scanLayout() {
        if (closed) return;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (compatibility && enabled && key != null && appLabel != null && MediaRoute.isLocal(route)
                && islandRoot != null && islandRoot.isAttachedToWindow()) findIcons(islandRoot, false, seen);
        for (Slot slot : new ArrayList<>(slots.values())) {
            if (slot.layout && !seen.contains(slot.holder))
                remove(slot.holder, ((View) slot.holder).isAttachedToWindow());
        }
    }

    private void findIcons(View view, boolean media, Set<Object> seen) {
        // The big island (and its fake transition copy) carries the media app label.
        CharSequence description = view.getContentDescription();
        if (description != null) media = appLabel.contentEquals(description);
        if (!(view instanceof ViewGroup group)) return;
        if (media && group instanceof FrameLayout container && isIcon(container) && layoutIcon(container))
            seen.add(container);
        for (int i = 0; i < group.getChildCount(); i++) findIcons(group.getChildAt(i), media, seen);
    }

    private boolean isIcon(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        if (iconId != 0) return id == iconId;
        try {
            if (!"island_icon".equals(view.getResources().getResourceEntryName(id))
                    || !PLUGIN_PACKAGE.equals(view.getResources().getResourcePackageName(id))) return false;
        } catch (android.content.res.Resources.NotFoundException missing) { return false; }
        iconId = id;
        return true;
    }

    private boolean layoutIcon(FrameLayout container) {
        Slot slot = slots.get(container);
        View original = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof SpectrumView) {
                if (slot == null || slot.view != child) return false; // Owned by a host holder.
            } else if (original == null && animation(child)) {
                original = child;
            }
        }
        if (original == null || !(original.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
        try {
            if (slot == null) {
                slot = new Slot(container, createView(container, original, false, playing), true);
                slots.put(container, slot);
                reporter.report("spectrum_view_install holder=" + System.identityHashCode(container)
                        + " slots=" + slots.size() + " source=layout original=" + original.getClass().getName()
                        + " drawable=" + ((ImageView) original).getDrawable().getClass().getName(), null);
            } else if (slot.view.bindOriginal(original, false)) {
                slot.view.setLayoutParams(new FrameLayout.LayoutParams((FrameLayout.LayoutParams) original.getLayoutParams()));
            }
            slot.view.playing(playing);
            slot.view.allow(enabled);
            slot.view.suppressOriginal(); // The foreign module may show its own Lottie again.
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reporter.report("spectrum_view_error stage=layout", failure);
            return false;
        }
    }

    /**
     * A Lottie view holding a composition. Measured with the lyric module: the album side also has
     * an empty Lottie view (null drawable) beside a plain ImageView with the artwork bitmap, and the
     * drawable class itself is obfuscated, so only the view class name is a usable marker.
     */
    private static boolean animation(View child) {
        return child instanceof ImageView image && image.getDrawable() != null
                && !(image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)
                && image.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("lottie");
    }

    private void seedOriginalColors(Object holder) {
        if (BuildConfig.NATIVE_CAPTURE || originalSeeded) return;
        try {
            Bundle bundle = (Bundle) holder.getClass().getMethod("getBitmapBundle").invoke(holder);
            Icon album = bundle == null ? null : bundle.getParcelable("miui_media_album_icon", Icon.class);
            if (album == null) return;
            int top = ((Number) field(holder, "gradientTopColor")).intValue();
            int bottom = ((Number) field(holder, "gradientBottomColor")).intValue();
            if ((top >>> 24) != 153 || (bottom >>> 24) != 153) return;
            originalSeeded = true;
            Context ctx = (Context) holder.getClass().getMethod("getContext").invoke(holder);
            engine.execute(() -> {
                try {
                    android.graphics.drawable.Drawable drawable = album.loadDrawable(ctx);
                    if (drawable == null) return;
                    long cover = OriginalArtwork.fingerprint(drawable);
                    originalColors(cover, top, bottom);
                    reporter.report("spectrum_original_backfill cover=" + cover + " top=" + Integer.toHexString(top)
                            + " bottom=" + Integer.toHexString(bottom), null);
                } catch (RuntimeException failure) { reporter.report("spectrum_original_backfill_error", failure); }
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reporter.report("spectrum_original_backfill_unavailable", failure);
        }
    }

    private void demand() {
        if (!closed) {
            int count = 0;
            for (SpectrumView view : active) if (view.wantsAudio()) count++;
            engine.demand(enabled && playing && interactive && count > 0, count);
        }
    }

    private void remove(Object holder, boolean restore) {
        Slot slot = slots.remove(holder);
        if (slot == null) return;
        slot.view.dispose(restore);
        if (lastPresentation == slot.view) lastPresentation = null;
        if (slot.view.getParent() instanceof FrameLayout parent) parent.removeView(slot.view);
        reporter.report("spectrum_view_remove holder=" + System.identityHashCode(holder)
                + " slots=" + slots.size(), null);
    }

    static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    /** Return only host holders/booleans, after removing every old-generation View/listener. */
    public Object[][] prepareReload() {
        if (reloadState != null) return reloadState;
        AtomicReference<Object[][]> snapshot = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        // 0 pending, 1 running, 2 cancelled: a rejected reload must leave this generation intact.
        AtomicInteger phase = new AtomicInteger();
        Runnable cleanup = () -> {
            if (!phase.compareAndSet(0, 1)) return;
            try {
                // Only host holders cross generations; layout slots are found again by the new scan.
                List<Object[]> held = new ArrayList<>();
                for (Slot slot : slots.values()) if (!slot.layout) held.add(new Object[]{slot.holder, slot.shown});
                Object[][] state = held.toArray(new Object[0][]);
                closed = true;
                unwatchLayout();
                palette.close();
                if (screenRegistered) {
                    try { context.unregisterReceiver(screen); }
                    catch (RuntimeException failure) { reporter.report("spectrum_screen_unregister_error", failure); }
                    screenRegistered = false;
                }
                engine.close();
                for (Object holder : slots.keySet().toArray()) remove(holder, true);
                active.clear(); lastPresentation = null; main.removeCallbacksAndMessages(null);
                reloadState = state; snapshot.set(state);
            } catch (RuntimeException failure) {
                reporter.report("spectrum_reload_cleanup_error", failure);
            } finally { done.countDown(); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run();
        else {
            main.post(cleanup);
            try {
                if (!done.await(2, TimeUnit.SECONDS)) {
                    if (phase.compareAndSet(0, 2)) { main.removeCallbacks(cleanup); return null; }
                    // Already running: engine retirement is bounded, so wait for a consistent result.
                    if (!done.await(5, TimeUnit.SECONDS)) return null;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (phase.compareAndSet(0, 2)) main.removeCallbacks(cleanup);
                return null;
            }
        }
        return snapshot.get();
    }

    public void restore(Object[][] state) {
        main(() -> {
            for (Object[] entry : state) {
                if (entry.length < 2) continue;
                apply(entry[0], "bind");
                if (Boolean.FALSE.equals(entry[1])) apply(entry[0], "onHidden");
            }
            reporter.report("spectrum_views_restored slots=" + slots.size(), null);
        });
    }
}
