package io.github.ooooooooohmygod.bilitweaks;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * 日志清理：设置里点「清理日志」会写入递增的 clear_logs_at 请求值并广播给哔哩哔哩进程，
 * 模块收到广播后立即删除 Download/BiliTweaks 下的全部日志（含旧版按进程分片的文件）。
 * 日志文件由哔哩哔哩进程（uid）创建，只有模块跑在哔哩哔哩进程里才能静默删除，所以清理放在模块侧；
 * 已处理到的请求值记在哔哩哔哩数据目录的小文件里，避免重复清理。
 * 哔哩哔哩未运行时收不到广播，会在下次启动时按请求值补清。
 */
final class LogCleaner {
    /** 设置页「清理日志」发出的广播。 */
    static final String ACTION_CLEAR_LOGS = "io.github.ooooooooohmygod.bilitweaks.action.CLEAR_LOGS";
    /** 广播附带的清理请求值。 */
    static final String EXTRA_REQUEST = "request";
    /** 广播结果码：已清理完成。 */
    static final int RESULT_CLEANED = 1;
    /** 广播结果码：清理失败。 */
    static final int RESULT_FAILED = 2;

    private static final String FOLDER = "BiliTweaks";
    private static final long POLL_INTERVAL_MS = 5000L;
    private static final int MAX_FAILED_ATTEMPTS = 3;
    /** cleanNow 返回值：该请求此前已处理过。 */
    private static final int CLEAN_ALREADY = -2;
    /** cleanNow 返回值：清理失败。 */
    private static final int CLEAN_FAILED = -1;
    private static final Object LOCK = new Object();

    private static volatile boolean started;
    private static volatile boolean receiverAttached;
    private static int failedAttempts;

    private LogCleaner() {}

    /** 进程启动时调用：先处理积压请求，再挂广播接收器并起轮询线程。 */
    static void start(Context context) {
        attachReceiver(context);
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

    /** 广播接收器：设置页点击后立即清理，不必等轮询。 */
    private static void attachReceiver(Context context) {
        if (receiverAttached) return;
        synchronized (LogCleaner.class) {
            if (receiverAttached) return;
            try {
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        Context target = receiverContext != null ? receiverContext : context;
                        long request = intent != null ? intent.getLongExtra(EXTRA_REQUEST, 0L) : 0L;
                        int cleaned = cleanNow(target, request);
                        try {
                            if (!isOrderedBroadcast() || getResultCode() != 0) return;
                            if (cleaned == CLEAN_FAILED) {
                                setResultCode(RESULT_FAILED);
                            } else {
                                setResultCode(RESULT_CLEANED);
                                setResultData(cleaned > 0 ? String.valueOf(cleaned) : "");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                };
                IntentFilter filter = new IntentFilter(ACTION_CLEAR_LOGS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                receiverAttached = true;
            } catch (Throwable ignored) {
            }
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

    /** 轮询：哔哩哔哩未运行时点过「清理日志」的积压请求，在进程存活期间也能补清。 */
    private static void check(Context context) {
        long request = HookRuntime.getLongPreference(Prefs.KEY_CLEAR_LOGS, 0L);
        if (request <= 0) return;
        int result = cleanNow(context, request);
        if (result != CLEAN_FAILED) {
            failedAttempts = 0;
            return;
        }
        synchronized (LOCK) {
            if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                failedAttempts = 0;
                saveState(context, request);
                HookRuntime.log("日志清理未能全部完成，已跳过（可再点一次清理重试）");
            }
        }
    }

    /**
     * 立即清理 Download/BiliTweaks 下的全部日志。
     *
     * @return 删除的文件数；{@link #CLEAN_FAILED} 表示失败；{@link #CLEAN_ALREADY} 表示该请求已处理过。
     */
    private static int cleanNow(Context context, long request) {
        synchronized (LOCK) {
            long value = Math.max(request, HookRuntime.getLongPreference(Prefs.KEY_CLEAR_LOGS, 0L));
            if (value > 0 && value <= loadState(context)) {
                return CLEAN_ALREADY;
            }
            int deleted = deleteAll(context);
            if (deleted < 0) {
                HookRuntime.log("日志清理失败，稍后自动重试");
                return CLEAN_FAILED;
            }
            if (value > 0) {
                saveState(context, value);
            }
            HookRuntime.log("已按请求清理日志：删除 " + deleted + " 个文件");
            return deleted;
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
