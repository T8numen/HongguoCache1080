package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Blocks native login pages independently of the local favorites setting. */
final class LoginNavigationBlocker {
    private static final String LOG_PREFIX = "[HongguoCache1080/Login] ";
    private static final String LOGIN_ACTIVITY =
            "com.dragon.read.component.biz.impl.mine.LoginActivity";
    private static final String HALF_SCREEN_LOGIN_ACTIVITY =
            "com.dragon.read.component.biz.impl.mine.HalfScreenLoginActivity";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private LoginNavigationBlocker() {
    }

    static void install(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            for (Object argument : param.args) {
                                if (argument instanceof Intent && isLoginIntent((Intent) argument)) {
                                    param.setResult(null);
                                    showBlocked(findContext(param.args), "navigation blocked");
                                    return;
                                }
                            }
                        }
                    });
            XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivities",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            for (int index = 0; index < param.args.length; index++) {
                                if (!(param.args[index] instanceof Intent[])) {
                                    continue;
                                }
                                Intent[] intents = (Intent[]) param.args[index];
                                List<Intent> retained = new ArrayList<>();
                                for (Intent intent : intents) {
                                    if (!isLoginIntent(intent)) {
                                        retained.add(intent);
                                    }
                                }
                                if (retained.size() != intents.length) {
                                    if (retained.isEmpty()) {
                                        param.setResult(null);
                                    } else {
                                        param.args[index] = retained.toArray(new Intent[0]);
                                    }
                                    showBlocked(findContext(param.args), "batch navigation blocked");
                                }
                                return;
                            }
                        }
                    });
            XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.args[0];
                            if (!param.hasThrowable() && !activity.isFinishing()
                                    && isLoginActivity(activity.getClass().getName())) {
                                // Keep the normal onCreate lifecycle for system-restored activities.
                                activity.finish();
                                showBlocked(activity, "restored login page closed");
                            }
                        }
                    });
            XposedBridge.log(LOG_PREFIX + "default login navigation blocker installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "login hook installation failed");
            XposedBridge.log(error);
        }
        try {
            XposedHelpers.findAndHookMethod("com.dragon.read.component.biz.impl.NsMineImpl",
                    classLoader, "handleLoginGuidance", Context.class, String.class,
                    String.class, String.class, Runnable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if ("mine_tab".equals(param.args[1])
                                    && "mine_tab_guide".equals(param.args[2])
                                    && "mine".equals(param.args[3])
                                    && Boolean.TRUE.equals(param.getResult())) {
                                // The login launch was blocked; let Main continue selecting MyProfile.
                                param.setResult(false);
                                XposedBridge.log(LOG_PREFIX + "My login guidance released; continue guest profile");
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "My login guidance hook unavailable");
            XposedBridge.log(error);
        }
    }

    private static boolean isLoginIntent(Intent intent) {
        ComponentName component = intent == null ? null : intent.getComponent();
        return component != null && TargetApp.current().equals(component.getPackageName())
                && isLoginActivity(component.getClassName());
    }

    private static boolean isLoginActivity(String className) {
        return LOGIN_ACTIVITY.equals(className) || HALF_SCREEN_LOGIN_ACTIVITY.equals(className);
    }

    private static Context findContext(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof Context) {
                return (Context) argument;
            }
        }
        return null;
    }

    private static void showBlocked(Context context, String reason) {
        XposedBridge.log(LOG_PREFIX + reason);
        if (context != null) {
            Context application = context.getApplicationContext();
            Context toastContext = application == null ? context : application;
            MAIN_HANDLER.post(() -> Toast.makeText(
                    toastContext, "已阻止跳转登录", Toast.LENGTH_SHORT).show());
        }
    }
}
