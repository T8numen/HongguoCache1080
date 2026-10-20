package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Playback-only settings and controls injected into the target process. */
final class PlaybackEnhancements {
    private static final String LOG_PREFIX = "[HongguoCache1080/Playback] ";
    private static final String PREFERENCES_NAME = "hongguo_cache_1080_playback_settings";
    private static final String KEY_VOLUME_ENABLED = "global_volume_enabled";
    private static final String KEY_VOLUME_PERCENT = "global_volume_percent";
    private static final String KEY_BRIGHTNESS_ENABLED = "global_brightness_enabled";
    private static final String KEY_BRIGHTNESS_PERCENT = "global_brightness_percent";
    private static final String KEY_PREVENT_COMMENT_REPLAY = "prevent_comment_replay";
    private static final String KEY_CLEAN_SCREEN_ENHANCE = "clean_screen_enhance";
    private static final String KEY_INTRO_PREFIX = "skip_intro_seconds:";
    private static final String KEY_OUTRO_PREFIX = "skip_outro_seconds:";
    private static final String KEY_INTRO_TENTHS_PREFIX = "skip_intro_tenths:";
    private static final String KEY_OUTRO_TENTHS_PREFIX = "skip_outro_tenths:";
    private static final long COMMENT_GUARD_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long MONITOR_INTERVAL_MS = 400L;
    private static final int MAX_SKIP_TENTHS = 36000;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean PLAYER_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SHARE_ENTRY_LOGGED = new AtomicBoolean(false);
    private static final Set<View> SHARE_LONG_PRESS_TARGETS =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Set<View> PROGRESS_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Map<Activity, Float> ORIGINAL_BRIGHTNESS = new WeakHashMap<>();
    private static final Map<View, Drawable> ORIGINAL_PROGRESS_FOREGROUNDS =
            new WeakHashMap<>();
    private static final Map<View, String> PROGRESS_MARKER_STATES = new WeakHashMap<>();
    private static final Map<View, Float> CLEAN_SCREEN_DIMMED_VIEWS = new WeakHashMap<>();

    private static WeakReference<Context> applicationContext = new WeakReference<>(null);
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static WeakReference<Object> hostAdapter = new WeakReference<>(null);
    private static WeakReference<Object> playerController = new WeakReference<>(null);
    private static WeakReference<Activity> enhancedClearScreenActivity = new WeakReference<>(null);
    private static View cleanScreenToastView;
    private static View skipToastView;

    private static String currentSeriesId = "";
    private static String currentVideoId = "";
    private static long currentDurationMs;
    private static long lastPositionMs;
    private static String skipStateVideoId = "";
    private static boolean introApplied;
    private static boolean outroApplied;
    private static boolean introToastShown;
    private static boolean outroToastShown;
    private static int introSeekAttempts;
    private static int outroSeekAttempts;

    private static volatile boolean commentGuardActive;
    private static boolean commentGuardSawNearEnd;
    private static volatile String commentGuardVideoId = "";
    private static volatile long commentGuardUntilElapsedRealtime;
    private static volatile WeakReference<Object> commentDialogHelper = new WeakReference<>(null);

    private static boolean originalVolumeCaptured;
    private static int originalMusicVolume;
    private static boolean monitorScheduled;

    private PlaybackEnhancements() {
    }

