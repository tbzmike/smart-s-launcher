package fr.neamar.kiss.forwarder;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.utils.Log;

/**
 * App-owned wallpaper target chooser.
 *
 * Android's ACTION_SET_WALLPAPER chooser is a system surface and can become hard to read over a
 * bright or detailed live wallpaper. Keeping target discovery identical while owning the small
 * selection dialog lets Smart S guarantee an opaque, high-contrast surface without changing any
 * global dialog/theme styling.
 */
final class WallpaperChooser {
    private static final String TAG = WallpaperChooser.class.getSimpleName();
    private static final int SURFACE_COLOR = Color.rgb(32, 33, 36);
    private static final int TEXT_COLOR = Color.WHITE;

    private WallpaperChooser() {}

    static void show(MainActivity activity) {
        Intent baseIntent = new Intent(Intent.ACTION_SET_WALLPAPER);
        PackageManager packageManager = activity.getPackageManager();
        List<ResolveInfo> targets = packageManager.queryIntentActivities(
                baseIntent, PackageManager.MATCH_DEFAULT_ONLY);

        if (targets == null || targets.isEmpty()) {
            launchSystemFallback(activity, baseIntent);
            return;
        }

        CharSequence[] labels = new CharSequence[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            CharSequence label = targets.get(i).loadLabel(packageManager);
            labels[i] = label == null ? activity.getString(R.string.menu_wallpaper) : label;
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.menu_wallpaper)
                .setItems(labels, (ignored, which) -> launchTarget(activity, baseIntent, targets, which))
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(ignored -> style(dialog, activity));
        dialog.show();
    }

    private static void launchTarget(MainActivity activity, Intent baseIntent,
                                     List<ResolveInfo> targets, int which) {
        if (which < 0 || which >= targets.size()) return;
        ResolveInfo target = targets.get(which);
        if (target.activityInfo == null) {
            launchSystemFallback(activity, baseIntent);
            return;
        }

        Intent explicitIntent = new Intent(baseIntent);
        explicitIntent.setComponent(new ComponentName(
                target.activityInfo.packageName, target.activityInfo.name));
        try {
            activity.startActivity(explicitIntent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Wallpaper target could not be opened; falling back to system chooser", e);
            launchSystemFallback(activity, baseIntent);
        }
    }

    private static void launchSystemFallback(MainActivity activity, Intent baseIntent) {
        try {
            activity.startActivity(Intent.createChooser(
                    baseIntent, activity.getString(R.string.menu_wallpaper)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "No wallpaper picker is available", e);
        }
    }

    private static void style(AlertDialog dialog, MainActivity activity) {
        Window window = dialog.getWindow();
        if (window == null) return;

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(SURFACE_COLOR);
        background.setCornerRadius(dp(activity, 22));
        window.setBackgroundDrawable(background);

        View decor = window.getDecorView();
        decor.setElevation(dp(activity, 12));
        tintText(decor);
    }

    private static void tintText(View view) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(TEXT_COLOR);
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            tintText(group.getChildAt(i));
        }
    }

    private static float dp(MainActivity activity, int value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }
}
