package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.AsyncTask;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.result.ShortcutsResult;
import fr.neamar.kiss.utils.Log;

/**
 * Applies visual depth to Smart S history tiles and makes shortcut-driven social
 * tiles prefer the shortcut's own artwork (often a conversation/profile picture)
 * instead of the parent application's generic icon.
 */
final class HistoryVisualEnhancer {
    private static final String TAG = HistoryVisualEnhancer.class.getSimpleName();

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;

    HistoryVisualEnhancer(MainActivity activity,
                          HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
    }

    void onCreate() {
        refreshSoon();
    }

    void onResume() {
        refreshSoon();
    }

    void onDataSetChanged() {
        refreshSoon();
    }

    private void refreshSoon() {
        View anchor = activity.listContainer;
        if (anchor != null) anchor.post(this::refreshNow);
    }

    private void refreshNow() {
        ViewGroup squareTrack = readField("squareTrack", ViewGroup.class);
        LinearLayout horizontalRow = readField("row", LinearLayout.class);
        ScrollView notificationScroller = readField("notificationScroller", ScrollView.class);

        enhanceHistoryGroup(squareTrack, true);
        enhanceHistoryGroup(horizontalRow, false);

        if (notificationScroller != null) {
            notificationScroller.setBackground(buildDepthBackground(dp(20), true));
            notificationScroller.setElevation(dp(12));
            notificationScroller.setTranslationZ(dp(2));
        }
    }

    private void enhanceHistoryGroup(ViewGroup group, boolean square) {
        if (group == null || activity.adapter == null) return;
        int count = Math.min(group.getChildCount(), activity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View child = group.getChildAt(position);
            if (child instanceof FrameLayout) {
                applyDepth((FrameLayout) child, square);
            }
            Result<?> result = activity.adapter.getItem(position);
            if (result instanceof ShortcutsResult) {
                applyShortcutArtwork(child, result);
            }
        }
    }

    private void applyDepth(FrameLayout tile, boolean square) {
        tile.setBackground(buildDepthBackground(dp(square ? 18 : 16), square));
        tile.setElevation(dp(square ? 10 : 7));
        tile.setClipToOutline(true);
    }

    private Drawable buildDepthBackground(int radius, boolean square) {
        int top = square ? Color.rgb(48, 53, 65) : Color.rgb(45, 48, 57);
        int middle = square ? Color.rgb(27, 30, 38) : Color.rgb(28, 30, 36);
        int bottom = square ? Color.rgb(13, 15, 20) : Color.rgb(16, 17, 21);

        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(Color.argb(235, 4, 5, 8));
        shadow.setCornerRadius(radius);

        GradientDrawable face = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{top, middle, bottom});
        face.setCornerRadius(radius);
        face.setStroke(dp(1), Color.argb(210, 205, 217, 238));

        GradientDrawable gloss = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(58, 255, 255, 255), Color.TRANSPARENT});
        gloss.setCornerRadius(radius);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{shadow, face, gloss});
        // Leave a visible dark edge on the right/bottom so the tile has physical thickness.
        layers.setLayerInset(1, 0, 0, dp(4), dp(5));
        layers.setLayerInset(2, dp(1), dp(1), dp(5), dp(7));
        return layers;
    }

    private void applyShortcutArtwork(View tile, Result<?> result) {
        ImageView target = findDirectImage(tile);
        if (target == null) return;

        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Drawable artwork = result.getDrawable(activity);
            if (artwork == null) return;
            activity.runOnUiThread(() -> {
                if (target.isAttachedToWindow()) {
                    target.setImageDrawable(artwork);
                    target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                }
            });
        });
    }

    private ImageView findDirectImage(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView && child.getVisibility() == View.VISIBLE) {
                return (ImageView) child;
            }
        }
        return null;
    }

    private <T> T readField(String name, Class<T> expectedType) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return expectedType.isInstance(value) ? expectedType.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve history field: " + name, e);
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
