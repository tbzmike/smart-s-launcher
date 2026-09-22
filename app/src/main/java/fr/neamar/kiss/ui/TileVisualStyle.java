package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import java.util.WeakHashMap;

import fr.neamar.kiss.R;
import fr.neamar.kiss.result.Result;

/**
 * Applies native-list styling without scheduling per-row work during scrolling.
 *
 * Dynamic icon sizing is intentionally owned by RecordAdapter's cached render configuration.
 * This class only performs the minimal visual reset and timestamp bind required for recycled
 * ListView rows.
 */
public final class TileVisualStyle {
    private static final WeakHashMap<View, Boolean> BASE_ROW_STYLED = new WeakHashMap<>();
    private static final WeakHashMap<View, ImageView> PRIMARY_ICON_CACHE = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, Boolean> BASE_ICON_STYLED = new WeakHashMap<>();

    private TileVisualStyle() {}

    public static void apply(@NonNull View row, @NonNull Result<?> result,
                             @NonNull Context context) {
        if (!BASE_ROW_STYLED.containsKey(row)) {
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setElevation(0f);
            row.setTranslationZ(0f);
            BASE_ROW_STYLED.put(row, Boolean.TRUE);
        }

        // Content is result-specific and therefore still refreshed on every recycled-row bind.
        UniversalHistoryTimestamp.bind(row, result, context);

        ImageView primary = ensureImmediateIcon(row, context);
        if (primary == null || BASE_ICON_STYLED.containsKey(primary)) return;

        primary.setBackground(null);
        primary.setPadding(0, 0, 0, 0);
        primary.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        BASE_ICON_STYLED.put(primary, Boolean.TRUE);
    }

    private static ImageView ensureImmediateIcon(View row, Context context) {
        ImageView cached = PRIMARY_ICON_CACHE.get(row);
        if (cached != null) {
            if (cached.getDrawable() == null) {
                Drawable fallback = context.getPackageManager().getDefaultActivityIcon();
                if (fallback != null) cached.setImageDrawable(fallback);
            }
            return cached;
        }

        ImageView firstSlot = null;
        int[] ids = new int[]{
                R.id.item_app_icon,
                R.id.item_contact_icon,
                R.id.item_setting_icon,
                R.id.item_shortcut_icon,
                R.id.item_notification_icon,
                R.id.item_search_icon,
                R.id.item_phone_icon
        };
        for (int id : ids) {
            View candidate = row.findViewById(id);
            if (!(candidate instanceof ImageView)) continue;
            ImageView image = (ImageView) candidate;
            if (firstSlot == null) firstSlot = image;
            if (image.getDrawable() != null) {
                PRIMARY_ICON_CACHE.put(row, image);
                return image;
            }
        }

        if (firstSlot != null) {
            PRIMARY_ICON_CACHE.put(row, firstSlot);
            Drawable fallback = context.getPackageManager().getDefaultActivityIcon();
            if (fallback != null) {
                firstSlot.setImageDrawable(fallback);
                firstSlot.setVisibility(View.VISIBLE);
            }
        }
        return firstSlot;
    }
}
