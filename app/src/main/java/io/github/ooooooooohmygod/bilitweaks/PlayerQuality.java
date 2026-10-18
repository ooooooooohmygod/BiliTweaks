package io.github.ooooooooohmygod.bilitweaks;

import android.content.Context;
import android.content.res.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** 在播放器最终请求清晰度前，根据当前屏幕模式返回设定档位。 */
final class PlayerQuality {
    private static volatile String lastLogged;

    private PlayerQuality() {}

    /** 播放器最终请求清晰度（hook #18）：按当前全屏状态返回档位，"自动最高"时取可用最高档。 */
    static int resolve(Object handler) {
        try {
            int inline = HookRuntime.getIntPreference(Prefs.KEY_INLINE_QUALITY);
            int fullscreen = HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY);
            if (inline == 0 && fullscreen == 0) return 0;

            Object container = invoke(handler, "getMPlayerContainer");
            int orientation = orientationOf(container);
            boolean isFullscreen = orientation == Configuration.ORIENTATION_LANDSCAPE;
            int preference = isFullscreen ? fullscreen : inline;
            if (preference == 0) return 0;

            boolean autoMax = preference == Prefs.AUTO_MAX_QUALITY;
            int qn = autoMax ? maxQuality(container) : preference;
            if (qn <= 0) {
                if (autoMax) {
                    logOnce("自动最高清晰度：档位列表未就绪，本次不干预 fullscreen=" + isFullscreen);
                }
                return 0;
            }
            logOnce("强制清晰度 qn=" + qn + " fullscreen=" + isFullscreen
                    + " orientation=" + orientation + " auto=" + autoMax);
            return qn;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 卡片播放器清晰度（hook #14）：默认限制 720P，"自动最高"时改为可用最高档。 */
    static int cardQuality(Object qualityProvider) {
        try {
            int preference = HookRuntime.getIntPreference(Prefs.KEY_INLINE_QUALITY);
            if (preference > 0) return preference;
            if (preference != Prefs.AUTO_MAX_QUALITY) return 0;
            Object service = readProperty(qualityProvider, "a");
            if (service == null) return 0;
            Object container = readProperty(service, "a");
            return maxQuality(container);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 解析完成后（hook #19）：选了"自动最高"且资源里有更高档位时，把选中档位对齐到最高。 */
    static void alignAutoMax(Object handler, Object resource) {
        try {
            if (handler == null || resource == null) return;
            Object container = invoke(handler, "getMPlayerContainer");
            if (container == null) return;
            boolean isFullscreen = orientationOf(container) == Configuration.ORIENTATION_LANDSCAPE;
            int preference = isFullscreen
                    ? HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY)
                    : HookRuntime.getIntPreference(Prefs.KEY_INLINE_QUALITY);
            if (preference != Prefs.AUTO_MAX_QUALITY) return;

            Object vodIndex = readProperty(resource, "mVodIndex");
            if (vodIndex == null) return;
            Object listObject = readProperty(vodIndex, "mVodList");
            if (!(listObject instanceof List)) return;

            List<?> list = (List<?>) listObject;
            int currentQn = intProperty(invoke(resource, "getPlayIndex"), "mQuality");
            int bestQn = 0;
            int bestIndex = -1;
            for (int i = 0; i < list.size(); i++) {
                int qn = intProperty(list.get(i), "mQuality");
                if (qn > bestQn) {
                    bestQn = qn;
                    bestIndex = i;
                }
            }
            if (bestIndex < 0 || bestQn <= currentQn) return;
            Method setter = resource.getClass().getMethod("setResolvedIndex", int.class);
            setter.setAccessible(true);
            setter.invoke(resource, bestIndex);
            logOnce("自动最高清晰度：已从 " + currentQn + " 对齐到 " + bestQn);
        } catch (Throwable ignored) {
        }
    }

    /** 沿播放器容器读取"可用档位列表"（MediaResource.mVodIndex.mVodList）中的最高档位。 */
    private static int maxQuality(Object container) {
        try {
            if (container == null) return 0;
            Object core = invoke(container, "getPlayerCoreService");
            if (core == null) return 0;
            Object resource = invoke(core, "getMediaResource");
            if (resource == null) return 0;
            Object vodIndex = readProperty(resource, "mVodIndex");
            if (vodIndex == null) return 0;
            Object listObject = readProperty(vodIndex, "mVodList");
            if (!(listObject instanceof List)) return 0;
            int max = 0;
            for (Object item : (List<?>) listObject) {
                max = Math.max(max, intProperty(item, "mQuality"));
            }
            return max;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 读取当前屏幕方向（Configuration.ORIENTATION_*），失败返回 -1。 */
    private static int orientationOf(Object container) {
        try {
            if (container == null) return -1;
            Object context = invoke(container, "getContext");
            if (!(context instanceof Context)) return -1;
            return ((Context) context).getResources().getConfiguration().orientation;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int intProperty(Object target, String name) {
        Object value = readProperty(target, name);
        return value instanceof Integer ? (Integer) value : 0;
    }

    /** 读取字段；字段不可用时回退到 getter。 */
    private static Object readProperty(Object target, String name) {
        if (target == null) return null;
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                break;
            }
        }
        try {
            return invoke(target, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String name) throws Exception {
        if (target == null) return null;
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
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void logOnce(String message) {
        if (message.equals(lastLogged)) return;
        lastLogged = message;
        HookRuntime.log(message);
    }
}
