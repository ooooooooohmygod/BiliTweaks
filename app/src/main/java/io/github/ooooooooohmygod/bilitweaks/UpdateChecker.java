package io.github.ooooooooohmygod.bilitweaks;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 通过 Release Atom 源检查版本，并支持下载对应 APK 资产。 */
final class UpdateChecker {
    static final String FEED_URL = "https://github.com/ooooooooohmygod/BiliTweaks/releases.atom";
    static final String RELEASES_URL = "https://github.com/ooooooooohmygod/BiliTweaks/releases";

    private static final String[] MIRRORS = {
            "",
            "https://ghproxy.net/",
            "https://gh-proxy.com/"
    };

    interface Callback {
        void onResult(String latestTag, String releaseUrl);
    }

    interface ProgressCallback {
        void onProgress(int percent);
    }

    interface DownloadCallback {
        void onResult(File file, String error);
    }

    private UpdateChecker() {}

    static void check(Callback callback) {
        Thread thread = new Thread(() -> {
            String tag = null;
            String url = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(FEED_URL).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "BiliTweaks");
                connection.setRequestProperty("Accept", "application/atom+xml");

                try (InputStream input = connection.getInputStream()) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(input, null);
                    boolean inEntry = false;
                    int event;
                    while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            String name = parser.getName();
                            if ("entry".equals(name)) {
                                inEntry = true;
                            } else if (inEntry && "title".equals(name)) {
                                tag = parser.nextText().trim();
                            } else if (inEntry && "link".equals(name)) {
                                String href = parser.getAttributeValue(null, "href");
                                if (href != null && href.contains("/releases/tag/")) {
                                    url = href;
                                }
                            }
                        } else if (event == XmlPullParser.END_TAG && "entry".equals(parser.getName())) {
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }

            if (url != null) {
                int index = url.lastIndexOf("/tag/");
                if (index >= 0) tag = url.substring(index + 5);
            } else if (tag != null && tag.startsWith("BiliTweaks ")) {
                tag = tag.substring("BiliTweaks ".length());
            } else if (tag != null && tag.startsWith("bili-noads ")) {
                tag = tag.substring("bili-noads ".length());
            }
            callback.onResult(tag, url);
        }, "BiliTweaks-update");
        thread.start();
    }

    static String assetUrl(String tag) {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        return "https://github.com/ooooooooohmygod/BiliTweaks/releases/download/"
                + tag + "/BiliTweaks_" + version + ".apk";
    }

    static void download(String originalUrl, File dest, ProgressCallback progress, DownloadCallback callback) {
        Thread thread = new Thread(() -> {
            String lastError = "unknown";
            for (String mirror : MIRRORS) {
                String url = mirror.isEmpty() ? originalUrl : mirror + originalUrl;
                try {
                    downloadOnce(url, dest, progress);
                    callback.onResult(dest, null);
                    return;
                } catch (Throwable t) {
                    lastError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                }
            }
            callback.onResult(null, lastError);
        }, "BiliTweaks-download");
        thread.start();
    }

    private static void downloadOnce(String url, File dest, ProgressCallback progress) throws IOException {
        File part = new File(dest.getParentFile(), dest.getName() + ".part");
        part.delete();
        HttpURLConnection connection = null;
        try {
            String current = url;
            for (int redirect = 0; redirect <= 8; redirect++) {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
                connection.setRequestProperty("Accept", "*/*");

                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null) throw new IOException("redirect without location");
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + code);
                }

                long total = connection.getContentLengthLong();
                if (progress != null) progress.onProgress(total > 0 ? 0 : -1);
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[8192];
                    long read = 0;
                    int count;
                    while ((count = input.read(buffer)) > 0) {
                        output.write(buffer, 0, count);
                        read += count;
                        if (progress != null && total > 0) {
                            progress.onProgress((int) (read * 100 / total));
                        }
                    }
                    output.flush();
                }

                if (part.length() < 100_000) {
                    throw new IOException("file too small");
                }
                try (InputStream magic = new FileInputStream(part)) {
                    byte[] head = new byte[2];
                    if (magic.read(head) != 2 || head[0] != 'P' || head[1] != 'K') {
                        throw new IOException("not an APK");
                    }
                }
                dest.delete();
                if (!part.renameTo(dest)) {
                    throw new IOException("rename failed");
                }
                return;
            }
            throw new IOException("too many redirects");
        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