    static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        installPlayerHook(classLoader);
        installShareEntryHook(classLoader);
        installCommentHook(classLoader);
        installCommentCompletionHooks(classLoader);
        installProgressMarkerHook(classLoader);
        installSeriesAdBlockHook(classLoader);
        installLandscapeSeriesAdBlockHook(classLoader);
        installActivityHooks();
    }

    private static void installSeriesAdBlockHook(ClassLoader classLoader) {
        try {
            Class<?> adModelClass = XposedHelpers.findClass(
                    "com.bytedance.tomato.onestop.base.model.OneStopAdModel", classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.ad.onestop.shortseries.cache.ShortSeriesAdOneStopCacheManager",
                    classLoader,
                    "p",
                    int.class,
                    adModelClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                            XposedBridge.log(LOG_PREFIX + "blocked short-series ad insertion");
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "short-series ad insertion hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "short-series ad insertion hook unavailable");
            XposedBridge.log(error);
        }
    }

    private static void installLandscapeSeriesAdBlockHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.ad.onestop.serieslandscape.cache.SeriesLandscapeAdOneStopCacheManager",
                    classLoader,
                    "n",
                    int.class,
                    Long.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                            XposedBridge.log(LOG_PREFIX + "blocked landscape-series ad insertion");
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "landscape-series ad insertion hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "landscape-series ad insertion hook unavailable");
            XposedBridge.log(error);
        }
    }

    private static void installProgressMarkerHook(ClassLoader classLoader) {
        try {
            Class<?> seekBarClass = XposedHelpers.findClass(
                    "com.dragon.read.component.seriessdk.ui.progressbarlayer.SeriesProhibitVerticalScrollSeekBar",
                    classLoader);
            XposedBridge.hookAllConstructors(
                    seekBarClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            View progressView = (View) param.thisObject;
                            synchronized (PROGRESS_VIEWS) {
                                PROGRESS_VIEWS.add(progressView);
                            }
                            progressView.post(PlaybackEnhancements::updateProgressMarkers);
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "progress marker hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "progress marker hook unavailable");
            XposedBridge.log(error);
        }
    }

    private static void installPlayerHook(ClassLoader classLoader) {
        try {
            Class<?> playbackHostClass = XposedHelpers.findClass("ba5.a", classLoader);
            XposedBridge.hookAllMethods(
                    playbackHostClass,
                    "a",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0) {
                                capturePlayer(param.thisObject);
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "player hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "player hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void installShareEntryHook(ClassLoader classLoader) {
        try {
            Class<?> shareViewClass = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.impl.rightview.c",
                    classLoader);
            XposedBridge.hookAllConstructors(
                    shareViewClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                View shareView = (View) param.thisObject;
                                shareView.post(() -> attachShareLongPressTargets(shareView));
                                shareView.postDelayed(
                                        () -> attachShareLongPressTargets(shareView),
                                        800L);
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "share long-press hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "share long-press class hook unavailable; UI fallback active");
            XposedBridge.log(error);
        }
    }

    private static void installCommentHook(ClassLoader classLoader) {
        try {
            Class<?> commentViewClass = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.impl.rightview.b",
                    classLoader);
            XposedBridge.hookAllMethods(
                    commentViewClass,
                    "h",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || param.args[0] == null) {
                                return;
                            }
                            String event = String.valueOf(param.args[0]);
                            if (event.startsWith("ShortVideoCommentEffect$Clicked:")) {
                                onCommentOpened();
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "comment replay guard hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "comment replay guard hook installation failed");
            XposedBridge.log(error);
        }
        try {
            Class<?> helperClass = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.impl.rightview.comment.CommentDialogHelper",
                    classLoader);
            XposedHelpers.findAndHookMethod(helperClass, "onDialogShow", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            commentDialogHelper = new WeakReference<>(param.thisObject);
                            onCommentOpened();
                        }
                    });
            XC_MethodHook closeHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (commentDialogHelper.get() == param.thisObject) {
                        clearCommentGuard();
                    }
                }
            };
            XposedHelpers.findAndHookMethod(helperClass, "hideCommentDialog", closeHook);
            XposedHelpers.findAndHookMethod(helperClass, "releaseCurDialog", boolean.class,
                    closeHook);
            XposedBridge.log(LOG_PREFIX + "comment dialog lifecycle hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "comment dialog lifecycle hooks unavailable");
            XposedBridge.log(error);
        }
    }

    private static void installCommentCompletionHooks(ClassLoader classLoader) {
        String[] adapterClasses = {
                "com.dragon.read.component.shortvideo.impl.v2.view.adapter.e",
                "com.dragon.read.component.shortvideo.impl.v2.view.adapter.f",
                "com.dragon.read.component.shortvideo.impl.fullscreen.d"
        };
        for (String adapterClassName : adapterClasses) {
            try {
                Class<?> adapterClass = XposedHelpers.findClass(adapterClassName, classLoader);
                XposedHelpers.findAndHookMethod(adapterClass, "Z1", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String videoId = (String) param.args[0];
                                if (!isCommentGuardEnabledFor(videoId)) {
                                    return;
                                }
                                try {
                                    pauseCurrentPlayer(param.thisObject);
                                    commentGuardSawNearEnd = true;
                                    param.setResult(null);
                                    XposedBridge.log(LOG_PREFIX
                                            + "comment completion blocked for vid=" + videoId
                                            + ", adapter=" + param.thisObject.getClass().getName());
                                } catch (Throwable error) {
                                    XposedBridge.log(LOG_PREFIX
                                            + "comment completion pause failed; native completion unchanged");
                                    XposedBridge.log(error);
                                }
                            }
                        });
                XposedBridge.log(LOG_PREFIX + "comment completion hook installed: "
                        + adapterClassName);
            } catch (Throwable error) {
                XposedBridge.log(LOG_PREFIX + "comment completion hook unavailable: "
                        + adapterClassName);
                XposedBridge.log(error);
            }
        }
    }

    private static void installActivityHooks() {
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onActivityResumed((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onPause",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            restoreGlobalControls(activity);
                            if (enhancedClearScreenActivity.get() == activity) {
                                restoreCleanScreenDimming();
                            }
                            if (currentActivity.get() == activity) {
                                removeSkipToast();
                                currentActivity = new WeakReference<>(null);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (enhancedClearScreenActivity.get() == activity) {
                                restoreCleanScreenDimming();
                            }
                            ORIGINAL_BRIGHTNESS.remove(activity);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onBackPressed",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            clearCommentGuard();
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "activity hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "activity hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void capturePlayer(Object playbackHost) {
        try {
            Object adapter = XposedHelpers.getObjectField(playbackHost, "a");
            Object controller = XposedHelpers.callMethod(adapter, "n");
            if (adapter == null || controller == null) {
                return;
            }
            hostAdapter = new WeakReference<>(adapter);
            playerController = new WeakReference<>(controller);
            Context context = null;
            try {
                Object candidate = XposedHelpers.callMethod(controller, "P1");
                if (candidate instanceof Context) {
                    context = (Context) candidate;
                }
            } catch (Throwable ignored) {
                // The resumed Activity below is the fallback context.
            }
            if (context != null) {
                rememberApplicationContext(context);
                Activity activity = findActivity(context);
                if (activity != null) {
                    currentActivity = new WeakReference<>(activity);
                }
            }
            refreshPlaybackSnapshot();
            resetSkipStateIfVideoChanged();
            Activity activity = currentActivity.get();
            if (activity != null) {
                applyGlobalControls(activity);
                scheduleUiEntries(activity);
            }
            scheduleMonitor();
        } catch (Throwable error) {
            if (PLAYER_ERROR_LOGGED.compareAndSet(false, true)) {
                XposedBridge.log(LOG_PREFIX + "capture player failed");
                XposedBridge.log(error);
            }
        }
    }

    private static void onActivityResumed(Activity activity) {
        currentActivity = new WeakReference<>(activity);
        rememberApplicationContext(activity);
        if (playerController.get() != null) {
            applyGlobalControls(activity);
            scheduleUiEntries(activity);
            scheduleMonitor();
        }
    }

    private static void scheduleUiEntries(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.postDelayed(() -> installUiEntries(activity), 250L);
        decor.postDelayed(() -> installUiEntries(activity), 1000L);
        decor.postDelayed(() -> installUiEntries(activity), 2000L);
    }

    private static void installUiEntries(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        View root = activity.getWindow().getDecorView();
        TextView shareLabel = findTextView(root, "分享");
        if (shareLabel != null) {
            View target = findClickableAncestor(shareLabel);
            if (target != null) {
                attachShareLongPressTarget(target);
            }
        }
    }

    private static void attachShareLongPressTargets(View view) {
        if (view.isClickable()) {
            attachShareLongPressTarget(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                attachShareLongPressTargets(group.getChildAt(index));
            }
        }
    }

    private static void attachShareLongPressTarget(View target) {
        if (!SHARE_LONG_PRESS_TARGETS.add(target)) {
            return;
        }
        target.setOnLongClickListener(view -> {
            Activity activity = findActivity(view.getContext());
            if (activity == null) {
                activity = currentActivity.get();
            }
            if (activity == null) {
                return false;
            }
            showPlaybackSettings(activity);
            return true;
        });
        if (SHARE_ENTRY_LOGGED.compareAndSet(false, true)) {
            XposedBridge.log(LOG_PREFIX + "long-press Share entry attached");
        }
    }

    private static void showPlaybackSettings(Activity activity) {
        refreshPlaybackSnapshot();
        if (currentSeriesId.isEmpty()) {
            Toast.makeText(activity, "尚未识别到当前剧集，请开始播放后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences preferences = preferences(activity);
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = dp(activity, 22);
        content.setPadding(horizontalPadding, dp(activity, 6), horizontalPadding, dp(activity, 8));
        scrollView.addView(
                content,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView series = addBodyText(content, "当前剧集 ID：" + currentSeriesId);
        series.setTextColor(Color.DKGRAY);
        addSectionTitle(content, "片头与片尾");
        EditText introInput = addSecondsInput(
                content,
                "片头跳过",
                readSkipTenths(preferences, true, currentSeriesId));
        EditText outroInput = addSecondsInput(
                content,
                "片尾提前跳过",
                readSkipTenths(preferences, false, currentSeriesId));
        addCaption(content, "支持一位小数；只绑定当前剧集，同一部剧不同集共用。设为 0 表示关闭。");

        addSectionTitle(content, "全局播放");
        Switch volumeEnabled = addSwitch(
                content,
                "启用全局音量",
                preferences.getBoolean(KEY_VOLUME_ENABLED, false));
        int initialVolume = preferences.contains(KEY_VOLUME_PERCENT)
                ? preferences.getInt(KEY_VOLUME_PERCENT, 60)
                : readCurrentVolumePercent(activity);
        TextView volumeValue = addBodyText(content, "音量：" + initialVolume + "%");
        SeekBar volumeSeek = addPercentSeekBar(content, initialVolume);
        bindEnabledState(volumeEnabled, volumeValue, volumeSeek);
        volumeSeek.setOnSeekBarChangeListener(
                simpleSeekListener(value -> volumeValue.setText(
                        String.format(Locale.CHINA, "音量：%d%%", value))));

        Switch brightnessEnabled = addSwitch(
                content,
                "启用全局亮度",
                preferences.getBoolean(KEY_BRIGHTNESS_ENABLED, false));
        int initialBrightness = preferences.getInt(KEY_BRIGHTNESS_PERCENT, 60);
        TextView brightnessValue = addBodyText(content, "亮度：" + initialBrightness + "%");
        SeekBar brightnessSeek = addPercentSeekBar(content, initialBrightness);
        bindEnabledState(brightnessEnabled, brightnessValue, brightnessSeek);
        brightnessSeek.setOnSeekBarChangeListener(
                simpleSeekListener(value -> brightnessValue.setText(
                        String.format(Locale.CHINA, "亮度：%d%%", value))));

        addSectionTitle(content, "清屏播放");
        Switch cleanScreenEnhance = addSwitch(
                content,
                "清屏播放增强",
                preferences.getBoolean(KEY_CLEAN_SCREEN_ENHANCE, false));
        addCaption(content, "开启后使用红果自带清屏播放时，额外调暗残留的界面按钮；退出清屏即恢复。");

        addSectionTitle(content, "评论区");
        Switch preventReplay = addSwitch(
                content,
                "打开评论区时禁止重播当集",
                preferences.getBoolean(KEY_PREVENT_COMMENT_REPLAY, false));
        addCaption(content, "评论打开时在当集播完后暂停，避免自动重播；关闭评论后可手动继续播放。");

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("播放设置")
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (ignored, which) -> savePlaybackSettings(
                                activity,
                                introInput,
                                outroInput,
                                volumeEnabled,
                                volumeSeek,
                                brightnessEnabled,
                                brightnessSeek,
                                cleanScreenEnhance,
                                preventReplay,
                                true))
                .create();
        dialog.show();
    }

    private static void savePlaybackSettings(
            Activity activity,
            EditText introInput,
            EditText outroInput,
            Switch volumeEnabled,
            SeekBar volumeSeek,
            Switch brightnessEnabled,
            SeekBar brightnessSeek,
            Switch cleanScreenEnhance,
            Switch preventReplay,
            boolean showConfirmation) {
        int introTenths = parseTenths(introInput);
        int outroTenths = parseTenths(outroInput);
        preferences(activity)
                .edit()
                .putInt(KEY_INTRO_TENTHS_PREFIX + currentSeriesId, introTenths)
                .putInt(KEY_OUTRO_TENTHS_PREFIX + currentSeriesId, outroTenths)
                .remove(KEY_INTRO_PREFIX + currentSeriesId)
                .remove(KEY_OUTRO_PREFIX + currentSeriesId)
                .putBoolean(KEY_VOLUME_ENABLED, volumeEnabled.isChecked())
                .putInt(KEY_VOLUME_PERCENT, volumeSeek.getProgress())
                .putBoolean(KEY_BRIGHTNESS_ENABLED, brightnessEnabled.isChecked())
                .putInt(KEY_BRIGHTNESS_PERCENT, Math.max(1, brightnessSeek.getProgress()))
                .putBoolean(KEY_CLEAN_SCREEN_ENHANCE, cleanScreenEnhance.isChecked())
                .putBoolean(KEY_PREVENT_COMMENT_REPLAY, preventReplay.isChecked())
                .apply();
        introApplied = false;
        outroApplied = false;
        introToastShown = false;
        outroToastShown = false;
        introSeekAttempts = 0;
        outroSeekAttempts = 0;
        applyGlobalControls(activity);
        if (!preventReplay.isChecked()) {
            clearCommentGuard();
        }
        updateProgressMarkers();
        updateCleanScreenEnhancement(preferences(activity));
        scheduleMonitor();
        if (showConfirmation) {
            Toast.makeText(activity, "播放设置已保存", Toast.LENGTH_SHORT).show();
        }
    }

    private static void scheduleMonitor() {
        if (monitorScheduled) {
            return;
        }
        monitorScheduled = true;
        MAIN_HANDLER.post(PROGRESS_MONITOR);
    }

    private static final Runnable PROGRESS_MONITOR = new Runnable() {
        @Override
        public void run() {
            monitorScheduled = false;
            Object controller = playerController.get();
            if (controller == null || hostAdapter.get() == null) {
                restoreCleanScreenDimming();
                return;
            }
            try {
                refreshPlaybackSnapshot();
                resetSkipStateIfVideoChanged();
                Number positionValue = (Number) XposedHelpers.callMethod(controller, "V");
                long positionMs = Math.max(0L, positionValue.longValue());
                long previousPositionMs = lastPositionMs;
                SharedPreferences preferences = preferencesOrNull();
                if (preferences != null) {
                    updateCleanScreenEnhancement(preferences);
                }
                if (preferences != null && !currentSeriesId.isEmpty() && !currentVideoId.isEmpty()) {
                    updateProgressMarkers(preferences);
                    if (applyCommentReplayGuard(
                            controller,
                            positionMs,
                            previousPositionMs,
                            preferences)) {
                        lastPositionMs = positionMs;
                        scheduleMonitorAgain();
                        return;
                    }
                    applyIntroSkip(controller, positionMs, preferences);
                    applyOutroSkip(controller, positionMs, preferences);
                }
                lastPositionMs = positionMs;
            } catch (Throwable error) {
                if (PLAYER_ERROR_LOGGED.compareAndSet(false, true)) {
                    XposedBridge.log(LOG_PREFIX + "progress monitor failed");
                    XposedBridge.log(error);
                }
            }
            scheduleMonitorAgain();
        }
    };

    private static void scheduleMonitorAgain() {
        monitorScheduled = true;
        MAIN_HANDLER.postDelayed(PROGRESS_MONITOR, MONITOR_INTERVAL_MS);
    }

    private static boolean applyCommentReplayGuard(
            Object controller,
            long positionMs,
            long previousPositionMs,
            SharedPreferences preferences) {
        if (!preferences.getBoolean(KEY_PREVENT_COMMENT_REPLAY, false)
                || !isCommentGuardEnabledFor(currentVideoId)) {
            if (commentGuardActive
                    && (!currentVideoId.equals(commentGuardVideoId)
                    || SystemClock.elapsedRealtime() > commentGuardUntilElapsedRealtime)) {
                clearCommentGuard();
            }
            return false;
        }
        if (currentDurationMs > 0L
                && positionMs >= Math.max(0L, currentDurationMs - 5000L)) {
            commentGuardSawNearEnd = true;
        }
        boolean wrapped = commentGuardSawNearEnd
                && positionMs < 3500L
                && (previousPositionMs > Math.max(5000L, currentDurationMs - 5000L)
                || previousPositionMs > positionMs + 5000L);
        if (!wrapped || currentDurationMs <= 0L) {
            return false;
        }
        try {
            pauseCurrentPlayer(controller);
            int nearEnd = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, currentDurationMs - 150L));
            XposedHelpers.callMethod(controller, "seekTo", nearEnd, null);
            pauseCurrentPlayer(controller);
            XposedBridge.log(LOG_PREFIX + "comment replay fallback stopped for vid="
                    + currentVideoId);
            return true;
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "comment replay fallback failed");
            XposedBridge.log(error);
            return false;
        }
    }

    private static boolean isCommentGuardEnabledFor(String videoId) {
        if (videoId == null || videoId.isEmpty() || !commentGuardActive
                || !videoId.equals(commentGuardVideoId)
                || SystemClock.elapsedRealtime() > commentGuardUntilElapsedRealtime) {
            return false;
        }
        SharedPreferences preferences = preferencesOrNull();
        if (preferences == null
                || !preferences.getBoolean(KEY_PREVENT_COMMENT_REPLAY, false)) {
            return false;
        }
        Object helper = commentDialogHelper.get();
        if (helper != null) {
            try {
                if (!Boolean.TRUE.equals(
                        XposedHelpers.callMethod(helper, "isCommentDialogShowing"))) {
                    clearCommentGuard();
                    return false;
                }
            } catch (Throwable error) {
                return false;
            }
        }
        return true;
    }

    private static void pauseCurrentPlayer(Object controller) {
        Object handler = XposedHelpers.callMethod(controller, "x3");
        Object activePlayer = XposedHelpers.getObjectField(handler, "f");
        XposedHelpers.callMethod(activePlayer, "pause");
    }

    private static void applyIntroSkip(
            Object controller,
            long positionMs,
            SharedPreferences preferences) {
        int tenths = readSkipTenths(preferences, true, currentSeriesId);
        long targetMs = tenths * 100L;
        if (tenths <= 0 || introApplied) {
            return;
        }
        if (positionMs >= Math.max(0L, targetMs - 400L)) {
            introApplied = true;
            return;
        }
        if (introSeekAttempts >= 8) {
            introApplied = true;
            return;
        }
        introSeekAttempts++;
        seek(controller, targetMs);
        if (!introToastShown) {
            introToastShown = true;
            showSkipToast(tenths, "片头");
        }
    }

    private static void applyOutroSkip(
            Object controller,
            long positionMs,
            SharedPreferences preferences) {
        int tenths = readSkipTenths(preferences, false, currentSeriesId);
        if (tenths <= 0 || outroApplied || currentDurationMs <= 0L) {
            return;
        }
        long thresholdMs = Math.max(0L, currentDurationMs - tenths * 100L);
        if (positionMs < thresholdMs) {
            return;
        }
        if (outroSeekAttempts >= 3) {
            outroApplied = true;
            return;
        }
        outroSeekAttempts++;
        seek(controller, Math.max(0L, currentDurationMs - 120L));
        if (!outroToastShown) {
            outroToastShown = true;
            showSkipToast(tenths, "片尾");
        }
        if (commentGuardActive && currentVideoId.equals(commentGuardVideoId)) {
            commentGuardSawNearEnd = true;
        }
        outroApplied = true;
    }

    private static void seek(Object controller, long positionMs) {
        int safePosition = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, positionMs));
        XposedHelpers.callMethod(controller, "N1", safePosition);
    }

    private static void showSkipToast(int tenths, String part) {
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            return;
        }
        removeSkipToast();
        TextView message = new TextView(activity);
        message.setText("已跳过 " + formatTenths(tenths) + " 秒" + part);
        message.setTextColor(Color.WHITE);
        message.setTextSize(14);
        message.setGravity(Gravity.CENTER);
        message.setBackgroundColor(Color.TRANSPARENT);
        message.setShadowLayer(dp(activity, 3), 0, dp(activity, 1), Color.BLACK);
        message.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(activity, 120);
        ((FrameLayout) decor).addView(message, params);
        skipToastView = message;
        MAIN_HANDLER.postDelayed(() -> {
            if (skipToastView == message) {
                removeSkipToast();
            }
        }, 1500L);
    }

    private static void removeSkipToast() {
        View message = skipToastView;
        skipToastView = null;
        if (message != null && message.getParent() instanceof ViewGroup) {
            ((ViewGroup) message.getParent()).removeView(message);
        }
    }

    private static int readSkipTenths(
            SharedPreferences preferences,
            boolean intro,
            String seriesId) {
        String tenthsKey = (intro ? KEY_INTRO_TENTHS_PREFIX : KEY_OUTRO_TENTHS_PREFIX)
                + seriesId;
        if (preferences.contains(tenthsKey)) {
            return Math.max(0, Math.min(MAX_SKIP_TENTHS, preferences.getInt(tenthsKey, 0)));
        }
        String legacyKey = (intro ? KEY_INTRO_PREFIX : KEY_OUTRO_PREFIX) + seriesId;
        return Math.max(
                0,
                Math.min(MAX_SKIP_TENTHS, preferences.getInt(legacyKey, 0) * 10));
    }

    private static void updateProgressMarkers() {
        SharedPreferences preferences = preferencesOrNull();
        if (preferences != null) {
            updateProgressMarkers(preferences);
        }
    }

    private static void updateProgressMarkers(SharedPreferences preferences) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        int introTenths = currentSeriesId.isEmpty()
                ? 0
                : readSkipTenths(preferences, true, currentSeriesId);
        int outroTenths = currentSeriesId.isEmpty()
                ? 0
                : readSkipTenths(preferences, false, currentSeriesId);
        float introFraction = currentDurationMs <= 0L || introTenths <= 0
                ? -1f
                : Math.min(1f, introTenths * 100f / currentDurationMs);
        float outroFraction = currentDurationMs <= 0L || outroTenths <= 0
                ? -1f
                : Math.max(0f, 1f - (outroTenths * 100f / currentDurationMs));
        String markerState = String.format(
                Locale.ROOT,
                "%s:%d:%d:%d",
                currentSeriesId,
                currentDurationMs,
                introTenths,
                outroTenths);
        synchronized (PROGRESS_VIEWS) {
            for (View view : PROGRESS_VIEWS) {
                if (view == null || markerState.equals(PROGRESS_MARKER_STATES.get(view))) {
                    continue;
                }
                if (!ORIGINAL_PROGRESS_FOREGROUNDS.containsKey(view)) {
                    ORIGINAL_PROGRESS_FOREGROUNDS.put(view, view.getForeground());
                }
                Drawable original = ORIGINAL_PROGRESS_FOREGROUNDS.get(view);
                if (introFraction < 0f && outroFraction < 0f) {
                    view.setForeground(original);
                } else {
                    Drawable markers = new SkipMarkerDrawable(
                            view,
                            introFraction,
                            outroFraction,
                            dp(view.getContext(), 2));
                    view.setForeground(original == null
                            ? markers
                            : new LayerDrawable(new Drawable[]{original, markers}));
                }
                PROGRESS_MARKER_STATES.put(view, markerState);
            }
        }
    }

    private static void onCommentOpened() {
        SharedPreferences preferences = preferencesOrNull();
        if (preferences == null
                || !preferences.getBoolean(KEY_PREVENT_COMMENT_REPLAY, false)) {
            return;
        }
        refreshPlaybackSnapshot();
        if (currentVideoId.isEmpty()) {
            return;
        }
        commentGuardActive = true;
        commentGuardVideoId = currentVideoId;
        commentGuardUntilElapsedRealtime =
                SystemClock.elapsedRealtime() + COMMENT_GUARD_TIMEOUT_MS;
        commentGuardSawNearEnd = currentDurationMs > 0L
                && lastPositionMs >= Math.max(0L, currentDurationMs - 5000L);
        scheduleMonitor();
    }

    private static void clearCommentGuard() {
        commentGuardActive = false;
        commentGuardSawNearEnd = false;
        commentGuardVideoId = "";
        commentGuardUntilElapsedRealtime = 0L;
        commentDialogHelper = new WeakReference<>(null);
    }

    private static void refreshPlaybackSnapshot() {
        Object adapter = hostAdapter.get();
        if (adapter == null) {
            return;
        }
        Object controller = XposedHelpers.callMethod(adapter, "n");
        if (controller != null) {
            playerController = new WeakReference<>(controller);
        }
        Object dataApi = XposedHelpers.callMethod(adapter, "a");
        if (dataApi == null) {
            return;
        }
        Object data = XposedHelpers.callMethod(dataApi, "q0");
        if (data == null) {
            return;
        }
        String seriesId = readStringField(data, "seriesId");
        String videoId = readStringField(data, "vid");
        long durationSeconds = readLongField(data, "duration");
        if (!seriesId.isEmpty()) {
            currentSeriesId = seriesId;
        }
        if (!videoId.isEmpty()) {
            currentVideoId = videoId;
        }
        currentDurationMs = durationSeconds > 0L
                ? Math.min(Integer.MAX_VALUE, durationSeconds * 1000L)
                : 0L;
    }

    static String[] currentSeriesForLocalFavorite() {
        Object adapter = hostAdapter.get();
        if (adapter == null) {
            return null;
        }
        try {
            Object dataApi = XposedHelpers.callMethod(adapter, "a");
            Object data = dataApi == null ? null : XposedHelpers.callMethod(dataApi, "q0");
            String seriesId = data == null ? "" : readStringField(data, "seriesId");
            if (seriesId.isEmpty()) {
                return null;
            }
            String title = readStringField(data, "seriesName").trim();
            if (title.isEmpty()) {
                try {
                    Object detail = XposedHelpers.callMethod(dataApi, "r0");
                    if (detail != null && seriesId.equals(XposedHelpers.callMethod(detail, "c"))) {
                        Object detailTitle = XposedHelpers.callMethod(detail, "b");
                        title = detailTitle instanceof String ? ((String) detailTitle).trim() : "";
                    }
                } catch (Throwable ignored) {
                    // Keep the series ID usable if this target version has no detail model.
                }
            }
            return new String[]{seriesId, title};
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "local favorite series lookup failed");
            XposedBridge.log(error);
            return null;
        }
    }

    private static void resetSkipStateIfVideoChanged() {
        if (currentVideoId.equals(skipStateVideoId)) {
            return;
        }
        skipStateVideoId = currentVideoId;
        introApplied = false;
        outroApplied = false;
        introToastShown = false;
        outroToastShown = false;
        introSeekAttempts = 0;
        outroSeekAttempts = 0;
        lastPositionMs = 0L;
        if (commentGuardActive && !currentVideoId.equals(commentGuardVideoId)) {
            clearCommentGuard();
        }
    }

    private static void applyGlobalControls(Activity activity) {
        SharedPreferences preferences = preferences(activity);
        applyBrightness(
                activity,
                preferences.getBoolean(KEY_BRIGHTNESS_ENABLED, false),
                preferences.getInt(KEY_BRIGHTNESS_PERCENT, 60));
        applyVolume(
                activity,
                preferences.getBoolean(KEY_VOLUME_ENABLED, false),
                preferences.getInt(KEY_VOLUME_PERCENT, 60));
    }

    private static void applyBrightness(Activity activity, boolean enabled, int percent) {
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (enabled) {
            if (!ORIGINAL_BRIGHTNESS.containsKey(activity)) {
                ORIGINAL_BRIGHTNESS.put(activity, attributes.screenBrightness);
            }
            attributes.screenBrightness = Math.max(0.01f, Math.min(1f, percent / 100f));
            window.setAttributes(attributes);
            return;
        }
        Float original = ORIGINAL_BRIGHTNESS.remove(activity);
        if (original != null) {
            attributes.screenBrightness = original;
            window.setAttributes(attributes);
        }
    }

    private static void applyVolume(Activity activity, boolean enabled, int percent) {
        AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        if (!enabled) {
            restoreVolume(audioManager);
            return;
        }
        if (!originalVolumeCaptured) {
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            originalVolumeCaptured = true;
        }
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(maximum * Math.max(0, Math.min(100, percent)) / 100f);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
    }

    private static void restoreGlobalControls(Activity activity) {
        applyBrightness(activity, false, 0);
        AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            restoreVolume(audioManager);
        }
    }

    private static void restoreVolume(AudioManager audioManager) {
        if (!originalVolumeCaptured) {
            return;
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0);
        originalVolumeCaptured = false;
    }

    private static int readCurrentVolumePercent(Activity activity) {
        AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return 60;
        }
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return maximum <= 0
                ? 60
                : Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maximum);
    }

    private static boolean isNativeClearScreenActive() {
        Object controller = playerController.get();
        if (controller == null) {
            return false;
        }
        try {
            Object value = XposedHelpers.callMethod(controller, "U");
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void updateCleanScreenEnhancement(SharedPreferences preferences) {
        Activity activity = currentActivity.get();
        boolean shouldDim = activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
                        .equals(activity.getClass().getName())
                && preferences.getBoolean(KEY_CLEAN_SCREEN_ENHANCE, false)
                && isNativeClearScreenActive();
        if (!shouldDim) {
            restoreCleanScreenDimming();
            return;
        }
        if (enhancedClearScreenActivity.get() != activity) {
            restoreCleanScreenDimming();
            enhancedClearScreenActivity = new WeakReference<>(activity);
            showDimmedCleanScreenToast(activity);
        }
        View decor = activity.getWindow().getDecorView();
        int topBarId = decor.getResources().getIdentifier("ixy", "id", "com.phoenix.read");
        int bottomBarId = decor.getResources().getIdentifier("hh", "id", "com.phoenix.read");
        dimViewsByClass(decor, topBarId, bottomBarId);
    }

    private static void dimViewsByClass(View view, int topBarId, int bottomBarId) {
        String className = view.getClass().getName();
        boolean target = className.equals(
                "com.dragon.read.component.shortvideo.impl.catalogview.c")
                || className.equals(
                        "com.dragon.read.component.shortvideo.impl.catalogview.a")
                || className.contains("CleanScreenQuickActionBarView")
                || className.endsWith("SeriesProhibitVerticalScrollSeekBar")
                || (topBarId != 0 && view.getId() == topBarId)
                || (bottomBarId != 0 && view.getId() == bottomBarId);
        if (target && view.getVisibility() == View.VISIBLE) {
            synchronized (CLEAN_SCREEN_DIMMED_VIEWS) {
                if (!CLEAN_SCREEN_DIMMED_VIEWS.containsKey(view)) {
                    CLEAN_SCREEN_DIMMED_VIEWS.put(view, view.getAlpha());
                }
                view.setAlpha(0.22f);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                dimViewsByClass(group.getChildAt(index), topBarId, bottomBarId);
            }
        }
    }

    private static void restoreCleanScreenDimming() {
        synchronized (CLEAN_SCREEN_DIMMED_VIEWS) {
            for (Map.Entry<View, Float> entry : CLEAN_SCREEN_DIMMED_VIEWS.entrySet()) {
                View view = entry.getKey();
                if (view != null) {
                    view.setAlpha(entry.getValue());
                }
            }
            CLEAN_SCREEN_DIMMED_VIEWS.clear();
        }
        enhancedClearScreenActivity = new WeakReference<>(null);
        removeCleanScreenToast();
    }

    private static void showDimmedCleanScreenToast(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            return;
        }
        TextView message = new TextView(activity);
        message.setText("清屏播放：界面按钮已调暗");
        message.setTextColor(Color.WHITE);
        message.setTextSize(14);
        message.setPadding(dp(activity, 16), dp(activity, 9), dp(activity, 16), dp(activity, 9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.BLACK);
        background.setCornerRadius(dp(activity, 18));
        message.setBackground(background);
        message.setAlpha(0.42f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(activity, 120);
        ((FrameLayout) decor).addView(message, params);
        cleanScreenToastView = message;
        MAIN_HANDLER.postDelayed(() -> {
            if (cleanScreenToastView == message) {
                removeCleanScreenToast();
            }
        }, 1500L);
    }

    private static void removeCleanScreenToast() {
        View message = cleanScreenToastView;
        cleanScreenToastView = null;
        if (message != null && message.getParent() instanceof ViewGroup) {
            ((ViewGroup) message.getParent()).removeView(message);
        }
    }

    private static SharedPreferences preferences(Context context) {
        rememberApplicationContext(context);
        return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences preferencesOrNull() {
        Context context = applicationContext.get();
        return context == null ? null : preferences(context);
    }

    private static void rememberApplicationContext(Context context) {
        Context candidate = context.getApplicationContext();
        applicationContext = new WeakReference<>(candidate == null ? context : candidate);
    }

    private static String readStringField(Object instance, String fieldName) {
        try {
            Object value = XposedHelpers.getObjectField(instance, fieldName);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long readLongField(Object instance, String fieldName) {
        try {
            return XposedHelpers.getLongField(instance, fieldName);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static TextView findTextView(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                String value = text.toString().trim();
                if (value.equals(expected)) {
                    return (TextView) view;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView match = findTextView(group.getChildAt(index), expected);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static View findClickableAncestor(View view) {
        View current = view;
        for (int depth = 0; depth < 6; depth++) {
            if (current.isClickable()) {
                return current;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            current = (View) parent;
        }
        return null;
    }

    private static TextView addSectionTitle(LinearLayout parent, String text) {
        TextView view = addBodyText(parent, text);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(parent.getContext(), 16);
        view.setLayoutParams(params);
        return view;
    }

    private static TextView addBodyText(LinearLayout parent, String text) {
        TextView view = new TextView(parent.getContext());
        view.setText(text);
        view.setTextSize(15f);
        view.setTextColor(Color.BLACK);
        parent.addView(
                view,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private static void addCaption(LinearLayout parent, String text) {
        TextView view = addBodyText(parent, text);
        view.setTextSize(12f);
        view.setTextColor(Color.GRAY);
    }

    private static EditText addSecondsInput(LinearLayout parent, String labelText, int tenths) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(parent.getContext());
        label.setText(labelText);
        label.setTextSize(15f);
        label.setTextColor(Color.BLACK);
        row.addView(
                label,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        EditText input = new EditText(parent.getContext());
        input.setText(formatTenths(tenths));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER);
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        row.addView(
                input,
                new LinearLayout.LayoutParams(
                        dp(parent.getContext(), 96),
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView suffix = new TextView(parent.getContext());
        suffix.setText("秒");
        suffix.setTextSize(15f);
        suffix.setTextColor(Color.DKGRAY);
        suffix.setPadding(dp(parent.getContext(), 8), 0, 0, 0);
        row.addView(
                suffix,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(
                row,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private static Switch addSwitch(LinearLayout parent, String text, boolean checked) {
        Switch setting = new Switch(parent.getContext());
        setting.setText(text);
        setting.setTextSize(15f);
        setting.setTextColor(Color.BLACK);
        setting.setChecked(checked);
        setting.setPadding(0, dp(parent.getContext(), 5), 0, dp(parent.getContext(), 5));
        parent.addView(
                setting,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return setting;
    }

    private static SeekBar addPercentSeekBar(LinearLayout parent, int progress) {
        SeekBar seekBar = new SeekBar(parent.getContext());
        seekBar.setMax(100);
        seekBar.setProgress(Math.max(0, Math.min(100, progress)));
        parent.addView(
                seekBar,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return seekBar;
    }

    private static void bindEnabledState(Switch setting, View... controlledViews) {
        View.OnClickListener update = ignored -> {
            boolean enabled = setting.isChecked();
            for (View view : controlledViews) {
                view.setEnabled(enabled);
                view.setAlpha(enabled ? 1f : 0.45f);
            }
        };
        setting.setOnClickListener(update);
        update.onClick(setting);
    }

    private interface ProgressListener {
        void onProgress(int progress);
    }

    private static SeekBar.OnSeekBarChangeListener simpleSeekListener(ProgressListener listener) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.onProgress(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private static int parseTenths(EditText input) {
        try {
            double seconds = Double.parseDouble(input.getText().toString().trim());
            if (!Double.isFinite(seconds)) {
                return 0;
            }
            return Math.max(
                    0,
                    Math.min(MAX_SKIP_TENTHS, (int) Math.round(seconds * 10.0d)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String formatTenths(int tenths) {
        int safeValue = Math.max(0, tenths);
        if (safeValue % 10 == 0) {
            return String.valueOf(safeValue / 10);
        }
        return String.format(Locale.CHINA, "%d.%d", safeValue / 10, safeValue % 10);
    }

    private static final class SkipMarkerDrawable extends Drawable {
        private final WeakReference<View> progressView;
        private final float introFraction;
        private final float outroFraction;
        private final float strokeWidth;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private SkipMarkerDrawable(View progressView, float introFraction,
                float outroFraction, float strokeWidth) {
            this.progressView = new WeakReference<>(progressView);
            this.introFraction = introFraction;
            this.outroFraction = outroFraction;
            this.strokeWidth = strokeWidth;
            paint.setStrokeWidth(strokeWidth);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            float top = getBounds().top;
            float bottom = getBounds().bottom;
            if (introFraction >= 0f) {
                paint.setColor(Color.rgb(76, 201, 240));
                drawMarker(canvas, introFraction, top, bottom);
            }
            if (outroFraction >= 0f) {
                paint.setColor(Color.rgb(255, 183, 77));
                drawMarker(canvas, outroFraction, top, bottom);
            }
        }

        private void drawMarker(Canvas canvas, float fraction, float top, float bottom) {
            View view = progressView.get();
            if (view == null) {
                return;
            }
            float left = getBounds().left + view.getPaddingLeft();
            float right = getBounds().right - view.getPaddingRight();
            float x = left + Math.max(0f, right - left) * fraction;
            float centerY = (top + bottom) / 2f;
            float halfHeight = Math.min((bottom - top) / 2f, strokeWidth * 3f);
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
