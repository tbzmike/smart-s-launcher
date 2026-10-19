package fr.neamar.kiss.ui;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

/**
 * Opens the public Google application launch entry point. Google's integrated
 * Pixel Discover overlay has no stable public third-party launcher API.
 */
public final class GoogleDiscoverLauncher {
    static final String GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox";

    private GoogleDiscoverLauncher() { }

    public static boolean open(Activity activity) {
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(GOOGLE_APP_PACKAGE);
        if (intent == null) {
            Toast.makeText(activity,
                    "Google app isn't installed or enabled. Enable it to use Discover.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            Toast.makeText(activity, "Unable to open Google app. Check that it is enabled.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
