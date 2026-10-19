package io.github.ooooooooohmygod.bilitweaks;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志落盘：把日志追加到 Download/BiliTweaks/ 下的文本文件，方便直接在手机上取日志。
 * 模块日志写 BiliTweaks.log，哔哩哔哩日志镜像写 BiliLog.log；哔哩哔哩各进程共用一个文件，
 * 每行带进程标签，只追加不滚动，统一由设置里的「打印日志」开关控制、「清理日志」清空。
 * 哔哩哔哩日志镜像最多 12MB，超出后自动裁掉较早内容、只留最近部分（模块日志不裁剪）。
 * Android 10+ 走 MediaStore 的 Downloads 集合（免存储权限），低版本直接写公共目录；
 * 两边都写不进去时退回哔哩哔哩的应用外部目录，保证日志尽量能落盘。
 */
final class LogFile {
    private static final String FOLDER = "BiliTweaks";
    private static final String MODULE_FILE = "BiliTweaks.log";
    private static final String BILI_FILE = "BiliLog.log";
    /** 哔哩哔哩日志镜像的单文件上限，以及触发裁剪后保留的尾部大小。 */
    private static final long BILI_MAX_BYTES = 12L * 1024 * 1024;
    private static final long BILI_KEEP_BYTES = 8L * 1024 * 1024;
    /** 累计写入这么多字节（或距上次检查超过一段时间）就校准一次文件状态。 */
    private static final long CHECK_STEP_BYTES = 1024L * 1024;
    private static final long CHECK_STEP_MS = 60_000L;
    /** 清理日志后给文件写的第一行说明。 */
    private static final String CLEARED_NOTE = "=== 日志已清空，以下为清空后新写入的日志 ===";

    private static Context context;
    private static String processTag = "main";
    private static Sink moduleSink;
    private static Sink biliSink;

    private LogFile() {}

