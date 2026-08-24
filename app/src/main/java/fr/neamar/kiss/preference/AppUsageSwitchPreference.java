package fr.neamar.kiss.preference;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.SwitchPreference;

import fr.neamar.kiss.appusage.AppUsageTracker;

/** Settings switch that immediately starts/stops the periodic local usage importer. */
public final class AppUsageSwitchPreference extends SwitchPreference {
    public AppUsageSwitchPreference(Context context) {
        super(context);
    }

    public AppUsageSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AppUsageSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AppUsageSwitchPreference(Context context, AttributeSet attrs,
                                    int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        boolean persisted = super.persistBoolean(value);
        AppUsageTracker.setEnabled(getContext().getApplicationContext(), value);
        return persisted;
    }
}
