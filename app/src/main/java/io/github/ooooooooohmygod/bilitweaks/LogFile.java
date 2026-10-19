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
 * 模块日志写 BiliTweaks.log，哔哩哔哩日志镜像写 BiliLog.log；哔哩哔哩各进程共用一个文件，
 * 每行带进程标签，只追加不滚动，统一由设置里的「清理日志」清空。
 * Android 10+ 走 MediaStore 的 Downloads 集合（免存储权限），低版本直接写公共目录；
 * 两边都写不进去时退回哔哩哔哩的应用外部目录，保证日志尽量能落盘。
 */
final class LogFile {
    private static final String FOLDER = "BiliTweaks";
    private static final String MODULE_FILE = "BiliTweaks.log";
    private static final String BILI_FILE = "BiliLog.log";

    private static Context context;
    private static String processTag = "main";
    private static Sink moduleSink;
    private static Sink biliSink;

    private LogFile() {}

    static void init(Context value, String processName, boolean moduleEnabled, boolean biliEnabled) {
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        processTag = tag(processName);
        // 先处理积压的「清理日志」请求，再接管日志文件，避免清理后马上又写回旧文件
        LogCleaner.start(context);
        Sink module = moduleEnabled ? new Sink(MODULE_FILE, processTag) : null;
        Sink bili = biliEnabled ? new Sink(BILI_FILE, processTag) : null;
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

    /** 进程标签：主进程 main，其余取冒号后的名字（如 download / web）。 */
    private static String tag(String processName) {
        if (processName == null || processName.isEmpty()) return "main";
        int colon = processName.indexOf(':');
        String tail = colon >= 0 ? processName.substring(colon + 1) : "";
        return tail.isEmpty() ? "main" : tail;
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** 一个落盘目标：一个文件，只追加不滚动；文件被清理或写失败时，下次写入自动重找/重建。 */
    static final class Sink {
        private final String fileName;
        private final String tag;
        private final Object lock = new Object();

        private Uri mediaUri;
        private File directFile;
        private boolean disabled;
        private int failures;

        Sink(String fileName, String tag) {
            this.fileName = fileName;
            this.tag = tag;
        }

        void append(String line) {
            appendAll(Collections.singletonList(line));
        }

        void appendAll(List<String> lines) {
            if (disabled || lines.isEmpty()) return;
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                text.append(timestamp()).append(" [").append(tag).append("] ").append(line).append('\n');
            }
            synchronized (lock) {
                try {
                    write(text.toString());
                    failures = 0;
                } catch (Throwable t) {
                    failures++;
                    if (failures >= 8 && !disabled) {
                        disabled = true;
                        HookRuntime.log("日志连续写入失败，本进程已暂停写 " + fileName);
                    }
                }
            }
        }

        private void write(String text) throws Exception {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    writeDownload(bytes);
                    return;
                } catch (Throwable ignored) {
                }
            }
            FileOutputStream out = new FileOutputStream(directFile(), true);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        }

        /** 追加到 MediaStore 的 Downloads；文件被「清理日志」删掉时自动重建。 */
        private void writeDownload(byte[] bytes) throws Exception {
            ContentResolver resolver = context.getContentResolver();
            Throwable last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (mediaUri == null) {
                    mediaUri = findExisting(resolver);
                    if (mediaUri == null) {
                        mediaUri = create(resolver);
                    }
                }
                if (mediaUri == null) {
                    last = new IllegalStateException("无法创建 Download 日志");
                    continue;
                }
                OutputStream out = null;
                try {
                    out = resolver.openOutputStream(mediaUri, "wa");
                } catch (Throwable t) {
                    last = t;
                }
                if (out == null) {
                    mediaUri = null;
                    continue;
                }
                try {
                    out.write(bytes);
                    return;
                } catch (Throwable t) {
                    last = t;
                    mediaUri = null;
                } finally {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            throw last != null ? new Exception(last) : new Exception("无法写入 Download 日志");
        }

        private Uri findExisting(ContentResolver resolver) {
            try (Cursor cursor = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"Download/" + FOLDER + "%", fileName + "%"},
                    MediaStore.MediaColumns._ID + " ASC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    return ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
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
            Uri created;
            try {
                created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            } catch (Throwable t) {
                return null;
            }
            if (created == null) return null;
            return settle(resolver, created);
        }

        /** 并发首建时多个进程可能各建一个文件，统一收敛到 _ID 最小的那个，删掉自己多建的空文件。 */
        private Uri settle(ContentResolver resolver, Uri created) {
            Uri canonical = findExisting(resolver);
            if (canonical == null || canonical.equals(created)) return created;
            try {
                resolver.delete(created, null, null);
            } catch (Throwable ignored) {
            }
            return canonical;
        }

        @SuppressWarnings("deprecation")
        private File directFile() throws Exception {
            if (directFile != null) return directFile;
            File dir = null;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                dir = new File(Environment.getExternalStorageDirectory(), "Download/" + FOLDER);
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) dir = null;
            }
            if (dir == null) {
                File external = context.getExternalFilesDir(null);
                if (external == null) throw new Exception("无法访问日志目录");
                dir = new File(external, FOLDER);
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new Exception("无法创建日志目录");
            }
            directFile = new File(dir, fileName);
            return directFile;
        }
    }
}
