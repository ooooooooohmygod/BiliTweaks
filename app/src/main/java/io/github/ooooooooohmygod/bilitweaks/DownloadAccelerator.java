package io.github.ooooooooohmygod.bilitweaks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;

/**
 * 离线缓存多线程下载：hook KDownloaderImpl.downloadFile（混淆名 b），对支持 Range 的单文件用 N 条并发连接
 * 分段写进引擎的临时文件，完成后依旧调用引擎原生 finishDownload（md5 校验 + 原子改名）。
 * 任一环节失败（不支持 Range、网络异常、暂停/取消、磁盘错误）都会把临时文件截断到连续前缀，
 * 再回退原生单连接逻辑，保证行为与校验语义不变。
 */
final class DownloadAccelerator {
    private static final String IMPL_CLASS = "kntr.base.downloader.KDownloaderImpl";
    private static final String TASK_CLASS = "kntr.base.downloader.j";
    private static final String PATH_CLASS = "kotlinx.io.files.Path";
    private static final String PATHS_HELPER_CLASS = "kotlinx.io.files.PathsJvmKt";
    private static final String INFO_LOADING_CLASS = "kntr.base.downloader.IDownloader$Info$Loading";
    private static final String USER_AGENT = "Bilibili Freedoooooom/MarkII";

    private static final long MIN_REMAINING_BYTES = 2L * 1024 * 1024;
    private static final long SEGMENT_TARGET_BYTES = 4L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_SEGMENT_ATTEMPTS = 3;
    private static final long STOP_CHECK_BYTES = 512L * 1024;
    private static final long NOTIFY_INTERVAL_MS = 300;

    private static boolean installed;

    private static Class<?> taskClass;
    private static Field fUrl;
    private static Field fTargetPath;
    private static Field fTmpPath;
    private static Field fTotal;
    private static Field fResume;
    private static Field fSpeedLimit;
    private static Field fHeaders;
    private static Field fProgress;
    private static Field fState;
    private static Field fJob;
    private static Field fListeners;
    private static Method mFinish;
    private static Method mPathOf;
    private static int mPathOfArgs;
    private static Constructor<?> ctorPath;
    private static Class<?> loadingClass;
    private static Constructor<?> ctorLoading;
    private static Method mGlobalSpeedLimit;
    private static Method mIsActive;

    private DownloadAccelerator() {}

