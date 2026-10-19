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

    static final int DOWNLOAD_THREADS_OFF = 0;

    static final int DOWNLOAD_THREADS_DEFAULT = 4;

    static final int DOWNLOAD_THREADS_MIN = 2;

    static final int DOWNLOAD_THREADS_MAX = 16;

    static final int AUTO_MAX_QUALITY = -1;

    static final int AUTO_MIN_QUALITY = -2;

    private Prefs() {}
}
