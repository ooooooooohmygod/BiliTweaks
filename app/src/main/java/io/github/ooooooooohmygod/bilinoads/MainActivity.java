package io.github.ooooooooohmygod.bilinoads;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.MaterialToolbar;
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

        boolean lightBars = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);
    }

    private void bindSwitch(int viewId, String key) {
        MaterialSwitch toggle = findViewById(viewId);
        toggle.setChecked(getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getBoolean(key, true));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
                        .putBoolean(key, isChecked)
                        .apply());
    }
}
