package io.github.ooooooooohmygod.bilitweaks;

import android.content.Context;
import android.content.res.Configuration;

import java.lang.reflect.Method;

/** 在播放器最终请求清晰度前，根据当前屏幕模式返回设定档位。 */
final class PlayerQuality {
    private static volatile String lastLogged;

    private PlayerQuality() {}

    static int resolve(Object handler) {
        int inline = HookRuntime.getIntPreference(Prefs.KEY_INLINE_QUALITY);
        int fullscreen = HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY);
        if (inline <= 0 && fullscreen <= 0) return 0;

        int orientation = screenOrientation(handler);
        boolean isFullscreen = orientation == Configuration.ORIENTATION_LANDSCAPE;
        int qn = isFullscreen ? fullscreen : inline;
        if (qn > 0) {
            String message = "强制清晰度 qn=" + qn + " fullscreen=" + isFullscreen
                    + " orientation=" + orientation;
            if (!message.equals(lastLogged)) {
                lastLogged = message;
                HookRuntime.log(message);
            }
        }
        return qn;
    }

    /** 读取当前屏幕方向（Configuration.ORIENTATION_*），失败返回 -1。 */
    private static int screenOrientation(Object handler) {
        try {
            Object container = invoke(handler, "getMPlayerContainer");
            if (container == null) return -1;
            Object context = invoke(container, "getContext");
            if (!(context instanceof Context)) return -1;
            return ((Context) context).getResources().getConfiguration().orientation;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = findMethod(target.getClass(), name);
        if (method == null) return null;
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
