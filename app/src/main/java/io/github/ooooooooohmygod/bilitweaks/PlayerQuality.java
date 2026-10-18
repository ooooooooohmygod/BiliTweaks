package io.github.ooooooooohmygod.bilitweaks;

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

        boolean isFullscreen = isFullscreen(handler);
        int qn = isFullscreen ? fullscreen : inline;
        if (qn > 0) {
            String message = "强制清晰度 qn=" + qn + " fullscreen=" + isFullscreen;
            if (!message.equals(lastLogged)) {
                lastLogged = message;
                HookRuntime.log(message);
            }
        }
        return qn;
    }

    private static boolean isFullscreen(Object handler) {
        try {
            Object container = invoke(handler, "getMPlayerContainer");
            if (container == null) return false;
            Object state = invoke(container, "getActivityStateService");
            if (state == null) return false;
            Object orientation = invoke(state, "getOrientation");
            return orientation instanceof Integer
                    && (Integer) orientation == Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable ignored) {
            return false;
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
