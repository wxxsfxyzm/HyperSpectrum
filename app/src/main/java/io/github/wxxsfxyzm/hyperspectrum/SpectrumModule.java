// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import io.github.wxxsfxyzm.hyperspectrum.audio.CaptureProbe;
import io.github.wxxsfxyzm.hyperspectrum.audio.MediaRoute;
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences;
import io.github.wxxsfxyzm.hyperspectrum.render.IslandSpectrumUi;
import io.github.wxxsfxyzm.hyperspectrum.render.OriginalArtwork;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Preserve host calls/results; keep six bars continuous across musicWave/musicPause. */
public final class SpectrumModule extends XposedModule {
    private static final String TAG = "IslandSpectrumProbe";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CONTROLLER = "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl";
    private static final String MEDIA = "com.android.systemui.media.controls.shared.model.MediaData";
    private static final String HOLDER = "miui.systemui.dynamicisland.module.IslandIconViewHolder";
    private static final String PLUGIN = "com.android.systemui.statusbar.notification.DynamicIslandPluginController";
    private final Map<Executable, HookHandle> handles = new LinkedHashMap<>();
    private final Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
    private CaptureProbe probe;
    private IslandSpectrumUi ui;
    private volatile boolean retired;
    private Object lastController;
    private Object lastMedia;
    private SharedPreferences preferences;
    private volatile Context systemUiContext;
    private volatile Boolean lastNativeAvailability;
    private volatile boolean availabilityQueryRegistered;
    private final BroadcastReceiver availabilityQuery = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!SpectrumPreferences.NATIVE_AVAILABILITY_QUERY_ACTION.equals(intent.getAction())) return;
            try {
                int appUid = context.getPackageManager().getApplicationInfo(
                        "io.github.wxxsfxyzm.hyperspectrum", 0).uid;
                int sender = getSentFromUid();
                String senderPackage = getSentFromPackage();
                long nonce = intent.getLongExtra(SpectrumPreferences.NATIVE_AVAILABILITY_NONCE, 0L);
                if (sender != appUid || !"io.github.wxxsfxyzm.hyperspectrum".equals(senderPackage) || nonce == 0L) {
                    report("native_availability_query_rejected sender=" + sender + " expected=" + appUid, null);
                    return;
                }
                boolean available = BuildConfig.NATIVE_CAPTURE && ui != null && ui.nativeAvailable();
                sendNativeAvailabilityReply(context, nonce, available);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        }
    };
    private boolean enabled = SpectrumPreferences.DEFAULT_ENABLED;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if (key == null || SpectrumPreferences.ENABLED.equals(key)
                || SpectrumPreferences.COLOR_SCHEME.equals(key) || SpectrumPreferences.STOP_WHEN_MUTED.equals(key)
                || SpectrumPreferences.CAPTURE_BACKEND.equals(key)) readPreferences();
    };

    private synchronized void readPreferences() {
        if (retired || preferences == null) return;
        try {
            enabled = preferences.getBoolean(SpectrumPreferences.ENABLED, SpectrumPreferences.DEFAULT_ENABLED);
            String scheme = SpectrumPreferences.colorScheme(preferences.getString(SpectrumPreferences.COLOR_SCHEME, SpectrumPreferences.DEFAULT_COLOR_SCHEME));
            boolean stopWhenMuted = preferences.getBoolean(SpectrumPreferences.STOP_WHEN_MUTED, SpectrumPreferences.DEFAULT_STOP_WHEN_MUTED);
            String captureBackend = SpectrumPreferences.captureBackend(preferences.getString(
                    SpectrumPreferences.CAPTURE_BACKEND, SpectrumPreferences.DEFAULT_CAPTURE_BACKEND));
            if (ui != null) {
                ui.enabled(enabled);
                ui.colorScheme(scheme);
                ui.stopWhenMuted(stopWhenMuted);
                ui.captureBackend(captureBackend);
            }
            report("spectrum_setting enabled=" + enabled + " colorScheme=" + scheme + " stopWhenMuted="
                    + stopWhenMuted + " captureBackend=" + captureBackend, null);
        } catch (RuntimeException failure) {
            report("spectrum_settings_unavailable", failure);
        }
    }

    private void report(String message, Throwable failure) {
        if (preferences != null && BuildConfig.NATIVE_CAPTURE) {
            Boolean available = message.startsWith("native_backend_available=true") ? Boolean.TRUE
                    : message.startsWith("native_backend_available=false") ? Boolean.FALSE : null;
            if (available != null) {
                try {
                    publishNativeAvailability(available);
                } catch (RuntimeException writeFailure) {
                    Log.w(TAG, "native_backend_broadcast_failed", writeFailure);
                    log(Log.WARN, TAG, "native_backend_broadcast_failed", writeFailure);
                }
            }
        }
        String tagged = "build=" + BuildConfig.PROBE_BUILD + " pid=" + Process.myPid() + " " + message;
        if (failure == null) {
            Log.i(TAG, tagged); log(Log.INFO, TAG, tagged);
        } else {
            Log.w(TAG, tagged, failure); log(Log.WARN, TAG, tagged, failure);
        }
    }

    private void publishNativeAvailability(boolean available) {
        Context context = systemUiContext;
        Boolean previous = lastNativeAvailability;
        if (context == null || (previous != null && available == previous.booleanValue())) return;
        Intent intent = new Intent(SpectrumPreferences.NATIVE_AVAILABILITY_ACTION)
                .setPackage("io.github.wxxsfxyzm.hyperspectrum")
                .putExtra(SpectrumPreferences.NATIVE_AVAILABILITY_EXTRA, available);
        context.sendBroadcast(intent);
        lastNativeAvailability = available;
        report("native_backend_signal_sent available=" + available, null);
    }

    private void sendNativeAvailabilityReply(Context context, long nonce, boolean available) {
        Intent reply = new Intent(SpectrumPreferences.NATIVE_AVAILABILITY_ACTION)
                .setPackage("io.github.wxxsfxyzm.hyperspectrum")
                .putExtra(SpectrumPreferences.NATIVE_AVAILABILITY_NONCE, nonce)
                .putExtra(SpectrumPreferences.NATIVE_AVAILABILITY_EXTRA, available);
        Bundle options = android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
        context.sendBroadcast(reply, null, options);
        report("native_availability_query answered=" + available, null);
    }

    private void registerAvailabilityQuery(Context context) {
        if (availabilityQueryRegistered) return;
        IntentFilter filter = new IntentFilter(SpectrumPreferences.NATIVE_AVAILABILITY_QUERY_ACTION);
        context.registerReceiver(availabilityQuery, filter, Context.RECEIVER_EXPORTED);
        availabilityQueryRegistered = true;
    }

    private void unregisterAvailabilityQuery() {
        Context context = systemUiContext;
        if (!availabilityQueryRegistered || context == null) return;
        try { context.unregisterReceiver(availabilityQuery); }
        catch (IllegalArgumentException ignored) { }
        availabilityQueryRegistered = false;
        systemUiContext = null;
        lastNativeAvailability = null;
    }

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        report("module_loaded process=" + param.getProcessName(), null);
        if (SYSTEM_UI.equals(param.getProcessName())) ensureProbe();
    }

    private synchronized void ensureProbe() {
        if (retired) return;
        if (preferences == null) {
            try {
                preferences = getRemotePreferences(SpectrumPreferences.GROUP);
                preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
                readPreferences();
            } catch (RuntimeException failure) {
                preferences = null;
                report("spectrum_settings_unavailable", failure);
            }
        }
        if (BuildConfig.DIAGNOSTIC_MODE) {
            if (probe == null) probe = new CaptureProbe(this::report);
        } else if (ui == null) {
            ui = new IslandSpectrumUi(this::report);
            ui.enabled(enabled);
            readPreferences();
        }
    }

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (SYSTEM_UI.equals(param.getPackageName())) {
            ensureProbe(); install(param.getDefaultClassLoader());
        }
    }

    private synchronized void install(ClassLoader loader) {
        if (retired || loader == null) return;
        boolean fresh = loaders.add(loader);
        boolean controller = hasClass(loader, CONTROLLER);
        boolean holder = hasClass(loader, HOLDER);
        boolean plugin = hasClass(loader, PLUGIN);
        if (fresh) report("loader_route controller=" + controller + " holder=" + holder + " plugin=" + plugin, null);
        if (controller) {
        installMethod(loader, CONTROLLER, "addDynamicIslandView", "media",
                "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder",
                "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder", MEDIA);
        installMethod(loader, CONTROLLER, "generateRightImage", "right", "org.json.JSONObject", MEDIA, "android.os.Bundle");
        installMethod(loader, CONTROLLER, "removeDynamicIslandView$1", "remove", "java.lang.String", "boolean");
        }
        if (holder) {
        for (String name : new String[]{"bind", "updatePartial"}) {
            installMethod(loader, HOLDER, name, "holder",
                    "miui.systemui.dynamicisland.model.IslandTemplate",
                    "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData");
        }
        for (String name : new String[]{"onShow", "onHidden", "onDetach"}) {
            installMethod(loader, HOLDER, name, "holder");
        }
        for (String name : new String[]{"playAnimation", "registerLottieCallback"}) {
            installMethod(loader, HOLDER, name, "animation");
        }
        installMethod(loader, HOLDER, "getLottieColor", "palette", "android.graphics.Bitmap");
        // Keep the small registerLottieCallback hook observable in its asynchronous caller.
        try {
            for (Method method : Class.forName(HOLDER, false, loader).getDeclaredMethods()) {
                if (method.getName().equals("playAnimation$lambda$4") || method.getName().equals("access$getLottieColor")) deoptimize(method);
            }
        } catch (ClassNotFoundException ignored) { }
        }
        // Observe the actual plugin loader if the UI implementation lives outside the default loader.
        if (plugin) installMethod(loader, PLUGIN, "onPluginLoaded", "plugin", "com.android.systemui.plugins.Plugin",
                "android.content.Context", "com.android.systemui.plugins.PluginLifecycleManager");
    }

    private static boolean hasClass(ClassLoader loader, String name) {
        try { Class.forName(name, false, loader); return true; }
        catch (ClassNotFoundException ignored) { return false; }
    }

    private void installMethod(ClassLoader loader, String owner, String name, String role, String... parameters) {
        try {
            Class<?> type = Class.forName(owner, false, loader);
            Method target = null;
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if ("palette".equals(role) ? method.getReturnType().isPrimitive() : method.getReturnType() != void.class) continue;
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != parameters.length) continue;
                boolean matches = true;
                for (int i = 0; i < actual.length; i++) matches &= actual[i].getName().equals(parameters[i]);
                if (!matches) continue;
                if (target != null) throw new IllegalStateException("Ambiguous hook " + owner + "." + name);
                target = method;
            }
            if (target == null) throw new NoSuchMethodException(owner + "." + name);
            if (handles.containsKey(target)) return;
            String id = "spectrum_" + role + "_" + name;
            HookHandle handle = hook(target).setId(id).intercept(hooker(role));
            handles.put(target, handle);
            report("hook_ready id=" + id + " owner=" + owner + " loader=" + loader, null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            report("hook_unavailable owner=" + owner + " method=" + name, failure);
        }
    }

    private XposedInterface.Hooker hooker(String role) {
        return chain -> {
            Object result = chain.proceed(); // Never retry or swallow an original SystemUI exception.
            try {
                synchronized (this) {
                    if (!retired) after(role, chain, result);
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                report("observer_error role=" + role, failure);
            }
            return result;
        };
    }

    private void after(String role, XposedInterface.Chain chain, Object result) throws ReflectiveOperationException {
        switch (role) {
            case "palette":
                if (ui != null && result != null && chain.getArg(0) instanceof Bitmap bitmap) {
                    int top = pairColor(result, true), bottom = pairColor(result, false);
                    long cover = OriginalArtwork.fingerprint(bitmap);
                    ui.originalColors(cover, top, bottom);
                    report("spectrum_original_colors cover=" + cover + " top=" + Integer.toHexString(top)
                            + " bottom=" + Integer.toHexString(bottom), null);
                }
                break;
            case "media":
                lastController = chain.getThisObject(); lastMedia = chain.getArg(2);
                observe(lastController, lastMedia, false);
                break;
            case "right":
                lastController = chain.getThisObject(); lastMedia = chain.getArg(1);
                observe(lastController, lastMedia, false);
                JSONObject json = (JSONObject) chain.getArg(0);
                JSONObject right = json.optJSONObject("imageTextInfoRight");
                JSONObject pic = right == null ? null : right.optJSONObject("picInfo");
                report("right_image pic=" + (pic == null ? "missing" : pic.optString("pic")), null);
                break;
            case "remove":
                if (probe != null) probe.removed((String) chain.getArg(0));
                if (ui != null) ui.removed((String) chain.getArg(0));
                break;
            case "holder":
                Object holder = chain.getThisObject();
                Object info = field(holder, "picInfo");
                String picName = info == null ? "" : String.valueOf(info.getClass().getMethod("getPic").invoke(info));
                if (picName.equals("musicWave") || picName.equals("musicPause")) {
                    report("holder_event method=" + chain.getExecutable().getName() + " pic=" + picName
                            + " holder=" + System.identityHashCode(holder), null);
                }
                if (ui != null) ui.holder(holder, chain.getExecutable().getName());
                break;
            case "animation":
                if (ui != null) ui.holder(chain.getThisObject(), "animation");
                break;
            case "plugin":
                Object plugin = chain.getArg(0);
                if (plugin != null) install(plugin.getClass().getClassLoader());
                break;
            default: throw new IllegalArgumentException(role);
        }
    }

    private static int pairColor(Object pair, boolean first) throws ReflectiveOperationException {
        try { return ((Number) field(pair, first ? "a" : "b")).intValue(); }
        catch (NoSuchFieldException missing) {
            return ((Number) pair.getClass().getMethod(first ? "getFirst" : "getSecond").invoke(pair)).intValue();
        }
    }

    private void observe(Object controller, Object media, boolean force) throws ReflectiveOperationException {
        if (controller == null || media == null) return;
        ensureProbe();
        Context context = (Context) field(controller, "context");
        systemUiContext = context.getApplicationContext();
        registerAvailabilityQuery(systemUiContext);
        if (BuildConfig.NATIVE_CAPTURE && ui != null) publishNativeAvailability(ui.nativeAvailable());
        String key = (String) field(media, "notificationKey");
        Boolean playing = (Boolean) field(media, "isPlaying");
        Object device = field(controller, "castDeviceType");
        String route = device == null ? "" : device.toString();
        if ("others".equals(route))
            route = MediaRoute.resolve(context, route, (MediaSession.Token) field(media, "token"));
        report("media_state playing=" + playing + " route=" + route, null);
        if (probe != null) probe.observe(context, key, playing, route, force);
        if (ui != null) {
            Icon artwork = (Icon) field(media, "artwork");
            String packageName = (String) field(media, "packageName");
            int userId = ((Number) field(media, "userId")).intValue();
            String track = packageName + "|" + userId + "|"
                    + key + "|" + field(media, "mediaId") + "|" + field(media, "song") + "|" + field(media, "artist");
            ui.media(context, key, playing, route, packageName, userId, track, artwork);
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    @Override public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        IslandSpectrumUi oldUi;
        synchronized (this) {
            retired = true; // In-flight old chain snapshots become passive before resource teardown.
            unregisterAvailabilityQuery();
            if (preferences != null) {
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
                preferences = null;
            }
            if (probe != null) probe.close();
            probe = null; oldUi = ui;
        }
        // Do not hold the module lock while waiting for main-looper View cleanup.
        Object[][] holders = oldUi == null ? new Object[0][] : oldUi.prepareReload();
        if (holders == null) { report("hot_reload_deferred reason=main_cleanup", null); return false; }
        synchronized (this) {
        ui = null;
        // Only host/framework objects cross generations. No old module runtime/listener/buffer.
        param.setSavedInstanceState(new Object[]{loaders.toArray(new ClassLoader[0]), lastController, lastMedia, holders});
        lastController = null; lastMedia = null;
        report("hot_reloading hooks=" + handles.size(), null);
        }
        return true;
    }

    @Override public synchronized void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        retired = false;
        Object saved = param.getSavedInstanceState();
        Object[][] holderState = new Object[0][];
        if (saved instanceof Object[] state && state.length >= 3) {
            if (state[0] instanceof ClassLoader[] oldLoaders) Collections.addAll(loaders, oldLoaders);
            lastController = state[1]; lastMedia = state[2];
            if (state.length >= 4 && state[3] instanceof Object[][] entries) holderState = entries;
        }
        boolean systemUi = SYSTEM_UI.equals(param.getProcessName());
        int replaced = 0;
        for (HookHandle old : param.getOldHookHandles()) {
            Executable executable = old.getExecutable();
            String role = role(old.getId());
            if (role != null) systemUi = true;
            if (executable.getDeclaringClass().getClassLoader() != null) {
                loaders.add(executable.getDeclaringClass().getClassLoader());
            }
            try {
                if (role == null) { old.unhook(); continue; }
                HookHandle replacement = old.replaceHook(hooker(role));
                handles.put(executable, replacement); // Presence only after successful replacement.
                replaced++;
            } catch (RuntimeException | LinkageError failure) {
                report("hook_replace_failed id=" + old.getId(), failure);
                // The same ID on the same executable is backfilled by install().
            }
        }
        if (!systemUi) return;
        ensureProbe();
        for (ClassLoader loader : loaders.toArray(new ClassLoader[0])) install(loader);
        try { observe(lastController, lastMedia, true); }
        catch (ReflectiveOperationException | RuntimeException failure) { report("restore_media_failed", failure); }
        if (ui != null) ui.restore(holderState);
        boolean mediaReady = handles.values().stream().anyMatch(h -> "media".equals(role(h.getId())));
        Set<String> ids = new java.util.HashSet<>();
        for (HookHandle handle : handles.values()) ids.add(handle.getId());
        boolean renderReady = ids.containsAll(java.util.List.of("spectrum_holder_bind",
                "spectrum_holder_updatePartial", "spectrum_holder_onShow", "spectrum_holder_onHidden",
                "spectrum_holder_onDetach", "spectrum_animation_playAnimation",
                "spectrum_animation_registerLottieCallback"));
        report("hot_reloaded process=" + param.getProcessName() + " replaced=" + replaced
                + " hooks=" + handles.size() + " mediaReady=" + mediaReady
                + " renderReady=" + renderReady + " originalColorReady=" + ids.contains("spectrum_palette_getLottieColor"), null);
    }

    private static String role(String id) {
        if (id == null) return null;
        // Exact IDs: retired/unknown IDs are unhooked instead of being accidentally adopted.
        return switch (id) {
            case "spectrum_media_addDynamicIslandView" -> "media";
            case "spectrum_right_generateRightImage" -> "right";
            case "spectrum_remove_removeDynamicIslandView$1" -> "remove";
            case "spectrum_holder_bind", "spectrum_holder_updatePartial",
                    "spectrum_holder_onShow", "spectrum_holder_onHidden", "spectrum_holder_onDetach" -> "holder";
            case "spectrum_plugin_onPluginLoaded" -> "plugin";
            case "spectrum_animation_playAnimation", "spectrum_animation_registerLottieCallback" -> "animation";
            case "spectrum_palette_getLottieColor" -> "palette";
            default -> null;
        };
    }
}
