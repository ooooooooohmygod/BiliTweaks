package io.github.ooooooooohmygod.bilitweaks;

import android.util.Log;

import java.lang.reflect.Executable;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** 把回调适配到 libxposed API 102 的拦截器链。 */
final class HookRuntime {
    private static final String TAG = "BiliTweaks";
    private static volatile XposedModule module;

    /** 自定义替换器要求继续执行原方法时返回该哨兵值。 */
    static final Object PASS_THROUGH = new Object();

    private HookRuntime() {}

    static void attach(XposedModule value) {
        module = value;
    }

    static void log(String message) {
        XposedModule value = module;
        if (value != null) {
            value.log(Log.INFO, TAG, message);
        } else {
            Log.i(TAG, message);
        }
        LogFile.append(message);
    }

    static void log(String message, Throwable error) {
        XposedModule value = module;
        if (value != null) {
            value.log(Log.ERROR, TAG, message, error);
        } else {
            Log.e(TAG, message, error);
        }
        StringBuilder line = new StringBuilder(message);
        if (error != null) {
            line.append("：").append(error);
            StackTraceElement[] trace = error.getStackTrace();
            int count = Math.min(trace.length, 8);
            for (int i = 0; i < count; i++) {
                line.append("\n    at ").append(trace[i]);
            }
        }
        LogFile.append(line.toString());
    }

    /** 跳过原方法，直接返回 constant（对应 smali 的 force_false / force_void / force_null）。 */
    static XposedInterface.HookHandle hookReturnConstant(Executable executable, Object constant) {
        return hookReturnConstant(executable, () -> true, constant);
    }

    /** 开关开启时跳过原方法返回 constant，关闭时正常执行原方法。 */
    static XposedInterface.HookHandle hookReturnConstant(Executable executable,
                                                        BooleanSupplier enabled,
                                                        Object constant) {
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> enabled.getAsBoolean() ? constant : chain.proceed());
    }

    /** 先执行原方法（保留副作用），再把返回值换成 constant（对应 smali 的 null_returns）。 */
    static XposedInterface.HookHandle hookAfterReturnConstant(Executable executable, Object constant) {
        return hookAfterReturnConstant(executable, () -> true, constant);
    }

    /** 开关开启时把返回值换成 constant，关闭时保留原返回值。 */
    static XposedInterface.HookHandle hookAfterReturnConstant(Executable executable,
                                                             BooleanSupplier enabled,
                                                             Object constant) {
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    return enabled.getAsBoolean() ? constant : result;
                });
    }

    /** 读取模块设置中的功能开关，默认开启；读取失败时保持原行为。 */
    static boolean isFeatureEnabled(String key) {
        XposedModule value = module;
        if (value == null) return true;
        try {
            return value.getRemotePreferences(Prefs.NAME).getBoolean(key, true);
        } catch (Throwable t) {
            log("读取功能开关失败：" + key, t);
            return true;
        }
    }

    /** 读取整型偏好，0 表示不覆盖原值。 */
    static int getIntPreference(String key) {
        return getIntPreference(key, 0);
    }

    /** 读取整型偏好，失败时返回默认值。 */
    static int getIntPreference(String key, int defaultValue) {
        XposedModule value = module;
        if (value == null) return defaultValue;
        try {
            return value.getRemotePreferences(Prefs.NAME).getInt(key, defaultValue);
        } catch (Throwable t) {
            log("读取整型偏好失败：" + key, t);
            return defaultValue;
        }
    }

    /** 偏好值大于 0 时返回该值，否则执行原方法。 */
    static XposedInterface.HookHandle hookReturnIntIf(Executable executable, IntSupplier value) {
        return hookReturnIntFrom(executable, ignored -> value.getAsInt());
    }

    /** 根据被拦截对象计算清晰度；结果大于 0 时覆盖原方法。 */
    static XposedInterface.HookHandle hookReturnIntFrom(Executable executable, IntResolver resolver) {
        XposedModule module = module();
        executable.setAccessible(true);
        return module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    int qn = resolver.resolve(chain.getThisObject());
                    return qn > 0 ? Integer.valueOf(qn) : chain.proceed();
                });
    }

    interface IntResolver {
        int resolve(Object thisObject) throws Throwable;
    }

    /** 先执行原方法，再把调用链交给回调。 */
    static XposedInterface.HookHandle hookAfter(Executable executable, AfterChain callback) {
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    callback.after(chain);
                    return result;
                });
    }

    interface AfterChain {
        void after(XposedInterface.Chain chain) throws Throwable;
    }

    /** 先执行回调（可在原方法执行前修改参数对象），再执行原方法。 */
    static XposedInterface.HookHandle hookBefore(Executable executable, BeforeChain callback) {
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    callback.before(chain);
                    return chain.proceed();
                });
    }

    interface BeforeChain {
        void before(XposedInterface.Chain chain) throws Throwable;
    }

    /** 完全接管原方法：回调返回 PASS_THROUGH 时执行原方法，否则把返回值作为方法结果。 */
    static XposedInterface.HookHandle hookReplace(Executable executable, Replace callback) {
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = callback.replace(chain);
                    return result == PASS_THROUGH ? chain.proceed() : result;
                });
    }

    interface Replace {
        Object replace(XposedInterface.Chain chain) throws Throwable;
    }

    private static XposedModule module() {
        XposedModule value = module;
        if (value == null) {
            throw new IllegalStateException("libxposed is not attached");
        }
        return value;
    }
}
