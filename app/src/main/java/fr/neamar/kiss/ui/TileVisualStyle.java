package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import fr.neamar.kiss.R;
import fr.neamar.kiss.result.Result;

/** Applies allocation-free native-list styling during adapter row binding. */
public final class TileVisualStyle {
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
