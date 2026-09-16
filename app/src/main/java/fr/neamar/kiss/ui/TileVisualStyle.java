package fr.neamar.kiss.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.WeakHashMap;

import fr.neamar.kiss.R;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/** Applies allocation-free native-list styling during adapter row binding. */
public final class TileVisualStyle {
    private static final int[] EXTRA_RESIZABLE_ICON_IDS = new int[]{
            R.id.item_notification_icon,
            R.id.item_shortcut_icon,
            R.id.item_setting_icon,
            R.id.item_contact_icon
    };

    private static final WeakHashMap<View, int[]> EXTRA_ICON_BASE_BOUNDS = new WeakHashMap<>();

    private TileVisualStyle() {}

    public static void apply(@NonNull View row, @NonNull Result<?> result,
                             @NonNull Context context) {
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setElevation(0f);
        row.setTranslationZ(0f);

        UniversalHistoryTimestamp.bind(row, result, context);

        ImageView primary = ensureImmediateIcon(row, context);
        if (primary == null) return;
        // Native list rows do not need card halos. Clearing the recycled slot is allocation-free
        // and avoids Bitmap/Canvas accent sampling and GradientDrawable creation inside getView().
        primary.setBackground(null);
        primary.setPadding(0, 0, 0, 0);
        primary.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // RecordAdapter applies the existing app-icon size preference to its primary icon when
        // possible. Some history renderers use fixed/wrapped containers for notification,
        // shortcut, settings and contact images, so those targets can remain at their native size.
        // Capture their native bounds before RecordAdapter runs, then apply the same percentage
        // after binding. Each target has its own switch so users can opt out independently.
        captureExtraIconBaseBounds(row);
        row.post(() -> applyExtraIconSizes(row, context));
    }

    private static void captureExtraIconBaseBounds(View row) {
        for (int id : EXTRA_RESIZABLE_ICON_IDS) {
            View candidate = row.findViewById(id);
            if (!(candidate instanceof ImageView) || candidate.getVisibility() == View.GONE) continue;
            View target = findIconResizeTarget((ImageView) candidate);
            ViewGroup.LayoutParams lp = target.getLayoutParams();
            int width = lp == null ? 0 : lp.width;
            int height = lp == null ? 0 : lp.height;
            if (width <= 0) width = target.getWidth();
            if (height <= 0) height = target.getHeight();
            if (width > 0 && height > 0 && !EXTRA_ICON_BASE_BOUNDS.containsKey(target)) {
                EXTRA_ICON_BASE_BOUNDS.put(target, new int[]{width, height});
            }
        }
    }

    private static void applyExtraIconSizes(@NonNull View row, @NonNull Context context) {
        if (SearchHandler.getInstance().getLastSearchType() != Searcher.Type.HISTORY) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // Each history icon type has an independent size slider. If the new preference has
        // never been saved, fall back to the existing App icon size so upgrading preserves the
        // 3.30.106 behaviour until the user chooses a custom value.
        int appIconPercent = safePercent(prefs, "smart-list-icon-size-percent", 110, 50, 240);
        int notificationPercent = safePercent(prefs, "smart-list-notification-icon-size-percent", appIconPercent, 50, 240);
        int shortcutPercent = safePercent(prefs, "smart-list-shortcut-icon-size-percent", appIconPercent, 50, 240);
        int featurePercent = safePercent(prefs, "smart-list-feature-icon-size-percent", appIconPercent, 50, 240);
        int contactPercent = safePercent(prefs, "smart-list-contact-icon-size-percent", appIconPercent, 50, 240);

        applyConfiguredIconSize(row, prefs, R.id.item_notification_icon,
                "smart-list-resize-notification-icons", notificationPercent);
        applyConfiguredIconSize(row, prefs, R.id.item_shortcut_icon,
                "smart-list-resize-shortcut-icons", shortcutPercent);
        applyConfiguredIconSize(row, prefs, R.id.item_setting_icon,
                "smart-list-resize-feature-icons", featurePercent);
        applyConfiguredIconSize(row, prefs, R.id.item_contact_icon,
                "smart-list-resize-contact-icons", contactPercent);
    }

    private static void applyConfiguredIconSize(@NonNull View row, @NonNull SharedPreferences prefs,
                                                int iconId, String preferenceKey, int percent) {
        View candidate = row.findViewById(iconId);
        if (!(candidate instanceof ImageView) || candidate.getVisibility() == View.GONE) return;

        ImageView icon = (ImageView) candidate;
        View target = findIconResizeTarget(icon);
        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (lp == null) return;

        int[] base = EXTRA_ICON_BASE_BOUNDS.get(target);
        if (base == null || base[0] <= 0 || base[1] <= 0) {
            int width = lp.width > 0 ? lp.width : target.getWidth();
            int height = lp.height > 0 ? lp.height : target.getHeight();
            if (width <= 0 || height <= 0) return;
            base = new int[]{width, height};
            EXTRA_ICON_BASE_BOUNDS.put(target, base);
        }

        boolean enabled = prefs.getBoolean(preferenceKey, true);
        int targetPercent = enabled ? percent : 100;
        lp.width = Math.max(1, Math.round(base[0] * targetPercent / 100f));
        lp.height = Math.max(1, Math.round(base[1] * targetPercent / 100f));
        target.setLayoutParams(lp);
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.setScaleType(iconId == R.id.item_setting_icon
                ? ImageView.ScaleType.CENTER_INSIDE
                : ImageView.ScaleType.FIT_CENTER);
    }

    private static View findIconResizeTarget(ImageView icon) {
        if (icon.getParent() instanceof View) {
            View parent = (View) icon.getParent();
            ViewGroup.LayoutParams lp = parent.getLayoutParams();
            if (lp != null && lp.width > 0 && lp.height > 0) return parent;
        }
        return icon;
    }

    private static int safePercent(SharedPreferences prefs, String key, int fallback, int min, int max) {
        Object raw = null;
        if (prefs.contains(key)) {
            try {
                raw = prefs.getString(key, null);
            } catch (ClassCastException ignored) {
                try {
                    raw = prefs.getInt(key, fallback);
                } catch (ClassCastException ignoredAgain) {
                    raw = null;
                }
            }
        }
        int value = fallback;
        if (raw instanceof Number) {
            value = Math.round(((Number) raw).floatValue());
        } else if (raw instanceof String) {
            try {
                value = Math.round(Float.parseFloat((String) raw));
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static ImageView ensureImmediateIcon(View row, Context context) {
        ImageView firstSlot = null;
        int[] ids = new int[]{
                R.id.item_app_icon,
                R.id.item_contact_icon,
                R.id.item_setting_icon,
                R.id.item_shortcut_icon,
                R.id.item_notification_icon
        };
        for (int id : ids) {
            View candidate = row.findViewById(id);
            if (!(candidate instanceof ImageView)) continue;
            ImageView image = (ImageView) candidate;
            if (firstSlot == null) firstSlot = image;
            if (image.getDrawable() != null) return image;
        }

        Drawable fallback = context.getPackageManager().getDefaultActivityIcon();
        if (firstSlot != null && fallback != null) {
            firstSlot.setImageDrawable(fallback);
            firstSlot.setVisibility(View.VISIBLE);
        }
        return firstSlot;
    }
}
