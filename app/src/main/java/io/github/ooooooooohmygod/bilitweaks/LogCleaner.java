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
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class LogCleaner {

    static final String ACTION_CLEAR_LOGS = "io.github.ooooooooohmygod.bilitweaks.action.CLEAR_LOGS";

    static final String EXTRA_REQUEST = "request";

    static final int RESULT_CLEANED = 1;

    static final int RESULT_FAILED = 2;

    private static final String FOLDER = "BiliTweaks";
    private static final long POLL_INTERVAL_MS = 5000L;
    private static final int MAX_FAILED_ATTEMPTS = 3;

    private static final int CLEAN_ALREADY = -2;

    private static final int CLEAN_FAILED = -1;
    private static final Object LOCK = new Object();

    private static volatile boolean started;
    private static volatile boolean receiverAttached;
    private static int failedAttempts;

    private LogCleaner() {}

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
        int ticks = 0;
        while (true) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
                check(context);
                if (++ticks >= 4) {
                    ticks = 0;
                    LogFile.housekeeping();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

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
            if (HookRuntime.isFeatureEnabled(Prefs.KEY_LOG)) {
                LogFile.reseed();
            }
            HookRuntime.log("已按请求清理日志：删除 " + deleted + " 个文件");
            return deleted;
        }
    }

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
        int ghosts = purgeHiddenRows(context);
        if (ghosts > 0) {
            HookRuntime.log("已清掉 " + ghosts + " 条占用文件名的系统隐藏残留记录");
        }
        for (File dir : directDirs(context)) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = hiddenName(file.getName());
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

    private static int purgeHiddenRows(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        ContentResolver resolver = context.getContentResolver();
        int deleted = purgeHiddenState(resolver, MediaStore.QUERY_ARG_MATCH_PENDING);
        deleted += purgeHiddenState(resolver, MediaStore.QUERY_ARG_MATCH_TRASHED);
        return deleted;
    }

    private static int purgeHiddenState(ContentResolver resolver, String matchArg) {
        int deleted = 0;
        try {
            Bundle extras = new Bundle();
            extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?");
            extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    new String[]{"Download/" + FOLDER + "%"});
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

    private static String hiddenName(String name) {
        if (name == null) return "";
        if (name.startsWith(".trashed-") || name.startsWith(".pending-")) {
            int index = name.indexOf('-', name.indexOf('-') + 1);
            if (index > 0 && index + 1 < name.length()) return name.substring(index + 1);
        }
        return name;
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
