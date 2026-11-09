package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Optional, host-private interception before a page enters the player or an ad is presented. */
final class AdBlocking {
    static final String OPEN_SETTINGS = "hongguo_open_ad_blocking";
    private static final String PREFS = "hongguo_cache_1080_ad_blocking";
    private static final String LOG = "[HongguoCache1080/AdBlocking] ";
    private static final int[] CATEGORIES = {
            AdBlockPolicy.EARLY_CONFIG, AdBlockPolicy.EARLY_REQUEST,
            AdBlockPolicy.PAUSE, AdBlockPolicy.GAME, AdBlockPolicy.LIVE, AdBlockPolicy.UNKNOWN,
            AdBlockPolicy.NO_AD_ENTITLEMENT};
    private static final String[] KEYS = {"early_config", "early_request",
            "suspected_pause", "suspected_game", "suspected_live", "unknown_page", "no_ad_entitlement"};
    private static final String[] LABELS = {
            "提前关闭切集广告配置", "提前拦截切集广告请求",
            "疑似暂停广告", "疑似游戏广告", "疑似直播广告", "未知切集页（激进）",
            "短剧免广告权益（实验）"};
    private static final String[] DESCRIPTIONS = {
            "关闭已适配的普通、横屏中插和短剧信息流广告判断；不改写服务器配置缓存。",
            "只拒绝已确认的两类切集广告位，保留未知广告位和主动激励入口。",
            "关闭已定位的暂停广告展示入口，保留正常暂停与继续。",
            "补充过滤游戏模型和广告试玩页跳转；原有已确认游戏过滤始终保留。",
            "过滤切集列表中的直播推荐模型，可能同时移除非付费直播推荐。",
            "过滤尚未识别的非空播放列表条目，可能误删新类型内容；不按卡顿或无进度判断。",
            "仅改变本地短剧免广告判断，不改变账号会员；建议单独开启作对照，异常时关闭。"};
    private static volatile int enabled;
    private static SharedPreferences preferences;
    private static Class<?> normalVideoWrapper;
    private static final Map<Switch, Integer> OPTIONS = new WeakHashMap<>();
    private static ClassLoader hostLoader;
    private static final AtomicInteger EARLY_ATTEMPTS = new AtomicInteger();
    private static final AtomicBoolean READY_HOOK_ATTEMPTED = new AtomicBoolean();
    private static volatile boolean applicationReady;
    private static volatile boolean earlyVersionChecked;
    private static boolean earlyVersionSupported;
    private static final AtomicLongArray GATE_COUNTS = new AtomicLongArray(6);
    private static final AtomicLongArray GATE_LAST_LOG = new AtomicLongArray(6);

    private AdBlocking() { }

    static void initialize(Application application) {
        if (preferences == null && application.getBaseContext() != null) {
            preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int flags = 0;
            for (int index = 0; index < CATEGORIES.length; index++) {
                if (preferences.getBoolean(KEYS[index], false)) {
                    flags |= CATEGORIES[index];
                }
            }
            enabled = flags;
            XposedBridge.log(LOG + "settings loaded: flags=" + enabled);
        }
        int early = enabled & (AdBlockPolicy.EARLY_CONFIG | AdBlockPolicy.EARLY_REQUEST
                | AdBlockPolicy.NO_AD_ENTITLEMENT);
        if (early == 0 || hostLoader == null) {
            return;
        }
        if (!earlyVersionChecked) {
            try {
                PackageInfo info = application.getPackageManager()
                        .getPackageInfo(TargetApp.current(), 0);
                long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                earlyVersionSupported = AdBlockPolicy.supportsEarlyRules(
                        TargetApp.current(), info.versionName, code);
            } catch (Exception error) {
                XposedBridge.log(LOG + "early rules version check failed; native behavior retained");
                XposedBridge.log(error);
            }
            earlyVersionChecked = true;
            if (!earlyVersionSupported) {
                XposedBridge.log(LOG + "early rules skipped for unsupported host version");
            }
        }
        if (earlyVersionSupported) {
            if (!applicationReady) {
                deferUntilApplicationCreated();
                return;
            }
            installEarlyRules(enabled & (AdBlockPolicy.EARLY_CONFIG | AdBlockPolicy.EARLY_REQUEST
                    | AdBlockPolicy.NO_AD_ENTITLEMENT));
        }
    }

