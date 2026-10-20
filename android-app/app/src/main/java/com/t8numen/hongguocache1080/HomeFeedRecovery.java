package com.t8numen.hongguocache1080;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class HomeFeedRecovery {
    private static final String LOG_PREFIX = "[HongguoCache1080/HomeRecovery] ";
    private static final String PREFERENCES_NAME = "hongguo_cache_1080_home_recovery";
    private static final String KEY_DID = "request_did";
    private static final String KEY_IID = "request_iid";
    private static final String KEY_LAST_ROTATION = "last_rotation_wall_time";
    private static final long MIN_ROTATION_INTERVAL_MS = 60_000L;
    private static final Pattern DID_QUERY = Pattern.compile("[?&]device_id=([^&#]*)");
    private static final Pattern IID_QUERY = Pattern.compile("[?&]iid=([^&#]*)");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();
    private static final AtomicBoolean APPLIED_LOGGED = new AtomicBoolean();

    private static volatile Context appContext;
    private static volatile boolean enabled;
    private static volatile IdPair requestIds;
    private static volatile long lastRotationWallTime;

    private HomeFeedRecovery() {
    }

    static void install(ClassLoader classLoader) {
        try {
            Class<?> responseClass = XposedHelpers.findClass(
                    "com.dragon.read.rpc.model.BookstoreTabResponse", classLoader);
            XposedHelpers.findAndHookMethod("gh4.l0", classLoader, "invoke", Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!enabled || !responseClass.isInstance(param.args[0])) {
                                return;
                            }
                            try {
                                if (XposedHelpers.getObjectField(param.args[0], "data") == null) {
                                    rotateRequestIds();
                                }
                            } catch (Throwable error) {
                                XposedBridge.log(LOG_PREFIX + "home response fields unavailable");
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "home response hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "home response hook unavailable");
        }

        try {
            Class<?> level = XposedHelpers.findClass("com.bytedance.bdinstall.Level", classLoader);
            XposedHelpers.findAndHookMethod("com.ss.android.common.applog.NetUtil", classLoader,
                    "appendCommonParamsWithLevel", StringBuilder.class, boolean.class, level,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                IdPair ids = requestIds;
                                if (!enabled || ids == null
                                        || !(param.args[0] instanceof StringBuilder)) {
                                    return;
                                }
                                StringBuilder url = (StringBuilder) param.args[0];
                                if (url.indexOf("/bookmall/tab/") < 0) {
                                    return;
                                }
                                String path = Uri.parse(url.toString()).getPath();
                                if (path == null || !path.contains("/bookmall/tab/")
                                        || !DID_QUERY.matcher(url).find()
                                        || !IID_QUERY.matcher(url).find()) {
                                    return;
                                }
                                replaceQueryValue(url, DID_QUERY, ids.did);
                                replaceQueryValue(url, IID_QUERY, ids.iid);
                                if (APPLIED_LOGGED.compareAndSet(false, true)) {
                                    XposedBridge.log(LOG_PREFIX + "request DID/IID override applied");
                                }
                            } catch (Throwable error) {
                                XposedBridge.log(LOG_PREFIX + "request override skipped");
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "request parameter hook installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "request parameter hook unavailable");
        }
    }

    static void configure(Context context, boolean isEnabled) {
        try {
            Context applicationContext = context.getApplicationContext();
            appContext = applicationContext != null ? applicationContext : context;
            SharedPreferences preferences = appContext.getSharedPreferences(
                    PREFERENCES_NAME, Context.MODE_PRIVATE);
            String did = preferences.getString(KEY_DID, null);
            String iid = preferences.getString(KEY_IID, null);
            requestIds = isValidId(did) && isValidId(iid) ? new IdPair(did, iid) : null;
            lastRotationWallTime = preferences.getLong(KEY_LAST_ROTATION, 0L);
            enabled = isEnabled;
            XposedBridge.log(LOG_PREFIX + "enabled=" + enabled
                    + " storedIdsPresent=" + (requestIds != null));
        } catch (Throwable error) {
            enabled = false;
            requestIds = null;
            XposedBridge.log(LOG_PREFIX + "settings unavailable; recovery disabled");
        }
    }

    private static void rotateRequestIds() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                long elapsed = now - lastRotationWallTime;
                if (!enabled || (requestIds != null
                        && elapsed >= 0 && elapsed < MIN_ROTATION_INTERVAL_MS)) {
                    return;
                }
                String did = randomId();
                String iid;
                do {
                    iid = randomId();
                } while (did.equals(iid));
                IdPair ids = new IdPair(did, iid);
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_DID, ids.did)
                        .putString(KEY_IID, ids.iid)
                        .putLong(KEY_LAST_ROTATION, now)
                        .apply();
                requestIds = ids;
                lastRotationWallTime = now;
                APPLIED_LOGGED.set(false);
                XposedBridge.log(LOG_PREFIX + "home data empty; request DID/IID rotated");
            }
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                    context,
                    "首页数据为空，已随机更新 DID/IID，请点击重试",
                    Toast.LENGTH_LONG).show());
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "request ID rotation failed");
        }
    }

    private static void replaceQueryValue(StringBuilder url, Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            url.replace(matcher.start(1), matcher.end(1), value);
        }
    }

    private static String randomId() {
        long number = 1_000_000_000_000_000L
                + (RANDOM.nextLong() & Long.MAX_VALUE) % 9_000_000_000_000_000L;
        return Long.toString(number);
    }

    private static boolean isValidId(String value) {
        return value != null && value.matches("[0-9]{16}");
    }

    private static final class IdPair {
        final String did;
        final String iid;

        IdPair(String did, String iid) {
            this.did = did;
            this.iid = iid;
        }
    }
}
