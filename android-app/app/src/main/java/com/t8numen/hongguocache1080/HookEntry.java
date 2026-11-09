package com.t8numen.hongguocache1080;

import android.app.AndroidAppHelper;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.t8numen.hongguocache1080";
    private static final String LOG_PREFIX = "[HongguoCache1080] ";
    private static final String LSPATCH_ORIGIN_PATH = "/cache/lspatch/origin/";
    private static final String EMBEDDED_PREFERENCES_NAME =
            "hongguo_cache_1080_embedded_settings";
    private static final String KEY_EMBEDDED_SETTINGS_INITIALIZED = "initialized";
    private static final String SETTINGS_SNAPSHOT_NAME = "hongguo_cache_1080_settings_snapshot";
    private static final String UNSAFE_VERSION_TOAST_TEXT =
            "当前版本不安全，请到正规应用市场下载";
    private static final AtomicBoolean TARGET_HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean TOAST_SHOWN = new AtomicBoolean(false);
    private static final Set<Activity> STARTUP_NAVIGATION_SCHEDULED =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final AtomicBoolean STARTUP_NAVIGATION_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean EARLY_REDIRECT_ACTIVE = new AtomicBoolean(false);
    private static final AtomicBoolean ONLINE_QUALITY_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean VIP_BADGE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean VIP_BADGE_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean VIP_SERIES_TOAST_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean UNSAFE_VERSION_TOAST_BLOCK_LOGGED =
            new AtomicBoolean(false);
    private static final long VIP_TOAST_FALLBACK_INTERVAL_MS = 5L * 60L * 1000L;
    private static final Object VIP_TOAST_LOCK = new Object();
    private static final Map<Activity, SettingsGesture> SETTINGS_GESTURES = new WeakHashMap<>();
    private static final Set<Activity> SETTINGS_HINT_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static String lastObservedSeriesId;
    private static long lastVipToastElapsedRealtime;
    private static volatile boolean embeddedLspatchEnvironment;
    private static volatile int startupPrimary = SettingsContract.DEFAULT_STARTUP_PRIMARY;
    private static volatile int startupSecondary = SettingsContract.DEFAULT_STARTUP_SECONDARY;
    private static volatile boolean blockHomeVideoEnabled;
    private static volatile boolean onlineHighestEnabled;
    private static volatile boolean downloadHighestEnabled;
    private static volatile boolean vipExclusiveBadgeEnabled;
    private static volatile boolean hideTheaterTabEnabled;
    private static volatile boolean hideMallTabEnabled;
    private static volatile boolean hideEarnTabEnabled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!TargetApp.supported(loadPackageParam.packageName)) {
            return;
        }
        TargetApp.activate(loadPackageParam.packageName);
        if (!TargetApp.isManju()) {
            installTargetHooks(loadPackageParam);
            return;
        }
        // Context is available here, so version validation precedes any business hook.
        HostSymbols.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        try {
                            android.content.pm.PackageInfo info = context.getPackageManager()
                                    .getPackageInfo(context.getPackageName(), 0);
                            long code = Build.VERSION.SDK_INT >= 28
                                    ? info.getLongVersionCode() : info.versionCode;
                            if (!"7.4.1.32".equals(info.versionName) || code != 74132L) {
                                XposedBridge.log(LOG_PREFIX + "unsupported Manju version; hooks skipped: "
                                        + info.versionName);
                                return;
                            }
                            if (!TARGET_HOOKS_INSTALLED.compareAndSet(false, true)) {
                                return;
                            }
                            installTargetHooks(loadPackageParam);
                            loadModuleSettings(context);
                            if (loadPackageParam.packageName.equals(loadPackageParam.processName)) {
                                AdDiagnostics.initialize((Application) param.thisObject);
                                installManjuLifecycle((Application) param.thisObject);
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(LOG_PREFIX + "Manju initialization failed; native app retained");
                            XposedBridge.log(error);
                        }
                    }
                });
    }

    private void installTargetHooks(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        embeddedLspatchEnvironment = isEmbeddedLspatchEnvironment(loadPackageParam);

        XposedBridge.log(
                LOG_PREFIX
                        + "loaded in process "
                        + loadPackageParam.processName
                        + ", module=" + BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE
                        + ", embedded="
                        + embeddedLspatchEnvironment);

        if (!TargetApp.isManju()) {
            installSettingsHook();
        }
        if (TargetApp.current().equals(loadPackageParam.processName)) {
            LoginNavigationBlocker.install(loadPackageParam.classLoader);
            if (embeddedLspatchEnvironment && Build.VERSION.SDK_INT == 31) {
                installAndroid12AnrCompatibilityHook(loadPackageParam.classLoader);
            }
            try {
                AdDiagnostics.install(loadPackageParam.classLoader);
            } catch (Throwable error) {
                XposedBridge.log(LOG_PREFIX + "advertisement diagnostics unavailable");
                XposedBridge.log(error);
            }
            installInjectedUpdateDialogHook();
            installUnsafeVersionToastBlockHook();
            installStartupNavigationHooks(loadPackageParam.classLoader);
            installVipExclusiveBadgeHooks(loadPackageParam.classLoader);
            PlaybackEnhancements.install(loadPackageParam.classLoader);
            LocalFavorites.install(loadPackageParam.classLoader);
            if (!TargetApp.isManju()) {
                HomeFeedRecovery.install(loadPackageParam.classLoader);
            }
        }

        try {
            Class<?> resolutionClass = HostSymbols.findClass(
                    "com.ss.ttvideoengine.Resolution",
                    loadPackageParam.classLoader);
            Class<?> videoModelInfoClass = HostSymbols.findClass(
                    "nr4.s",
                    loadPackageParam.classLoader);
            Object superHigh = HostSymbols.getStaticObjectField(
                    resolutionClass,
                    "SuperHigh");
            Object extremelyHigh = HostSymbols.getStaticObjectField(
                    resolutionClass,
                    "ExtremelyHigh");

            installResolutionPersistenceHooks(resolutionClass, extremelyHigh);
            if (TargetApp.current().equals(loadPackageParam.processName)) {
                installOnlineHighestQualityHooks(
                        loadPackageParam.classLoader,
                        extremelyHigh);
            }

            HostSymbols.findAndHookConstructor(
                    "so4.c",
                    loadPackageParam.classLoader,
                    videoModelInfoClass,
                    resolutionClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!downloadHighestEnabled || param.args[1] != superHigh) {
                                return;
                            }
                            param.args[1] = extremelyHigh;
                            XposedBridge.log(
                                    LOG_PREFIX
                                            + "download resolution overridden: SuperHigh -> ExtremelyHigh");
                            if (TargetApp.current().equals(loadPackageParam.processName)
                                    && TOAST_SHOWN.compareAndSet(false, true)) {
                                showOverrideToast();
                            }
                        }
                    });

            XposedBridge.log(LOG_PREFIX + "hook installed: so4.c constructor");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void installAndroid12AnrCompatibilityHook(ClassLoader classLoader) {
        AtomicBoolean logged = new AtomicBoolean(false);
        try {
            HostSymbols.findAndHookMethod("com.bytedance.crash.jni.NativeBridge", classLoader,
                    "e", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // NPTH's extra ART dump can deadlock with the system Signal Catcher.
                            param.setResult(null);
                            if (logged.compareAndSet(false, true)) {
                                XposedBridge.log(LOG_PREFIX + "Android 12: skipped concurrent NPTH ART trace dump");
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "Android 12 NPTH ART trace compatibility hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "Android 12 NPTH trace compatibility hook unavailable");
            XposedBridge.log(error);
        }
    }

    private static boolean isEmbeddedLspatchEnvironment(
            XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam.appInfo == null) {
            return false;
        }
        String dataDirectory = loadPackageParam.appInfo.dataDir;
        String sourceDirectory = loadPackageParam.appInfo.sourceDir;
        if (dataDirectory == null || sourceDirectory == null) {
            return false;
        }
        return sourceDirectory.startsWith(dataDirectory + LSPATCH_ORIGIN_PATH);
    }

    private static void installSettingsHook() {
        try {
            HostSymbols.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            loadModuleSettings(context);
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "settings hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "settings hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void installOnlineHighestQualityHooks(
            ClassLoader classLoader,
            Object extremelyHigh) {
        try {
            Class<?> playbackControlClass = HostSymbols.findClass("ba5.a", classLoader);

            HostSymbols.findAndHookMethod(
                    playbackControlClass,
                    "a",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!onlineHighestEnabled) {
                                return;
                            }
                            try {
                                Object hostAdapter = HostSymbols.getObjectField(
                                        param.thisObject,
                                        "a");
                                Object playerController = HostSymbols.callMethod(
                                        hostAdapter,
                                        "n");
                                if (playerController == null) {
                                    return;
                                }
                                HostSymbols.callMethod(
                                        playerController,
                                        "r",
                                        extremelyHigh);
                                if (ONLINE_QUALITY_LOGGED.compareAndSet(false, true)) {
                                    XposedBridge.log(
                                            LOG_PREFIX
                                                    + "online default resolution applied: ExtremelyHigh");
                                }
                            } catch (Throwable error) {
                                XposedBridge.log(LOG_PREFIX + "online highest-quality apply failed");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "online highest-quality hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "online highest-quality hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void installVipExclusiveBadgeHooks(ClassLoader classLoader) {
        try {
            Class<?> badgeManagerClass = HostSymbols.findClass("ma4.q3", classLoader);
            Class<?> videoPayInfoClass = HostSymbols.findClass(
                    "com.dragon.read.rpc.model.VideoPayInfo",
                    classLoader);
            XC_MethodHook badgeHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!vipExclusiveBadgeEnabled
                            || !(param.getResult() instanceof Drawable)
                            || param.getResult() instanceof VipExclusiveBadgeDrawable) {
                        return;
                    }
                    try {
                        Object payInfo = param.args[0];
                        boolean isVip = Boolean.TRUE.equals(HostSymbols.callStaticMethod(
                                badgeManagerClass,
                                "k",
                                payInfo));
                        boolean isLimitedFree = Boolean.TRUE.equals(
                                HostSymbols.callStaticMethod(
                                        badgeManagerClass,
                                        "j",
                                        payInfo));
                        if (!isVip || isLimitedFree) {
                            return;
                        }
                        param.setResult(new VipExclusiveBadgeDrawable(
                                (Drawable) param.getResult()));
                        if (VIP_BADGE_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log(
                                    LOG_PREFIX + "VIP badge replaced: VIP -> VIP专享");
                        }
                    } catch (Throwable error) {
                        if (VIP_BADGE_ERROR_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log(LOG_PREFIX + "VIP badge replacement failed");
                            XposedBridge.log(error);
                        }
                    }
                }
            };

            // The four badge variants share the same replacement callback.
            String[] badgeMethods = TargetApp.isManju()
                    ? new String[] {"GQG66Q", "QGQ6Q", "q9Qgq9Qq", "qq"}
                    : new String[] {"d", "e", "f", "g"};
            for (String methodName : badgeMethods) {
                HostSymbols.findAndHookMethod(
                        badgeManagerClass,
                        methodName,
                        videoPayInfoClass,
                        badgeHook);
            }
            HostSymbols.findAndHookMethod(
                    badgeManagerClass,
                    "o",
                    String.class,
                    videoPayInfoClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            maybeShowVipSeriesToast(
                                    badgeManagerClass,
                                    (String) param.args[0],
                                    param.args[1]);
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "VIP exclusive badge hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "VIP exclusive badge hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void maybeShowVipSeriesToast(
            Class<?> badgeManagerClass,
            String seriesId,
            Object payInfo) {
        if (!vipExclusiveBadgeEnabled || payInfo == null) {
            return;
        }
        try {
            boolean isVip = Boolean.TRUE.equals(HostSymbols.callStaticMethod(
                    badgeManagerClass,
                    "k",
                    payInfo));
            boolean isLimitedFree = Boolean.TRUE.equals(HostSymbols.callStaticMethod(
                    badgeManagerClass,
                    "j",
                    payInfo));
            boolean hasSeriesId = seriesId != null && !seriesId.isEmpty();
            boolean shouldShow;
            long now = SystemClock.elapsedRealtime();
            synchronized (VIP_TOAST_LOCK) {
                if (hasSeriesId) {
                    boolean switchedSeries = !seriesId.equals(lastObservedSeriesId);
                    lastObservedSeriesId = seriesId;
                    shouldShow = isVip && switchedSeries;
                } else {
                    shouldShow = isVip
                            && (lastVipToastElapsedRealtime == 0L
                                    || now - lastVipToastElapsedRealtime
                                            >= VIP_TOAST_FALLBACK_INTERVAL_MS);
                }
                if (shouldShow) {
                    lastVipToastElapsedRealtime = now;
                }
            }
            if (!shouldShow) {
                return;
            }
            showVipSeriesToast(isLimitedFree);
            XposedBridge.log(
                    LOG_PREFIX
                            + "VIP series toast shown: seriesId="
                            + (hasSeriesId ? seriesId : "<missing>")
                            + ", limitedFree="
                            + isLimitedFree);
        } catch (Throwable error) {
            if (VIP_SERIES_TOAST_ERROR_LOGGED.compareAndSet(false, true)) {
                XposedBridge.log(LOG_PREFIX + "VIP series toast failed");
                XposedBridge.log(error);
            }
        }
    }

    private static void showVipSeriesToast(boolean isLimitedFree) {
        Application application = AndroidAppHelper.currentApplication();
        if (application == null) {
            throw new IllegalStateException("application unavailable");
        }
        new Handler(Looper.getMainLooper()).post(
                () -> {
                    Toast toast = Toast.makeText(
                            application,
                            isLimitedFree
                                    ? "当前剧类型：VIP专享（限时免费）"
                                    : "当前剧类型：VIP专享",
                            Toast.LENGTH_SHORT);
                    toast.show();
                    new Handler(Looper.getMainLooper()).postDelayed(toast::cancel, 2000L);
                });
    }

    private static void installStartupNavigationHooks(ClassLoader classLoader) {
        try {
            Class<?> mainActivityClass = HostSymbols.findClass(
                    "com.dragon.read.pages.main.MainFragmentActivity",
                    classLoader);
            Class<?> tabTypeClass = HostSymbols.findClass(
                    "com.dragon.read.rpc.model.BottomTabBarItemType",
                    classLoader);
            Object myProfile = HostSymbols.getStaticObjectField(tabTypeClass, "MyProfile");
            Object bookStore = HostSymbols.getStaticObjectField(tabTypeClass, "BookStore");
            installSettingsTouchHook(mainActivityClass);

            HostSymbols.findAndHookMethod(
                    mainActivityClass,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!isLauncherLaunch(activity)) {
                                return;
                            }
                            loadModuleSettings(activity);
                            STARTUP_NAVIGATION_PENDING.set(
                                    startupPrimary != SettingsContract.STARTUP_NONE);
                            EARLY_REDIRECT_ACTIVE.set(
                                    startupPrimary == SettingsContract.STARTUP_MY
                                            && blockHomeVideoEnabled);
                        }
                    });

            HostSymbols.findAndHookMethod(
                    mainActivityClass,
                    "W1",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (startupPrimary != SettingsContract.STARTUP_MY
                                    || !blockHomeVideoEnabled
                                    || !isLauncherLaunch(activity)) {
                                return;
                            }
                            STARTUP_NAVIGATION_PENDING.set(true);
                            EARLY_REDIRECT_ACTIVE.set(true);
                            param.setResult(myProfile);
                            XposedBridge.log(
                                    LOG_PREFIX + "initial tab overridden: BookStore -> MyProfile");
                        }
                    });

            HostSymbols.findAndHookMethod(
                    mainActivityClass,
                    "t2",
                    tabTypeClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!STARTUP_NAVIGATION_PENDING.get()
                                    || !EARLY_REDIRECT_ACTIVE.get()
                                    || !blockHomeVideoEnabled
                                    || param.args[0] != bookStore) {
                                return;
                            }
                            param.args[0] = myProfile;
                            XposedBridge.log(
                                    LOG_PREFIX + "startup BookStore fragment creation blocked");
                        }
                    });

            HostSymbols.findAndHookMethod(
                    mainActivityClass,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            scheduleSettingsEntry(activity, 0);
                            scheduleBottomTabVisibility(activity, 0);
                            if (startupPrimary == SettingsContract.STARTUP_NONE
                                    || !STARTUP_NAVIGATION_PENDING.get()
                                    || (!TargetApp.isManju() && !isLauncherLaunch(activity))) {
                                return;
                            }
                            scheduleStartupNavigation(activity, 0);
                        }
                    });

            XposedBridge.log(LOG_PREFIX + "startup navigation hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "startup navigation hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void loadModuleSettings(Context context) {
        int startupPrimarySetting = SettingsContract.DEFAULT_STARTUP_PRIMARY;
        int startupSecondarySetting = SettingsContract.DEFAULT_STARTUP_SECONDARY;
        boolean blockHomeVideo = SettingsContract.DEFAULT_BLOCK_HOME_VIDEO;
        boolean localFavorites = SettingsContract.DEFAULT_LOCAL_FAVORITES;
        boolean homeIdRotation = SettingsContract.DEFAULT_HOME_ID_ROTATION;
        boolean onlineHighest = SettingsContract.DEFAULT_ONLINE_HIGHEST;
        boolean downloadHighest = SettingsContract.DEFAULT_DOWNLOAD_HIGHEST;
        boolean vipExclusiveBadge = SettingsContract.DEFAULT_VIP_EXCLUSIVE_BADGE;
        boolean hideTheaterTab = SettingsContract.DEFAULT_HIDE_THEATER_TAB;
        boolean hideMallTab = SettingsContract.DEFAULT_HIDE_MALL_TAB;
        boolean hideEarnTab = SettingsContract.DEFAULT_HIDE_EARN_TAB;
        if (embeddedLspatchEnvironment) {
            SharedPreferences preferences = context.getSharedPreferences(
                    EMBEDDED_PREFERENCES_NAME,
                    Context.MODE_PRIVATE);
            if (preferences.getBoolean(KEY_EMBEDDED_SETTINGS_INITIALIZED, false)) {
                startupPrimarySetting = SettingsContract.startupPrimary(preferences);
                startupSecondarySetting = SettingsContract.startupSecondary(preferences);
                blockHomeVideo = preferences.getBoolean(
                        SettingsContract.KEY_BLOCK_HOME_VIDEO,
                        SettingsContract.DEFAULT_BLOCK_HOME_VIDEO);
                localFavorites = preferences.getBoolean(
                        SettingsContract.KEY_LOCAL_FAVORITES,
                        SettingsContract.DEFAULT_LOCAL_FAVORITES);
                homeIdRotation = preferences.getBoolean(
                        SettingsContract.KEY_HOME_ID_ROTATION,
                        SettingsContract.DEFAULT_HOME_ID_ROTATION);
                onlineHighest = preferences.getBoolean(
                        SettingsContract.KEY_ONLINE_HIGHEST,
                        SettingsContract.DEFAULT_ONLINE_HIGHEST);
                downloadHighest = preferences.getBoolean(
                        SettingsContract.KEY_DOWNLOAD_HIGHEST,
                        SettingsContract.DEFAULT_DOWNLOAD_HIGHEST);
                vipExclusiveBadge = preferences.getBoolean(
                        SettingsContract.KEY_VIP_EXCLUSIVE_BADGE,
                        SettingsContract.DEFAULT_VIP_EXCLUSIVE_BADGE);
                hideTheaterTab = preferences.getBoolean(
                        SettingsContract.KEY_HIDE_THEATER_TAB,
                        SettingsContract.DEFAULT_HIDE_THEATER_TAB);
                hideMallTab = preferences.getBoolean(
                        SettingsContract.KEY_HIDE_MALL_TAB,
                        SettingsContract.DEFAULT_HIDE_MALL_TAB);
                hideEarnTab = preferences.getBoolean(
                        SettingsContract.KEY_HIDE_EARN_TAB,
                        SettingsContract.DEFAULT_HIDE_EARN_TAB);
            }
            applySettings(
                    startupPrimarySetting,
                    startupSecondarySetting,
                    blockHomeVideo,
                    localFavorites,
                    onlineHighest,
                    downloadHighest,
                    vipExclusiveBadge,
                    hideTheaterTab,
                    hideMallTab,
                    hideEarnTab);
            logSettings("embedded", startupPrimarySetting, startupSecondarySetting,
                    blockHomeVideo, localFavorites, onlineHighest,
                    downloadHighest, vipExclusiveBadge, hideTheaterTab, hideMallTab,
                    hideEarnTab);
            HomeFeedRecovery.configure(context, homeIdRotation);
            return;
        }
        String[] projection = {
                SettingsContract.KEY_STARTUP_PRIMARY,
                SettingsContract.KEY_STARTUP_SECONDARY,
                SettingsContract.KEY_BLOCK_HOME_VIDEO,
                SettingsContract.KEY_LOCAL_FAVORITES,
                SettingsContract.KEY_ONLINE_HIGHEST,
                SettingsContract.KEY_DOWNLOAD_HIGHEST,
                SettingsContract.KEY_VIP_EXCLUSIVE_BADGE,
                SettingsContract.KEY_HIDE_THEATER_TAB,
                SettingsContract.KEY_HIDE_MALL_TAB,
                SettingsContract.KEY_HIDE_EARN_TAB,
                SettingsContract.KEY_HOME_ID_ROTATION
        };
        SharedPreferences snapshot = context.getSharedPreferences(
                SETTINGS_SNAPSHOT_NAME,
                Context.MODE_PRIVATE);
        boolean hasSnapshot = snapshot.getBoolean(KEY_EMBEDDED_SETTINGS_INITIALIZED, false);
        if (hasSnapshot) {
            startupPrimarySetting = SettingsContract.startupPrimary(snapshot);
            startupSecondarySetting = SettingsContract.startupSecondary(snapshot);
            blockHomeVideo = snapshot.getBoolean(
                    SettingsContract.KEY_BLOCK_HOME_VIDEO, blockHomeVideo);
            localFavorites = snapshot.getBoolean(
                    SettingsContract.KEY_LOCAL_FAVORITES, localFavorites);
            homeIdRotation = snapshot.getBoolean(
                    SettingsContract.KEY_HOME_ID_ROTATION, homeIdRotation);
            onlineHighest = snapshot.getBoolean(
                    SettingsContract.KEY_ONLINE_HIGHEST, onlineHighest);
            downloadHighest = snapshot.getBoolean(
                    SettingsContract.KEY_DOWNLOAD_HIGHEST, downloadHighest);
            vipExclusiveBadge = snapshot.getBoolean(
                    SettingsContract.KEY_VIP_EXCLUSIVE_BADGE, vipExclusiveBadge);
            hideTheaterTab = snapshot.getBoolean(
                    SettingsContract.KEY_HIDE_THEATER_TAB, hideTheaterTab);
            hideMallTab = snapshot.getBoolean(
                    SettingsContract.KEY_HIDE_MALL_TAB, hideMallTab);
            hideEarnTab = snapshot.getBoolean(
                    SettingsContract.KEY_HIDE_EARN_TAB, hideEarnTab);
        }
        String settingsSource = hasSnapshot ? "snapshot" : "defaults";
        try (Cursor cursor = context.getContentResolver().query(
                TargetApp.settingsUri(),
                projection,
                null,
                null,
                null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IllegalStateException("settings provider returned no row");
            }
            startupPrimarySetting = cursor.getInt(0);
            startupSecondarySetting = cursor.getInt(1);
            blockHomeVideo = cursor.getInt(2) != 0;
            localFavorites = cursor.getInt(3) != 0;
            onlineHighest = cursor.getInt(4) != 0;
            downloadHighest = cursor.getInt(5) != 0;
            vipExclusiveBadge = cursor.getInt(6) != 0;
            hideTheaterTab = cursor.getInt(7) != 0;
            hideMallTab = cursor.getInt(8) != 0;
            hideEarnTab = cursor.getInt(9) != 0;
            homeIdRotation = cursor.getInt(10) != 0;
            settingsSource = "provider";
            snapshot.edit()
                    .putBoolean(KEY_EMBEDDED_SETTINGS_INITIALIZED, true)
                    .putInt(SettingsContract.KEY_STARTUP_PRIMARY, startupPrimarySetting)
                    .putInt(SettingsContract.KEY_STARTUP_SECONDARY, startupSecondarySetting)
                    .putBoolean(SettingsContract.KEY_BLOCK_HOME_VIDEO, blockHomeVideo)
                    .putBoolean(SettingsContract.KEY_LOCAL_FAVORITES, localFavorites)
                    .putBoolean(SettingsContract.KEY_HOME_ID_ROTATION, homeIdRotation)
                    .putBoolean(SettingsContract.KEY_ONLINE_HIGHEST, onlineHighest)
                    .putBoolean(SettingsContract.KEY_DOWNLOAD_HIGHEST, downloadHighest)
                    .putBoolean(SettingsContract.KEY_VIP_EXCLUSIVE_BADGE, vipExclusiveBadge)
                    .putBoolean(SettingsContract.KEY_HIDE_THEATER_TAB, hideTheaterTab)
                    .putBoolean(SettingsContract.KEY_HIDE_MALL_TAB, hideMallTab)
                    .putBoolean(SettingsContract.KEY_HIDE_EARN_TAB, hideEarnTab)
                    .apply();
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "module settings unavailable; using last known settings");
            XposedBridge.log(error);
        }
        applySettings(
                startupPrimarySetting,
                startupSecondarySetting,
                blockHomeVideo,
                localFavorites,
                onlineHighest,
                downloadHighest,
                vipExclusiveBadge,
                hideTheaterTab,
                hideMallTab,
                hideEarnTab);
        logSettings(settingsSource, startupPrimarySetting, startupSecondarySetting,
                blockHomeVideo, localFavorites, onlineHighest,
                downloadHighest, vipExclusiveBadge, hideTheaterTab, hideMallTab,
                hideEarnTab);
        HomeFeedRecovery.configure(context, homeIdRotation);
    }

    private static void applySettings(
            int startupPrimarySetting,
            int startupSecondarySetting,
            boolean blockHomeVideo,
            boolean localFavorites,
            boolean onlineHighest,
            boolean downloadHighest,
            boolean vipExclusiveBadge,
            boolean hideTheaterTab,
            boolean hideMallTab,
            boolean hideEarnTab) {
        startupPrimary = startupPrimarySetting;
        startupSecondary = startupSecondarySetting;
        blockHomeVideoEnabled = blockHomeVideo;
        LocalFavorites.configure(localFavorites);
        onlineHighestEnabled = onlineHighest;
        downloadHighestEnabled = downloadHighest;
        vipExclusiveBadgeEnabled = vipExclusiveBadge;
        hideTheaterTabEnabled = hideTheaterTab
                && startupPrimarySetting != SettingsContract.STARTUP_THEATER;
        hideMallTabEnabled = hideMallTab;
        hideEarnTabEnabled = hideEarnTab;
    }

    private static void logSettings(
            String source,
            int startupPrimarySetting,
            int startupSecondarySetting,
            boolean blockHomeVideo,
            boolean localFavorites,
            boolean onlineHighest,
            boolean downloadHighest,
            boolean vipExclusiveBadge,
            boolean hideTheaterTab,
            boolean hideMallTab,
            boolean hideEarnTab) {
        XposedBridge.log(
                LOG_PREFIX
                        + "settings loaded from "
                        + source
                        + ": startupPrimary="
                        + startupPrimarySetting
                        + ", startupSecondary="
                        + startupSecondarySetting
                        + ", blockHomeVideo="
                        + blockHomeVideo
                        + ", localFavorites="
                        + localFavorites
                        + ", onlineHighest="
                        + onlineHighest
                        + ", downloadHighest="
                        + downloadHighest
                        + ", vipExclusiveBadge="
                        + vipExclusiveBadge
                        + ", hideTheaterTab="
                        + hideTheaterTab
                        + ", hideMallTab="
                        + hideMallTab
                        + ", hideEarnTab="
                        + hideEarnTab);
    }

    private static void scheduleSettingsEntry(Activity activity, int attempt) {
        WeakReference<Activity> activityReference = new WeakReference<>(activity);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> {
                    Activity currentActivity = activityReference.get();
                    if (currentActivity == null
                            || currentActivity.isFinishing()
                            || currentActivity.isDestroyed()) {
                        return;
                    }
                    if (installSettingsEntry(currentActivity) || attempt >= 12) {
                        return;
                    }
                    scheduleSettingsEntry(currentActivity, attempt + 1);
                },
                attempt == 0 ? 150L : 400L);
    }

    private static boolean installSettingsEntry(Activity activity) {
        View settingsEntry = findMineTabEntry(activity);
        if (settingsEntry == null) {
            return false;
        }
        settingsEntry.setLongClickable(true);
        settingsEntry.setOnLongClickListener(view -> {
            openModuleSettings(activity);
            return true;
        });
        boolean showHint;
        synchronized (SETTINGS_HINT_ACTIVITIES) {
            showHint = SETTINGS_HINT_ACTIVITIES.add(activity);
        }
        if (showHint) {
            Toast.makeText(
                            activity,
                            "长按“我的”进入模块设置",
                            Toast.LENGTH_LONG)
                    .show();
        }
        XposedBridge.log(LOG_PREFIX + "settings entry installed, embedded="
                + embeddedLspatchEnvironment);
        return true;
    }

    private static View findMineTabEntry(Activity activity) {
        View current = findLowestTextView(activity.getWindow().getDecorView(), "我的", null);
        while (current != null) {
            ViewParent parent = current.getParent();
            if (parent instanceof View
                    && "com.dragon.read.widget.BottomTabBarLayout".equals(
                            parent.getClass().getName())) {
                return current;
            }
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static void openModuleSettings(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        try {
            if (embeddedLspatchEnvironment) {
                showEmbeddedSettingsDialog(activity);
            } else {
                activity.startActivity(new Intent().setClassName(
                        MODULE_PACKAGE, MODULE_PACKAGE + ".SettingsActivity")
                        .putExtra(TargetApp.EXTRA, TargetApp.current())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
        } catch (RuntimeException error) {
            XposedBridge.log(LOG_PREFIX + "module settings launch failed");
            XposedBridge.log(error);
            Toast.makeText(activity, "无法打开模块设置，请重开红果后重试", Toast.LENGTH_LONG).show();
        }
    }

    private static void installSettingsTouchHook(Class<?> mainActivityClass) {
        try {
            HostSymbols.findAndHookMethod(mainActivityClass, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            SettingsGesture gesture = SETTINGS_GESTURES.get(activity);
                            if (gesture == null) {
                                gesture = new SettingsGesture(activity);
                                SETTINGS_GESTURES.put(activity, gesture);
                            }
                            if (gesture.onTouch((MotionEvent) param.args[0])) {
                                param.setResult(true);
                            }
                        }
                    });
            HostSymbols.findAndHookMethod(mainActivityClass, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    SettingsGesture gesture = SETTINGS_GESTURES.remove(param.thisObject);
                    if (gesture != null) {
                        gesture.cancel();
                    }
                }
            });
            XposedBridge.log(LOG_PREFIX + "bottom settings gesture hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "bottom settings gesture hook unavailable");
            XposedBridge.log(error);
        }
    }

    /** Observe the whole tab area before its children; let normal taps reach the app. */
    private static final class SettingsGesture extends GestureDetector.SimpleOnGestureListener {
        private final WeakReference<Activity> activityReference;
        private final GestureDetector detector;
        private WeakReference<View> entryReference = new WeakReference<>(null);
        private boolean tracking;
        private boolean consumed;
        private boolean dispatchingCancel;

        SettingsGesture(Activity activity) {
            activityReference = new WeakReference<>(activity);
            detector = new GestureDetector(activity.getApplicationContext(), this);
        }

        boolean onTouch(MotionEvent event) {
            if (dispatchingCancel) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancel();
                Activity activity = activityReference.get();
                View entry = activity == null ? null : findMineTabEntry(activity);
                Rect bounds = new Rect();
                if (entry != null && entry.isShown() && entry.isEnabled()) {
                    int[] location = new int[2];
                    entry.getLocationOnScreen(location);
                    bounds.set(location[0], location[1], location[0] + entry.getWidth(),
                            location[1] + entry.getHeight());
                }
                tracking = bounds.contains((int) event.getRawX(), (int) event.getRawY());
                entryReference = new WeakReference<>(entry);
            }
            if (!tracking) {
                return false;
            }
            boolean wasConsumed = consumed;
            detector.onTouchEvent(event);
            boolean handled = wasConsumed || consumed;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                cancel();
            }
            return handled;
        }

        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override
        public void onLongPress(MotionEvent event) {
            Activity activity = activityReference.get();
            View entry = entryReference.get();
            if (!tracking || consumed || activity == null || entry == null
                    || !entry.isShown() || !activity.hasWindowFocus()) {
                return;
            }
            consumed = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            dispatchingCancel = true;
            try {
                activity.dispatchTouchEvent(cancel);
            } finally {
                dispatchingCancel = false;
                cancel.recycle();
            }
            openModuleSettings(activity);
        }

        void cancel() {
            MotionEvent cancel = MotionEvent.obtain(0L, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
            detector.onTouchEvent(cancel);
            cancel.recycle();
            tracking = false;
            consumed = false;
            entryReference.clear();
        }
    }

    private static void showEmbeddedSettingsDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        SharedPreferences preferences = activity.getSharedPreferences(
                EMBEDDED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = dp(activity, 24);
        content.setPadding(horizontalPadding, dp(activity, 8), horizontalPadding, dp(activity, 8));

        Spinner startupPrimarySpinner = addEmbeddedChoice(
                content,
                "一级：启动页面",
                "打开红果后进入选定页面。",
                new String[]{"不跳转", "我的", "剧场"},
                SettingsContract.startupPrimary(preferences));
        Spinner startupSecondarySpinner = addEmbeddedChoice(
                content,
                "二级：我的页面",
                "仅在一级选择“我的”时生效。",
                new String[]{"仅我的", "收藏", "点赞", "历史"},
                SettingsContract.startupSecondary(preferences));
        Switch blockHomeVideoSwitch = addEmbeddedSetting(
                content,
                "阻止首页首视频加载",
                "一级选择“我的”时跳过首页 Fragment，避免首个视频写入历史。",
                preferences.getBoolean(
                        SettingsContract.KEY_BLOCK_HOME_VIDEO,
                        SettingsContract.DEFAULT_BLOCK_HOME_VIDEO));
        Switch localFavoritesSwitch = addEmbeddedSetting(
                content,
                "未登录时使用本地收藏",
                "默认关闭；关闭时保留红果原生收藏和已有本地记录。",
                preferences.getBoolean(
                        SettingsContract.KEY_LOCAL_FAVORITES,
                        SettingsContract.DEFAULT_LOCAL_FAVORITES));
        Switch homeIdRotationSwitch = TargetApp.isManju() ? null : addEmbeddedSetting(
                content,
                "空数据时更新 DID/IID",
                "首页数据为空时生成新请求 ID 并提示；点击重试后使用新 ID。",
                preferences.getBoolean(
                        SettingsContract.KEY_HOME_ID_ROTATION,
                        SettingsContract.DEFAULT_HOME_ID_ROTATION));
        Switch onlineHighestSwitch = addEmbeddedSetting(
                content,
                "在线播放默认最高画质",
                "播放器启动时默认选择当前版本提供的最高档。",
                preferences.getBoolean(
                        SettingsContract.KEY_ONLINE_HIGHEST,
                        SettingsContract.DEFAULT_ONLINE_HIGHEST));
        Switch downloadHighestSwitch = addEmbeddedSetting(
                content,
                "下载默认最高画质",
                "创建官方下载任务时默认使用可用的 1080P。",
                preferences.getBoolean(
                        SettingsContract.KEY_DOWNLOAD_HIGHEST,
                        SettingsContract.DEFAULT_DOWNLOAD_HIGHEST));
        Switch vipExclusiveBadgeSwitch = addEmbeddedSetting(
                content,
                "统一显示“VIP专享”",
                "统一 VIP 角标，并在进入 VIP 剧时显示一次类型提示。",
                preferences.getBoolean(
                        SettingsContract.KEY_VIP_EXCLUSIVE_BADGE,
                        SettingsContract.DEFAULT_VIP_EXCLUSIVE_BADGE));
        TextView bottomTabTitle = new TextView(activity);
        bottomTabTitle.setText("底栏精简");
        bottomTabTitle.setTextSize(15.0f);
        bottomTabTitle.setAlpha(0.78f);
        bottomTabTitle.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        content.addView(bottomTabTitle);
        Switch hideTheaterTabSwitch = addEmbeddedSetting(
                content,
                "隐藏“剧场”",
                "隐藏底部剧场入口。",
                preferences.getBoolean(
                        SettingsContract.KEY_HIDE_THEATER_TAB,
                        SettingsContract.DEFAULT_HIDE_THEATER_TAB));
        Switch hideMallTabSwitch = addEmbeddedSetting(
                content,
                "隐藏“商城”",
                "隐藏底部商城入口。",
                preferences.getBoolean(
                        SettingsContract.KEY_HIDE_MALL_TAB,
                        SettingsContract.DEFAULT_HIDE_MALL_TAB));
        Switch hideEarnTabSwitch = addEmbeddedSetting(
                content,
                "隐藏“赚钱”",
                "隐藏底部赚钱入口；三个选项可独立组合。",
                preferences.getBoolean(
                        SettingsContract.KEY_HIDE_EARN_TAB,
                        SettingsContract.DEFAULT_HIDE_EARN_TAB));
        TextView fixedFeatureNote = new TextView(activity);
        fixedFeatureNote.setText("模块自带切集广告与游戏推广页拦截、强制更新弹窗拦截，并默认阻止跳转登录页（包括主动点击登录）。保存后请完全关闭并重新打开红果。");
        fixedFeatureNote.setTextSize(13.0f);
        fixedFeatureNote.setAlpha(0.72f);
        fixedFeatureNote.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        content.addView(fixedFeatureNote);
        AdDiagnosticUi.addButtons(activity, content);

        Runnable updateStartupState = () -> {
            boolean my = startupPrimarySpinner.getSelectedItemPosition()
                    == SettingsContract.STARTUP_MY;
            startupSecondarySpinner.setEnabled(my);
            startupSecondarySpinner.setAlpha(my ? 1.0f : 0.55f);
            blockHomeVideoSwitch.setEnabled(my);
            blockHomeVideoSwitch.setAlpha(my ? 1.0f : 0.55f);
            boolean theater = startupPrimarySpinner.getSelectedItemPosition()
                    == SettingsContract.STARTUP_THEATER;
            if (theater) {
                hideTheaterTabSwitch.setChecked(false);
            }
            hideTheaterTabSwitch.setEnabled(!theater);
            hideTheaterTabSwitch.setAlpha(theater ? 0.55f : 1.0f);
        };
        startupPrimarySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateStartupState.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateStartupState.run();

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(content);
        new AlertDialog.Builder(activity)
                .setTitle("红果增强设置")
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    int startupPrimarySetting = startupPrimarySpinner.getSelectedItemPosition();
                    int startupSecondarySetting = startupSecondarySpinner.getSelectedItemPosition();
                    boolean blockHomeVideo = blockHomeVideoSwitch.isChecked();
                    boolean localFavorites = localFavoritesSwitch.isChecked();
                    boolean homeIdRotation = homeIdRotationSwitch != null
                            && homeIdRotationSwitch.isChecked();
                    boolean onlineHighest = onlineHighestSwitch.isChecked();
                    boolean downloadHighest = downloadHighestSwitch.isChecked();
                    boolean vipExclusiveBadge = vipExclusiveBadgeSwitch.isChecked();
                    boolean hideTheaterTab = hideTheaterTabSwitch.isChecked()
                            && startupPrimarySetting != SettingsContract.STARTUP_THEATER;
                    boolean hideMallTab = hideMallTabSwitch.isChecked();
                    boolean hideEarnTab = hideEarnTabSwitch.isChecked();
                    preferences.edit()
                            .putBoolean(KEY_EMBEDDED_SETTINGS_INITIALIZED, true)
                            .putInt(SettingsContract.KEY_STARTUP_PRIMARY, startupPrimarySetting)
                            .putInt(SettingsContract.KEY_STARTUP_SECONDARY,
                                    startupSecondarySetting)
                            .putBoolean(
                                    SettingsContract.KEY_BLOCK_HOME_VIDEO,
                                    blockHomeVideo)
                            .putBoolean(SettingsContract.KEY_LOCAL_FAVORITES, localFavorites)
                            .putBoolean(
                                    SettingsContract.KEY_HOME_ID_ROTATION,
                                    homeIdRotation)
                            .putBoolean(
                                    SettingsContract.KEY_ONLINE_HIGHEST,
                                    onlineHighest)
                            .putBoolean(
                                    SettingsContract.KEY_DOWNLOAD_HIGHEST,
                                    downloadHighest)
                            .putBoolean(
                                    SettingsContract.KEY_VIP_EXCLUSIVE_BADGE,
                                    vipExclusiveBadge)
                            .putBoolean(
                                    SettingsContract.KEY_HIDE_THEATER_TAB,
                                    hideTheaterTab)
                            .putBoolean(
                                    SettingsContract.KEY_HIDE_MALL_TAB,
                                    hideMallTab)
                            .putBoolean(
                                    SettingsContract.KEY_HIDE_EARN_TAB,
                                    hideEarnTab)
                            .apply();
                    applySettings(
                            startupPrimarySetting,
                            startupSecondarySetting,
                            blockHomeVideo,
                            localFavorites,
                            onlineHighest,
                            downloadHighest,
                            vipExclusiveBadge,
                            hideTheaterTab,
                            hideMallTab,
                            hideEarnTab);
                    HomeFeedRecovery.configure(activity, homeIdRotation);
                    scheduleBottomTabVisibility(activity, 0);
                    Toast.makeText(
                                    activity,
                                    "设置已保存，完全关闭并重新打开红果后生效",
                                    Toast.LENGTH_LONG)
                            .show();
                    XposedBridge.log(LOG_PREFIX + "embedded settings saved");
                })
                .show();
    }

    private static Switch addEmbeddedSetting(
            LinearLayout content,
            String title,
            String summary,
            boolean checked) {
        Context context = content.getContext();
        Switch switchView = new Switch(context);
        switchView.setText(title);
        switchView.setTextSize(16.0f);
        switchView.setMinHeight(dp(context, 48));
        switchView.setChecked(checked);
        content.addView(
                switchView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView summaryView = new TextView(context);
        summaryView.setText(summary);
        summaryView.setTextSize(13.0f);
        summaryView.setAlpha(0.72f);
        summaryView.setPadding(0, 0, 0, dp(context, 8));
        content.addView(
                summaryView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return switchView;
    }

    private static Spinner addEmbeddedChoice(
            LinearLayout content,
            String title,
            String summary,
            String[] options,
            int selected) {
        Context context = content.getContext();
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(16.0f);
        titleView.setPadding(0, dp(context, 8), 0, 0);
        content.addView(titleView);
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(context, 48));
        spinner.setSelection(selected);
        content.addView(spinner);
        TextView summaryView = new TextView(context);
        summaryView.setText(summary);
        summaryView.setTextSize(13.0f);
        summaryView.setAlpha(0.72f);
        summaryView.setPadding(0, 0, 0, dp(context, 8));
        content.addView(summaryView);
        return spinner;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void scheduleBottomTabVisibility(Activity activity, int attempt) {
        if (!hideTheaterTabEnabled && !hideMallTabEnabled && !hideEarnTabEnabled) {
            return;
        }
        WeakReference<Activity> activityReference = new WeakReference<>(activity);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> {
                    Activity currentActivity = activityReference.get();
                    if (currentActivity == null
                            || currentActivity.isFinishing()
                            || currentActivity.isDestroyed()) {
                        return;
                    }
                    boolean allResolved = true;
                    if (hideTheaterTabEnabled) {
                        allResolved &= hideBottomTab(currentActivity, "剧场");
                    }
                    if (hideMallTabEnabled) {
                        allResolved &= hideBottomTab(currentActivity, "商城");
                    }
                    if (hideEarnTabEnabled) {
                        allResolved &= hideBottomTab(currentActivity, "赚钱");
                    }
                    if (!allResolved && attempt < 12) {
                        scheduleBottomTabVisibility(currentActivity, attempt + 1);
                    }
                },
                attempt == 0 ? 120L : 350L);
    }

    private static boolean hideBottomTab(Activity activity, String label) {
        View decor = activity.getWindow().getDecorView();
        TextView textView = findLowestTextView(decor, label, null);
        if (textView == null) {
            return false;
        }
        View tab = findBottomTabContainer(textView, decor.getWidth());
        if (tab == null) {
            return false;
        }
        tab.setVisibility(View.GONE);
        XposedBridge.log(LOG_PREFIX + "bottom tab hidden: " + label);
        return true;
    }

    private static View findBottomTabContainer(View label, int rootWidth) {
        View current = label;
        for (int depth = 0; current != null && depth < 6; depth++) {
            int width = current.getWidth();
            if (current.isClickable()
                    && current.isEnabled()
                    && (rootWidth <= 0 || width <= rootWidth / 2)) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return label;
    }

    private static boolean isLauncherLaunch(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null) {
            return false;
        }
        Uri data = intent.getData();
        if (data != null
                && TargetApp.launcherScheme().equals(data.getScheme())
                && "main".equals(data.getHost())) {
            return (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0;
        }
        return activity.isTaskRoot()
                && data == null
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && (Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getAction() == null);
    }

    private static void installManjuLifecycle(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private boolean main(Activity activity) {
                return "com.dragon.read.pages.main.MainFragmentActivity".equals(
                        activity.getClass().getName());
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                if (main(activity) && isLauncherLaunch(activity)) {
                    STARTUP_NAVIGATION_PENDING.set(startupPrimary != SettingsContract.STARTUP_NONE);
                }
            }

            @Override
            public void onActivityResumed(Activity activity) {
                if (!main(activity)) {
                    return;
                }
                scheduleSettingsEntry(activity, 0);
                scheduleBottomTabVisibility(activity, 0);
                if (STARTUP_NAVIGATION_PENDING.get()) {
                    scheduleStartupNavigation(activity, 0);
                }
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static void scheduleStartupNavigation(Activity activity, int attempt) {
        if (TargetApp.isManju() && attempt == 0) {
            synchronized (STARTUP_NAVIGATION_SCHEDULED) {
                if (!STARTUP_NAVIGATION_SCHEDULED.add(activity)) {
                    return;
                }
            }
        }
        WeakReference<Activity> activityReference = new WeakReference<>(activity);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> navigateToStartupDestination(activityReference, attempt),
                attempt == 0 ? 100L : 250L);
    }

    private static void navigateToStartupDestination(
            WeakReference<Activity> activityReference,
            int attempt) {
        Activity activity = activityReference.get();
        if (activity == null
                || activity.isFinishing()
                || activity.isDestroyed()
                || !STARTUP_NAVIGATION_PENDING.get()) {
            return;
        }
        View root = activity.getWindow().getDecorView();
        String primaryLabel = startupPrimary == SettingsContract.STARTUP_THEATER
                ? "剧场" : "我的";
        TextView primaryTab = findLowestTextView(root, primaryLabel, null);
        if (primaryTab == null || !isSelected(primaryTab)) {
            clickViewOrAncestor(primaryTab);
            retryStartupNavigation(activity, attempt);
            return;
        }
        if (startupPrimary == SettingsContract.STARTUP_MY
                && startupSecondary != SettingsContract.MY_HOME) {
            String secondaryLabel = startupSecondary == SettingsContract.MY_FAVORITES
                    ? "收藏" : startupSecondary == SettingsContract.MY_LIKES
                    ? "点赞" : "历史";
            TextView secondaryTab = findTextView(root, secondaryLabel);
            if (secondaryTab == null || !isSelected(secondaryTab)) {
                clickViewOrAncestor(secondaryTab);
                retryStartupNavigation(activity, attempt);
                return;
            }
        }

        STARTUP_NAVIGATION_PENDING.set(false);
        STARTUP_NAVIGATION_SCHEDULED.remove(activity);
        EARLY_REDIRECT_ACTIVE.set(false);
        XposedBridge.log(LOG_PREFIX + "startup navigation completed: "
                + primaryLabel + "/" + startupSecondary);
    }

    private static void retryStartupNavigation(Activity activity, int attempt) {
        if (attempt >= 15) {
            STARTUP_NAVIGATION_PENDING.set(false);
            STARTUP_NAVIGATION_SCHEDULED.remove(activity);
            EARLY_REDIRECT_ACTIVE.set(false);
            XposedBridge.log(LOG_PREFIX + "startup navigation timed out");
            return;
        }
        scheduleStartupNavigation(activity, attempt + 1);
    }

    private static TextView findTextView(View view, String expectedText) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && expectedText.contentEquals(text)) {
                return (TextView) view;
            }
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView match = findTextView(group.getChildAt(index), expectedText);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static TextView findLowestTextView(
            View view,
            String expectedText,
            TextView currentBest) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return currentBest;
        }
        TextView best = currentBest;
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            CharSequence text = textView.getText();
            if (text != null && expectedText.contentEquals(text)) {
                if (best == null || screenY(textView) > screenY(best)) {
                    best = textView;
                }
            }
        }
        if (!(view instanceof ViewGroup)) {
            return best;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            best = findLowestTextView(group.getChildAt(index), expectedText, best);
        }
        return best;
    }

    private static int screenY(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[1];
    }

    private static boolean isSelected(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current.isSelected()) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean clickViewOrAncestor(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current.isClickable() && current.isEnabled()) {
                return current.performClick();
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static void installInjectedUpdateDialogHook() {
        try {
            HostSymbols.findAndHookMethod(
                    Dialog.class,
                    "show",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Dialog dialog = (Dialog) param.thisObject;
                            if (!isInjectedUpdateDialog(dialog)) {
                                return;
                            }
                            dialog.dismiss();
                            XposedBridge.log(LOG_PREFIX + "blocked injected forced-update dialog");
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "injected update dialog hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "injected update dialog hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void installUnsafeVersionToastBlockHook() {
        try {
            HostSymbols.findAndHookMethod(
                    Toast.class,
                    "show",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String message = readToastText((Toast) param.thisObject);
                            if (!UNSAFE_VERSION_TOAST_TEXT.equals(message.trim())) {
                                return;
                            }
                            param.setResult(null);
                            if (UNSAFE_VERSION_TOAST_BLOCK_LOGGED.compareAndSet(false, true)) {
                                XposedBridge.log(LOG_PREFIX + "blocked unsafe-version toast");
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "unsafe-version toast block hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "unsafe-version toast block hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static String readToastText(Toast toast) {
        View view = null;
        try {
            Object value = HostSymbols.callMethod(toast, "getView");
            if (value instanceof View) {
                view = (View) value;
            }
        } catch (Throwable ignored) {
        }
        if (view == null) {
            try {
                Object toastPresenter = HostSymbols.getObjectField(toast, "mTN");
                Object value = HostSymbols.getObjectField(toastPresenter, "mNextView");
                if (value instanceof View) {
                    view = (View) value;
                }
            } catch (Throwable ignored) {
            }
        }
        if (view == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        appendText(view, text);
        return text.toString();
    }

    private static boolean isInjectedUpdateDialog(Dialog dialog) {
        try {
            Window window = dialog.getWindow();
            if (window == null) {
                return false;
            }
            StringBuilder text = new StringBuilder();
            appendText(window.getDecorView(), text);
            String dialogText = text.toString();
            return dialogText.contains("立即更新")
                    && dialogText.contains("红果短剧")
                    && (dialogText.contains("修复版")
                            || dialogText.contains("会员纯净")
                            || dialogText.contains("更新直接免费获取"));
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "injected update dialog inspection failed");
            XposedBridge.log(error);
            return false;
        }
    }

    private static void appendText(View view, StringBuilder text) {
        if (view == null) {
            return;
        }
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null) {
                text.append(value).append('\n');
            }
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            appendText(group.getChildAt(index), text);
        }
    }

    private static void installResolutionPersistenceHooks(
            Class<?> resolutionClass,
            Object extremelyHigh) {
        try {
            HostSymbols.findAndHookMethod(
                    resolutionClass,
                    "toString",
                    resolutionClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] == extremelyHigh) {
                                param.setResult("ExtremelyHigh");
                            }
                        }
                    });
            HostSymbols.findAndHookMethod(
                    resolutionClass,
                    "forString",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ("ExtremelyHigh".equals(param.args[0])) {
                                param.setResult(extremelyHigh);
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "resolution persistence hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "resolution persistence hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void showOverrideToast() {
        Application application = AndroidAppHelper.currentApplication();
        if (application == null) {
            XposedBridge.log(LOG_PREFIX + "toast skipped: application unavailable");
            return;
        }
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(
                                application,
                                "红果下载任务已自动切换为 1080P",
                                Toast.LENGTH_SHORT)
                        .show());
    }
}
