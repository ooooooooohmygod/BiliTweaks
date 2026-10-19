package io.github.ooooooooohmygod.bilitweaks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 把哔哩哔哩自己的日志镜像到 Download/BiliTweaks/BiliLog-&lt;进程&gt;.log。
 *
 * 哔哩哔哩的日志出口是 BLog（tv.danmaku.android.log.BLog），所有日志最终都会进
 * an1.b 接口的某个实现：默认的 an1.j（适配器分发，可接 logcat / 文件适配器）或
 * tv.danmaku.android.log.internal.NativeLogger（libblog.so 原生写日志）。
 * 这里把这两个实现的 4 个入口都挂上，覆盖两种运行配置。
 */
final class BiliLogMirror {
    /** 只收 Debug 及以上（2=Verbose 丢掉，避免日志爆炸）。 */
    private static final int MIN_PRIORITY = 3;
    private static final int QUEUE_CAPACITY = 4096;
    private static final int BATCH_SIZE = 256;

    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final AtomicInteger DROPPED = new AtomicInteger();
    private static final Map<Class<?>, Method> INVOKE_CACHE = new ConcurrentHashMap<>();

    private static volatile boolean enabled;
    private static volatile boolean workerStarted;

    private BiliLogMirror() {}

    static void install(ClassLoader classLoader) {
        enabled = true;
        int hooked = hook(classLoader, "an1.j") + hook(classLoader, "tv.danmaku.android.log.internal.NativeLogger");
        HookRuntime.log("哔哩哔哩日志镜像：已挂 " + hooked + " 个入口");
    }

    private static int hook(ClassLoader classLoader, String className) {
        int count = 0;
        try {
            Class<?> target = Class.forName(className, false, classLoader);
            for (Method method : target.getDeclaredMethods()) {
                if (!isLogEntry(method)) continue;
                String name = method.getName();
                try {
                    HookRuntime.hookAfter(method, chain -> capture(name, chain));
                    count++;
                } catch (Throwable t) {
                    HookRuntime.log("哔哩哔哩日志入口挂载失败：" + className + "#" + name, t);
                }
            }
        } catch (Throwable t) {
            HookRuntime.log("哔哩哔哩日志镜像未安装：" + className, t);
        }
        return count;
    }

    /**
     * an1.b 的入口：
     * a(int, String, String, Object[])     格式化日志
     * b(int, String, Throwable, String)    文本日志
     * c(int, String, Throwable, Function0) 懒加载日志
     * event(String, String)                事件日志
     */
    private static boolean isLogEntry(Method method) {
        String name = method.getName();
        Class<?>[] types = method.getParameterTypes();
        if (name.equals("event")) {
            return types.length == 2 && types[0] == String.class;
        }
        if (types.length != 4 || types[0] != int.class) return false;
        if (name.equals("a")) {
            return types[1] == String.class && types[2] == String.class && types[3] == Object[].class;
        }
        if (name.equals("b")) {
            return types[1] == String.class && types[3] == String.class;
        }
        return name.equals("c") && types[1] == String.class;
    }

    private static void capture(String name, XposedInterface.Chain chain) {
        if (!enabled) return;
        try {
            if (name.equals("event")) {
                emit(4, "EVENT", asString(chain.getArg(0)), asString(chain.getArg(1)), null, true);
                return;
            }
            int priority = asInt(chain.getArg(0));
            if (priority < MIN_PRIORITY) return;
            String tag = asString(chain.getArg(1));
            if (name.equals("a")) {
                String format = asString(chain.getArg(2));
                Object[] args = new Object[0];
                if (chain.getArg(3) instanceof Object[]) {
                    args = (Object[]) chain.getArg(3);
                }
                String message = format;
                if (format != null) {
                    try {
                        message = String.format(format, args);
                    } catch (Throwable ignored) {
                    }
                }
                emit(priority, level(priority), tag, message, null, false);
                return;
            }
            Throwable error = chain.getArg(2) instanceof Throwable ? (Throwable) chain.getArg(2) : null;
            String message;
            if (name.equals("c")) {
                message = invokeLazy(chain.getArg(3));
            } else {
                message = asString(chain.getArg(3));
            }
            emit(priority, level(priority), tag, message, error, false);
        } catch (Throwable ignored) {
        }
    }

    private static void emit(int priority, String level, String tag, String message, Throwable error, boolean force) {
        if (!force && priority < MIN_PRIORITY) return;
        StringBuilder line = new StringBuilder();
        line.append(level).append('/').append(tag == null ? "BLOG" : tag).append(": ");
        if (message != null) line.append(message);
        if (error != null) {
            line.append('\n').append(error);
            StackTraceElement[] trace = error.getStackTrace();
            int count = Math.min(trace.length, 6);
            for (int i = 0; i < count; i++) {
                line.append("\n    at ").append(trace[i]);
            }
        }
        if (QUEUE.offer(line.toString())) {
            startWorker();
        } else {
            DROPPED.incrementAndGet();
        }
    }

    private static String invokeLazy(Object supplier) {
        if (supplier == null) return null;
        try {
            Method invoke = INVOKE_CACHE.get(supplier.getClass());
            if (invoke == null) {
                invoke = supplier.getClass().getMethod("invoke");
                INVOKE_CACHE.put(supplier.getClass(), invoke);
            }
            Object value = invoke.invoke(supplier);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return "<日志内容求值失败: " + t + ">";
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 4;
    }

    private static String asString(Object value) {
        if (value instanceof String) return (String) value;
        return value == null ? null : String.valueOf(value);
    }

    private static String level(int priority) {
        switch (priority) {
            case 2: return "V";
            case 3: return "D";
            case 4: return "I";
            case 5: return "W";
            case 6: return "E";
            case 7: return "WTF";
            default: return String.valueOf(priority);
        }
    }

    private static void startWorker() {
        if (workerStarted) return;
        synchronized (BiliLogMirror.class) {
            if (workerStarted) return;
            workerStarted = true;
            Thread worker = new Thread(BiliLogMirror::drainLoop, "BiliTweaks-BLog");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void drainLoop() {
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        while (true) {
            try {
                String first = QUEUE.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    flushDropped();
                    continue;
                }
                batch.add(first);
                QUEUE.drainTo(batch, BATCH_SIZE - 1);
                LogFile.appendBili(batch);
                batch.clear();
                flushDropped();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void flushDropped() {
        int dropped = DROPPED.getAndSet(0);
        if (dropped > 0) {
            LogFile.appendBili(Collections.singletonList("（日志过快，已丢弃 " + dropped + " 行）"));
        }
    }
}
