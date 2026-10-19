package io.github.ooooooooohmygod.bilitweaks;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedModule;

public final class MainHook extends XposedModule {
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final long TARGET_VERSION_CODE = 9150300L;
    private static final String TARGET_VERSION_NAME = "9.15.0";

    private static final AtomicBoolean ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private boolean mainProcess;
    private boolean biliProcess;
    private boolean downloadProcess;
    private String processName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        HookRuntime.attach(this);
        String process = param.getProcessName();
        processName = process;
        mainProcess = TARGET_PACKAGE.equals(process);
        biliProcess = process != null
                && (process.equals(TARGET_PACKAGE) || process.startsWith(TARGET_PACKAGE + ":"));
        downloadProcess = !mainProcess && biliProcess
                && (process.contains("download") || process.endsWith(":web"));
        if (!biliProcess) {
            detach();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!biliProcess
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
                installHooks(context.getClassLoader());
            });
        } catch (Throwable t) {
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
                return true;
            }
        } catch (Throwable t) {
        }
        return false;
    }

    private void installHooks(ClassLoader classLoader) {
        if (mainProcess) {
            installMainHooks(classLoader);
        }

        if (mainProcess || downloadProcess) {
            install("下载加速", () -> DownloadAccelerator.install(classLoader));
        }
    }

    private static void installMainHooks(ClassLoader classLoader) {
        install("暂停页总开关", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.adcommon.config.DDConfig", "getPausePageEnable"),
                enabled(Prefs.KEY_PAUSE_AD),
                Boolean.FALSE));

        install("结束页解码", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService",
                        "R", byte[].class, boolean.class),
                enabled(Prefs.KEY_END_AD),
                Boolean.FALSE));
        install("结束页兜底", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService",
                        "e0", byte[].class),
                enabled(Prefs.KEY_END_AD),
                Boolean.FALSE));

        install("开屏订单", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "tv.danmaku.bili.MainActivitySplashComponentExtKt",
                        "a", clazz(classLoader, "tv.danmaku.bili.MainActivityV2")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));
        install("开屏内容", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "kntr.srcs.app.splash.core.vm.BusinessSplashViewModel",
                        "a", clazz(classLoader, "kntr.srcs.app.splash.model.SplashSource")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));
        install("开屏页面工厂", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "tv.danmaku.bili.splash.ad.core.SplashManager",
                        "a", clazz(classLoader, "tv.danmaku.bili.splash.ad.model.SplashOrder")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));

        install("启动更新检查", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "tv.danmaku.bili.update.api.UpdateHelper", "checkUpdateInStartup",
                        Activity.class,
                        clazz(classLoader, "tv.danmaku.bili.update.api.updater.IUpdater")),
                enabled(Prefs.KEY_UPDATE),
                null));
        install("手动更新检查", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "tv.danmaku.bili.update.api.UpdateHelper", "checkUpdateAndShowDialog",
                        Context.class,
                        clazz(classLoader, "tv.danmaku.bili.update.api.updater.IUpdater")),
                enabled(Prefs.KEY_UPDATE),
                null));

        install("缓存更新读取", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "qs1.g", "call"),
                enabled(Prefs.KEY_UPDATE),
                null));

        install("更新安装提示", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "y30.l$a", "checkInstall", Context.class),
                enabled(Prefs.KEY_UPDATE),
                null));

        install("视频下方横幅广告", () -> HookRuntime.hookReturnConstant(
                method(classLoader,
                        "com.bilibili.ad.adview.videodetail.upper.banner.v2.BannerV2Controller",
                        "bindUniteBanner",
                        boolean.class,
                        ViewGroup.class,
                        clazz(classLoader, "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.AdUpperBridge"),
                        clazz(classLoader, "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.AdUpperConfig"),
                        List.class,
                        List.class,
                        clazz(classLoader, "com.bilibili.adcommon.data.model.AdUpperExtraDataDto")),
                enabled(Prefs.KEY_VIDEO_BANNER_AD),
                null));

        install("开屏实时广告请求", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "kntr.srcs.app.splash.services.d", "b",
                        String.class, String.class,
                        clazz(classLoader, "kotlin.coroutines.jvm.internal.ContinuationImpl")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));

        install("开屏列表请求", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "kntr.srcs.app.splash.services.d", "a",
                        clazz(classLoader, "kntr.srcs.app.splash.core.model.OpenEvent"),
                        String.class, String.class, String.class,
                        clazz(classLoader, "kotlin.coroutines.jvm.internal.ContinuationImpl")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));

        install("非全屏清晰度", () -> HookRuntime.hookReturnIntFrom(
                method(classLoader, "su1.f$d", "getExpectedQuality",
                        clazz(classLoader, "tv.danmaku.biliplayerv2.service.IVideoQualityProvider$ResolveFrom")),
                PlayerQuality::cardQuality));

        install("全屏清晰度", () -> HookRuntime.hookReturnIntIf(
                method(classLoader, "com.bilibili.playerbizcommon.utils.PlayerSettingHelper",
                        "getSettingsQuality"),
                () -> HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY)));

        install("播放器默认清晰度", () -> HookRuntime.hookReturnIntIf(
                method(classLoader, "com.bilibili.playerbizcommon.utils.PlayerSettingHelper",
                        "getDefaultQuality"),
                () -> HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY)));

        install("关闭自动清晰度", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.playerbizcommon.utils.PlayerSettingHelper",
                        "isQualityAutoSwitch"),
                () -> HookRuntime.getIntPreference(Prefs.KEY_INLINE_QUALITY) != 0
                        || HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY) != 0,
                Boolean.FALSE));

        install("最终请求清晰度", () -> HookRuntime.hookReturnIntFrom(
                method(classLoader, "tv.danmaku.biliplayerv2.service.VideoPlayHandler", "expectedQuality"),
                PlayerQuality::resolve));

        install("自动档位对齐", () -> HookRuntime.hookBefore(
                method(classLoader, "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler", "d",
                        clazz(classLoader, "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler"),
                        clazz(classLoader, "com.bilibili.lib.media.resource.MediaResource"),
                        clazz(classLoader, "tv.danmaku.biliplayerv2.service.Video$PlayableParams"),
                        boolean.class,
                        clazz(classLoader, "tv.danmaku.videoplayer.coreV2.MediaItem"),
                        Long.class,
                        int.class),
                chain -> PlayerQuality.alignAuto(chain.getArg(0), chain.getArg(1))));
    }

    private static BooleanSupplier enabled(String key) {
        return () -> HookRuntime.isFeatureEnabled(key);
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

    private static void install(String id, Task task) {
        try {
            task.run();
        } catch (Throwable t) {
        }
    }

    private interface Task {
        void run() throws Throwable;
    }
}
