package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** UI lives in the target app so exporting its private data never requires Root. */
final class AdDiagnosticUi {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CREATE_DOCUMENT = 0x4847;
    private static final AtomicBoolean EXPORTING = new AtomicBoolean();
    private static WeakReference<Activity> pendingActivity = new WeakReference<>(null);
    private static String pendingName;

    private AdDiagnosticUi() {
    }

    static void addButtons(Activity activity, LinearLayout content) {
        AdBlocking.addSettings(activity, content);
        button(activity, content, "广告诊断设置", () -> show(activity));
        button(activity, content, "导出广告诊断到 Download", () -> AdDiagnostics.export(activity));
    }

    static void addRootShortcut(Activity activity, LinearLayout content) {
        String host = TargetApp.selected(activity);
        rootShortcut(activity, content, host, "广告拦截设置", AdBlocking.OPEN_SETTINGS);
        rootShortcut(activity, content, host, "广告诊断与导出", AdDiagnostics.OPEN_SETTINGS);
    }

    private static void rootShortcut(Activity activity, LinearLayout content,
            String host, String label, String extra) {
        button(activity, content, "在" + TargetApp.label(host) + "内打开" + label, () -> {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(host);
            if (launch == null) {
                Toast.makeText(activity, "未安装红果，无法打开设置", Toast.LENGTH_LONG).show();
                return;
            }
            launch.putExtra(extra, true);
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(launch);
        });
    }

    static void show(Activity activity) {
        if (!available(activity)) {
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        Switch enabled = new Switch(activity);
        enabled.setText("记录广告诊断（即时生效）");
        enabled.setMinHeight(Math.round(48 * activity.getResources().getDisplayMetrics().density));
        enabled.setChecked(AdDiagnostics.isEnabled());
        content.addView(enabled);
        TextView summary = new TextView(activity);
        summary.setText(AdDiagnostics.summary());
        summary.setTextSize(14);
        summary.setPadding(0, padding / 2, 0, padding / 2);
        content.addView(summary);
        Button marker = button(activity, content, "标记刚才出现广告", () -> {
            WeakReference<Activity> reference = new WeakReference<>(activity);
            AdDiagnostics.mark(() -> MAIN.post(() -> {
                Activity current = reference.get();
                if (available(current)) {
                    Toast.makeText(current, "已标记刚才广告并保存上下文", Toast.LENGTH_SHORT).show();
                }
            }), error -> failure(reference, error));
        });
        marker.setEnabled(enabled.isChecked());
        enabled.setOnCheckedChangeListener((view, value) -> {
            AdDiagnostics.setEnabled(value);
            marker.setEnabled(value);
        });
        button(activity, content, "检查诊断记录功能", () -> {
            WeakReference<Activity> reference = new WeakReference<>(activity);
            AdDiagnostics.checkRecording(() -> MAIN.post(() -> {
                Activity current = reference.get();
                if (available(current)) {
                    Toast.makeText(current, "测试记录已保存，可导出核对", Toast.LENGTH_LONG).show();
                }
            }), error -> failure(reference, error));
        });
        button(activity, content, "导出到 Download", () -> AdDiagnostics.export(activity));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("广告诊断")
                .setView(scroll).setPositiveButton("关闭", null).show();
    }

    static void export(Activity activity) {
        if (!EXPORTING.compareAndSet(false, true)) {
            Toast.makeText(activity, "正在导出，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = "红果广告诊断-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
                + "-" + Long.toString(SystemClock.elapsedRealtime(), 36) + ".zip";
        WeakReference<Activity> reference = new WeakReference<>(activity);
        Context context = activity.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 29) {
            AdDiagnostics.withStore(store -> {
                Uri uri = null;
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/" + AdDiagnosticStore.DIRECTORY);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = context.getContentResolver().insert(
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                    if (uri == null) {
                        throw new IOException("无法创建 Download 导出文件");
                    }
                    writeZip(store.snapshot(), context.getContentResolver().openOutputStream(uri, "w"));
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (context.getContentResolver().update(uri, values, null, null) != 1) {
                        throw new IOException("无法完成 Download 导出");
                    }
                    success(reference, downloadPath(filename));
                } catch (Exception error) {
                    if (uri != null) {
                        try {
                            context.getContentResolver().delete(uri, null, null);
                        } catch (RuntimeException ignored) {
                            // Report the original export failure.
                        }
                    }
                    throw error;
                }
            }, error -> failure(reference, error));
        } else {
            pendingActivity = reference;
            pendingName = filename;
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, filename)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
            try {
                activity.startActivityForResult(create, CREATE_DOCUMENT);
            } catch (RuntimeException error) {
                pendingActivity.clear();
                pendingName = null;
                failure(reference, error);
            }
        }
    }

