package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import java.util.concurrent.ConcurrentHashMap;

import fr.neamar.kiss.R;
import fr.neamar.kiss.result.Result;

/**
 * Lightweight visual fallback for launcher records that do not expose rich card artwork.
 * The accent is sampled once from an already-available real icon and cached by stable result id.
 */
public final class TileVisualStyle {
    private static final ConcurrentHashMap<Long, Integer> ACCENT_CACHE = new ConcurrentHashMap<>();
    private static final int SAMPLE_SIZE = 10;
    private static final int NEUTRAL_ACCENT = Color.rgb(64, 84, 118);

    private TileVisualStyle() {}

    public static void apply(@NonNull View row, @NonNull Result<?> result, @NonNull Context context) {
        IconState iconState = ensureImmediateIcon(row, context);
        Drawable icon = iconState.drawable;
        if (icon == null) return;

        int accent;
        if (iconState.realIcon) {
            accent = ACCENT_CACHE.computeIfAbsent(result.getUniqueId(), ignored -> sampleAccent(icon));
        } else {
            // Do not synchronously load contact photos/app resources just to color the list row.
            // The normal async icon pipeline will replace the placeholder without blocking scroll.
            accent = NEUTRAL_ACCENT;
        }

        boolean hasRichNotification = false;
        View notification = row.findViewById(R.id.item_notification_row);
        if (notification != null && notification.getVisibility() == View.VISIBLE) {
            hasRichNotification = true;
        }

        if (!hasRichNotification) {
            GradientDrawable background = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{tone(accent, 1.16f, 205), tone(accent, 0.70f, 220)});
            background.setCornerRadius(dp(context, 15));
            background.setStroke(dp(context, 1), tone(accent, 1.34f, 175));
            row.setBackground(background);
            row.setElevation(dp(context, 2));
        }

        ImageView primary = findPrimaryIcon(row);
        if (primary != null) {
            GradientDrawable halo = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{tone(accent, 1.20f, 88), tone(accent, 0.72f, 52)});
            halo.setCornerRadius(dp(context, 14));
            primary.setBackground(halo);
            primary.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
        }
    }

    private static IconState ensureImmediateIcon(View row, Context context) {
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
            if (image.getDrawable() != null) {
                return new IconState(image.getDrawable(), true);
            }
        }

        Drawable fallback = context.getPackageManager().getDefaultActivityIcon();
        if (firstSlot != null && fallback != null) {
            firstSlot.setImageDrawable(fallback);
            firstSlot.setVisibility(View.VISIBLE);
        }
        return new IconState(fallback, false);
    }

    private static ImageView findPrimaryIcon(View row) {
        int[] ids = new int[]{
                R.id.item_app_icon,
                R.id.item_contact_icon,
                R.id.item_setting_icon,
                R.id.item_shortcut_icon,
                R.id.item_notification_icon
        };
        ImageView first = null;
        for (int id : ids) {
            View candidate = row.findViewById(id);
            if (!(candidate instanceof ImageView)) continue;
            ImageView image = (ImageView) candidate;
            if (first == null) first = image;
            if (image.getDrawable() != null) return image;
        }
        return first;
    }

    private static int sampleAccent(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int oldLeft = drawable.getBounds().left;
        int oldTop = drawable.getBounds().top;
        int oldRight = drawable.getBounds().right;
        int oldBottom = drawable.getBounds().bottom;
        drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
        drawable.draw(canvas);
        drawable.setBounds(oldLeft, oldTop, oldRight, oldBottom);

        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) < 48) continue;
                float[] hsv = new float[3];
                Color.colorToHSV(color, hsv);
                if (hsv[2] < 0.12f) continue;
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                count++;
            }
        }
        bitmap.recycle();
        if (count == 0) return NEUTRAL_ACCENT;

        int result = Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
        float[] hsv = new float[3];
        Color.colorToHSV(result, hsv);
        hsv[1] = Math.max(0.30f, Math.min(0.82f, hsv[1]));
        hsv[2] = Math.max(0.38f, Math.min(0.82f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private static int tone(int color, float valueMultiplier, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * valueMultiplier));
        return Color.HSVToColor(alpha, hsv);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class IconState {
        final Drawable drawable;
        final boolean realIcon;

        IconState(Drawable drawable, boolean realIcon) {
            this.drawable = drawable;
            this.realIcon = realIcon;
        }
    }
}
