package fr.neamar.kiss;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.battery.BatteryWidgetProvider;
import fr.neamar.kiss.forwarder.InterfaceTweaks;

public final class BatteryWidgetStyleActivity extends AppCompatActivity {
    private static final String[] IDS = {
            "material_you", "google_pill", "squircle", "glass", "soft_card", "stadium", "pixel"
    };
    private static final String[] NAMES = {
            "Material You", "Google Pill", "Squircle", "Glass", "Soft Card", "Stadium", "Pixel"
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        InterfaceTweaks.applySettingsTheme(this, prefs);
        super.onCreate(savedInstanceState);
        setTitle("Battery widget style");

        ScrollView scroll = new ScrollView(this);
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        int p = dp(20);
        group.setPadding(p, p, p, p);

        TextView intro = new TextView(this);
        intro.setText("Choose the shape used by both Smart S Battery widgets. Changes apply immediately to widgets already on the launcher.");
        intro.setTextSize(16f);
        intro.setPadding(0, 0, 0, dp(16));
        group.addView(intro, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String current = prefs.getString(BatteryWidgetProvider.PREF_WIDGET_STYLE, "material_you");
        for (int i = 0; i < IDS.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(1000 + i);
            button.setText(NAMES[i]);
            button.setTextSize(17f);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setPadding(0, dp(10), 0, dp(10));
            button.setChecked(IDS[i].equals(current));
            final String value = IDS[i];
            button.setOnClickListener(v -> {
                prefs.edit().putString(BatteryWidgetProvider.PREF_WIDGET_STYLE, value).apply();
                BatteryWidgetProvider.updateAll(this);
            });
            group.addView(button, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        scroll.addView(group);
        setContentView(scroll);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
