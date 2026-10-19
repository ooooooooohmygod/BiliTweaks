package io.github.ooooooooohmygod.bilitweaks;

import java.lang.reflect.Executable;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class HookRuntime {
    private static volatile XposedModule module;

    static final Object PASS_THROUGH = new Object();

    private HookRuntime() {}

    static void attach(XposedModule value) {
        module = value;
    }

    static XposedInterface.HookHandle hookReturnConstant(Executable executable, Object constant) {
        return hookReturnConstant(executable, () -> true, constant);
    }

    static XposedInterface.HookHandle hookReturnConstant(Executable executable,
                                                        BooleanSupplier enabled,
                                                        Object constant) {
        XposedModule value = module();
        executable.setAccessible(true);
        return value.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> enabled.getAsBoolean() ? constant : chain.proceed());
    }

    static XposedInterface.HookHandle hookAfterReturnConstant(Executable executable, Object constant) {
        return hookAfterReturnConstant(executable, () -> true, constant);
    }

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

    static boolean isFeatureEnabled(String key) {
        XposedModule value = module;
        if (value == null) return true;
        try {
            return value.getRemotePreferences(Prefs.NAME).getBoolean(key, true);
        } catch (Throwable t) {
            return true;
        }
    }

    static int getIntPreference(String key) {
        return getIntPreference(key, 0);
    }

    static int getIntPreference(String key, int defaultValue) {
        XposedModule value = module;
        if (value == null) return defaultValue;
        try {
            return value.getRemotePreferences(Prefs.NAME).getInt(key, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    static long getLongPreference(String key, long defaultValue) {
        XposedModule value = module;
        if (value == null) return defaultValue;
        try {
            return value.getRemotePreferences(Prefs.NAME).getLong(key, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    static XposedInterface.HookHandle hookReturnIntIf(Executable executable, IntSupplier value) {
        return hookReturnIntFrom(executable, ignored -> value.getAsInt());
    }

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