    private static void deferUntilApplicationCreated() {
        if (!READY_HOOK_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        try {
            HostSymbols.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                    Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.args[0];
                            if (param.hasThrowable() || applicationReady
                                    || !TargetApp.current().equals(app.getPackageName())) {
                                return;
                            }
                            applicationReady = true;
                            XposedBridge.log(LOG + "host Application.onCreate completed; installing early rules");
                            initialize(app);
                        }
                    });
            XposedBridge.log(LOG + "early rules deferred until Application.onCreate completion");
        } catch (Throwable error) {
            XposedBridge.log(LOG + "application readiness hook unavailable; native behavior retained until settings open");
            XposedBridge.log(error);
        }
    }

    static void install(ClassLoader loader) {
        hostLoader = loader;
        try {
            normalVideoWrapper = HostSymbols.findClass(
                    "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDataWrapper", loader);
        } catch (Throwable error) {
            XposedBridge.log(LOG + "normal video wrapper unavailable; unknown-page blocking disabled");
        }
        try {
            Class<?> pause = HostSymbols.findClass(
                    "com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl", loader);
            XC_MethodHook gate = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (AdBlockPolicy.blocks(AdBlockPolicy.PAUSE, enabled)) {
                        param.setResult(false);
                        AdDiagnostics.source("experimental_pause_gate_blocked", param.thisObject, true);
                    }
                }
            };
            HostSymbols.findAndHookMethod(pause, "enablePauseAd", gate);
            HostSymbols.findAndHookMethod(pause, "canShowPauseAd",
                    HostSymbols.findClass("gq4.i", loader), gate);
            XposedBridge.log(LOG + "pause gates installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG + "pause gates unavailable; native behavior retained");
            XposedBridge.log(error);
        }
        try {
            XC_MethodHook navigation = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!AdBlockPolicy.blocks(AdBlockPolicy.GAME, enabled)) {
                        return;
                    }
                    for (int index = 0; index < param.args.length; index++) {
                        Object argument = param.args[index];
                        if (argument instanceof Intent && blockIntent((Intent) argument)) {
                            param.setResult(null);
                            return;
                        }
                        if (argument instanceof Intent[]) {
                            Intent[] intents = (Intent[]) argument;
                            List<Intent> retained = new ArrayList<>();
                            for (Intent intent : intents) {
                                if (!blockIntent(intent)) {
                                    retained.add(intent);
                                }
                            }
                            if (retained.size() != intents.length) {
                                if (retained.isEmpty()) {
                                    param.setResult(null);
                                } else {
                                    param.args[index] = retained.toArray(new Intent[0]);
                                }
                            }
                            return;
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", navigation);
            XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivities", navigation);
            XposedBridge.log(LOG + "playable navigation hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG + "playable navigation unavailable; native behavior retained");
            XposedBridge.log(error);
        }
    }

    private static void installEarlyRules(int requested) {
        int pending = requested & ~EARLY_ATTEMPTS.getAndUpdate(previous -> previous | requested);
        if ((pending & AdBlockPolicy.EARLY_CONFIG) != 0) {
            String owner = "com.dragon.read.reader.ad.experiment.ExperimentUtil";
            installBooleanGate(owner, "t0", AdBlockPolicy.EARLY_CONFIG, false, 0,
                    "early_config_short_series", false);
            installBooleanGate(owner, "s0", AdBlockPolicy.EARLY_CONFIG, false, 1,
                    "early_config_landscape", false);
            installBooleanGate(owner, "p", AdBlockPolicy.EARLY_CONFIG, false, 2,
                    "early_config_multi_series", false);
        }
        if ((pending & AdBlockPolicy.EARLY_REQUEST) != 0) {
            for (String owner : new String[]{"com.dragon.read.component.biz.impl.NsAdImpl", "tc3.a"}) {
                try {
                    HostSymbols.findAndHookMethod(owner, hostLoader, "checkAdAvailable",
                            String.class, String.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    String position = (String) param.args[0];
                                    if (AdBlockPolicy.blockPosition(position, enabled)) {
                                        param.setResult(false);
                                        recordGate("short_series_ad".equals(position) ? 3 : 4,
                                                "early_position_" + position, true);
                                    }
                                }
                            });
                    XposedBridge.log(LOG + "early position gate installed: " + HostSymbols.className(owner));
                } catch (Throwable error) {
                    XposedBridge.log(LOG + "early position gate unavailable; native behavior retained: " + owner);
                    XposedBridge.log(error);
                }
            }
        }
        if ((pending & AdBlockPolicy.NO_AD_ENTITLEMENT) != 0) {
            installBooleanGate("com.dragon.read.component.biz.impl.privilege.PrivilegeManager",
                    "hasNoAdForShortSeries", AdBlockPolicy.NO_AD_ENTITLEMENT, true, 5,
                    "experimental_short_series_no_ad_entitlement", true);
        }
    }

    private static void installBooleanGate(String owner, String method, int category,
            boolean value, int counter, String reason, boolean before) {
        try {
            Class<?> type = HostSymbols.findClass(owner, hostLoader);
            XC_MethodHook gate = new XC_MethodHook() {
                private void apply(MethodHookParam param) {
                    if (!AdBlockPolicy.blocks(category, enabled)) {
                        return;
                    }
                    boolean changed = before || !Boolean.valueOf(value).equals(param.getResult());
                    if (before || !param.hasThrowable()) {
                        param.setResult(value);
                        recordGate(counter, reason, changed);
                    }
                }
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (before) apply(param);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!before) apply(param);
                }
            };
            HostSymbols.findAndHookMethod(type, method, gate);
            XposedBridge.log(LOG + "early boolean gate installed: " + type.getName() + "#" + method);
        } catch (Throwable error) {
            XposedBridge.log(LOG + "early boolean gate unavailable; native behavior retained: " + owner + "#" + method);
            XposedBridge.log(error);
        }
    }

    private static void recordGate(int index, String reason, boolean changed) {
        long count = GATE_COUNTS.incrementAndGet(index);
        long now = SystemClock.elapsedRealtime();
        long previous = GATE_LAST_LOG.get(index);
        if ((count != 1 && now - previous < 30_000L)
                || !GATE_LAST_LOG.compareAndSet(index, previous, now)) {
            return;
        }
        AdDiagnostics.source(reason + "_count_" + count, null, changed);
        XposedBridge.log(LOG + reason + ": count=" + count + ", intercepted=" + changed);
    }

    private static boolean blockIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        ComponentName component = intent.getComponent();
        if (component == null || !TargetApp.current().equals(component.getPackageName())) {
            return false;
        }
        try {
            if (AdBlockPolicy.playableLanding(component.getClassName(), intent.getStringExtra("page_type"))) {
                AdDiagnostics.source("experimental_game_playable_blocked", null, true);
                XposedBridge.log(LOG + "blocked playable navigation");
                return true;
            }
        } catch (RuntimeException ignored) {
            // Unknown extras are not sufficient evidence to block navigation.
        }
        return false;
    }

    static boolean blockPage(Object page) {
        if (enabled == 0 || page == null) {
            return false;
        }
        int category = AdBlockPolicy.pageCategory(page.getClass().getName(),
                normalVideoWrapper != null && normalVideoWrapper.isInstance(page));
        if (category == AdBlockPolicy.UNKNOWN && normalVideoWrapper == null) {
            return false;
        }
        if (!AdBlockPolicy.blocks(category, enabled)) {
            return false;
        }
        AdDiagnostics.source("experimental_page_blocked_" + category, page, true);
        return true;
    }

    static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        applicationReady = true;
        initialize(activity.getApplication());
        if (preferences == null) {
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        addSettings(activity, content);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("广告拦截设置")
                .setView(scroll).setPositiveButton("关闭", null).show();
    }

    static void addSettings(Activity activity, LinearLayout content) {
        applicationReady = true;
        initialize(activity.getApplication());
        if (preferences == null) {
            return;
        }
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        caption(activity, content, "以下实验项默认关闭，两个设置入口共用状态。修改立即保存；异常时关闭对应项，并重新打开当前剧。原有已确认广告/游戏拦截保留。", padding);
        for (int index = 0; index < CATEGORIES.length; index++) {
            if (index == 0 || index == 2 || index == 6) {
                TextView title = new TextView(activity);
                title.setText(index == 0 ? "提前拦截" : index == 2 ? "疑似页面" : "会员实验");
                title.setTextSize(16);
                title.setPadding(0, padding / 2, 0, padding / 4);
                content.addView(title);
            }
            final int category = CATEGORIES[index];
            final String key = KEYS[index];
            Switch option = new Switch(activity);
            option.setText(LABELS[index]);
            option.setMinHeight(Math.round(48 * activity.getResources().getDisplayMetrics().density));
            option.setChecked(AdBlockPolicy.blocks(category, enabled));
            content.addView(option);
            OPTIONS.put(option, category);
            caption(activity, content, DESCRIPTIONS[index], padding);
            option.setOnCheckedChangeListener((button, checked) -> {
                int flags = checked ? enabled | category : enabled & ~category;
                if (flags == enabled) {
                    return;
                }
                enabled = flags;
                preferences.edit().putBoolean(key, checked).apply();
                initialize(activity.getApplication());
                for (Map.Entry<Switch, Integer> binding : OPTIONS.entrySet()) {
                    Switch other = binding.getKey();
                    if (other != null) {
                        other.setChecked(AdBlockPolicy.blocks(binding.getValue(), enabled));
                    }
                }
                AdDiagnostics.source("experimental_settings_flags_" + enabled, null, false);
                XposedBridge.log(LOG + "settings changed: flags=" + enabled);
            });
        }
    }

    private static void caption(Activity activity, LinearLayout content, String value, int padding) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(14);
        text.setPadding(0, 0, 0, padding / 2);
        content.addView(text);
    }
}
