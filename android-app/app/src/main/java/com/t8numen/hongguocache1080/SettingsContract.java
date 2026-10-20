package com.t8numen.hongguocache1080;

import android.content.SharedPreferences;
import android.net.Uri;

public final class SettingsContract {
    public static final String PREFERENCES_NAME = "module_settings";
    public static final String KEY_DIRECT_FAVORITES = "direct_favorites";
    public static final String KEY_STARTUP_PRIMARY = "startup_primary";
    public static final String KEY_STARTUP_SECONDARY = "startup_secondary";
    public static final String KEY_BLOCK_HOME_VIDEO = "block_home_video";
    public static final String KEY_LOCAL_FAVORITES = "local_favorites";
    public static final String KEY_HOME_ID_ROTATION = "home_id_rotation";
    public static final String KEY_ONLINE_HIGHEST = "online_highest";
    public static final String KEY_DOWNLOAD_HIGHEST = "download_highest";
    public static final String KEY_VIP_EXCLUSIVE_BADGE = "vip_exclusive_badge";
    public static final String KEY_HIDE_THEATER_TAB = "hide_theater_tab";
    public static final String KEY_HIDE_MALL_TAB = "hide_mall_tab";
    public static final String KEY_HIDE_EARN_TAB = "hide_earn_tab";
    public static final boolean DEFAULT_DIRECT_FAVORITES = false;
    public static final int STARTUP_NONE = 0;
    public static final int STARTUP_MY = 1;
    public static final int STARTUP_THEATER = 2;
    public static final int MY_HOME = 0;
    public static final int MY_FAVORITES = 1;
    public static final int MY_LIKES = 2;
    public static final int MY_HISTORY = 3;
    public static final int DEFAULT_STARTUP_PRIMARY = STARTUP_NONE;
    public static final int DEFAULT_STARTUP_SECONDARY = MY_HOME;
    public static final boolean DEFAULT_BLOCK_HOME_VIDEO = true;
    public static final boolean DEFAULT_LOCAL_FAVORITES = false;
    public static final boolean DEFAULT_HOME_ID_ROTATION = true;
    public static final boolean DEFAULT_ONLINE_HIGHEST = true;
    public static final boolean DEFAULT_DOWNLOAD_HIGHEST = true;
    public static final boolean DEFAULT_VIP_EXCLUSIVE_BADGE = true;
    public static final boolean DEFAULT_HIDE_THEATER_TAB = false;
    public static final boolean DEFAULT_HIDE_MALL_TAB = false;
    public static final boolean DEFAULT_HIDE_EARN_TAB = false;
    public static final Uri CONTENT_URI = Uri.parse(
            "content://com.t8numen.hongguocache1080.settings/config");

    public static int startupPrimary(SharedPreferences preferences) {
        if (preferences.contains(KEY_STARTUP_PRIMARY)) {
            int value = preferences.getInt(KEY_STARTUP_PRIMARY, DEFAULT_STARTUP_PRIMARY);
            if (value >= STARTUP_NONE && value <= STARTUP_THEATER) {
                return value;
            }
        }
        return preferences.getBoolean(KEY_DIRECT_FAVORITES, DEFAULT_DIRECT_FAVORITES)
                ? STARTUP_MY : STARTUP_NONE;
    }

    public static int startupSecondary(SharedPreferences preferences) {
        if (preferences.contains(KEY_STARTUP_SECONDARY)) {
            int value = preferences.getInt(KEY_STARTUP_SECONDARY, DEFAULT_STARTUP_SECONDARY);
            if (value >= MY_HOME && value <= MY_HISTORY) {
                return value;
            }
        }
        return preferences.getBoolean(KEY_DIRECT_FAVORITES, DEFAULT_DIRECT_FAVORITES)
                ? MY_FAVORITES : MY_HOME;
    }

    private SettingsContract() {
    }
}
