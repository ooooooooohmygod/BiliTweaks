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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志落盘：把日志追加到 Download/BiliTweaks/ 下的文本文件，方便直接在手机上取日志。
 * Android 10+ 走 MediaStore 的 Downloads 集合（免存储权限），低版本直接写公共目录；
 * 两边都写不进去时退回哔哩哔哩的应用外部目录，保证日志尽量能落盘。
 */
final class LogFile {
    private static final String FOLDER = "BiliTweaks";

    private static Context context;
    private static String fileSuffix = "main";
    private static Sink moduleSink;
    private static Sink biliSink;

    private LogFile() {}

    static void init(Context value, String processName, boolean moduleEnabled, boolean biliEnabled) {
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        fileSuffix = suffix(processName);
        Sink module = moduleEnabled ? new Sink("BiliTweaks-" + fileSuffix + ".log", 512 * 1024) : null;
        Sink bili = biliEnabled ? new Sink("BiliLog-" + fileSuffix + ".log", 2 * 1024 * 1024) : null;
        moduleSink = module;
        biliSink = bili;
        if (module != null) {
            module.append("=== 日志开始（进程：" + processName + "）===");
        }
        if (bili != null) {
            bili.append("=== 哔哩哔哩日志镜像开始（进程：" + processName + "）===");
        }
    }

    /** 模块自己的日志。 */
    static void append(String line) {
        Sink sink = moduleSink;
        if (sink != null) sink.append(line);
    }

    /** 哔哩哔哩的日志（镜像）。 */
    static void appendBili(String line) {
        Sink sink = biliSink;
        if (sink != null) sink.append(line);
    }

    static void appendBili(List<String> lines) {
        Sink sink = biliSink;
        if (sink != null) sink.appendAll(lines);
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

    /** 一个落盘目标：一个文件，追加写入，超过上限后清空重来。 */
    static final class Sink {
        private final String fileName;
        private final long maxBytes;
        private final Object lock = new Object();
        private final StringBuilder history = new StringBuilder();

        private Uri mediaUri;
        private File directFile;
        private boolean disabled;
        private int failures;

        Sink(String fileName, long maxBytes) {
            this.fileName = fileName;
            this.maxBytes = maxBytes;
        }

        void append(String line) {
            appendAll(Collections.singletonList(line));
        }

        void appendAll(List<String> lines) {
            if (disabled || lines.isEmpty()) return;
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                text.append(timestamp()).append("  ").append(line).append('\n');
            }
            synchronized (lock) {
                try {
                    write(text.toString());
                    if (history.length() < maxBytes) history.append(text);
                    failures = 0;
                } catch (Throwable t) {
                    if (++failures >= 5) disabled = true;
                }
            }
        }

        private void write(String text) throws Exception {
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
            if (directFile.length() > maxBytes) {
                new FileOutputStream(directFile, false).close();
            }
        }

        private void writeDownload(byte[] bytes, String text) throws Exception {
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

        private Uri findExisting(ContentResolver resolver) {
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

        private Uri create(ContentResolver resolver) {
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

        private boolean isTooLarge(ContentResolver resolver, Uri uri) {
            try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getLong(0) > maxBytes;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        @SuppressWarnings("deprecation")
        private File prepareDirectFile() throws Exception {
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
    }
}
