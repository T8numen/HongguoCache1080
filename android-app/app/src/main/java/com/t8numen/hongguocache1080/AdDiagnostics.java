package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Passive diagnostics. Never changes playback, advertising or completion callbacks. */
final class AdDiagnostics {
    static final String OPEN_SETTINGS = "hongguo_open_ad_diagnostics";
    private static final String PREFS = "hongguo_cache_1080_ad_diagnostics";
    private static final String LOG = "[HongguoCache1080/AdDiagnostics] ";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final Map<String, String> HOOKS = new ConcurrentHashMap<>();
    private static final Map<String, String> SOURCES = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
                    return size() > 256;
                }
            });
    private static final Set<String> STACKS = new LinkedHashSet<>();
    private static final Map<String, Long> SOURCE_TIMES = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> entry) {
            return size() > 256;
        }
    };
    private static final Map<View, String> LAYERS = new WeakHashMap<>();
    private static final Map<View, String> LAYER_SOURCES = new WeakHashMap<>();
    private static final Map<Object, WeakReference<Object>> CONTROLLERS = new WeakHashMap<>();
    private static final ArrayDeque<JSONObject> CONTEXT = new ArrayDeque<>();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "HongguoAdDiagnostics");
                thread.setDaemon(true);
                return thread;
            });
    private static final String SESSION = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            .format(new java.util.Date()) + "-" + Process.myPid();
    private static volatile Application application;
    private static volatile AdDiagnosticStore store;
    private static volatile boolean enabled = true;
    private static volatile String writerError = "";
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static WeakReference<Object> adapter = new WeakReference<>(null);
    private static Class<?> videoType;
    private static Class<?> gameType;
    private static Class<?> miniGameType;
    private static Class<?> oneStopType;
    private static Class<?> adapterType;
    private static String appVersion = "";
    private static boolean pendingSettings;
    private static boolean pendingBlockingSettings;
    private static boolean sampling;
    private static JSONObject latest = new JSONObject();
    private static String unknownKey = "";
    private static long unknownSince;
    private static String activeIncident = "";
    private static String incidentKey = "";
    private static long followUntil;
    private static String followIncident = "";
    private static long lastStatus;
    private static long repeated;
    private static long ioFailures;
    private static long lastPosition = -1;
    private static long positionChanged;
    private static String positionVideo = "";

    private AdDiagnostics() {
    }

    static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        videoType = optional("com.dragon.read.component.shortvideo.data.saas.video.d", loader);
        gameType = optional("wr3.f", loader);
        miniGameType = optional("com.dragon.read.feed.bookmall.subtab.model.VideoTabSingleMiniGameModel", loader);
        oneStopType = optional("com.bytedance.tomato.onestop.base.model.OneStopAdModel", loader);
        adapterType = optional("com.dragon.read.component.shortvideo.impl.v2.view.adapter.a", loader);
        hook("application", () -> HostSymbols.findAndHookMethod(Application.class,
                "attach", Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        initialize((Application) param.thisObject);
                    }
                }));
        String[] adapters = {"com.dragon.read.component.shortvideo.impl.v2.view.adapter.a",
                "com.dragon.read.component.shortvideo.impl.v2.view.adapter.f",
                "com.dragon.read.component.shortvideo.impl.fullscreen.d"};
        for (String name : adapters) {
            hook(name + ".r4", () -> HostSymbols.findAndHookMethod(name, loader,
                    "r4", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable()) {
                                observeAdapter(param.thisObject);
                            }
                        }
                    }));
        }
        if (oneStopType != null) {
            hook("one_stop_created", () -> XposedBridge.hookAllConstructors(oneStopType,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            source("one_stop_created", param.thisObject, false);
                        }
                    }));
        }
        Class<?> fragment = optional("androidx.fragment.app.Fragment", loader);
        if (fragment != null) {
            for (String lifecycle : new String[]{"onResume", "onPause"}) {
                hook("fragment." + lifecycle, () -> HostSymbols.hookAllMethods(fragment,
                        lifecycle, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (promotionClass(param.thisObject.getClass().getName())) {
                                    Object view = call(param.thisObject, "getView");
                                    if (view instanceof View) {
                                        if ("onResume".equals(lifecycle)) {
                                            source("ad_fragment_resumed", param.thisObject, false);
                                        }
                                        trackLayer((View) view, "onResume".equals(lifecycle)
                                                ? param.thisObject.getClass().getName() : null,
                                                token(param.thisObject));
                                    }
                                }
                            }
                        }));
            }
        }
        hook("view_layers", () -> HostSymbols.hookAllMethods(View.class,
                "onAttachedToWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String name = param.thisObject.getClass().getName();
                        if (promotionClass(name)) {
                            source("ad_view_attached", param.thisObject, false);
                            trackLayer((View) param.thisObject, name, token(param.thisObject));
                        }
                    }
                }));
        hook("dialog", () -> HostSymbols.findAndHookMethod(Dialog.class, "show",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Dialog dialog = (Dialog) param.thisObject;
                        if (enabled && dialog.getWindow() != null) {
                            MAIN.post(() -> {
                                View view = dialog.getWindow().getDecorView();
                                String name = dialog.getClass().getName();
                                if (promotionClass(name) || hasPromotionAction(view, new int[]{0}, 0)) {
                                    source("dialog_candidate", dialog, false);
                                    trackLayer(view, promotionClass(name) ? name : "UNKNOWN_DIALOG_ACTION", token(dialog));
                                }
                            });
                        }
                    }
                }));
        hook("navigation", () -> HostSymbols.hookAllMethods(Instrumentation.class,
                "execStartActivity", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!enabled) {
                            return;
                        }
                        for (Object argument : param.args) {
                            if (argument instanceof Intent) {
                                Intent intent = (Intent) argument;
                                String target = intent.getComponent() == null ? ""
                                        : intent.getComponent().getClassName();
                                if (promotionClass(target)) {
                                    JSONObject event = event("navigation_candidate");
                                    put(event, "target_class", target);
                                    put(event, "stack", stack());
                                    emit(event);
                                }
                                break;
                            }
                        }
                    }
                }));
        hook("document_result", () -> HostSymbols.findAndHookMethod(Activity.class,
                "onActivityResult", int.class, int.class, Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        AdDiagnosticUi.onActivityResult((Activity) param.thisObject,
                                (Integer) param.args[0], (Integer) param.args[1], (Intent) param.args[2]);
                    }
                }));
        hook("settings_request", () -> HostSymbols.hookAllMethods(Instrumentation.class,
                "callActivityOnNewIntent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 1 && param.args[1] instanceof Intent) {
                            consumeOpenIntent((Intent) param.args[1]);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (pendingSettings || pendingBlockingSettings) {
                            MAIN.post(() -> showPendingSettings(foreground.get()));
                        }
                    }
                }));
        Application current = android.app.AndroidAppHelper.currentApplication();
        if (current != null) {
            initialize(current);
        }
        XposedBridge.log(LOG + "passive diagnostic hooks installed");
    }

    static void initialize(Application app) {
        AdBlocking.initialize(app);
        initializeStore(app);
    }

    private static synchronized void initializeStore(Application app) {
        if (application != null || app.getBaseContext() == null) {
            return;
        }
        application = app;
        enabled = preferences().getBoolean("enabled", true);
        try {
            appVersion = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            appVersion = "unavailable";
        }
        store = new AdDiagnosticStore(new File(app.getFilesDir(), AdDiagnosticStore.DIRECTORY),
                SESSION, 2L * 1024 * 1024, 20L * 1024 * 1024, TimeUnit.DAYS.toMillis(14));
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                consumeOpenRequest(activity);
                if (promotionClass(activity.getClass().getName())) {
                    source("ad_activity_created", activity, false);
                }
            }

            @Override
            public void onActivityResumed(Activity activity) {
                foreground = new WeakReference<>(activity);
                consumeOpenRequest(activity);
                MAIN.post(() -> showPendingSettings(activity));
                schedule();
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (foreground.get() == activity) {
                    foreground.clear();
                    MAIN.removeCallbacks(SAMPLE);
                    sampling = false;
                    unknownKey = "";
                    JSONObject state = event("background");
                    put(state, "content_state", "BACKGROUND");
                    latest = state;
                    status();
                }
            }

            @Override public void onActivityDestroyed(Activity activity) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        });
        status();
    }

    private static void consumeOpenRequest(Activity activity) {
        consumeOpenIntent(activity.getIntent());
    }

    private static void showPendingSettings(Activity activity) {
        if ((pendingSettings || pendingBlockingSettings)
                && activity != null && foreground.get() == activity
                && !activity.isFinishing() && !activity.isDestroyed()
                && !activity.getClass().getName().endsWith("SplashActivity")) {
            if (pendingBlockingSettings) {
                pendingBlockingSettings = false;
                AdBlocking.show(activity);
            } else {
                pendingSettings = false;
                AdDiagnosticUi.show(activity);
            }
        }
    }

    private static void consumeOpenIntent(Intent intent) {
        try {
            if (intent != null && intent.getBooleanExtra(OPEN_SETTINGS, false)) {
                intent.removeExtra(OPEN_SETTINGS);
                pendingSettings = true;
            }
            if (intent != null && intent.getBooleanExtra(AdBlocking.OPEN_SETTINGS, false)) {
                intent.removeExtra(AdBlocking.OPEN_SETTINGS);
                pendingBlockingSettings = true;
            }
        } catch (RuntimeException ignored) {
            // Unrelated application extras are not diagnostic input.
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean value) {
        enabled = value;
        preferences().edit().putBoolean("enabled", value).apply();
        CONTEXT.clear();
        activeIncident = "";
        incidentKey = "";
        unknownKey = "";
        followUntil = 0;
        followIncident = "";
        if (value) {
            schedule();
        } else {
            MAIN.removeCallbacks(SAMPLE);
            sampling = false;
        }
        status();
    }

    private static SharedPreferences preferences() {
        return application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void observeAdapter(Object value) {
        observeAdapter(value, null);
    }

    static void observeAdapter(Object value, Object controller) {
        if (enabled && value != null) {
            MAIN.post(() -> {
                Object current = value;
                if (adapterType != null && !adapterType.isInstance(current)) {
                    Object dataApi = call(current, "a");
                    if (adapterType.isInstance(dataApi)) {
                        current = dataApi;
                    }
                }
                if (controller != null) {
                    CONTROLLERS.put(current, new WeakReference<>(controller));
                }
                adapter = new WeakReference<>(current);
                schedule();
                scheduleFastSample();
            });
        }
    }

    static void sourceList(String reason, List<?> values) {
        if (!enabled || values == null) {
            return;
        }
        try {
            int count = 0;
            for (Object model : values) {
                if (model != null && !"normal".equals(kind(model))) {
                    source(reason, model, false);
                    if (++count == 16) {
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            DROPPED.incrementAndGet();
        }
    }

    static void source(String reason, Object model, boolean blocked) {
        if (!enabled || application == null) {
            return;
        }
        try {
            String token = token(model);
            String dedup = reason + "/" + token;
            String id;
            synchronized (SOURCES) {
                long now = SystemClock.elapsedRealtime();
                Long previous = SOURCE_TIMES.put(dedup, now);
                if (previous != null && now - previous < 250) {
                    return;
                }
                id = SESSION + ":" + SEQUENCE.incrementAndGet();
                SOURCES.put(dedup, id);
                if (model != null) {
                    SOURCES.put(token, id);
                }
            }
            JSONObject event = event("candidate_source");
            put(event, "source_id", id);
            put(event, "reason", reason);
            put(event, "model_token", token);
            put(event, "model_class", name(model));
            put(event, "model_kind", kind(model));
            put(event, "blocked", blocked);
            Object ad = field(model, "oneStopAdModel");
            if (ad == null) {
                ad = model;
            }
            for (String key : new String[]{"adChannel", "position", "positionDetail", "forcedViewingTime"}) {
                scalar(event, key, field(ad, key));
            }
            scalar(event, "cardId", field(field(ad, "adData"), "cardId"));
            JSONArray trace = stack();
            String fingerprint = reason + "/" + name(model) + "/" + trace.toString().hashCode();
            put(event, "stack_ref", fingerprint);
            synchronized (STACKS) {
                if (STACKS.add(fingerprint)) {
                    put(event, "stack", trace);
                    if (STACKS.size() > 128) {
                        STACKS.remove(STACKS.iterator().next());
                    }
                }
            }
            emit(event);
        } catch (Throwable error) {
            DROPPED.incrementAndGet();
        }
    }

    static void mark(Runnable success, java.util.function.Consumer<Exception> onError) {
        JSONObject event = event("user_marked");
        followIncident = SESSION + ":" + event.optLong("sequence");
        put(event, "incident_id", followIncident);
        put(event, "snapshot", latest);
        put(event, "context_before", new JSONArray(new ArrayList<>(CONTEXT)));
        withStore(value -> {
            value.append(event.toString(), System.currentTimeMillis());
            success.run();
        }, onError);
        followUntil = SystemClock.elapsedRealtime() + 10000;
    }

    static void checkRecording(Runnable success, java.util.function.Consumer<Exception> onError) {
        JSONObject event = event("diagnostic_self_test");
        put(event, "synthetic", true);
        put(event, "note", "测试记录，不代表真实广告");
        withStore(value -> {
            value.append(event.toString(), System.currentTimeMillis());
            success.run();
        }, onError);
    }

    static void export(Activity activity) {
        status();
        AdDiagnosticUi.export(activity);
    }

    interface StoreAction {
        void run(AdDiagnosticStore value) throws Exception;
    }

    static void withStore(StoreAction action, java.util.function.Consumer<Exception> onError) {
        boolean accepted = enqueue(() -> {
            try {
                if (store == null) {
                    throw new java.io.IOException("诊断目录尚未初始化");
                }
                action.run(store);
            } catch (Exception error) {
                fail(error);
                onError.accept(error);
            }
        });
        if (!accepted) {
            onError.accept(new java.io.IOException("记录队列繁忙，请稍后导出"));
        }
    }

    static String summary() {
        return "默认记录到红果私有目录；保留最多14天或20MiB。\n"
                + "正常状态仅留少量摘要，广告和未知页面保存上下文；不会自动跳过未知内容。\n"
                + "已丢弃记录：" + DROPPED.get()
                + (writerError.isEmpty() ? "" : "\n记录异常：" + writerError);
    }

    private static final Runnable SAMPLE = new Runnable() {
        @Override
        public void run() {
            sampling = false;
            Activity activity = foreground.get();
            if (!enabled || activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            try {
                sample(activity);
            } catch (Throwable error) {
                HOOKS.put("sample", error.getClass().getSimpleName());
            }
            schedule();
        }
    };

    private static final Runnable FAST_SAMPLE = () -> {
        Activity current = foreground.get();
        if (enabled && current != null && !current.isFinishing() && !current.isDestroyed()) {
            try {
                sample(current);
            } catch (Throwable error) {
                HOOKS.put("sample", error.getClass().getSimpleName());
            }
        }
    };

    private static void scheduleFastSample() {
        MAIN.removeCallbacks(FAST_SAMPLE);
        MAIN.postDelayed(FAST_SAMPLE, 150);
    }

    private static void schedule() {
        if (enabled && foreground.get() != null && !sampling) {
            sampling = true;
            MAIN.postDelayed(SAMPLE, 1500);
        }
    }

    private static void sample(Activity activity) {
        long now = SystemClock.elapsedRealtime();
        Object host = adapter.get();
        Object model = call(host, "getCurrentData");
        Object holder = call(host, "V0");
        Object holderView = field(holder, "itemView");
        View view = holderView instanceof View ? (View) holderView : null;
        boolean visible = visible(view) && view.getRootView() == activity.getWindow().getDecorView();
        JSONObject sample = event("sample");
        put(sample, "activity", activity.getClass().getName());
        put(sample, "activity_instance", System.identityHashCode(activity));
        put(sample, "screen_orientation", activity.getResources().getConfiguration().orientation);
        put(sample, "screen_rotation", activity.getWindowManager().getDefaultDisplay().getRotation());
        put(sample, "adapter", name(host));
        put(sample, "holder", name(holder));
        put(sample, "model", name(model));
        put(sample, "model_token", token(model));
        scalar(sample, "selected_index", field(host, "D"));
        put(sample, "holder_visible", visible);
        if (view != null) {
            put(sample, "container_width", view.getWidth());
            put(sample, "container_height", view.getHeight());
        }
        Object data = visible ? call(host, "q0") : null;
        String vid = text(field(data, "vid"));
        scalar(sample, "vid", field(data, "vid"));
        scalar(sample, "series_id", field(data, "seriesId"));
        scalar(sample, "episode", field(data, "vidIndex"));
        scalar(sample, "duration_seconds", field(data, "duration"));
        scalar(sample, "content_type", field(data, "contentType"));
        Object detail = visible ? call(host, "r0") : null;
        if (name(detail).endsWith(".AlbumDetailModel")) {
            scalar(sample, "album_id", call(detail, "c"));
        }
        WeakReference<Object> reference = CONTROLLERS.get(host);
        Object controller = visible && reference != null ? reference.get() : null;
        if (visible && controller == null) {
            controller = call(host, "n");
        }
        Object position = call(controller, "V");
        scalar(sample, "position_ms", position);
        scalar(sample, "player_vertical", call(controller, "isVertical"));
        put(sample, "controller", name(controller));
        String content;
        if (!visible) {
            content = "NOT_VISIBLE";
        } else if (!activity.hasWindowFocus()) {
            content = "WINDOW_OVERLAY";
        } else if ("normal".equals(kind(model))) {
            content = vid.isEmpty() ? "CONTENT_LOADING" : "NORMAL_CONTENT";
            if (position instanceof Number && !vid.isEmpty()) {
                long value = ((Number) position).longValue();
                if (!vid.equals(positionVideo) || value != lastPosition) {
                    positionChanged = now;
                } else if (now - positionChanged > 4000) {
                    content = "CONTENT_IDLE_PAUSED_BUFFERING_OR_ENDED";
                }
                positionVideo = vid;
                lastPosition = value;
            }
        } else if ("live".equals(kind(model))) {
            content = "LIVE_RECOMMENDATION";
        } else if (!"unknown".equals(kind(model))) {
            content = "NON_CONTENT_PROMOTION";
        } else {
            content = "UNKNOWN_MODEL";
        }
        String promotion = "NONE";
        String reason = "";
        String layerToken = "";
        if (visible && ("ad".equals(kind(model)) || "game".equals(kind(model)))) {
            promotion = "CONFIRMED";
            reason = "VISIBLE_" + kind(model).toUpperCase(Locale.ROOT) + "_MODEL";
        }
        for (Map.Entry<View, String> layer : new ArrayList<>(LAYERS.entrySet())) {
            if (visible(layer.getKey()) && layer.getKey().hasWindowFocus()) {
                boolean known = !layer.getValue().startsWith("UNKNOWN_");
                if (known || !"CONFIRMED".equals(promotion)) {
                    promotion = known ? "CONFIRMED" : "UNKNOWN";
                    reason = layer.getValue();
                    String matched = LAYER_SOURCES.get(layer.getKey());
                    layerToken = matched == null ? "" : matched;
                }
                if (known) {
                    break;
                }
            }
        }
        if (promotionClass(activity.getClass().getName())) {
            promotion = "CONFIRMED";
            reason = "AD_ACTIVITY";
            layerToken = token(activity);
        }
        String key = token(activity) + "/" + token(model) + "/" + reason
                + "/" + activity.getResources().getConfiguration().orientation;
        if (!"CONFIRMED".equals(promotion)
                && ("UNKNOWN_MODEL".equals(content) || "UNKNOWN".equals(promotion))) {
            if (!key.equals(unknownKey)) {
                unknownKey = key;
                unknownSince = now;
            }
            if (now - unknownSince >= 1500) {
                promotion = "UNKNOWN";
                if (reason.isEmpty()) {
                    reason = "MODEL_UNKNOWN";
                }
            } else if ("UNKNOWN".equals(promotion)) {
                promotion = "PENDING_UNKNOWN";
            }
        } else {
            unknownKey = "";
        }
        put(sample, "content_state", content);
        put(sample, "promotion_state", promotion);
        put(sample, "reason", reason);
        String source = SOURCES.get(layerToken.isEmpty() ? token(model) : layerToken);
        String association = source == null ? "UNDETERMINED" : "PROCESS_OBJECT_TOKEN";
        if (source == null && field(model, "oneStopAdModel") != null) {
            source = SOURCES.get(token(field(model, "oneStopAdModel")));
            if (source != null) {
                association = "WRAPPED_PROCESS_OBJECT_TOKEN";
            }
        }
        put(sample, "source_id", source == null ? "" : source);
        put(sample, "association", association);
        latest = sample;
        while (!CONTEXT.isEmpty() && (CONTEXT.size() >= 40
                || now - CONTEXT.peekFirst().optLong("elapsed_ms") > 60000)) {
            CONTEXT.removeFirst();
        }
        boolean suspicious = "CONFIRMED".equals(promotion) || "UNKNOWN".equals(promotion);
        String incident = key + "/" + token(model) + "/" + promotion;
        if (suspicious && !incident.equals(incidentKey)) {
            JSONObject event = event("CONFIRMED".equals(promotion) ? "promotion_visible" : "unknown_visible");
            activeIncident = SESSION + ":" + SEQUENCE.get();
            followIncident = activeIncident;
            put(event, "incident_id", activeIncident);
            put(event, "snapshot", sample);
            put(event, "context_before", new JSONArray(new ArrayList<>(CONTEXT)));
            put(event, "view_structure", structure(activity.getWindow().getDecorView()));
            emit(event);
            incidentKey = incident;
            followUntil = now + 10000;
        } else if (suspicious) {
            repeated++;
        } else if (!activeIncident.isEmpty()) {
            JSONObject event = event("content_recovered");
            put(event, "incident_id", activeIncident);
            put(event, "snapshot", sample);
            emit(event);
            activeIncident = "";
            incidentKey = "";
        }
        if (now < followUntil) {
            JSONObject event = event("context_after");
            put(event, "incident_id", followIncident);
            put(event, "snapshot", sample);
            emit(event);
        }
        CONTEXT.addLast(sample);
        if (now - lastStatus > 60000) {
            lastStatus = now;
            status();
        }
    }

    private static void trackLayer(View view, String kind, String sourceToken) {
        WeakReference<View> reference = new WeakReference<>(view);
        MAIN.post(() -> {
            View current = reference.get();
            if (current != null) {
                if (kind == null) {
                    LAYERS.remove(current);
                    LAYER_SOURCES.remove(current);
                } else {
                    LAYERS.put(current, kind);
                    LAYER_SOURCES.put(current, sourceToken);
                }
                schedule();
                scheduleFastSample();
            }
        });
    }

    private static boolean visible(View view) {
        return view != null && view.isShown() && view.getWindowVisibility() == View.VISIBLE
                && view.getGlobalVisibleRect(new Rect());
    }

    private static boolean hasPromotionAction(View view, int[] count, int depth) {
        if (view == null || ++count[0] > 160 || depth > 8) {
            return false;
        }
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString().trim();
            if (text.equals("点击进入游戏") || text.equals("立即下载")
                    || text.equals("跳过广告") || text.equals("立即玩")) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && count[0] < 160; i++) {
                if (hasPromotionAction(group.getChildAt(i), count, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JSONArray structure(View root) {
        JSONArray result = new JSONArray();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty() && result.length() < 96) {
            View view = pending.removeFirst();
            JSONObject item = new JSONObject();
            put(item, "class", name(view));
            put(item, "id", view.getId());
            put(item, "visible", view.isShown());
            put(item, "clickable", view.isClickable());
            Rect bounds = new Rect();
            view.getGlobalVisibleRect(bounds);
            put(item, "bounds", bounds.toShortString());
            result.put(item);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && pending.size() < 96; i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
        return result;
    }

    private static String kind(Object value) {
        if (value == null) {
            return "unknown";
        }
        if ((gameType != null && gameType.isInstance(value))
                || (miniGameType != null && miniGameType.isInstance(value))) {
            return "game";
        }
        String name = name(value);
        if (AdBlockPolicy.pageCategory(name) == AdBlockPolicy.LIVE) {
            return "live";
        }
        if ((oneStopType != null && oneStopType.isInstance(value)) || promotionClass(name)) {
            return "ad";
        }
        if ((videoType != null && videoType.isInstance(value))
                || name.endsWith(".SaasVideoData") || name.endsWith(".SaasVideoDataWrapper")) {
            return "normal";
        }
        return "unknown";
    }

    private static boolean promotionClass(String name) {
        return name.startsWith("com.dragon.read.ad.")
                || name.startsWith("com.bytedance.tomato.onestop.base.model.")
                || name.contains("DirectGameInnerFlowLynxView")
                || name.contains("DirectGameCommonNativeCardView")
                || name.contains("LandscapeOneStopAdModel");
    }

    private static JSONObject event(String type) {
        JSONObject event = new JSONObject();
        put(event, "event", type);
        put(event, "session", SESSION);
        put(event, "sequence", SEQUENCE.incrementAndGet());
        put(event, "wall_ms", System.currentTimeMillis());
        put(event, "elapsed_ms", SystemClock.elapsedRealtime());
        put(event, "pid", Process.myPid());
        put(event, "tid", Process.myTid());
        put(event, "module", BuildConfig.VERSION_NAME);
        put(event, "module_code", BuildConfig.VERSION_CODE);
        put(event, "app_version", appVersion);
        return event;
    }

    private static void emit(JSONObject event) {
        if (!enabled || store == null) {
            return;
        }
        enqueue(() -> {
            try {
                store.append(event.toString(), System.currentTimeMillis());
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private static void status() {
        if (store == null) {
            return;
        }
        JSONObject state = event("recorder_status");
        put(state, "enabled", enabled);
        put(state, "sdk", Build.VERSION.SDK_INT);
        put(state, "app_package", TargetApp.current());
        put(state, "hooks", new JSONObject(HOOKS));
        put(state, "dropped", DROPPED.get());
        put(state, "repeated_samples", repeated);
        put(state, "writer_error", writerError);
        put(state, "snapshot", latest);
        enqueue(() -> {
            try {
                store.writeStatus(state.toString(), System.currentTimeMillis());
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private static boolean enqueue(Runnable action) {
        try {
            WRITER.execute(action);
            return true;
        } catch (RejectedExecutionException error) {
            DROPPED.incrementAndGet();
            return false;
        }
    }

    private static void fail(Exception error) {
        writerError = error.getClass().getSimpleName() + ": " + text(error.getMessage());
        if (ioFailures++ == 0) {
            XposedBridge.log(LOG + "diagnostic writer unavailable: " + writerError);
        }
    }

    private static JSONArray stack() {
        JSONArray result = new JSONArray();
        for (StackTraceElement element : new Throwable().getStackTrace()) {
            if (!element.getClassName().startsWith(AdDiagnostics.class.getName())
                    && result.length() < 20) {
                result.put(element.toString());
            }
        }
        return result;
    }

    private interface HookAction { void run() throws Throwable; }

    private static void hook(String name, HookAction action) {
        try {
            action.run();
            HOOKS.put(name, "installed");
        } catch (Throwable error) {
            HOOKS.put(name, error.getClass().getSimpleName());
            XposedBridge.log(LOG + "optional hook unavailable: " + name);
        }
    }

    private static Class<?> optional(String name, ClassLoader loader) {
        try {
            return HostSymbols.findClass(name, loader);
        } catch (Throwable error) {
            HOOKS.put(name, "class_missing");
            return null;
        }
    }

    private static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return HostSymbols.callMethod(target, method);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object field(Object target, String key) {
        if (target == null) {
            return null;
        }
        try {
            return HostSymbols.getObjectField(target, key);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String name(Object value) {
        return value == null ? "" : value.getClass().getName();
    }

    private static String token(Object value) {
        return value == null ? "no_model" : SESSION + "/" + System.identityHashCode(value);
    }

    private static void scalar(JSONObject json, String key, Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?>) {
            put(json, key, value instanceof String || value instanceof Enum<?> ? text(value) : value);
        }
    }

    private static String text(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (org.json.JSONException ignored) {
            // Only fixed keys and bounded primitive values are supplied.
        }
    }
}
