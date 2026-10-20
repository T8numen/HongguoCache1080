package com.t8numen.hongguocache1080;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;

public final class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SharedPreferences preferences = getSharedPreferences(
                SettingsContract.PREFERENCES_NAME,
                MODE_PRIVATE);
        LinearLayout startupPrimaryRow = findViewById(R.id.startup_primary_row);
        LinearLayout startupSecondaryRow = findViewById(R.id.startup_secondary_row);
        LinearLayout blockRow = findViewById(R.id.block_home_video_row);
        LinearLayout localFavoritesRow = findViewById(R.id.local_favorites_row);
        LinearLayout homeIdRotationRow = findViewById(R.id.home_id_rotation_row);
        LinearLayout onlineHighestRow = findViewById(R.id.online_highest_row);
        LinearLayout downloadHighestRow = findViewById(R.id.download_highest_row);
        LinearLayout vipExclusiveBadgeRow = findViewById(R.id.vip_exclusive_badge_row);
        LinearLayout hideTheaterTabRow = findViewById(R.id.hide_theater_tab_row);
        LinearLayout hideMallTabRow = findViewById(R.id.hide_mall_tab_row);
        LinearLayout hideEarnTabRow = findViewById(R.id.hide_earn_tab_row);
        Spinner startupPrimary = findViewById(R.id.startup_primary_spinner);
        Spinner startupSecondary = findViewById(R.id.startup_secondary_spinner);
        Switch blockSwitch = findViewById(R.id.block_home_video_switch);
        Switch localFavoritesSwitch = findViewById(R.id.local_favorites_switch);
        Switch homeIdRotationSwitch = findViewById(R.id.home_id_rotation_switch);
        Switch onlineHighestSwitch = findViewById(R.id.online_highest_switch);
        Switch downloadHighestSwitch = findViewById(R.id.download_highest_switch);
        Switch vipExclusiveBadgeSwitch = findViewById(R.id.vip_exclusive_badge_switch);
        Switch hideTheaterTabSwitch = findViewById(R.id.hide_theater_tab_switch);
        Switch hideMallTabSwitch = findViewById(R.id.hide_mall_tab_switch);
        Switch hideEarnTabSwitch = findViewById(R.id.hide_earn_tab_switch);

        ArrayAdapter<CharSequence> primaryOptions = ArrayAdapter.createFromResource(
                this, R.array.startup_primary_options, android.R.layout.simple_spinner_item);
        primaryOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        startupPrimary.setAdapter(primaryOptions);
        startupPrimary.setSelection(SettingsContract.startupPrimary(preferences));
        ArrayAdapter<CharSequence> secondaryOptions = ArrayAdapter.createFromResource(
                this, R.array.startup_secondary_options, android.R.layout.simple_spinner_item);
        secondaryOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        startupSecondary.setAdapter(secondaryOptions);
        startupSecondary.setSelection(SettingsContract.startupSecondary(preferences));
        blockSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_BLOCK_HOME_VIDEO,
                SettingsContract.DEFAULT_BLOCK_HOME_VIDEO));
        localFavoritesSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_LOCAL_FAVORITES,
                SettingsContract.DEFAULT_LOCAL_FAVORITES));
        homeIdRotationSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_HOME_ID_ROTATION,
                SettingsContract.DEFAULT_HOME_ID_ROTATION));
        onlineHighestSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_ONLINE_HIGHEST,
                SettingsContract.DEFAULT_ONLINE_HIGHEST));
        downloadHighestSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_DOWNLOAD_HIGHEST,
                SettingsContract.DEFAULT_DOWNLOAD_HIGHEST));
        vipExclusiveBadgeSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_VIP_EXCLUSIVE_BADGE,
                SettingsContract.DEFAULT_VIP_EXCLUSIVE_BADGE));
        hideTheaterTabSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_HIDE_THEATER_TAB,
                SettingsContract.DEFAULT_HIDE_THEATER_TAB));
        hideMallTabSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_HIDE_MALL_TAB,
                SettingsContract.DEFAULT_HIDE_MALL_TAB));
        hideEarnTabSwitch.setChecked(preferences.getBoolean(
                SettingsContract.KEY_HIDE_EARN_TAB,
                SettingsContract.DEFAULT_HIDE_EARN_TAB));
        Runnable updateStartupState = () -> {
            boolean my = startupPrimary.getSelectedItemPosition() == SettingsContract.STARTUP_MY;
            startupSecondary.setEnabled(my);
            startupSecondaryRow.setEnabled(my);
            startupSecondaryRow.setAlpha(my ? 1.0f : 0.55f);
            blockSwitch.setEnabled(my);
            blockRow.setEnabled(my);
            blockRow.setAlpha(my ? 1.0f : 0.55f);
            boolean theater = startupPrimary.getSelectedItemPosition()
                    == SettingsContract.STARTUP_THEATER;
            if (theater && hideTheaterTabSwitch.isChecked()) {
                hideTheaterTabSwitch.setChecked(false);
                preferences.edit().putBoolean(SettingsContract.KEY_HIDE_THEATER_TAB, false).apply();
            }
            hideTheaterTabSwitch.setEnabled(!theater);
            hideTheaterTabRow.setEnabled(!theater);
            hideTheaterTabRow.setAlpha(theater ? 0.55f : 1.0f);
        };
        updateStartupState.run();
        startupPrimaryRow.setOnClickListener(view -> startupPrimary.performClick());
        startupSecondaryRow.setOnClickListener(view -> {
            if (startupSecondary.isEnabled()) {
                startupSecondary.performClick();
            }
        });
        blockRow.setOnClickListener(view -> {
            if (blockSwitch.isEnabled()) {
                blockSwitch.toggle();
            }
        });
        localFavoritesRow.setOnClickListener(view -> localFavoritesSwitch.toggle());
        homeIdRotationRow.setOnClickListener(view -> homeIdRotationSwitch.toggle());
        onlineHighestRow.setOnClickListener(view -> onlineHighestSwitch.toggle());
        downloadHighestRow.setOnClickListener(view -> downloadHighestSwitch.toggle());
        vipExclusiveBadgeRow.setOnClickListener(view -> vipExclusiveBadgeSwitch.toggle());
        hideTheaterTabRow.setOnClickListener(view -> hideTheaterTabSwitch.toggle());
        hideMallTabRow.setOnClickListener(view -> hideMallTabSwitch.toggle());
        hideEarnTabRow.setOnClickListener(view -> hideEarnTabSwitch.toggle());
        startupPrimary.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view,
                    int position, long id) {
                preferences.edit().putInt(SettingsContract.KEY_STARTUP_PRIMARY, position).apply();
                updateStartupState.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        startupSecondary.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view,
                    int position, long id) {
                preferences.edit().putInt(SettingsContract.KEY_STARTUP_SECONDARY, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        blockSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_BLOCK_HOME_VIDEO, isChecked)
                        .apply());
        localFavoritesSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_LOCAL_FAVORITES, isChecked)
                        .apply());
        homeIdRotationSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_HOME_ID_ROTATION, isChecked)
                        .apply());
        onlineHighestSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_ONLINE_HIGHEST, isChecked)
                        .apply());
        downloadHighestSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_DOWNLOAD_HIGHEST, isChecked)
                        .apply());
        vipExclusiveBadgeSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_VIP_EXCLUSIVE_BADGE, isChecked)
                        .apply());
        hideTheaterTabSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_HIDE_THEATER_TAB, isChecked)
                        .apply());
        hideMallTabSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_HIDE_MALL_TAB, isChecked)
                        .apply());
        hideEarnTabSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit()
                        .putBoolean(SettingsContract.KEY_HIDE_EARN_TAB, isChecked)
                        .apply());
    }
}
