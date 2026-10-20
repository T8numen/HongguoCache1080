package com.t8numen.hongguocache1080;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

public final class SettingsProvider extends ContentProvider {
    private static final String TARGET_PACKAGE = "com.phoenix.read";
    private SharedPreferences preferences;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        preferences = context.getSharedPreferences(SettingsContract.PREFERENCES_NAME, 0);
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        enforceAllowedCaller();
        String[] columns = projection == null
                ? new String[] {
                        SettingsContract.KEY_DIRECT_FAVORITES,
                        SettingsContract.KEY_STARTUP_PRIMARY,
                        SettingsContract.KEY_STARTUP_SECONDARY,
                        SettingsContract.KEY_BLOCK_HOME_VIDEO,
                        SettingsContract.KEY_LOCAL_FAVORITES,
                        SettingsContract.KEY_HOME_ID_ROTATION,
                        SettingsContract.KEY_ONLINE_HIGHEST,
                        SettingsContract.KEY_DOWNLOAD_HIGHEST,
                        SettingsContract.KEY_VIP_EXCLUSIVE_BADGE,
                        SettingsContract.KEY_HIDE_THEATER_TAB,
                        SettingsContract.KEY_HIDE_MALL_TAB,
                        SettingsContract.KEY_HIDE_EARN_TAB
                }
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (SettingsContract.KEY_DIRECT_FAVORITES.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_DIRECT_FAVORITES,
                        SettingsContract.DEFAULT_DIRECT_FAVORITES) ? 1 : 0;
            } else if (SettingsContract.KEY_STARTUP_PRIMARY.equals(columns[index])) {
                row[index] = SettingsContract.startupPrimary(preferences);
            } else if (SettingsContract.KEY_STARTUP_SECONDARY.equals(columns[index])) {
                row[index] = SettingsContract.startupSecondary(preferences);
            } else if (SettingsContract.KEY_BLOCK_HOME_VIDEO.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_BLOCK_HOME_VIDEO,
                        SettingsContract.DEFAULT_BLOCK_HOME_VIDEO) ? 1 : 0;
            } else if (SettingsContract.KEY_LOCAL_FAVORITES.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_LOCAL_FAVORITES,
                        SettingsContract.DEFAULT_LOCAL_FAVORITES) ? 1 : 0;
            } else if (SettingsContract.KEY_HOME_ID_ROTATION.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_HOME_ID_ROTATION,
                        SettingsContract.DEFAULT_HOME_ID_ROTATION) ? 1 : 0;
            } else if (SettingsContract.KEY_ONLINE_HIGHEST.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_ONLINE_HIGHEST,
                        SettingsContract.DEFAULT_ONLINE_HIGHEST) ? 1 : 0;
            } else if (SettingsContract.KEY_DOWNLOAD_HIGHEST.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_DOWNLOAD_HIGHEST,
                        SettingsContract.DEFAULT_DOWNLOAD_HIGHEST) ? 1 : 0;
            } else if (SettingsContract.KEY_VIP_EXCLUSIVE_BADGE.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_VIP_EXCLUSIVE_BADGE,
                        SettingsContract.DEFAULT_VIP_EXCLUSIVE_BADGE) ? 1 : 0;
            } else if (SettingsContract.KEY_HIDE_THEATER_TAB.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_HIDE_THEATER_TAB,
                        SettingsContract.DEFAULT_HIDE_THEATER_TAB) ? 1 : 0;
            } else if (SettingsContract.KEY_HIDE_MALL_TAB.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_HIDE_MALL_TAB,
                        SettingsContract.DEFAULT_HIDE_MALL_TAB) ? 1 : 0;
            } else if (SettingsContract.KEY_HIDE_EARN_TAB.equals(columns[index])) {
                row[index] = preferences.getBoolean(
                        SettingsContract.KEY_HIDE_EARN_TAB,
                        SettingsContract.DEFAULT_HIDE_EARN_TAB) ? 1 : 0;
            } else {
                row[index] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.t8numen.hongguocache1080.settings";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Settings provider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Settings provider is read-only");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Settings provider is read-only");
    }

    private void enforceAllowedCaller() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return;
        }
        Context context = getContext();
        String[] packages = context == null
                ? null
                : context.getPackageManager().getPackagesForUid(callingUid);
        if (packages != null) {
            for (String packageName : packages) {
                if (TARGET_PACKAGE.equals(packageName)) {
                    return;
                }
            }
        }
        throw new SecurityException("Caller is not allowed to read module settings");
    }
}
