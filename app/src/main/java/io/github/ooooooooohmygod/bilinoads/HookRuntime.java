package io.github.ooooooooohmygod.bilinoads;

import android.util.Log;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** 把回调适配到 libxposed API 102 的拦截器链。 */
final class HookRuntime {
    private static final String TAG = "bili-noads";
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
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> constant);
    }

    /** 先执行原方法（保留副作用），再把返回值换成 constant（对应 smali 的 null_returns）。 */
    static XposedInterface.HookHandle hookAfterReturnConstant(Executable executable, Object constant) {
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    chain.proceed();
                    return constant;
                });
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
