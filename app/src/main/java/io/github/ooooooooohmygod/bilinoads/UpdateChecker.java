package io.github.ooooooooohmygod.bilinoads;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 通过 GitHub Releases 的 Atom 源检查最新版本，避免调用 API 触发限流。 */
final class UpdateChecker {
    static final String FEED_URL = "https://github.com/ooooooooohmygod/BiliTweaks/releases.atom";
    static final String RELEASES_URL = "https://github.com/ooooooooohmygod/BiliTweaks/releases";

    interface Callback {
        void onResult(String latestTag, String releaseUrl);
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
}
