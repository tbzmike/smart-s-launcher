package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.result.ShortcutsResult;
import fr.neamar.kiss.utils.Log;

/**
 * Adds physical depth and live, app-provided information to history tiles.
 * The normal app/shortcut icon remains in the foreground. Artwork, sender/profile
 * pictures and notification information are presented as supporting card content.
 */
final class HistoryVisualEnhancer {
    private static final String TAG = HistoryVisualEnhancer.class.getSimpleName();
    private static final int TAG_LIVE_BACKGROUND = 0x534D4201;
    private static final int TAG_LIVE_TEXT = 0x534D4202;
    private static final int TAG_LIVE_PROGRESS = 0x534D4203;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final Set<String> liveLoadInFlight = new HashSet<>();

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
            if (!(child instanceof FrameLayout)) continue;

            FrameLayout tile = (FrameLayout) child;
            applyDepth(tile, square);
            clearLiveLayers(tile);

            Result<?> result = activity.adapter.getItem(position);
            if (result.getPojo() instanceof AppPojo) {
                AppPojo app = (AppPojo) result.getPojo();
                // Do not scan Android's entire active-notification array for every tile. Only
                // apps already known to have notification content can provide live card data.
                String latest = NotificationListener.getLatestMessage(activity, app.getPackageKey());
                if (!TextUtils.isEmpty(latest)) {
                    loadLiveAppData(tile, app.packageName, square);
                }
            } else if (result instanceof ShortcutsResult) {
                loadShortcutArtwork(tile, result);
            }
        }
    }

    private void applyDepth(FrameLayout tile, boolean square) {
        // HistoryDisplayForwarder already builds an app-icon-derived gradient for the card.
        // Preserve that identity instead of overwriting every tile with a generic grey surface.
        tile.setElevation(dp(square ? 10 : 7));
        tile.setTranslationZ(dp(square ? 2 : 1));
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
        layers.setLayerInset(1, 0, 0, dp(4), dp(5));
        layers.setLayerInset(2, dp(1), dp(1), dp(5), dp(7));
        return layers;
    }

    private void loadLiveAppData(FrameLayout tile, String packageName, boolean square) {
        synchronized (liveLoadInFlight) {
            if (!liveLoadInFlight.add(packageName)) return;
        }
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            LiveTileDataProvider.LiveTileData data =
                    LiveTileDataProvider.latestForPackage(activity, packageName);
            activity.runOnUiThread(() -> {
                synchronized (liveLoadInFlight) {
                    liveLoadInFlight.remove(packageName);
                }
                if (!tile.isAttachedToWindow() || data == null) return;
                if (data.artwork != null) addArtworkBackground(tile, data.artwork);
                addLiveText(tile, data, square);
                addProgress(tile, data);
                bringForegroundIconForward(tile);
            });
        });
    }

    private void loadShortcutArtwork(FrameLayout tile, Result<?> result) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Drawable artwork = result.getDrawable(activity);
            if (artwork == null) return;
            activity.runOnUiThread(() -> {
                if (!tile.isAttachedToWindow()) return;
                addArtworkBackground(tile, artwork);
                bringForegroundIconForward(tile);
            });
        });
    }

    private void addArtworkBackground(FrameLayout tile, Drawable artwork) {
        ImageView background = new ImageView(activity);
        background.setTag(TAG_LIVE_BACKGROUND);
        background.setImageDrawable(artwork);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setAlpha(0.38f);
        background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        frame.setCornerRadius(dp(16));
        background.setBackground(frame);
        background.setClipToOutline(true);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(dp(5), dp(5), dp(8), dp(10));
        tile.addView(background, 0, params);
    }

    private void addLiveText(FrameLayout tile, LiveTileDataProvider.LiveTileData data,
                             boolean square) {
        StringBuilder content = new StringBuilder();
        appendDistinct(content, data.title);
        appendDistinct(content, data.text);
        appendDistinct(content, data.subText);
        if (content.length() == 0) return;

        TextView information = new TextView(activity);
        information.setTag(TAG_LIVE_TEXT);
        information.setText(content.toString());
        information.setTextColor(Color.WHITE);
        information.setTextSize(square ? 10.5f : 11.5f);
        information.setMaxLines(square ? 3 : 2);
        information.setEllipsize(TextUtils.TruncateAt.END);
        information.setGravity(Gravity.START | Gravity.BOTTOM);
        information.setPadding(dp(7), dp(4), dp(7), dp(4));
        information.setShadowLayer(dp(2), 0f, dp(1), Color.BLACK);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(120, 0, 0, 0));
        background.setCornerRadius(dp(9));
        information.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        params.leftMargin = dp(7);
        params.rightMargin = dp(7);
        params.bottomMargin = dp(square ? 48 : 46);
        tile.addView(information, params);
    }

    private void addProgress(FrameLayout tile, LiveTileDataProvider.LiveTileData data) {
        if (data.progressMax <= 0 && !data.progressIndeterminate) return;
        ProgressBar progress = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setTag(TAG_LIVE_PROGRESS);
        progress.setIndeterminate(data.progressIndeterminate);
        if (!data.progressIndeterminate) {
            progress.setMax(Math.max(1, data.progressMax));
            progress.setProgress(Math.max(0, Math.min(data.progress, data.progressMax)));
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP);
        params.leftMargin = dp(8);
        params.rightMargin = dp(8);
        params.topMargin = dp(7);
        tile.addView(progress, params);
    }

    private void bringForegroundIconForward(FrameLayout tile) {
        ImageView icon = findForegroundImage(tile);
        if (icon != null) {
            icon.bringToFront();
            icon.setElevation(dp(8));
            icon.setTranslationZ(dp(2));
        }
        // Labels are deliberately kept above live artwork as well.
        for (int i = 0; i < tile.getChildCount(); i++) {
            View child = tile.getChildAt(i);
            if (child instanceof TextView && child.getTag() == null) child.bringToFront();
        }
    }

    private ImageView findForegroundImage(FrameLayout tile) {
        for (int i = tile.getChildCount() - 1; i >= 0; i--) {
            View child = tile.getChildAt(i);
            if (child instanceof ImageView && !Integer.valueOf(TAG_LIVE_BACKGROUND).equals(child.getTag())
                    && child.getVisibility() == View.VISIBLE) {
                return (ImageView) child;
            }
        }
        return null;
    }

    private void clearLiveLayers(FrameLayout tile) {
        for (int i = tile.getChildCount() - 1; i >= 0; i--) {
            Object tag = tile.getChildAt(i).getTag();
            if (Integer.valueOf(TAG_LIVE_BACKGROUND).equals(tag)
                    || Integer.valueOf(TAG_LIVE_TEXT).equals(tag)
                    || Integer.valueOf(TAG_LIVE_PROGRESS).equals(tag)) {
                tile.removeViewAt(i);
            }
        }
    }

    private void appendDistinct(StringBuilder builder, String value) {
        if (TextUtils.isEmpty(value)) return;
        String clean = value.trim();
        if (clean.isEmpty()) return;
        String existing = builder.toString();
        if (!existing.isEmpty() && (existing.equals(clean) || existing.contains(clean))) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(clean);
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
