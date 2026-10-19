package io.github.ooooooooohmygod.bilitweaks;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志清理：设置里点「清理日志」会写入递增的 clear_logs_at 请求值，这里轮询到新请求后
 * 删除 Download/BiliTweaks 下的全部日志（含旧版按进程分片的文件）。
 * 日志文件由哔哩哔哩进程（uid）创建，只有模块跑在哔哩哔哩进程里才能静默删除，所以清理放在模块侧；
 * 已处理到的请求值记在哔哩哔哩数据目录的小文件里，避免每次进程启动重复清理。
 */
final class LogCleaner {
    private static final String FOLDER = "BiliTweaks";
    private static final long POLL_INTERVAL_MS = 5000L;
    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final Object LOCK = new Object();

    private static volatile boolean started;
    private static int failedAttempts;

    private LogCleaner() {}

    /** 进程启动时调用：先同步处理一次积压请求，再起常驻线程轮询。 */
    static void start(Context context) {
        try {
            check(context);
        } catch (Throwable ignored) {
        }
        if (started) return;
        synchronized (LogCleaner.class) {
            if (started) return;
            started = true;
            Thread worker = new Thread(() -> loop(context), "BiliTweaks-LogClean");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void loop(Context context) {
        while (true) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
                check(context);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void check(Context context) {
        long request = HookRuntime.getLongPreference(Prefs.KEY_CLEAR_LOGS, 0L);
        if (request <= 0) return;
        synchronized (LOCK) {
            if (request <= loadState(context)) return;
            int deleted = deleteAll(context);
            if (deleted >= 0) {
                failedAttempts = 0;
                saveState(context, request);
                HookRuntime.log("已按请求清理日志：删除 " + deleted + " 个文件");
            } else if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                failedAttempts = 0;
                saveState(context, request);
                HookRuntime.log("日志清理未能全部完成，已跳过（可再点一次清理重试）");
            }
        }
    }

    /** @return 删除的文件数；-1 表示有失败（请求保留，稍后重试）。 */
    private static int deleteAll(Context context) {
        int count = 0;
        boolean clean = true;
        ContentResolver resolver = context.getContentResolver();
        List<Uri> targets = new ArrayList<>();
        boolean scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        String pathColumn = scoped ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
        String pathPattern = scoped ? "Download/" + FOLDER + "%" : "%/Download/" + FOLDER + "/%";
        try (Cursor cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                pathColumn + " LIKE ? AND (" + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?)",
                new String[]{pathPattern, "BiliTweaks%", "BiliLog%"},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    targets.add(ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)));
                }
            }
        } catch (Throwable t) {
            clean = false;
        }
        for (Uri uri : targets) {
            try {
                count += resolver.delete(uri, null, null);
            } catch (Throwable t) {
                clean = false;
            }
        }
        for (File dir : directDirs(context)) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName();
                if (!file.isFile()) continue;
                if (!name.startsWith("BiliTweaks") && !name.startsWith("BiliLog")) continue;
                if (file.delete()) {
                    count++;
                } else if (file.exists()) {
                    clean = false;
                }
            }
        }
        return clean ? count : -1;
    }

    @SuppressWarnings("deprecation")
    private static List<File> directDirs(Context context) {
        List<File> dirs = new ArrayList<>(2);
        dirs.add(new File(new File(Environment.getExternalStorageDirectory(), "Download"), FOLDER));
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            dirs.add(new File(external, FOLDER));
        }
        return dirs;
    }

    private static File stateFile(Context context) {
        return new File(context.getFilesDir(), "bilitweaks-log-state.txt");
    }

    private static long loadState(Context context) {
        try {
            byte[] bytes = Files.readAllBytes(stateFile(context).toPath());
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void saveState(Context context, long value) {
        File target = stateFile(context);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(Long.toString(value).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return;
        }
        if (!temp.renameTo(target)) {
            target.delete();
            if (!temp.renameTo(target)) {
                temp.delete();
            }
        }
    }
}