    static void init(Context value, String processName, boolean logEnabled) {
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        processTag = tag(processName);
        // 先处理积压的「清理日志」请求，再接管日志文件，避免清理后马上又写回旧文件
        LogCleaner.start(context);
        Sink module = logEnabled ? new Sink(MODULE_FILE, processTag, 0L, 0L) : null;
        Sink bili = logEnabled ? new Sink(BILI_FILE, processTag, BILI_MAX_BYTES, BILI_KEEP_BYTES) : null;
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

    /**
     * 「清理日志」之后调用：立刻重建两个日志文件并各写一行说明。
     * 这样清完马上看到的就是「两个只有说明行的新文件」，而不是文件迟迟不出现（或陆续冒出来）让人以为漏删。
     */
    static boolean reseed() {
        if (context == null) return false;
        boolean module = reseedOne(MODULE_FILE);
        boolean bili = reseedOne(BILI_FILE);
        return module && bili;
    }

    private static boolean reseedOne(String name) {
        Sink sink = new Sink(name, "clean", 0L, 0L);
        sink.append(CLEARED_NOTE);
        return sink.failures == 0;
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

    /**
     * 一个落盘目标：一个文件，只追加不滚动；文件被清理或写失败时，下次写入自动重找/重建。
     * 哔哩哔哩各进程可能同时重建日志文件（各建一份重名文件），写入过程中会周期性收敛到同一份、
     * 删掉多出来的重名文件；超过上限时自动裁掉较早内容。系统偶尔会留下「查得到、写不进」的
     * 失效记录，同一个文件连续失败几次后会被删掉重建，保证日志始终有地方写。
     */
    static final class Sink {
        private final String fileName;
        private final String tag;
        /** 单文件上限；0 表示不裁剪。 */
        private final long maxBytes;
        /** 裁剪后保留的尾部字节数。 */
        private final long keepBytes;
        private final Object lock = new Object();

        private Uri mediaUri;
        private File directFile;
        private boolean disabled;
        private int failures;
        private long pendingBytes;
        private long lastCheckAt;
        /** 最近一个「能查到但写不进去」的文件（例如系统留下的失效记录），连续失败就清掉它。 */
        private Uri badUri;
        private int badCount;
        /** 上次已经提示过「Download 写不进去，暂存应用目录」，避免刷屏。 */
        private boolean fallbackLogged;

        Sink(String fileName, String tag, long maxBytes, long keepBytes) {
            this.fileName = fileName;
            this.tag = tag;
            this.maxBytes = maxBytes;
            this.keepBytes = keepBytes;
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
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (lock) {
                try {
                    write(bytes);
                    failures = 0;
                    pendingBytes += bytes.length;
                    checkIfNeeded();
                } catch (Throwable t) {
                    failures++;
                    if (failures >= 8 && !disabled) {
                        disabled = true;
                        HookRuntime.log("日志连续写入失败，本进程已暂停写 " + fileName);
                    }
                }
            }
        }

        private void write(byte[] bytes) throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    writeDownload(bytes);
                    fallbackLogged = false;
                    return;
                } catch (Throwable ignored) {
                    if (!fallbackLogged) {
                        fallbackLogged = true;
                        HookRuntime.log(fileName + "：Download 目录暂时写不进去，日志先存到应用目录");
                    }
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
                    noteBad(resolver, mediaUri);
                    mediaUri = null;
                    continue;
                }
                try {
                    out.write(bytes);
                    badUri = null;
                    badCount = 0;
                    return;
                } catch (Throwable t) {
                    last = t;
                    noteBad(resolver, mediaUri);
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

        /**
         * 同一个文件连续 3 次写不进去，就删掉它的记录、下次写入重新建一个。
         * 系统删除文件时偶尔会留下「有记录没文件」的残局，删掉记录才能恢复正常记录日志。
         */
        private void noteBad(ContentResolver resolver, Uri uri) {
            if (uri == null) return;
            if (uri.equals(badUri)) {
                badCount++;
            } else {
                badUri = uri;
                badCount = 1;
            }
            if (badCount < 3) return;
            badUri = null;
            badCount = 0;
            try {
                resolver.delete(uri, null, null);
                HookRuntime.log(fileName + "：清理了一条失效的日志记录，稍后自动重建");
            } catch (Throwable ignored) {
            }
        }

        private Uri findExisting(ContentResolver resolver) {
            long[] canonical = scanFiles(resolver, null);
            return canonical == null ? null
                    : ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, canonical[0]);
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

        /** 并发首建时多个进程可能各建一个文件，统一收敛到同一份，删掉自己多建的空文件。 */
        private Uri settle(ContentResolver resolver, Uri created) {
            long[] canonical = scanFiles(resolver, null);
            if (canonical == null || canonical[0] == ContentUris.parseId(created)) return created;
            try {
                resolver.delete(created, null, null);
            } catch (Throwable ignored) {
            }
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, canonical[0]);
        }

        /**
         * 扫描同类日志文件（名字含 ROM 补的后缀和并发多建的「 (N)」变体）：
         * 返回应使用的那份 [_ID, SIZE]——优先名字最正、其次 _ID 最小；其余重复文件的 _ID 收进 outOthers。
         */
        private long[] scanFiles(ContentResolver resolver, List<Long> outOthers) {
            long[] canonical = null;
            int bestRank = Integer.MAX_VALUE;
            try (Cursor cursor = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"Download/" + FOLDER + "%", fileName + "%"},
                    MediaStore.MediaColumns._ID + " ASC")) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(0);
                        int rank = named(cursor.getString(1)) ? 0 : 1;
                        if (canonical == null || rank < bestRank) {
                            if (canonical != null && outOthers != null) outOthers.add(canonical[0]);
                            canonical = new long[]{id, cursor.getLong(2)};
                            bestRank = rank;
                        } else if (outOthers != null) {
                            outOthers.add(id);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return canonical;
        }

        /** 名字是否「正」：原始名，或 ROM 自动补 .txt 后的名字；带「 (N)」的都是重名变体。 */
        private boolean named(String name) {
            return fileName.equals(name) || (fileName + ".txt").equals(name);
        }

        /** 校准节流：每写满约 1MB 或间隔一段时间才真正校一次，避免频繁查库。 */
        private void checkIfNeeded() {
            long now = SystemClock.uptimeMillis();
            if (pendingBytes < CHECK_STEP_BYTES && now - lastCheckAt < CHECK_STEP_MS) return;
            pendingBytes = 0L;
            lastCheckAt = now;
            try {
                checkFile();
            } catch (Throwable ignored) {
            }
        }

        /** 校准文件状态：收敛重名文件，必要时裁剪超限内容。 */
        private void checkFile() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                List<Long> others = new ArrayList<>();
                long[] canonical = scanFiles(resolver, others);
                if (canonical == null) {
                    // 文件全被删了（比如刚「清理日志」），交给下次写入重建
                    mediaUri = null;
                } else {
                    mediaUri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, canonical[0]);
                    for (Long id : others) {
                        try {
                            resolver.delete(ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (maxBytes > 0 && canonical[1] > maxBytes) {
                        trim(resolver, canonical[1]);
                    }
                }
            }
            if (directFile != null && maxBytes > 0) {
                try {
                    if (directFile.length() > maxBytes) trimDirect(directFile);
                } catch (Throwable ignored) {
                }
            }
        }

        /** 超过上限：读尾部保留量，按行边界截断重写，保留最近的完整日志。 */
        private void trim(ContentResolver resolver, long size) {
            long skip = Math.max(0L, size - keepBytes);
            byte[] tail = null;
            try (InputStream in = resolver.openInputStream(mediaUri)) {
                if (in != null) tail = readTail(in, skip);
            } catch (Throwable ignored) {
            }
            if (tail == null) {
                // 读不到就退一步：直接删掉重建（宁可丢历史，也不能让文件无限涨）
                try {
                    resolver.delete(mediaUri, null, null);
                } catch (Throwable ignored) {
                }
                mediaUri = null;
                return;
            }
            byte[] content = withTrimNotice(tail);
            try (OutputStream out = resolver.openOutputStream(mediaUri, "rwt")) {
                if (out == null) throw new IllegalStateException("无法截断重写");
                out.write(content);
                return;
            } catch (Throwable ignored) {
            }
            // 「rwt」不可用：删掉重建，把保留内容写回新文件
            try {
                resolver.delete(mediaUri, null, null);
            } catch (Throwable ignored) {
            }
            mediaUri = null;
            try {
                writeDownload(content);
            } catch (Throwable ignored) {
            }
        }

        /** 直写文件（低版本公共目录 / 退回应用外部目录）的裁剪。 */
        private void trimDirect(File file) {
            try {
                long skip = Math.max(0L, file.length() - keepBytes);
                byte[] tail;
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(skip);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = raf.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                    tail = out.toByteArray();
                }
                byte[] content = withTrimNotice(tail);
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(content);
                }
            } catch (Throwable ignored) {
            }
        }

        /** 丢掉被切开的不完整行，前面加一行裁剪说明。 */
        private byte[] withTrimNotice(byte[] tail) {
            int start = 0;
            for (int i = 0; i < tail.length; i++) {
                if (tail[i] == '\n') {
                    start = i + 1;
                    break;
                }
            }
            byte[] kept = Arrays.copyOfRange(tail, start, tail.length);
            byte[] notice = (timestamp() + " [" + tag + "] === 日志超过 "
                    + (maxBytes / (1024 * 1024)) + "MB，已裁掉较早内容，仅保留最近部分 ===\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] content = new byte[notice.length + kept.length];
            System.arraycopy(notice, 0, content, 0, notice.length);
            System.arraycopy(kept, 0, content, notice.length, kept.length);
            return content;
        }

        private static byte[] readTail(InputStream in, long skip) throws Exception {
            long done = 0L;
            while (done < skip) {
                long n = in.skip(skip - done);
                if (n > 0) {
                    done += n;
                } else if (in.read() < 0) {
                    return new byte[0];
                } else {
                    done++;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
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