    static void install(ClassLoader classLoader) throws Throwable {
        if (installed) return;
        Class<?> impl = Class.forName(IMPL_CLASS, false, classLoader);
        Method target = null;
        for (Method candidate : impl.getDeclaredMethods()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 4
                    && TASK_CLASS.equals(p[0].getName())
                    && p[1] == String.class
                    && p[2] == long.class
                    && "kotlin.coroutines.Continuation".equals(p[3].getName())) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException(IMPL_CLASS + "#downloadFile");
        }
        resolve(classLoader, impl);
        HookRuntime.hookReplace(target, DownloadAccelerator::attempt);
        installed = true;
        HookRuntime.log("下载加速已安装：离线缓存单文件 " + Prefs.DOWNLOAD_THREADS_MIN + "-"
                + Prefs.DOWNLOAD_THREADS_MAX + " 线程分段下载");
    }

    private static void resolve(ClassLoader classLoader, Class<?> impl) throws Throwable {
        taskClass = Class.forName(TASK_CLASS, false, classLoader);
        fTargetPath = field(taskClass, "c");
        fTmpPath = field(taskClass, "d");
        fTotal = field(taskClass, "h");
        fUrl = optionalField(taskClass, "b");
        fResume = optionalField(taskClass, "i");
        fSpeedLimit = optionalField(taskClass, "k");
        fHeaders = optionalField(taskClass, "o");
        fProgress = optionalField(taskClass, "q");
        fState = optionalField(taskClass, "s");
        fJob = optionalField(taskClass, "t");
        fListeners = optionalField(taskClass, "u");

        for (Method candidate : impl.getDeclaredMethods()) {
            if (!"access$finishDownload".equals(candidate.getName())) continue;
            candidate.setAccessible(true);
            mFinish = candidate;
            break;
        }
        if (mFinish == null) {
            for (Method candidate : impl.getDeclaredMethods()) {
                Class<?>[] p = candidate.getParameterTypes();
                if (candidate.getReturnType() != String.class || p.length != 7) continue;
                if (!Modifier.isStatic(candidate.getModifiers())) continue;
                if (p[1] != taskClass) continue;
                if (!PATH_CLASS.equals(p[2].getName()) || !PATH_CLASS.equals(p[3].getName())) continue;
                if (p[4] != long.class || p[5] != long.class || p[6] != Map.class) continue;
                candidate.setAccessible(true);
                mFinish = candidate;
                break;
            }
        }
        if (mFinish == null) {
            throw new NoSuchMethodException(IMPL_CLASS + "#finishDownload");
        }

        try {
            Class<?> helper = Class.forName(PATHS_HELPER_CLASS, false, classLoader);
            for (Method candidate : helper.getDeclaredMethods()) {
                Class<?>[] p = candidate.getParameterTypes();
                if (!"Path".equals(candidate.getName()) || p.length < 1 || p[0] != String.class) continue;
                if (!PATH_CLASS.equals(candidate.getReturnType().getName())) continue;
                candidate.setAccessible(true);
                mPathOf = candidate;
                mPathOfArgs = p.length;
                break;
            }
        } catch (Throwable ignored) {
        }
        if (mPathOf == null) {
            Class<?> pathClass = Class.forName(PATH_CLASS, false, classLoader);
            Constructor<?> ctor = pathClass.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            ctorPath = ctor;
        }

        try {
            Class<?> clazz = Class.forName(INFO_LOADING_CLASS, false, classLoader);
            Constructor<?> ctor = clazz.getDeclaredConstructor(long.class, long.class);
            ctor.setAccessible(true);
            loadingClass = clazz;
            ctorLoading = ctor;
        } catch (Throwable ignored) {
        }
        try {
            Method method = impl.getDeclaredMethod("getGlobalSpeedLimitKBps");
            method.setAccessible(true);
            mGlobalSpeedLimit = method;
        } catch (Throwable ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field optionalField(Class<?> owner, String name) {
        try {
            return field(owner, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Object attempt(XposedInterface.Chain chain) {
        HttpURLConnection probe = null;
        try {
            int threads = HookRuntime.getIntPreference(Prefs.KEY_DOWNLOAD_THREADS, Prefs.DOWNLOAD_THREADS_DEFAULT);
            if (threads == Prefs.DOWNLOAD_THREADS_OFF) return HookRuntime.PASS_THROUGH;
            if (threads < Prefs.DOWNLOAD_THREADS_MIN || threads > Prefs.DOWNLOAD_THREADS_MAX) {
                threads = Prefs.DOWNLOAD_THREADS_DEFAULT;
            }
            Object task = chain.getArg(0);
            if (task == null || !taskClass.isInstance(task)) return HookRuntime.PASS_THROUGH;
            String url = (String) chain.getArg(1);
            if ((url == null || url.isEmpty()) && fUrl != null) {
                url = (String) fUrl.get(task);
            }
            if (url == null || url.isEmpty()) return HookRuntime.PASS_THROUGH;
            if (fSpeedLimit != null && ((Number) fSpeedLimit.get(task)).intValue() > 0) {
                return HookRuntime.PASS_THROUGH;
            }
            Object impl = chain.getThisObject();
            if (mGlobalSpeedLimit != null && impl != null) {
                Object limit = mGlobalSpeedLimit.invoke(impl);
                if (limit instanceof Integer && (Integer) limit > 0) return HookRuntime.PASS_THROUGH;
            }
            String targetPath = (String) fTargetPath.get(task);
            String tmpPath = (String) fTmpPath.get(task);
            if (targetPath == null || targetPath.isEmpty() || tmpPath == null || tmpPath.isEmpty()) {
                return HookRuntime.PASS_THROUGH;
            }
            File target = new File(targetPath);
            if (target.exists()) return HookRuntime.PASS_THROUGH;

            boolean resumable = fResume == null || Boolean.TRUE.equals(fResume.get(task));
            File tmp = new File(tmpPath);
            long offset = 0;
            if (tmp.exists()) {
                if (resumable) {
                    offset = Math.max(0, tmp.length());
                } else if (!tmp.delete()) {
                    return HookRuntime.PASS_THROUGH;
                }
            }

            Map<String, String> headers = headersOf(task);
            long total = -1;
            try {
                probe = openConnection(url, headers, offset, offset);
                int code = probe.getResponseCode();
                if (code != 206) return HookRuntime.PASS_THROUGH;
                String contentRange = probe.getHeaderField("Content-Range");
                if (contentRange == null) return HookRuntime.PASS_THROUGH;
                int slash = contentRange.lastIndexOf('/');
                if (slash < 0) return HookRuntime.PASS_THROUGH;
                total = Long.parseLong(contentRange.substring(slash + 1).trim());
            } finally {
                if (probe != null) probe.disconnect();
                probe = null;
            }
            if (total <= 0) return HookRuntime.PASS_THROUGH;
            long remaining = total - offset;
            if (remaining < MIN_REMAINING_BYTES) return HookRuntime.PASS_THROUGH;

            File parent = tmp.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return HookRuntime.PASS_THROUGH;
            }
            parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return HookRuntime.PASS_THROUGH;
            }
            try {
                fTotal.setLong(task, total);
            } catch (Throwable ignored) {
            }
            long startTimeMs = ((Number) chain.getArg(2)).longValue();
            HookRuntime.log("多线程下载开始：线程=" + threads + " 总大小=" + total
                    + " 已缓存=" + offset + " 临时文件=" + tmpPath);
            long began = System.currentTimeMillis();
            if (!downloadSegments(task, url, headers, tmp, offset, total, threads)) {
                return HookRuntime.PASS_THROUGH;
            }
            HookRuntime.log("多线程下载完成：用时=" + (System.currentTimeMillis() - began)
                    + "ms 大小=" + total + " 临时文件=" + tmpPath);
            notifyLoading(task, total, total);
            Object result = mFinish.invoke(null, impl, task, makePath(tmpPath), makePath(targetPath),
                    total, startTimeMs, Collections.emptyMap());
            return result != null ? result : "";
        } catch (Throwable t) {
            HookRuntime.log("多线程下载失败，回退原生单连接", t);
            return HookRuntime.PASS_THROUGH;
        } finally {
            if (probe != null) probe.disconnect();
        }
    }

    private static boolean downloadSegments(Object task, String url, Map<String, String> headers,
                                            File tmp, long offset, long total, int threads) {
        long remaining = total - offset;
        int count = (int) Math.min((long) threads, Math.max(2L, remaining / SEGMENT_TARGET_BYTES));
        if (count < 2) return false;
        long chunk = (remaining + count - 1) / count;
        Segment[] segments = new Segment[count];
        long start = offset;
        for (int i = 0; i < count; i++) {
            long end = (i == count - 1) ? total - 1 : Math.min(total - 1, start + chunk - 1);
            segments[i] = new Segment(start, end);
            start = end + 1;
        }
        AtomicLong done = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        Set<HttpURLConnection> live = ConcurrentHashMap.newKeySet();
        ProgressReporter reporter = new ProgressReporter(task, offset, total);
        List<Thread> workers = new ArrayList<>();
        for (Segment segment : segments) {
            Thread worker = new Thread(() -> {
                try {
                    copySegment(task, url, headers, tmp, segment, stop, live, done, reporter);
                } catch (StopRequested ignored) {
                } catch (Throwable error) {
                    segment.failed = true;
                    HookRuntime.log("分段下载失败", error);
                }
                if (!stop.get() && segment.written < segment.length()) {
                    segment.failed = true;
                }
            }, "BiliTweaks-offline");
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        boolean stopped = false;
        while (true) {
            boolean alive = false;
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    alive = true;
                    break;
                }
            }
            if (!alive) break;
            if (stop.get()) break;
            boolean failed = false;
            for (Segment segment : segments) {
                if (segment.failed) {
                    failed = true;
                    break;
                }
            }
            if (failed) {
                stop.set(true);
                break;
            }
            if (shouldStop(task)) {
                stopped = true;
                stop.set(true);
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }
        if (stop.get()) {
            for (HttpURLConnection connection : live) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        for (Thread worker : workers) {
            try {
                worker.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
        boolean success = !stopped;
        for (Segment segment : segments) {
            if (segment.failed || segment.written < segment.length()) success = false;
        }
        if (!success) {
            truncateToPrefix(tmp, segments, offset);
            HookRuntime.log("多线程下载中止：" + (stopped ? "任务已暂停或取消" : "分段失败"));
            return false;
        }
        reporter.report(done.get(), true);
        return true;
    }

    private static void copySegment(Object task, String url, Map<String, String> headers, File tmp,
                                    Segment segment, AtomicBoolean stop, Set<HttpURLConnection> live,
                                    AtomicLong done, ProgressReporter reporter) throws IOException, StopRequested {
        try (RandomAccessFile file = new RandomAccessFile(tmp, "rw")) {
            int attempts = 0;
            long lastCheck = 0;
            while (segment.written < segment.length()) {
                if (stop.get()) throw new StopRequested();
                HttpURLConnection connection = null;
                try {
                    long from = segment.start + segment.written;
                    connection = openConnection(url, headers, from, segment.end);
                    live.add(connection);
                    int code = connection.getResponseCode();
                    if (code != 206) {
                        throw new IOException("Range 请求返回 " + code);
                    }
                    long limit = segment.end - from + 1;
                    long received = 0;
                    try (InputStream in = connection.getInputStream()) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while (received < limit && (read = in.read(buffer)) > 0) {
                            int allowed = (int) Math.min(read, limit - received);
                            file.seek(segment.start + segment.written);
                            file.write(buffer, 0, allowed);
                            segment.written += allowed;
                            received += allowed;
                            long progress = done.addAndGet(allowed);
                            if (progress - lastCheck >= STOP_CHECK_BYTES) {
                                lastCheck = progress;
                                if (stop.get() || shouldStop(task)) throw new StopRequested();
                            }
                            reporter.report(progress, false);
                        }
                    }
                    if (received < limit) {
                        throw new IOException("分段数据不完整：" + received + "/" + limit);
                    }
                } catch (StopRequested stopRequested) {
                    throw stopRequested;
                } catch (IOException error) {
                    if (stop.get()) throw new StopRequested();
                    if (++attempts >= MAX_SEGMENT_ATTEMPTS) throw error;
                    HookRuntime.log("分段重试 " + attempts + "：" + error);
                } finally {
                    if (connection != null) {
                        live.remove(connection);
                        try {
                            connection.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    private static void truncateToPrefix(File tmp, Segment[] segments, long offset) {
        long prefix = offset;
        for (Segment segment : segments) {
            if (segment.written >= segment.length()) {
                prefix = segment.end + 1;
            } else {
                prefix = segment.start + segment.written;
                break;
            }
        }
        try (RandomAccessFile file = new RandomAccessFile(tmp, "rw")) {
            file.setLength(prefix);
        } catch (Throwable t) {
            HookRuntime.log("截断临时文件失败", t);
        }
    }

    private static boolean shouldStop(Object task) {
        try {
            if (fState != null) {
                Object reference = fState.get(task);
                Object state = atomicGet(reference);
                if (state != null) {
                    String name = String.valueOf(state);
                    if (name.contains("PAUSE") || name.contains("CANCEL")) return true;
                }
            }
            if (fJob != null) {
                Object job = fJob.get(task);
                Method method = isActiveMethod(job);
                if (method != null) {
                    Object active = method.invoke(job);
                    if (active instanceof Boolean && !(Boolean) active) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Method isActiveMethod(Object job) {
        if (job == null) return null;
        if (mIsActive != null) return mIsActive;
        try {
            Method method = job.getClass().getMethod("isActive");
            mIsActive = method;
            return method;
        } catch (Throwable ignored) {
        }
        try {
            Method method = job.getClass().getMethod("getIsActive");
            mIsActive = method;
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object atomicGet(Object reference) {
        if (reference == null) return null;
        try {
            Method method = null;
            try {
                method = reference.getClass().getMethod("getValue");
            } catch (NoSuchMethodException ignored) {
            }
            if (method == null) {
                for (Method candidate : reference.getClass().getMethods()) {
                    if (candidate.getParameterCount() == 0
                            && candidate.getReturnType() == Object.class
                            && !Modifier.isStatic(candidate.getModifiers())) {
                        method = candidate;
                        break;
                    }
                }
            }
            return method == null ? null : method.invoke(reference);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void atomicSet(Object reference, long value) {
        if (reference == null) return;
        try {
            for (Method candidate : reference.getClass().getMethods()) {
                if (candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == long.class
                        && candidate.getReturnType() == void.class
                        && !Modifier.isStatic(candidate.getModifiers())) {
                    candidate.invoke(reference, value);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void notifyLoading(Object task, long loaded, long total) {
        try {
            if (fProgress != null) {
                atomicSet(fProgress.get(task), Math.min(loaded, total));
            }
        } catch (Throwable ignored) {
        }
        if (ctorLoading == null || fListeners == null) return;
        try {
            Object raw = fListeners.get(task);
            if (!(raw instanceof List)) return;
            List<?> listeners = (List<?>) raw;
            if (listeners.isEmpty()) return;
            Object info = ctorLoading.newInstance(loaded, total);
            for (Object listener : listeners) {
                if (listener == null) continue;
                try {
                    Method method = listener.getClass().getMethod("onLoading", loadingClass);
                    method.invoke(listener, info);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headersOf(Object task) {
        try {
            if (fHeaders == null) return null;
            Object raw = fHeaders.get(task);
            if (raw instanceof Map) return (Map<String, String>) raw;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object makePath(String value) throws Throwable {
        if (mPathOf != null) {
            if (mPathOfArgs == 1) return mPathOf.invoke(null, value);
            return mPathOf.invoke(null, value, new String[0]);
        }
        return ctorPath.newInstance(value);
    }

    private static HttpURLConnection openConnection(String url, Map<String, String> headers, long from, long to)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || value == null) continue;
                if ("Range".equalsIgnoreCase(name) || "User-Agent".equalsIgnoreCase(name)) continue;
                connection.setRequestProperty(name, value);
            }
        }
        connection.setRequestProperty("Range", "bytes=" + from + "-" + to);
        return connection;
    }

    private static final class Segment {
        final long start;
        final long end;
        volatile long written;
        volatile boolean failed;

        Segment(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1;
        }
    }

    private static final class StopRequested extends Exception {
    }

    private static final class ProgressReporter {
        private final Object task;
        private final long offset;
        private final long total;
        private volatile long lastNotifyMs;

        ProgressReporter(Object task, long offset, long total) {
            this.task = task;
            this.offset = offset;
            this.total = total;
        }

        void report(long written, boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastNotifyMs < NOTIFY_INTERVAL_MS) return;
            synchronized (this) {
                if (!force && now - lastNotifyMs < NOTIFY_INTERVAL_MS) return;
                lastNotifyMs = now;
                notifyLoading(task, Math.min(offset + written, total), total);
            }
        }
    }
}
