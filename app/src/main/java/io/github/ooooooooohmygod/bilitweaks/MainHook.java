package io.github.ooooooooohmygod.bilitweaks;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedModule;

/**
 * 哔哩哔哩 9.15.0 去广告（开屏 / 暂停页 / 结束页）、去更新检测、清晰度与离线下载加速。
 * 与 BiliTweaks 的 smali 补丁点一一对应，改为在官方包上运行时 hook。
 */
public final class MainHook extends XposedModule {
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final long TARGET_VERSION_CODE = 9150300L;
    private static final String TARGET_VERSION_NAME = "9.15.0";

    private static final AtomicBoolean ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private boolean mainProcess;
    private boolean downloadProcess;
    private String processName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        HookRuntime.attach(this);
        String process = param.getProcessName();
        processName = process;
        mainProcess = TARGET_PACKAGE.equals(process);
        // 离线缓存下载跑在 :download 进程（下载器也初始化在 :web），这两个进程只装下载加速
        downloadProcess = !mainProcess && process != null && process.startsWith(TARGET_PACKAGE)
                && (process.contains("download") || process.endsWith(":web"));
        if (!mainProcess && !downloadProcess) {
            detach();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if ((!mainProcess && !downloadProcess)
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
                LogFile.init(context, processName, HookRuntime.isFeatureEnabled(Prefs.KEY_DEBUG_LOG));
                HookRuntime.log("设置：离线下载线程数=" + HookRuntime.getIntPreference(
                        Prefs.KEY_DOWNLOAD_THREADS, Prefs.DOWNLOAD_THREADS_DEFAULT));
                if (!isTargetVersion(context)) return;
                if (!INSTALLED.compareAndSet(false, true)) return;
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

    private void installHooks(ClassLoader classLoader) {
        List<String> missing = new ArrayList<>();
        if (mainProcess) {
            installMainHooks(classLoader, missing);
        }

        // 20. 离线缓存单文件多线程下载：主进程与 :download / :web 进程都会安装
        int downloadInstalled = install(missing, "下载加速", () -> DownloadAccelerator.install(classLoader));
        HookRuntime.log("下载加速 hook：" + (downloadInstalled == 1 ? "已安装" : "未安装"));
    }

    private static void installMainHooks(ClassLoader classLoader, List<String> missing) {
        int installed = 0;

        // 1. 暂停页总开关：关闭时放行原逻辑
        installed += install(missing, "暂停页总开关", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.adcommon.config.DDConfig", "getPausePageEnable"),
                enabled(Prefs.KEY_PAUSE_AD),
                Boolean.FALSE));

        // 2/3. 结束页广告：解码入口与兜底入口
        installed += install(missing, "结束页解码", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService",
                        "R", byte[].class, boolean.class),
                enabled(Prefs.KEY_END_AD),
                Boolean.FALSE));
        installed += install(missing, "结束页兜底", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService",
                        "e0", byte[].class),
                enabled(Prefs.KEY_END_AD),
                Boolean.FALSE));

        // 4/5/6. 开屏三处：保留副作用，返回值置空
        installed += install(missing, "开屏订单", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "tv.danmaku.bili.MainActivitySplashComponentExtKt",
                        "a", clazz(classLoader, "tv.danmaku.bili.MainActivityV2")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));
        installed += install(missing, "开屏内容", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "kntr.srcs.app.splash.core.vm.BusinessSplashViewModel",
                        "a", clazz(classLoader, "kntr.srcs.app.splash.model.SplashSource")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));
        installed += install(missing, "开屏页面工厂", () -> HookRuntime.hookAfterReturnConstant(
                method(classLoader, "tv.danmaku.bili.splash.ad.core.SplashManager",
                        "a", clazz(classLoader, "tv.danmaku.bili.splash.ad.model.SplashOrder")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));

        // 7/8. 去更新检测：启动与手动检查入口
        installed += install(missing, "启动更新检查", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "tv.danmaku.bili.update.api.UpdateHelper", "checkUpdateInStartup",
                        Activity.class,
                        clazz(classLoader, "tv.danmaku.bili.update.api.updater.IUpdater")),
                enabled(Prefs.KEY_UPDATE),
                null));
        installed += install(missing, "手动更新检查", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "tv.danmaku.bili.update.api.UpdateHelper", "checkUpdateAndShowDialog",
                        Context.class,
                        clazz(classLoader, "tv.danmaku.bili.update.api.updater.IUpdater")),
                enabled(Prefs.KEY_UPDATE),
                null));

        // 9. 缓存的强制更新读取
        installed += install(missing, "缓存更新读取", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "qs1.g", "call"),
                enabled(Prefs.KEY_UPDATE),
                null));

        // 10. 已下载更新包安装提示
        installed += install(missing, "更新安装提示", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "y30.l$a", "checkInstall", Context.class),
                enabled(Prefs.KEY_UPDATE),
                null));

        // 11. 视频下方横幅广告：跳过 BannerV2Controller 的绑定（该方法本身允许返回 null）
        installed += install(missing, "视频下方横幅广告", () -> HookRuntime.hookReturnConstant(
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

        // 12. 开屏实时广告请求：直接返回 null，跳过网络等待
        installed += install(missing, "开屏实时广告请求", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "kntr.srcs.app.splash.services.d", "b",
                        String.class, String.class,
                        clazz(classLoader, "kotlin.coroutines.jvm.internal.ContinuationImpl")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));

        // 13. 开屏列表网络请求：直接返回 null，使用本地列表/品牌壁纸，避免壁纸前长时间等待
        installed += install(missing, "开屏列表请求", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "kntr.srcs.app.splash.services.d", "a",
                        clazz(classLoader, "kntr.srcs.app.splash.core.model.OpenEvent"),
                        String.class, String.class, String.class,
                        clazz(classLoader, "kotlin.coroutines.jvm.internal.ContinuationImpl")),
                enabled(Prefs.KEY_SPLASH_AD),
                null));

        // 14. 非全屏清晰度：CardPlayerQualityService 默认把卡片播放器限制在 720P，自动最低/最高时改用极值档
        installed += install(missing, "非全屏清晰度", () -> HookRuntime.hookReturnIntFrom(
                method(classLoader, "su1.f$d", "getExpectedQuality",
                        clazz(classLoader, "tv.danmaku.biliplayerv2.service.IVideoQualityProvider$ResolveFrom")),
                PlayerQuality::cardQuality));

        // 15. 全屏默认清晰度：覆盖播放器默认清晰度设置
        installed += install(missing, "全屏清晰度", () -> HookRuntime.hookReturnIntIf(
                method(classLoader, "com.bilibili.playerbizcommon.utils.PlayerSettingHelper",
                        "getSettingsQuality"),
                () -> HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY)));

        // 16. 播放器默认清晰度（getDefaultQuality 会受自动切档影响）
        installed += install(missing, "播放器默认清晰度", () -> HookRuntime.hookReturnIntIf(
                method(classLoader, "com.bilibili.playerbizcommon.utils.PlayerSettingHelper",
                        "getDefaultQuality"),
                () -> HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY)));

        // 17. 选了固定档位或自动最低/最高时关闭自动清晰度，避免被自动切档覆盖
        installed += install(missing, "关闭自动清晰度", () -> HookRuntime.hookReturnConstant(
                method(classLoader, "com.bilibili.playerbizcommon.utils.PlayerSettingHelper",
                        "isQualityAutoSwitch"),
                () -> HookRuntime.getIntPreference(Prefs.KEY_INLINE_QUALITY) != 0
                        || HookRuntime.getIntPreference(Prefs.KEY_FULLSCREEN_QUALITY) != 0,
                Boolean.FALSE));

        // 18. 播放器最终请求清晰度：按当前全屏状态强制档位
        installed += install(missing, "最终请求清晰度", () -> HookRuntime.hookReturnIntFrom(
                method(classLoader, "tv.danmaku.biliplayerv2.service.VideoPlayHandler", "expectedQuality"),
                PlayerQuality::resolve));

        // 19. 自动档位对齐：解析完成后把选中档位对齐到自动最低/最高档
        installed += install(missing, "自动档位对齐", () -> HookRuntime.hookBefore(
                method(classLoader, "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler", "d",
                        clazz(classLoader, "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler"),
                        clazz(classLoader, "com.bilibili.lib.media.resource.MediaResource"),
                        clazz(classLoader, "tv.danmaku.biliplayerv2.service.Video$PlayableParams"),
                        boolean.class,
                        clazz(classLoader, "tv.danmaku.videoplayer.coreV2.MediaItem"),
                        Long.class,
                        int.class),
                chain -> PlayerQuality.alignAuto(chain.getArg(0), chain.getArg(1))));

        String summary = "hook 安装完成：" + installed + "/19";
        if (!missing.isEmpty()) {
            summary += "，未命中：" + String.join("、", missing);
        }
        HookRuntime.log(summary);
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
