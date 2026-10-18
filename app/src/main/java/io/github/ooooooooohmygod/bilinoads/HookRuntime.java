package io.github.ooooooooohmygod.bilinoads;

import android.util.Log;

import java.lang.reflect.Executable;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** 把回调适配到 libxposed API 102 的拦截器链。 */
final class HookRuntime {
    private static final String TAG = "BiliTweaks";
    private static volatile XposedModule module;

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
    }

    static void log(String message, Throwable error) {
        XposedModule value = module;
        if (value != null) {
            value.log(Log.ERROR, TAG, message, error);
        } else {
            Log.e(TAG, message, error);
        }
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

    private static XposedModule module() {
        XposedModule value = module;
        if (value == null) {
            throw new IllegalStateException("libxposed is not attached");
        }
        return value;
    }
}
