package io.github.ooooooooohmygod.bilinoads;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        android.view.View content = findViewById(R.id.content);
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
                .setPositiveButton(R.string.update_go, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