    static void onActivityResult(Activity activity, int request, int result, Intent data) {
        if (request != CREATE_DOCUMENT || pendingName == null || !EXPORTING.get()) {
            return;
        }
        WeakReference<Activity> reference = new WeakReference<>(activity);
        String filename = pendingName;
        pendingActivity = new WeakReference<>(null);
        pendingName = null;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            EXPORTING.set(false);
            return;
        }
        Uri uri = data.getData();
        Context context = activity.getApplicationContext();
        AdDiagnostics.withStore(store -> {
            writeZip(store.snapshot(), context.getContentResolver().openOutputStream(uri, "w"));
            success(reference, "已保存到系统选择的位置：\n" + filename + "\n" + uri);
        }, error -> failure(reference, error));
    }

    private static void writeZip(List<File> files, OutputStream output) throws Exception {
        if (output == null) {
            throw new IOException("无法打开导出文件");
        }
        JSONObject manifest = new JSONObject();
        manifest.put("format", 1);
        manifest.put("module", BuildConfig.VERSION_NAME);
        manifest.put("exported_wall_ms", System.currentTimeMillis());
        manifest.put("note", "候选/已阻止不等于展示；user_marked为人工标记，diagnostic_self_test不是真实广告。");
        JSONArray entries = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] buffer = new byte[8192];
            for (File file : files) {
                zip.putNextEntry(new ZipEntry(file.getName()));
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (FileInputStream input = new FileInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        zip.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        length += count;
                    }
                }
                zip.closeEntry();
                JSONObject entry = new JSONObject();
                entry.put("file", file.getName());
                entry.put("bytes", length);
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) {
                    hash.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                }
                entry.put("sha256", hash.toString());
                entries.put(entry);
            }
            manifest.put("files", entries);
            zip.putNextEntry(new ZipEntry("导出说明.json"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    @SuppressWarnings("deprecation")
    private static String downloadPath(String filename) {
        // Display only. Writes above use MediaStore and its returned URI.
        return new File(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), AdDiagnosticStore.DIRECTORY), filename).getAbsolutePath();
    }

    private static void success(WeakReference<Activity> reference, String path) {
        EXPORTING.set(false);
        MAIN.post(() -> {
            Activity activity = reference.get();
            if (available(activity)) {
                Toast.makeText(activity, "广告诊断已导出到 Download", Toast.LENGTH_LONG).show();
                TextView location = new TextView(activity);
                location.setText(path);
                location.setTextIsSelectable(true);
                int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
                location.setPadding(padding, padding / 2, padding, padding / 2);
                new AlertDialog.Builder(activity).setTitle("广告诊断导出完成")
                        .setView(location).setPositiveButton("知道了", null).show();
            }
        });
    }

    private static void failure(WeakReference<Activity> reference, Exception error) {
        EXPORTING.set(false);
        MAIN.post(() -> {
            Activity activity = reference.get();
            if (available(activity)) {
                new AlertDialog.Builder(activity).setTitle("广告诊断操作失败")
                        .setMessage(error.getClass().getSimpleName() + ": " + error.getMessage())
                        .setPositiveButton("知道了", null).show();
            }
        });
    }

    private static boolean available(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static Button button(Activity activity, LinearLayout content, String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(Math.round(48 * activity.getResources().getDisplayMetrics().density));
        button.setOnClickListener(view -> action.run());
        content.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }
}
