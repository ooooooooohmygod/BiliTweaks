package io.github.ooooooooohmygod.bilitweaks;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

final class LogFile {
    private static final String FOLDER = "BiliTweaks";
    private static final String MODULE_FILE = "BiliTweaks.log";
    private static final String BILI_FILE = "BiliLog.log";

    private static final long BILI_MAX_BYTES = 12L * 1024 * 1024;
    private static final long BILI_KEEP_BYTES = 8L * 1024 * 1024;

    private static final long CHECK_STEP_BYTES = 1024L * 1024;
    private static final long CHECK_STEP_MS = 60_000L;

    private static final String CLEARED_NOTE = "=== 日志已清空，以下为清空后新写入的日志 ===";

    private static final int ROUTE_NONE = 0;
    private static final int ROUTE_MEDIA = 1;
    private static final int ROUTE_RAW = 2;
    private static final int ROUTE_ALT = 3;
    private static final int ROUTE_APP = 4;

    private static final long UPGRADE_INTERVAL_MS = 60_000L;

    private static final long MERGE_MAX_BYTES = 8L * 1024 * 1024;

    private static Context context;
    private static String processTag = "main";
    private static Sink moduleSink;
    private static Sink biliSink;

    private LogFile() {}

    static void init(Context value, String processName, boolean logEnabled) {
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        processTag = tag(processName);
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

    static void append(String line) {
        Sink sink = moduleSink;
        if (sink != null) sink.append(line);
    }

    static void appendBili(String line) {
        Sink sink = biliSink;
        if (sink != null) sink.append(line);
    }

    static void appendBili(List<String> lines) {
        Sink sink = biliSink;
        if (sink != null) sink.appendAll(lines);
    }

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

    static void housekeeping() {
        Sink module = moduleSink;
        if (module != null) module.probe();
        Sink bili = biliSink;
        if (bili != null) bili.probe();
    }

    private static String tag(String processName) {
        if (processName == null || processName.isEmpty()) return "main";
        int colon = processName.indexOf(':');
        String tail = colon >= 0 ? processName.substring(colon + 1) : "";
        return tail.isEmpty() ? "main" : tail;
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    static final class Sink {
        private final String fileName;
        private final String tag;

        private final long maxBytes;

        private final long keepBytes;
        private final Object lock = new Object();

        private Uri mediaUri;
        private File directFile;
        private boolean disabled;
        private int failures;
        private long pendingBytes;
        private long lastCheckAt;

        private Uri badUri;
        private int badCount;

        private boolean fallbackLogged;

        private boolean createWarned;

        private int route = ROUTE_NONE;
        private File rawFile;

        private long upgradeAt;

        private boolean everBroken;
        private boolean recoveredLogged;
        private boolean rawLogged;
        private boolean altLogged;
        private boolean altsMerged;

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
                if (route == ROUTE_APP) {
                    writeApp(bytes);
                    return;
                }
                if (route == ROUTE_MEDIA || route == ROUTE_ALT) {
                    try {
                        writeMedia(bytes);
                        return;
                    } catch (Throwable ignored) {
                        route = ROUTE_NONE;
                    }
                } else if (route == ROUTE_RAW) {
                    try {
                        writeRaw(bytes);
                        return;
                    } catch (Throwable ignored) {
                        route = ROUTE_NONE;
                    }
                }
                try {
                    chainLogged(bytes);
                    return;
                } catch (Throwable ignored) {
                }
                writeApp(bytes);
                route = ROUTE_APP;
                return;
            }
            writeApp(bytes);
        }

        private void chain(byte[] bytes) throws Exception {
            ContentResolver resolver = context.getContentResolver();
            Uri plain = findPlain(resolver);
            if (plain != null) {
                mediaUri = plain;
                route = ROUTE_MEDIA;
                writeMedia(bytes);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now >= upgradeAt) {
                upgradeAt = now + UPGRADE_INTERVAL_MS;
                purgeGhost(resolver);
                if (!rawPath().exists()) {
                    Uri created = insertPlain(resolver);
                    if (created != null) {
                        mediaUri = created;
                        route = ROUTE_MEDIA;
                        mergeAlts(resolver, created);
                        writeMedia(bytes);
                        return;
                    }
                }
            }
            try {
                mergeAlts(resolver, null);
                writeRaw(bytes);
                route = ROUTE_RAW;
                everBroken = true;
                if (!rawLogged) {
                    rawLogged = true;
                    HookRuntime.log(fileName + "：原名字被系统残留记录占着，已改为直接写入 Download 目录");
                }
                return;
            } catch (Throwable ignored) {
            }
            Uri alt = findExisting(resolver);
            if (alt != null) {
                mediaUri = alt;
                route = ROUTE_ALT;
                everBroken = true;
                if (!altLogged) {
                    altLogged = true;
                    HookRuntime.log(fileName + "：原名字被系统残留记录占用，日志暂写在 "
                            + displayName(resolver, alt) + "，稍后会自动恢复原名");
                }
                writeMedia(bytes);
                return;
            }
            writeApp(bytes);
            route = ROUTE_APP;
        }

        private void chainLogged(byte[] bytes) throws Exception {
            int before = route;
            chain(bytes);
            if (before != ROUTE_MEDIA && route == ROUTE_MEDIA && everBroken && !recoveredLogged) {
                recoveredLogged = true;
                HookRuntime.log(fileName + "：已恢复写回 Download 目录");
            }
        }

        private void writeMedia(byte[] bytes) throws Exception {
            ContentResolver resolver = context.getContentResolver();
            OutputStream out;
            try {
                out = resolver.openOutputStream(mediaUri, "wa");
            } catch (Throwable t) {
                out = null;
            }
            if (out == null) {
                noteBad(resolver, mediaUri);
                mediaUri = null;
                throw new Exception("无法打开日志文件");
            }
            try {
                out.write(bytes);
                badUri = null;
                badCount = 0;
                createWarned = false;
            } catch (Throwable t) {
                noteBad(resolver, mediaUri);
                mediaUri = null;
                throw new Exception(t);
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }

        private void writeRaw(byte[] bytes) throws Exception {
            FileOutputStream out = new FileOutputStream(rawPath(), true);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        }

        private File rawPath() {
            if (rawFile == null) {
                rawFile = new File(new File(new File(Environment.getExternalStorageDirectory(), "Download"), FOLDER), fileName);
            }
            return rawFile;
        }

        private void writeApp(byte[] bytes) throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !fallbackLogged) {
                fallbackLogged = true;
                HookRuntime.log(fileName + "：Download 目录暂时写不进去，日志先存到应用目录");
            }
            FileOutputStream out = new FileOutputStream(directFile(), true);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        }

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

