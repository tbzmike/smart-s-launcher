package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import java.util.WeakHashMap;

import fr.neamar.kiss.R;
import fr.neamar.kiss.result.Result;

/**
 * Lightweight visual fallback for launcher records that do not expose rich card artwork.
 * The accent is sampled once from an already-available real icon and cached by stable result id.
 * Native list rows stay transparent; app-derived card surfaces are reserved for tile modes.
 */
public final class TileVisualStyle {
    private static final int MAX_ACCENT_ENTRIES = 512;
    private static final LruCache<Long, Integer> ACCENT_CACHE = new LruCache<>(MAX_ACCENT_ENTRIES);
    private static final WeakHashMap<ImageView, HaloState> HALO_CACHE = new WeakHashMap<>();
    private static final int SAMPLE_SIZE = 10;
    private static final int NEUTRAL_ACCENT = Color.rgb(64, 84, 118);

    private TileVisualStyle() {}

    public static void apply(@NonNull View row, @NonNull Result<?> result, @NonNull Context context) {
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setElevation(0f);
        row.setTranslationZ(0f);

        UniversalHistoryTimestamp.bind(row, result, context);

        IconState iconState = ensureImmediateIcon(row, context);
        Drawable icon = iconState.drawable;
        if (icon == null) return;

        ImageView primary = findPrimaryIcon(row);
        if (primary == null) return;

        if (primary.getId() == R.id.item_setting_icon) {
            primary.setBackground(null);
            primary.setPadding(0, 0, 0, 0);
            primary.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            return;
        }

        int accent;
        if (iconState.realIcon) {
            accent = cachedAccent(result.getUniqueId(), icon);
        } else {
            accent = NEUTRAL_ACCENT;
        }

        HaloState haloState = HALO_CACHE.get(primary);
        if (haloState == null || haloState.accent != accent) {
            GradientDrawable halo = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{tone(accent, 1.20f, 88), tone(accent, 0.72f, 52)});
            halo.setCornerRadius(dp(context, 14));
            haloState = new HaloState(accent, halo);
            HALO_CACHE.put(primary, haloState);
        }
        if (primary.getBackground() != haloState.drawable) primary.setBackground(haloState.drawable);
        int inset = dp(context, 4);
        if (primary.getPaddingLeft() != inset || primary.getPaddingTop() != inset
                || primary.getPaddingRight() != inset || primary.getPaddingBottom() != inset) {
            primary.setPadding(inset, inset, inset, inset);
        }
    }

    private static int cachedAccent(long resultId, Drawable icon) {
        synchronized (ACCENT_CACHE) {
            Integer cached = ACCENT_CACHE.get(resultId);
            if (cached != null) return cached;
        }
        int sampled = sampleAccent(icon);
        synchronized (ACCENT_CACHE) {
            ACCENT_CACHE.put(resultId, sampled);
        }
        return sampled;
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
        float[] hsv = new float[3];
        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) < 48) continue;
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

    private static final class HaloState {
        final int accent;
        final GradientDrawable drawable;

        HaloState(int accent, GradientDrawable drawable) {
            this.accent = accent;
            this.drawable = drawable;
        }
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
