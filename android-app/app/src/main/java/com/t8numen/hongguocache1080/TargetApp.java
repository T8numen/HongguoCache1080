package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Host identity is process-local; the module UI keeps a separate selected host. */
final class TargetApp {
    static final String SHORT = "com.phoenix.read";
    static final String MANJU = "com.kylin.read";
    static final String EXTRA = "hongguo_target_package";
    private static volatile String current = SHORT;

    private TargetApp() { }

    static boolean supported(String value) {
        return SHORT.equals(value) || MANJU.equals(value);
    }

    static void activate(String value) {
        if (!supported(value)) {
            throw new IllegalArgumentException("Unsupported host");
        }
        current = value;
    }

    static String current() {
        return current;
    }

    static boolean isManju() {
        return MANJU.equals(current);
    }

    static String launcherScheme() {
        return isManju() ? "dragon8704" : "dragon8662";
    }

    static String preferencesName(String host) {
        return MANJU.equals(host) ? "module_settings_manju" : SettingsContract.PREFERENCES_NAME;
    }

    static Uri settingsUri() {
        return SettingsContract.CONTENT_URI.buildUpon().appendQueryParameter("host", current).build();
    }

    static String selected(Activity activity) {
        String host = activity.getIntent().getStringExtra(EXTRA);
        if (!supported(host)) {
            host = activity.getSharedPreferences("module_ui", Context.MODE_PRIVATE)
                    .getString("selected_host", SHORT);
        }
        return supported(host) ? host : SHORT;
    }

    static String label(String host) {
        return MANJU.equals(host) ? "红果漫剧" : "红果短剧";
    }

    static SharedPreferences preferences(Context context, String host) {
        return context.getSharedPreferences(preferencesName(host), Context.MODE_PRIVATE);
    }
}
