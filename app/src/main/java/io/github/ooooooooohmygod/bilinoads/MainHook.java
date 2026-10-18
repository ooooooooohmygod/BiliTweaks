package io.github.ooooooooohmygod.bilinoads;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * 哔哩哔哩 9.15.0 去广告（开屏 / 暂停页 / 结束页）与去更新检测。
 * 与 bili-noads 的 10 个 smali 补丁点一一对应，改为在官方包上运行时 hook。
 */
public final class MainHook extends XposedModule {
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final long TARGET_VERSION_CODE = 9150300L;
    private static final String TARGET_VERSION_NAME = "9.15.0";

    private static final AtomicBoolean ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private boolean mainProcess;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        HookRuntime.attach(this);
        AiBlock.attach(this);
        mainProcess = TARGET_PACKAGE.equals(param.getProcessName());
        if (!mainProcess) {
            detach();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!mainProcess
                || !TARGET_PACKAGE.equals(param.getPackageName())
                || !param.isFirstPackage()
                || !ATTACH_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            HookRuntime.hookAfter(attach, chain -> {
                Context context = (Context) chain.getArg(0);
                if (context == null) return;
                if (!isTargetVersion(context)) return;
                if (!INSTALLED.compareAndSet(false, true)) return;
                if (context instanceof Application) {
                    AiBlock.install((Application) context);
                }
                installHooks(context.getClassLoader());
            });
        } catch (Throwable t) {
            HookRuntime.log("hook Application.attach 失败，模块未生效", t);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isTargetVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            if (versionCode == TARGET_VERSION_CODE && TARGET_VERSION_NAME.equals(info.versionName)) {
                HookRuntime.log("命中哔哩哔哩 " + info.versionName + "（" + versionCode + "），开始安装 hook");
                return true;
            }
            HookRuntime.log("版本不匹配：哔哩哔哩 " + info.versionName + "（" + versionCode
                    + "），本模块仅支持 " + TARGET_VERSION_NAME + "，跳过安装");
        } catch (Throwable t) {
            HookRuntime.log("读取哔哩哔哩版本失败", t);
        }
        return false;
    }

    private static void installHooks(ClassLoader classLoader) {
        List<String> missing = new ArrayList<>();
        int installed = 0;

        // 1. 暂停页总开关恒 false
        installed += install(missing, "暂停页总开关", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.adcommon.config.DDConfig", "getPausePageEnable"),
                Boolean.FALSE));

        // 2/3. 结束页广告：解码入口与兜底入口恒 false
        installed += install(missing, "结束页解码", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService",
                        "R", byte[].class, boolean.class),
                Boolean.FALSE));
        installed += install(missing, "结束页兜底", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService",
                        "e0", byte[].class),
                Boolean.FALSE));

        // 4/5/6. 开屏三处：保留副作用，返回值恒 null
        installed += install(missing, "开屏订单", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "tv.danmaku.bili.MainActivitySplashComponentExtKt",
                        "a", clazz(classLoader, "tv.danmaku.bili.MainActivityV2")),
                null));
        installed += install(missing, "开屏内容", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "kntr.srcs.app.splash.core.vm.BusinessSplashViewModel",
                        "a", clazz(classLoader, "kntr.srcs.app.splash.model.SplashSource")),
                null));
        installed += install(missing, "开屏页面工厂", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "tv.danmaku.bili.splash.ad.core.SplashManager",
                        "a", clazz(classLoader, "tv.danmaku.bili.splash.ad.model.SplashOrder")),
                null));

        // 7/8. 去更新检测：启动与手动检查入口整体禁用
        installed += install(missing, "启动更新检查", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "tv.danmaku.bili.update.api.UpdateHelper", "checkUpdateInStartup",
                        Activity.class,
                        clazz(classLoader, "tv.danmaku.bili.update.api.updater.IUpdater")),
                null));
        installed += install(missing, "手动更新检查", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "tv.danmaku.bili.update.api.UpdateHelper", "checkUpdateAndShowDialog",
                        Context.class,
                        clazz(classLoader, "tv.danmaku.bili.update.api.updater.IUpdater")),
                null));

        // 9. 缓存的强制更新读取恒 null
        installed += install(missing, "缓存更新读取", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "qs1.g", "call"),
                null));

        // 10. 已下载更新包安装提示禁用
        installed += install(missing, "更新安装提示", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "y30.l$a", "checkInstall", Context.class),
                null));

        // 11/12/13. 屏蔽 AI 相关视频：详情页 argue 标记恒为“含AI生成内容”
        installed += install(missing, "AI标记文案", () -> HookRuntime.hookAfterResult(
                method(classLoader, "com.bapis.bilibili.app.view.v1.ArgueBar", "getMsg"),
                result -> AiBlock.onText(result instanceof String ? (String) result : null)));
        installed += install(missing, "AI标记详情", () -> HookRuntime.hookAfterResult(
                method(classLoader, "com.bapis.bilibili.app.view.v1.ViewReply", "getArgueBar"),
                AiBlock::inspectArgueBar));
        installed += install(missing, "AI标记剧集", () -> HookRuntime.hookAfterResult(
                method(classLoader, "com.bapis.bilibili.app.view.v1.ActivitySeason", "getArgueBar"),
                AiBlock::inspectArgueBar));
        // 兜底：详情页若用原生 TextView 展示该标记，也能在 UI 线程精确命中
        installed += install(missing, "AI标记文本", () -> HookRuntime.hookAfter(
                TextView.class.getDeclaredMethod("setText", CharSequence.class),
                chain -> {
                    Object text = chain.getArg(0);
                    if (text instanceof CharSequence) {
                        AiBlock.onText(text.toString());
                    }
                }));

        String summary = "hook 安装完成：" + installed + "/14";
        if (!missing.isEmpty()) {
            summary += "，未命中：" + String.join("、", missing);
        }
        HookRuntime.log(summary);
    }

    private static Class<?> clazz(ClassLoader classLoader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, classLoader);
    }

    private static Method method(ClassLoader classLoader, String className, String name, Class<?>... parameterTypes)
            throws Throwable {
        Class<?> current = clazz(classLoader, className);
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(className + "#" + name);
    }

    private static int install(List<String> missing, String id, Task task) {
        try {
            task.run();
            return 1;
        } catch (Throwable t) {
            missing.add(id);
            HookRuntime.log("hook 未安装：" + id, t);
            return 0;
        }
    }

    private interface Task {
        void run() throws Throwable;
    }
}
