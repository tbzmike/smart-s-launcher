package fr.neamar.kiss.preference;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.preference.Preference;

import fr.neamar.kiss.AppUsageActivity;

/** Opens the local App usage tree timeline from Settings without implicit-intent routing. */
public final class AppUsageOpenPreference extends Preference {
    public AppUsageOpenPreference(Context context) {
        super(context);
        init();
    }

    public AppUsageOpenPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AppUsageOpenPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public AppUsageOpenPreference(Context context, AttributeSet attrs,
                                  int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        setOnPreferenceClickListener(preference -> {
            Context context = getContext();
            context.startActivity(new Intent(context, AppUsageActivity.class));
            return true;
        });
    }
}
