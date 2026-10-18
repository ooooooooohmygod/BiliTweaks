package io.github.ooooooooohmygod.bilinoads;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Switch;

public final class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch toggle = findViewById(R.id.switch_block_ai);
        toggle.setChecked(getSharedPreferences(AiBlock.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(AiBlock.KEY_BLOCK_AI, false));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(AiBlock.PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(AiBlock.KEY_BLOCK_AI, isChecked)
                        .apply());
    }
}