        private Uri findPlain(ContentResolver resolver) {
            long[] canonical = scanFiles(resolver, null);
            if (canonical == null || canonical[2] == 0) return null;
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, canonical[0]);
        }

        private Uri insertPlain(ContentResolver resolver) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + FOLDER);
            Uri created;
            try {
                created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            } catch (Throwable t) {
                warnCreate(t);
                return null;
            }
            if (created == null) {
                warnCreate(new IllegalStateException("系统没有返回文件"));
                return null;
            }
            return settle(resolver, created);
        }

        private void purgeGhost(ContentResolver resolver) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
            int cleared = purgeHidden(resolver, MediaStore.QUERY_ARG_MATCH_PENDING);
            cleared += purgeHidden(resolver, MediaStore.QUERY_ARG_MATCH_TRASHED);
            if (cleared > 0) {
                HookRuntime.log(fileName + "：清掉了 " + cleared + " 条占用文件名的系统残留记录");
            }
        }

        private int purgeHidden(ContentResolver resolver, String matchArg) {
            int deleted = 0;
            try {
                Bundle extras = new Bundle();
                extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?");
                extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        new String[]{"Download/" + FOLDER + "%", fileName + "%"});
                extras.putInt(matchArg, MediaStore.MATCH_ONLY);
                try (Cursor cursor = resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING,
                                MediaStore.MediaColumns.IS_TRASHED},
                        extras, null)) {
                    if (cursor == null) return 0;
                    while (cursor.moveToNext()) {
                        boolean hidden = false;
                        try {
                            hidden = cursor.getInt(1) == 1 || cursor.getInt(2) == 1;
                        } catch (Throwable ignored) {
                        }
                        if (!hidden) continue;
                        try {
                            deleted += resolver.delete(ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)), null, null);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return deleted;
        }

        private void mergeAlts(ContentResolver resolver, Uri dest) {
            if (altsMerged) return;
            altsMerged = true;
            long destId = dest != null ? ContentUris.parseId(dest) : -1L;
            List<Long> others = new ArrayList<>();
            long[] canonical = scanFiles(resolver, others);
            List<Long> all = new ArrayList<>(others);
            if (canonical != null) all.add(0, canonical[0]);
            for (Long id : all) {
                if (id == null || id == destId) continue;
                Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                boolean merged = false;
                try {
                    byte[] content = readAll(resolver, uri);
                    if (content == null || content.length == 0) {
                        merged = true;
                    } else if (dest != null) {
                        try (OutputStream out = resolver.openOutputStream(dest, "wa")) {
                            if (out == null) throw new Exception("无法写入");
                            out.write(content);
                        }
                        merged = true;
                    } else {
                        try (FileOutputStream out = new FileOutputStream(rawPath(), true)) {
                            out.write(content);
                        }
                        merged = true;
                    }
                } catch (Throwable ignored) {
                }
                if (merged) {
                    try {
                        resolver.delete(uri, null, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private byte[] readAll(ContentResolver resolver, Uri uri) {
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int n;
                while ((n = in.read(buffer)) > 0 && total < MERGE_MAX_BYTES) {
                    out.write(buffer, 0, n);
                    total += n;
                }
                return out.toByteArray();
            } catch (Throwable t) {
                return null;
            }
        }

        private String displayName(ContentResolver resolver, Uri uri) {
            try (Cursor cursor = resolver.query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.isEmpty()) return name;
                }
            } catch (Throwable ignored) {
            }
            return fileName + " 的重名文件";
        }

        private void warnCreate(Throwable t) {
            if (createWarned) return;
            createWarned = true;
            HookRuntime.log(fileName + "：新建 Download 日志失败：" + t);
        }

        void probe() {
            if (disabled) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
            synchronized (lock) {
                if (route == ROUTE_MEDIA) return;
                try {
                    chainLogged(new byte[0]);
                } catch (Throwable ignored) {
                }
            }
        }

        private Uri settle(ContentResolver resolver, Uri created) {
            long[] canonical = scanFiles(resolver, null);
            if (canonical == null || canonical[0] == ContentUris.parseId(created)) return created;
            try {
                resolver.delete(created, null, null);
            } catch (Throwable ignored) {
            }
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, canonical[0]);
        }

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
                            canonical = new long[]{id, cursor.getLong(2), rank == 0 ? 1 : 0};
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

        private boolean named(String name) {
            return fileName.equals(name) || (fileName + ".txt").equals(name);
        }

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

        private void checkFile() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                List<Long> others = new ArrayList<>();
                long[] canonical = scanFiles(resolver, others);
                if (canonical == null) {
                    mediaUri = null;
                } else {
                    int before = route;
                    mediaUri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, canonical[0]);
                    if (canonical[2] == 1) {
                        route = ROUTE_MEDIA;
                        if (before != ROUTE_MEDIA && everBroken && !recoveredLogged) {
                            recoveredLogged = true;
                            HookRuntime.log(fileName + "：已恢复写回 Download 目录");
                        }
                    } else if (route != ROUTE_RAW && route != ROUTE_APP) {
                        route = ROUTE_ALT;
                    }
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
            if (rawFile != null && maxBytes > 0) {
                try {
                    if (rawFile.length() > maxBytes) trimDirect(rawFile);
                } catch (Throwable ignored) {
                }
            }
            if (directFile != null && maxBytes > 0) {
                try {
                    if (directFile.length() > maxBytes) trimDirect(directFile);
                } catch (Throwable ignored) {
                }
            }
        }

        private void trim(ContentResolver resolver, long size) {
            long skip = Math.max(0L, size - keepBytes);
            byte[] tail = null;
            try (InputStream in = resolver.openInputStream(mediaUri)) {
                if (in != null) tail = readTail(in, skip);
            } catch (Throwable ignored) {
            }
            if (tail == null) {
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
            try {
                resolver.delete(mediaUri, null, null);
            } catch (Throwable ignored) {
            }
            mediaUri = null;
            try {
                chainLogged(content);
            } catch (Throwable ignored) {
            }
        }

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
