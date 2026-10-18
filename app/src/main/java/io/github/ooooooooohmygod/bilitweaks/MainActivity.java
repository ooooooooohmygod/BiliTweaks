package io.github.ooooooooohmygod.bilitweaks;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

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

        bindSwitch(R.id.switch_splash, Prefs.KEY_SPLASH_AD);
        bindSwitch(R.id.switch_pause, Prefs.KEY_PAUSE_AD);
        bindSwitch(R.id.switch_end, Prefs.KEY_END_AD);
        bindSwitch(R.id.switch_update, Prefs.KEY_UPDATE);

        findViewById(R.id.button_check_update).setOnClickListener(v -> checkForUpdates(true));

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

    private void bindSwitch(int viewId, String key) {
        MaterialSwitch toggle = findViewById(viewId);
        toggle.setChecked(getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getBoolean(key, true));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
                        .putBoolean(key, isChecked)
                        .apply());
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
