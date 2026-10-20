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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.phoenix.read";
    private static final String LOG_PREFIX = "[HongguoCache1080] ";
    private static final String LSPATCH_ORIGIN_PATH = "/cache/lspatch/origin/";
    private static final String EMBEDDED_PREFERENCES_NAME =
            "hongguo_cache_1080_embedded_settings";
    private static final String KEY_EMBEDDED_SETTINGS_INITIALIZED = "initialized";
    private static final String SETTINGS_SNAPSHOT_NAME = "hongguo_cache_1080_settings_snapshot";
    private static final String UNSAFE_VERSION_TOAST_TEXT =
            "当前版本不安全，请到正规应用市场下载";
    private static final AtomicBoolean TOAST_SHOWN = new AtomicBoolean(false);
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
    private static final Set<View> EMBEDDED_SETTINGS_ENTRY_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Set<Activity> EMBEDDED_SETTINGS_HINT_ACTIVITIES =
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
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        embeddedLspatchEnvironment = isEmbeddedLspatchEnvironment(loadPackageParam);

        XposedBridge.log(
                LOG_PREFIX
                        + "loaded in process "
                        + loadPackageParam.processName
                        + ", embedded="
                        + embeddedLspatchEnvironment);

        installSettingsHook();
        if (TARGET_PACKAGE.equals(loadPackageParam.processName)) {
            installInjectedUpdateDialogHook();
            installUnsafeVersionToastBlockHook();
            installStartupNavigationHooks(loadPackageParam.classLoader);
            installVipExclusiveBadgeHooks(loadPackageParam.classLoader);
            PlaybackEnhancements.install(loadPackageParam.classLoader);
            LocalFavorites.install(loadPackageParam.classLoader);
            HomeFeedRecovery.install(loadPackageParam.classLoader);
        }

        try {
            Class<?> resolutionClass = XposedHelpers.findClass(
                    "com.ss.ttvideoengine.Resolution",
                    loadPackageParam.classLoader);
            Class<?> videoModelInfoClass = XposedHelpers.findClass(
                    "nr4.s",
                    loadPackageParam.classLoader);
            Object superHigh = XposedHelpers.getStaticObjectField(
                    resolutionClass,
                    "SuperHigh");
            Object extremelyHigh = XposedHelpers.getStaticObjectField(
                    resolutionClass,
                    "ExtremelyHigh");

            installResolutionPersistenceHooks(resolutionClass, extremelyHigh);
            if (TARGET_PACKAGE.equals(loadPackageParam.processName)) {
                installOnlineHighestQualityHooks(
                        loadPackageParam.classLoader,
                        extremelyHigh);
            }

            XposedHelpers.findAndHookConstructor(
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
                            if (TARGET_PACKAGE.equals(loadPackageParam.processName)
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
            XposedHelpers.findAndHookMethod(
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
            Class<?> playbackControlClass = XposedHelpers.findClass("ba5.a", classLoader);

            XposedHelpers.findAndHookMethod(
                    playbackControlClass,
                    "a",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!onlineHighestEnabled) {
                                return;
                            }
                            try {
                                Object hostAdapter = XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "a");
                                Object playerController = XposedHelpers.callMethod(
                                        hostAdapter,
                                        "n");
                                if (playerController == null) {
                                    return;
                                }
                                XposedHelpers.callMethod(
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
            Class<?> badgeManagerClass = XposedHelpers.findClass("ma4.q3", classLoader);
            Class<?> videoPayInfoClass = XposedHelpers.findClass(
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
                        boolean isVip = Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                                badgeManagerClass,
                                "k",
                                payInfo));
                        boolean isLimitedFree = Boolean.TRUE.equals(
                                XposedHelpers.callStaticMethod(
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

            for (String methodName : new String[] {"d", "e", "f", "g"}) {
                XposedHelpers.findAndHookMethod(
                        badgeManagerClass,
                        methodName,
                        videoPayInfoClass,
                        badgeHook);
            }
            XposedHelpers.findAndHookMethod(
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
            boolean isVip = Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    badgeManagerClass,
                    "k",
                    payInfo));
            boolean isLimitedFree = Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
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
            Class<?> mainActivityClass = XposedHelpers.findClass(
                    "com.dragon.read.pages.main.MainFragmentActivity",
                    classLoader);
            Class<?> tabTypeClass = XposedHelpers.findClass(
                    "com.dragon.read.rpc.model.BottomTabBarItemType",
                    classLoader);
            Object myProfile = XposedHelpers.getStaticObjectField(tabTypeClass, "MyProfile");
            Object bookStore = XposedHelpers.getStaticObjectField(tabTypeClass, "BookStore");

            XposedHelpers.findAndHookMethod(
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

            XposedHelpers.findAndHookMethod(
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

            XposedHelpers.findAndHookMethod(
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

            XposedHelpers.findAndHookMethod(
                    mainActivityClass,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (embeddedLspatchEnvironment) {
                                scheduleEmbeddedSettingsEntry(activity, 0);
                            }
                            scheduleBottomTabVisibility(activity, 0);
                            if (startupPrimary == SettingsContract.STARTUP_NONE
                                    || !STARTUP_NAVIGATION_PENDING.get()
                                    || !isLauncherLaunch(activity)) {
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
                Uri.parse("content://com.t8numen.hongguocache1080.settings/config"),
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

    private static void scheduleEmbeddedSettingsEntry(Activity activity, int attempt) {
        WeakReference<Activity> activityReference = new WeakReference<>(activity);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> {
                    Activity currentActivity = activityReference.get();
                    if (currentActivity == null
                            || currentActivity.isFinishing()
                            || currentActivity.isDestroyed()) {
                        return;
                    }
                    if (installEmbeddedSettingsEntry(currentActivity) || attempt >= 12) {
                        return;
                    }
                    scheduleEmbeddedSettingsEntry(currentActivity, attempt + 1);
                },
                attempt == 0 ? 150L : 400L);
    }

    private static boolean installEmbeddedSettingsEntry(Activity activity) {
        TextView mineTab = findLowestTextView(
                activity.getWindow().getDecorView(),
                "我的",
                null);
        View settingsEntry = findClickableViewOrSelf(mineTab);
        if (settingsEntry == null) {
            return false;
        }
        synchronized (EMBEDDED_SETTINGS_ENTRY_VIEWS) {
            if (!EMBEDDED_SETTINGS_ENTRY_VIEWS.add(settingsEntry)) {
                return true;
            }
        }
        settingsEntry.setLongClickable(true);
        settingsEntry.setOnLongClickListener(view -> {
            showEmbeddedSettingsDialog(activity);
            return true;
        });
        boolean showHint;
        synchronized (EMBEDDED_SETTINGS_HINT_ACTIVITIES) {
            showHint = EMBEDDED_SETTINGS_HINT_ACTIVITIES.add(activity);
        }
        if (showHint) {
            Toast.makeText(
                            activity,
                            "长按“我的”进入模块设置",
                            Toast.LENGTH_LONG)
                    .show();
        }
        XposedBridge.log(LOG_PREFIX + "embedded settings entry installed");
        return true;
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
        Switch homeIdRotationSwitch = addEmbeddedSetting(
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
        fixedFeatureNote.setText("模块自带短剧切集广告拦截，无需额外开启；强制更新弹窗拦截也固定开启。保存后请完全关闭并重新打开红果。");
        fixedFeatureNote.setTextSize(13.0f);
        fixedFeatureNote.setAlpha(0.72f);
        fixedFeatureNote.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        content.addView(fixedFeatureNote);

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
                    boolean homeIdRotation = homeIdRotationSwitch.isChecked();
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
                && "dragon8662".equals(data.getScheme())
                && "main".equals(data.getHost())) {
            return (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0;
        }
        return activity.isTaskRoot()
                && data == null
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && (Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getAction() == null);
    }

    private static void scheduleStartupNavigation(Activity activity, int attempt) {
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
        EARLY_REDIRECT_ACTIVE.set(false);
        XposedBridge.log(LOG_PREFIX + "startup navigation completed: "
                + primaryLabel + "/" + startupSecondary);
    }

    private static void retryStartupNavigation(Activity activity, int attempt) {
        if (attempt >= 15) {
            STARTUP_NAVIGATION_PENDING.set(false);
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

    private static View findClickableViewOrSelf(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current.isClickable() && current.isEnabled()) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return view;
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
            XposedHelpers.findAndHookMethod(
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
            XposedHelpers.findAndHookMethod(
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
            Object value = XposedHelpers.callMethod(toast, "getView");
            if (value instanceof View) {
                view = (View) value;
            }
        } catch (Throwable ignored) {
        }
        if (view == null) {
            try {
                Object toastPresenter = XposedHelpers.getObjectField(toast, "mTN");
                Object value = XposedHelpers.getObjectField(toastPresenter, "mNextView");
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
            XposedHelpers.findAndHookMethod(
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
            XposedHelpers.findAndHookMethod(
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
