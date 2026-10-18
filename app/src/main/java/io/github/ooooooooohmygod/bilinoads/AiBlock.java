package io.github.ooooooooohmygod.bilinoads;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import io.github.libxposed.api.XposedModule;

/** 读取“屏蔽 AI 相关视频”开关，并在详情页命中“含AI生成内容”时拦截当前页面。 */
final class AiBlock {
    static final String PREFS_NAME = "settings";
    static final String KEY_BLOCK_AI = "block_ai_videos";

    private static final String AI_MARKER = "含AI生成内容";
    private static final long DEBOUNCE_MS = 2000L;

    private static volatile XposedModule module;
    private static volatile Activity resumedActivity;
    private static WeakReference<Activity> lastBlocked = new WeakReference<>(null);
    private static long lastBlockedAt;

    private AiBlock() {}

    static void attach(XposedModule value) {
        module = value;
    }

    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                resumedActivity = activity;
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumedActivity == activity) resumedActivity = null;
            }

            @Override
            public void onActivityStopped(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (resumedActivity == activity) resumedActivity = null;
            }
        });
    }

    static boolean isEnabled() {
        XposedModule value = module;
        if (value == null) return false;
        try {
            SharedPreferences prefs = value.getRemotePreferences(PREFS_NAME);
            return prefs.getBoolean(KEY_BLOCK_AI, false);
        } catch (Throwable t) {
            HookRuntime.log("读取 AiBlock 开关失败", t);
            return false;
        }
    }

    static void onText(String text) {
        if (text == null || text.length() > 32 || !text.contains("AI")) return;
        String normalized = text.replaceAll("\\s+", "");
        if (!normalized.equals(AI_MARKER)) return;
        if (!isEnabled()) return;
        blockCurrentPage();
    }

    /** proto 字段 msg_ 是私有字段，UI 可能通过 getter 或桥接读取，这里直接读字段以覆盖两种情况。 */
    static void inspectArgueBar(Object argueBar) {
        if (argueBar == null) return;
        try {
            Field field = argueBar.getClass().getDeclaredField("msg_");
            field.setAccessible(true);
            Object value = field.get(argueBar);
            if (value instanceof String) onText((String) value);
        } catch (Throwable ignored) {
        }
    }

    private static void blockCurrentPage() {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        Activity activity = resumedActivity;
        if (activity == null || activity.isFinishing()) return;

        long now = SystemClock.elapsedRealtime();
        if (lastBlocked.get() == activity && now - lastBlockedAt < DEBOUNCE_MS) return;
        lastBlocked = new WeakReference<>(activity);
        lastBlockedAt = now;

        HookRuntime.log("命中含AI生成内容，拦截当前页面");
        Toast.makeText(activity, "已屏蔽含 AI 生成内容的视频", Toast.LENGTH_SHORT).show();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            @SuppressWarnings("deprecation")
            public void run() {
                activity.onBackPressed();
            }
        });
    }
}
