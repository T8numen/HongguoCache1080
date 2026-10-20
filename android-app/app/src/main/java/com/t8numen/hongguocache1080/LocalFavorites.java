package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Device-local series favorites used only while the target app is logged out. */
final class LocalFavorites {
    private static final String LOG_PREFIX = "[HongguoCache1080/LocalFavorites] ";
    private static final String MAIN_ACTIVITY =
            "com.dragon.read.pages.main.MainFragmentActivity";
    private static final String PLAYER_ACTIVITY =
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity";
    private static final String PREFERENCES_NAME = "hongguo_cache_1080_local_favorites";
    private static final String KEY_ENTRIES = "entries";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean LOGIN_ERROR_LOGGED = new AtomicBoolean(false);

    private static ClassLoader targetClassLoader;
    private static volatile boolean enabled = SettingsContract.DEFAULT_LOCAL_FAVORITES;
    private static WeakReference<Activity> overlayActivity = new WeakReference<>(null);
    private static WeakReference<View> overlayView = new WeakReference<>(null);

    private LocalFavorites() {
    }

    static void configure(boolean isEnabled) {
        enabled = isEnabled;
        if (!isEnabled) {
            removeOverlay();
        }
    }

    static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        targetClassLoader = classLoader;
        try {
            Class<?> mainClass = XposedHelpers.findClass(MAIN_ACTIVITY, classLoader);
            XposedHelpers.findAndHookMethod(mainClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    scheduleSync((Activity) param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(mainClass, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (overlayActivity.get() == param.thisObject) {
                        removeOverlay();
                    }
                }
            });
            installCollectClickHook(classLoader, "a");
            installCollectClickHook(classLoader, "b");
            installCatalogCollectClickHook(classLoader);
            XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled) {
                        return;
                    }
                    Activity activity = findActivity(((View) param.thisObject).getContext());
                    if (activity != null && MAIN_ACTIVITY.equals(activity.getClass().getName())) {
                        scheduleSync(activity);
                    }
                }
            });
            XposedBridge.log(LOG_PREFIX + "logged-out favorites hooks installed");
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "hook installation failed; native favorites unchanged");
            XposedBridge.log(error);
        }
    }

    private static void installCollectClickHook(ClassLoader classLoader, String variant) {
        try {
            Class<?> collectView = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.ssbiz.collect.ui." + variant,
                    classLoader);
            XposedHelpers.findAndHookMethod(collectView, "b", collectView, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    View view = (View) param.args[0];
                    if (interceptCollectClick(view, variant)) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "collect click hook unavailable: " + variant);
            XposedBridge.log(error);
        }
    }

    private static void installCatalogCollectClickHook(ClassLoader classLoader) {
        try {
            Class<?> clickListener = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.impl.catalog.l3", classLoader);
            XposedHelpers.findAndHookMethod(clickListener, "onClick", View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            View view = (View) param.args[0];
                            int buttonId = view.getResources().getIdentifier(
                                    "c7z", "id", "com.phoenix.read");
                            if (buttonId != 0 && view.getId() == buttonId
                                    && interceptCollectClick(view, "catalog")) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "catalog collect click hook unavailable");
            XposedBridge.log(error);
        }
    }

    private static boolean interceptCollectClick(View view, String source) {
        if (!enabled) {
            return false;
        }
        Activity activity = findActivity(view.getContext());
        if (activity == null || !PLAYER_ACTIVITY.equals(activity.getClass().getName())
                || !Boolean.FALSE.equals(isLoggedIn())) {
            return false;
        }
        toggleCurrentSeries(activity);
        XposedBridge.log(LOG_PREFIX + "native collect click intercepted: " + source);
        return true;
    }

    private static Boolean isLoggedIn() {
        try {
            Class<?> dependClass = XposedHelpers.findClass(
                    "com.dragon.read.NsCommonDepend", targetClassLoader);
            Object depend = XposedHelpers.getStaticObjectField(dependClass, "IMPL");
            Object manager = XposedHelpers.callMethod(depend, "acctManager");
            Object value = XposedHelpers.callMethod(manager, "islogin");
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable error) {
            if (LOGIN_ERROR_LOGGED.compareAndSet(false, true)) {
                XposedBridge.log(LOG_PREFIX + "login state unavailable; native favorites unchanged");
                XposedBridge.log(error);
            }
            return null;
        }
    }

    private static void toggleCurrentSeries(Activity activity) {
        String[] currentSeries = PlaybackEnhancements.currentSeriesForLocalFavorite();
        if (currentSeries == null) {
            Toast.makeText(activity, "尚未识别当前剧集", Toast.LENGTH_SHORT).show();
            return;
        }
        String seriesId = currentSeries[0];
        List<Entry> entries = readEntries(activity);
        if (entries == null) {
            Toast.makeText(activity, "本地收藏读取失败，未修改", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean removed = entries.removeIf(entry -> seriesId.equals(entry.id));
        if (!removed) {
            String title = currentSeries[1].isEmpty() ? "剧集 " + seriesId : currentSeries[1];
            entries.add(0, new Entry(seriesId, title));
        }
        if (writeEntries(activity, entries)) {
            Toast.makeText(activity,
                    removed ? "已从本地收藏移除" : "已加入本地收藏",
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, "本地收藏保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static void scheduleSync(Activity activity) {
        if (!enabled) {
            return;
        }
        WeakReference<Activity> reference = new WeakReference<>(activity);
        MAIN_HANDLER.postDelayed(() -> syncOverlay(reference.get()), 180L);
        MAIN_HANDLER.postDelayed(() -> syncOverlay(reference.get()), 500L);
    }

    private static void syncOverlay(Activity activity) {
        if (!enabled || activity == null || activity.isFinishing() || activity.isDestroyed()
                || !MAIN_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        TextView mineTab = findTextView(decor, "我的");
        TextView favoritesTab = findTextView(decor, "收藏");
        if (mineTab == null || favoritesTab == null
                || !isSelected(mineTab) || !isSelected(favoritesTab)
                || !Boolean.FALSE.equals(isLoggedIn())
                || !(decor instanceof FrameLayout)) {
            removeOverlay();
            return;
        }
        if (overlayActivity.get() == activity && overlayView.get() != null) {
            return;
        }
        View favoriteTabContainer = (View) favoritesTab.getParent();
        View mineTabContainer = (View) mineTab.getParent();
        int[] decorLocation = new int[2];
        int[] topLocation = new int[2];
        int[] bottomLocation = new int[2];
        decor.getLocationOnScreen(decorLocation);
        favoriteTabContainer.getLocationOnScreen(topLocation);
        mineTabContainer.getLocationOnScreen(bottomLocation);
        int top = topLocation[1] + favoriteTabContainer.getHeight() - decorLocation[1];
        int bottom = bottomLocation[1] - decorLocation[1];
        if (bottom - top < dp(activity, 120)) {
            return;
        }
        List<Entry> entries = readEntries(activity);
        if (entries == null) {
            return;
        }
        removeOverlay();
        View overlay = createOverlay(activity, entries);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bottom - top);
        params.topMargin = top;
        ((FrameLayout) decor).addView(overlay, params);
        overlayActivity = new WeakReference<>(activity);
        overlayView = new WeakReference<>(overlay);
    }

    private static View createOverlay(Activity activity, List<Entry> entries) {
        TypedArray colors = activity.obtainStyledAttributes(new int[]{
                android.R.attr.colorBackground,
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary});
        int background = colors.getColor(0, Color.WHITE);
        int primary = colors.getColor(1, Color.BLACK);
        int secondary = colors.getColor(2, Color.GRAY);
        colors.recycle();

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(background);
        content.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), 0);
        content.setClickable(true);

        TextView title = new TextView(activity);
        title.setText("本地收藏 · " + entries.size() + " 部");
        title.setTextColor(primary);
        title.setTextSize(18);
        content.addView(title);

        TextView hint = new TextView(activity);
        hint.setText("仅保存在这台手机 · 点击播放，长按移除");
        hint.setTextColor(secondary);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(activity, 5);
        hintParams.bottomMargin = dp(activity, 12);
        content.addView(hint, hintParams);

        if (entries.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("暂无本地收藏\n播放时点星标即可添加");
            empty.setTextColor(secondary);
            empty.setTextSize(15);
            empty.setGravity(android.view.Gravity.CENTER);
            content.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return content;
        }

        List<String> titles = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            titles.add(entry.title);
        }
        ListView list = new ListView(activity);
        list.setAdapter(new ArrayAdapter<String>(
                activity, android.R.layout.simple_list_item_1, titles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(primary);
                row.setTextSize(16);
                row.setMinimumHeight(dp(activity, 56));
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) ->
                openSeries(activity, entries.get(position).id));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Entry entry = entries.get(position);
            new AlertDialog.Builder(activity)
                    .setTitle("移除本地收藏？")
                    .setMessage(entry.title)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除", (dialog, which) -> {
                        List<Entry> latest = readEntries(activity);
                        if (latest != null && latest.removeIf(item -> entry.id.equals(item.id))
                                && writeEntries(activity, latest)) {
                            removeOverlay();
                            syncOverlay(activity);
                        }
                    })
                    .show();
            return true;
        });
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return content;
    }

    private static void openSeries(Activity activity, String seriesId) {
        try {
            Class<?> argsClass = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.api.model.ShortSeriesLaunchArgs",
                    targetClassLoader);
            Object args = XposedHelpers.newInstance(argsClass);
            XposedHelpers.callMethod(args, "d", activity);
            XposedHelpers.callMethod(args, "n", seriesId);
            Class<?> apiClass = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.api.ShortSeriesApi",
                    targetClassLoader);
            Class<?> serviceManager = XposedHelpers.findClass(
                    "com.bytedance.news.common.service.manager.ServiceManager",
                    targetClassLoader);
            Object api = XposedHelpers.callStaticMethod(serviceManager, "getService", apiClass);
            if (api == null) {
                throw new IllegalStateException("ShortSeriesApi unavailable");
            }
            XposedHelpers.callMethod(api, "openShortSeriesActivity", args);
        } catch (Throwable error) {
            XposedBridge.log(LOG_PREFIX + "open local favorite failed: " + seriesId);
            XposedBridge.log(error);
            Toast.makeText(activity, "打开剧集失败，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    private static List<Entry> readEntries(Context context) {
        String raw = preferences(context).getString(KEY_ENTRIES, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            List<Entry> entries = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                if (!id.isEmpty()) {
                    entries.add(new Entry(id, item.optString("title", "剧集 " + id)));
                }
            }
            return entries;
        } catch (JSONException error) {
            XposedBridge.log(LOG_PREFIX + "local favorites data unreadable; not overwriting");
            XposedBridge.log(error);
            return null;
        }
    }

    private static boolean writeEntries(Context context, List<Entry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("title", entry.title);
                array.put(item);
            }
            preferences(context).edit().putString(KEY_ENTRIES, array.toString()).apply();
            return true;
        } catch (JSONException error) {
            XposedBridge.log(LOG_PREFIX + "local favorites serialization failed");
            XposedBridge.log(error);
            return false;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void removeOverlay() {
        View overlay = overlayView.get();
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlayActivity = new WeakReference<>(null);
        overlayView = new WeakReference<>(null);
    }

    private static TextView findTextView(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && text.contentEquals(value)) {
                return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findTextView(group.getChildAt(index), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    private static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    private static final class Entry {
        final String id;
        final String title;

        Entry(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }
}
