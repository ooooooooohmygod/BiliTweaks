package io.github.ooooooooohmygod.bilitweaks;

final class Prefs {
    static final String NAME = "settings";
    static final String KEY_SPLASH_AD = "splash_ad";
    static final String KEY_PAUSE_AD = "pause_ad";
    static final String KEY_END_AD = "end_ad";
    static final String KEY_VIDEO_BANNER_AD = "video_banner_ad";
    static final String KEY_INLINE_QUALITY = "inline_quality";
    static final String KEY_FULLSCREEN_QUALITY = "fullscreen_quality";
    static final String KEY_UPDATE = "update";
    static final String KEY_DOWNLOAD_THREADS = "download_threads";
    static final String KEY_DEBUG_LOG = "debug_log";
    static final String KEY_BILIBILI_LOG = "bilibili_log";
    static final String KEY_CLEAR_LOGS = "clear_logs_at";

    /** 离线下载多线程：关闭。 */
    static final int DOWNLOAD_THREADS_OFF = 0;

    /** 离线下载多线程：默认线程数。 */
    static final int DOWNLOAD_THREADS_DEFAULT = 4;

    /** 离线下载多线程：自定义下限。 */
    static final int DOWNLOAD_THREADS_MIN = 2;

    /** 离线下载多线程：自定义上限。 */
    static final int DOWNLOAD_THREADS_MAX = 16;

    /** 清晰度偏好：动态取当前视频可用的最高档。 */
    static final int AUTO_MAX_QUALITY = -1;

    /** 清晰度偏好：动态取当前视频可用的最低档。 */
    static final int AUTO_MIN_QUALITY = -2;

    private Prefs() {}
}
