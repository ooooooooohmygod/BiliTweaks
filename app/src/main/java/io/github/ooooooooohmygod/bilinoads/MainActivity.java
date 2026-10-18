package io.github.ooooooooohmygod.bilinoads;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public final class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        TextView placeholder = findViewById(R.id.content_placeholder);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(0, bars.top, 0, 0);
            bottomNav.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.tab_features) {
                toolbar.setTitle(R.string.app_name);
                placeholder.setText(R.string.ui_features_placeholder);
            } else {
                toolbar.setTitle(R.string.tab_about);
                placeholder.setText(R.string.ui_about_placeholder);
            }
            return true;
        });

        boolean lightBars = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);
    }
}
