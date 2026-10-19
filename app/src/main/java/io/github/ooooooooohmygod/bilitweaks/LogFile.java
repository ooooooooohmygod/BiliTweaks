package io.github.ooooooooohmygod.bilitweaks;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把模块日志追加到 Download/BiliTweaks/BiliTweaks-<进程>.log，方便直接在手机上取日志。
 * Android 10+ 走 MediaStore 的 Downloads 集合（免存储权限），低版本直接写公共目录；
 * 两边都写不进去时退回哔哩哔哩的应用外部目录，保证日志尽量能落盘。
 */
final class LogFile {
    private static final String FOLDER = "BiliTweaks";
    private static final long MAX_BYTES = 512 * 1024;

    private static final Object LOCK = new Object();
    private static final StringBuilder history = new StringBuilder();

    private static Context context;
    private static String fileName = "BiliTweaks-main.log";
    private static Uri mediaUri;
    private static File directFile;
    private static boolean enabled;
    private static boolean disabled;
    private static int failures;

    private LogFile() {}

    static void init(Context value, String processName, boolean fileLogEnabled) {
        synchronized (LOCK) {
            Context app = value.getApplicationContext();
            context = app != null ? app : value;
            fileName = "BiliTweaks-" + suffix(processName) + ".log";
            mediaUri = null;
            directFile = null;
            enabled = fileLogEnabled;
            disabled = false;
            failures = 0;
            history.setLength(0);
        }
        append("=== 日志开始（进程：" + processName + "）===");
    }

    static boolean enabled() {
        return enabled && !disabled && context != null;
    }

    static void append(String line) {
        if (!enabled()) return;
        String record = timestamp() + "  " + line;
        synchronized (LOCK) {
            try {
                write(record + "\n");
                if (history.length() < MAX_BYTES) {
                    history.append(record).append('\n');
                }
                failures = 0;
            } catch (Throwable t) {
                if (++failures >= 5) disabled = true;
            }
        }
    }

    private static void write(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                writeDownload(bytes, text);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (directFile == null) {
            directFile = prepareDirectFile();
        }
        FileOutputStream out = new FileOutputStream(directFile, true);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        if (directFile.length() > MAX_BYTES) {
            new FileOutputStream(directFile, false).close();
        }
    }

    private static void writeDownload(byte[] bytes, String text) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        if (mediaUri == null) {
            Uri existing = findExisting(resolver);
            if (existing != null && isTooLarge(resolver, existing)) {
                try {
                    resolver.delete(existing, null, null);
                } catch (Throwable ignored) {
                }
                existing = null;
                history.setLength(0);
            }
            mediaUri = existing != null ? existing : create(resolver);
        } else if (isTooLarge(resolver, mediaUri)) {
            try {
                resolver.delete(mediaUri, null, null);
            } catch (Throwable ignored) {
            }
            history.setLength(0);
            mediaUri = create(resolver);
        }
        if (mediaUri == null) throw new Exception("无法创建 Download 日志");
        OutputStream out = null;
        try {
            out = resolver.openOutputStream(mediaUri, "wa");
        } catch (Throwable ignored) {
        }
        if (out == null) {
            // 系统不支持追加模式时整文件重写（日志量小，代价可控）
            out = resolver.openOutputStream(mediaUri, "w");
            if (out == null) throw new Exception("无法写入 Download 日志");
            try {
                out.write((history + text).getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            return;
        }
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private static Uri findExisting(ContentResolver resolver) {
        try (Cursor cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{fileName},
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Uri create(ContentResolver resolver) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + FOLDER);
        try {
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTooLarge(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0) > MAX_BYTES;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static File prepareDirectFile() throws Exception {
        File dir = null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            dir = new File(Environment.getExternalStorageDirectory(), "Download/" + FOLDER);
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) dir = null;
        }
        if (dir == null) {
            dir = new File(context.getExternalFilesDir(null), FOLDER);
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new Exception("无法创建日志目录");
        }
        return new File(dir, fileName);
    }

    private static String suffix(String processName) {
        if (processName == null || processName.isEmpty()) return "main";
        int colon = processName.indexOf(':');
        String tail = colon >= 0 ? processName.substring(colon + 1) : processName;
        if (tail.isEmpty()) tail = "main";
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return safe.toString();
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
