package io.github.ooooooooohmygod.bilitweaks;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.io.File;

public final class MainActivity extends AppCompatActivity {
    private AlertDialog progressDialog;
    private LinearProgressIndicator progressBar;
    private TextView progressText;
    private int lastPercent = Integer.MIN_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        View content = findViewById(R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(0, bars.top, 0, 0);
            content.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });

        bindCard(R.id.card_splash, Prefs.KEY_SPLASH_AD);
        bindCard(R.id.card_pause, Prefs.KEY_PAUSE_AD);
        bindCard(R.id.card_end, Prefs.KEY_END_AD);
        bindCard(R.id.card_video_banner, Prefs.KEY_VIDEO_BANNER_AD);
        bindQualityDropdown(R.id.dropdown_inline_quality, Prefs.KEY_INLINE_QUALITY);
        bindQualityDropdown(R.id.dropdown_fullscreen_quality, Prefs.KEY_FULLSCREEN_QUALITY);
        bindThreadDropdown(R.id.dropdown_download_threads, Prefs.KEY_DOWNLOAD_THREADS);
        bindCard(R.id.card_update, Prefs.KEY_UPDATE);
        bindCard(R.id.card_log, Prefs.KEY_LOG);

        findViewById(R.id.button_check_update).setOnClickListener(v -> checkForUpdates(true));
        findViewById(R.id.button_clear_logs).setOnClickListener(v -> clearLogs());

        boolean lightBars = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);

        checkForUpdates(false);
    }

    @Override
    protected void onDestroy() {
        dismissProgress();
        super.onDestroy();
    }

    /** 功能卡片就是开关：点卡片本体切换，选中态由卡片描边与对勾显示。 */
    private void bindCard(int cardId, String key) {
        MaterialCardView card = findViewById(cardId);
        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        card.setChecked(prefs.getBoolean(key, true));
        card.setOnCheckedChangeListener((view, checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        card.setOnClickListener(view -> card.toggle());
    }

    /**
     * 「清理日志」：点击后立即删除 Download/BiliTweaks 下的全部日志文件。
     * 日志文件属哔哩哔哩 uid，App 删不了，所以直接广播给哔哩哔哩进程由模块侧删除；
     * 哔哩哔哩未运行时收不到广播，写入的请求值会在它下次启动时补清。
     */
    private void clearLogs() {
        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        long request = Math.max(
                prefs.getLong(Prefs.KEY_CLEAR_LOGS, 0L) + 1L,
                System.currentTimeMillis());
        prefs.edit().putLong(Prefs.KEY_CLEAR_LOGS, request).commit();
        Intent intent = new Intent(LogCleaner.ACTION_CLEAR_LOGS);
        intent.setPackage("tv.danmaku.bili");
        intent.putExtra(LogCleaner.EXTRA_REQUEST, request);
        try {
            sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent received) {
                    int deleted = -1;
                    String data = getResultData();
                    if (data != null && !data.isEmpty()) {
                        try {
                            deleted = Integer.parseInt(data);
                        } catch (Throwable ignored) {
                        }
                    }
                    showClearLogsResult(getResultCode(), deleted);
                }
            }, null, 0, null, null);
        } catch (Throwable t) {
            try {
                sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
            Toast.makeText(this, R.string.clear_logs_pending, Toast.LENGTH_LONG).show();
        }
    }

    private void showClearLogsResult(int result, int deleted) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (result == LogCleaner.RESULT_CLEANED) {
                Toast.makeText(this, deleted > 0
                        ? getString(R.string.clear_logs_done, deleted)
                        : getString(R.string.clear_logs_cleared), Toast.LENGTH_SHORT).show();
            } else if (result == LogCleaner.RESULT_FAILED) {
                Toast.makeText(this, R.string.clear_logs_failed, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.clear_logs_pending, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static final int[] QUALITY_VALUES = {0, Prefs.AUTO_MIN_QUALITY, Prefs.AUTO_MAX_QUALITY, 16, 32, 64, 74};

    private void bindQualityDropdown(int viewId, String key) {
        MaterialAutoCompleteTextView view = findViewById(viewId);
        String[] labels = getResources().getStringArray(R.array.quality_options);
        view.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        int current = getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getInt(key, 0);
        int index = 0;
        boolean found = false;
        for (int i = 0; i < QUALITY_VALUES.length; i++) {
            if (QUALITY_VALUES[i] == current) {
                index = i;
                found = true;
                break;
            }
        }
        if (!found) {
            // 旧版本设置过的档位已下线，重置为"自动"，避免界面与实际行为不一致
            getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().remove(key).apply();
        }
        view.setText(labels[index], false);
        view.setOnItemClickListener((parent, item, position, id) ->
                getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
                        .putInt(key, QUALITY_VALUES[position])
                        .apply());
    }

    /** 下拉框前 5 项是固定档位，最后一项「自定义…」弹输入框。 */
    private static final int[] THREAD_VALUES = {
            Prefs.DOWNLOAD_THREADS_OFF, 2, 4, 6, 8
    };

    private void bindThreadDropdown(int viewId, String key) {
        MaterialAutoCompleteTextView view = findViewById(viewId);
        refreshThreadDropdown(view, key);
        view.setOnItemClickListener((parent, item, position, id) -> {
            if (position >= 0 && position < THREAD_VALUES.length) {
                getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
                        .putInt(key, THREAD_VALUES[position])
                        .apply();
                refreshThreadDropdown(view, key);
            } else {
                showThreadDialog(view, key);
            }
        });
    }

    private void refreshThreadDropdown(MaterialAutoCompleteTextView view, String key) {
        String[] labels = getResources().getStringArray(R.array.download_thread_options).clone();
        int current = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getInt(key, Prefs.DOWNLOAD_THREADS_DEFAULT);
        if (current != Prefs.DOWNLOAD_THREADS_OFF
                && (current < Prefs.DOWNLOAD_THREADS_MIN || current > Prefs.DOWNLOAD_THREADS_MAX)) {
            // 存量值不合法，重置为默认线程数
            getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().remove(key).apply();
            current = Prefs.DOWNLOAD_THREADS_DEFAULT;
        }
        int index = -1;
        for (int i = 0; i < THREAD_VALUES.length; i++) {
            if (THREAD_VALUES[i] == current) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            index = THREAD_VALUES.length;
            labels[index] = getString(R.string.thread_custom_label, current);
        }
        view.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        view.setText(labels[index], false);
    }

    private void showThreadDialog(MaterialAutoCompleteTextView view, String key) {
        int current = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getInt(key, Prefs.DOWNLOAD_THREADS_DEFAULT);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current >= Prefs.DOWNLOAD_THREADS_MIN
                ? current : Prefs.DOWNLOAD_THREADS_DEFAULT));
        input.setSelection(input.getText().length());
        float density = getResources().getDisplayMetrics().density;
        FrameLayout container = new FrameLayout(this);
        container.setPadding((int) (density * 20), (int) (density * 8), (int) (density * 20), 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.thread_dialog_title)
                .setMessage(R.string.thread_dialog_message)
                .setView(container)
                .setPositiveButton(R.string.thread_dialog_confirm, (dialog, which) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable t) {
                        value = Prefs.DOWNLOAD_THREADS_DEFAULT;
                    }
                    value = Math.max(Prefs.DOWNLOAD_THREADS_MIN,
                            Math.min(Prefs.DOWNLOAD_THREADS_MAX, value));
                    getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
                            .putInt(key, value)
                            .apply();
                    refreshThreadDropdown(view, key);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> refreshThreadDropdown(view, key))
                .show();
    }

    private void checkForUpdates(boolean userInitiated) {
        if (userInitiated) {
            Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show();
        }
        UpdateChecker.check((latestTag, releaseUrl) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (latestTag == null) {
                if (userInitiated) {
                    Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            String current = currentVersion();
            if (compareVersions(latestTag, current) > 0) {
                showUpdateDialog(current, latestTag, releaseUrl);
            } else if (userInitiated) {
                Toast.makeText(this, R.string.update_latest, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    private void showUpdateDialog(String current, String latest, String releaseUrl) {
        String url = releaseUrl != null ? releaseUrl : UpdateChecker.RELEASES_URL;
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.update_available_title, latest))
                .setMessage(getString(R.string.update_available_message, current, latest))
                .setPositiveButton(R.string.update_now, (dialog, which) -> startUpdate(latest))
                .setNeutralButton(R.string.update_browser, (dialog, which) -> openBrowser(url))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startUpdate(String tag) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.install_permission_required, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
            }
            return;
        }

        showProgressDialog();
        File dest = new File(getCacheDir(), "BiliTweaks-update.apk");
        UpdateChecker.download(UpdateChecker.assetUrl(tag), dest,
                percent -> runOnUiThread(() -> updateProgress(percent)),
                (file, error) -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        if (file != null) file.delete();
                        return;
                    }
                    dismissProgress();
                    if (file == null) {
                        showDownloadFailure(error);
                        return;
                    }
                    PackageInfo info = getPackageManager()
                            .getPackageArchiveInfo(file.getAbsolutePath(), 0);
                    if (info == null || !getPackageName().equals(info.packageName)) {
                        file.delete();
                        Toast.makeText(this, R.string.invalid_update_package, Toast.LENGTH_LONG).show();
                        return;
                    }
                    installApk(file);
                }));
    }

    private void showProgressDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_update_progress, null);
        progressBar = view.findViewById(R.id.progress_bar);
        progressText = view.findViewById(R.id.progress_text);
        lastPercent = Integer.MIN_VALUE;
        progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_now)
                .setView(view)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void updateProgress(int percent) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        if (percent < 0) {
            if (progressBar != null) progressBar.setIndeterminate(true);
            if (progressText != null) progressText.setText(R.string.downloading_update);
            return;
        }
        if (percent == lastPercent) return;
        lastPercent = percent;
        if (progressBar != null) {
            if (progressBar.isIndeterminate()) progressBar.setIndeterminate(false);
            progressBar.setProgressCompat(percent, true);
        }
        if (progressText != null) {
            progressText.setText(getString(R.string.update_progress_percent, percent));
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            if (progressDialog.isShowing()) progressDialog.dismiss();
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
    }

    private void installApk(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            Toast.makeText(this, R.string.installing_update, Toast.LENGTH_SHORT).show();
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.install_failed,
                    String.valueOf(t.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private void showDownloadFailure(String error) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_failed_title)
                .setMessage(getString(R.string.download_failed_reason,
                        error != null ? error : "unknown"))
                .setPositiveButton(R.string.update_browser,
                        (dialog, which) -> openBrowser(UpdateChecker.RELEASES_URL))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
    }

    private static int compareVersions(String a, String b) {
        String[] left = a.replaceFirst("^v", "").split("\\.");
        String[] right = b.replaceFirst("^v", "").split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int diff = versionPart(left, i) - versionPart(right, i);
            if (diff != 0) return diff;
        }
        return 0;
    }

    private static int versionPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
        } catch (Throwable t) {
            return 0;
        }
    }
}
