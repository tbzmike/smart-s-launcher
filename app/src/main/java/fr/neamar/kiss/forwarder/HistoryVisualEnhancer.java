package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.text.format.DateFormat;
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
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.result.ShortcutsResult;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.SmartTextAppearance;
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
    private boolean statsLoadInFlight;

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
        refreshListPresentation();

        if (notificationScroller != null) {
            notificationScroller.setBackground(buildDepthBackground(dp(20), true));
            notificationScroller.setElevation(dp(12));
            notificationScroller.setTranslationZ(dp(2));
        }
    }

    /**
     * Native list mode deliberately stays transparent. History statistics and the cached UsageStats
     * snapshot are loaded together off the UI thread, then applied only to currently visible rows.
     */
    private void refreshListPresentation() {
        if (activity.list == null || activity.adapter == null) return;

        for (int i = 0; i < activity.list.getChildCount(); i++) {
            View row = activity.list.getChildAt(i);
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setElevation(0f);
            row.setTranslationZ(0f);
            TextView metadata = findMetadataView(row);
            if (metadata != null) {
                metadata.setText("");
                metadata.setVisibility(View.GONE);
            }
        }

        synchronized (this) {
            if (statsLoadInFlight) return;
            statsLoadInFlight = true;
        }
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Map<String, LaunchStatsProvider.LaunchStats> stats =
                    LaunchStatsProvider.loadAll(activity.getApplicationContext());
            AppUsageTodayStore.Snapshot usage =
                    AppUsageTodayStore.getToday(activity.getApplicationContext());
            activity.runOnUiThread(() -> {
                synchronized (HistoryVisualEnhancer.this) {
                    statsLoadInFlight = false;
                }
                applyStatsToVisibleRows(stats, usage);
            });
        });
    }

    private void applyStatsToVisibleRows(Map<String, LaunchStatsProvider.LaunchStats> stats,
                                         AppUsageTodayStore.Snapshot usage) {
        if (activity.list == null || activity.adapter == null) return;
        int first = activity.list.getFirstVisiblePosition();
        java.text.DateFormat timeFormat = DateFormat.getTimeFormat(activity);

        for (int childIndex = 0; childIndex < activity.list.getChildCount(); childIndex++) {
            int position = first + childIndex;
            if (position < 0 || position >= activity.adapter.getCount()) continue;

            View row = activity.list.getChildAt(childIndex);
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setElevation(0f);
            row.setTranslationZ(0f);

            Result<?> result = activity.adapter.getItem(position);
            if (result == null || result.getPojo() == null) continue;

            TextView metadataView = findMetadataView(row);
            if (metadataView == null) continue;

            LaunchStatsProvider.LaunchStats launchStats = stats.get(result.getPojo().getHistoryId());
            StringBuilder metadata = new StringBuilder();
            if (result.getPojo() instanceof NotificationPojo) {
                long postTime = ((NotificationPojo) result.getPojo()).postTime;
                if (postTime > 0) {
                    appendMetadata(metadata, "Posted "
                            + timeFormat.format(new java.util.Date(postTime)));
                }
            } else if (result.getPojo() instanceof CommunicationPojo) {
                long eventTime = ((CommunicationPojo) result.getPojo()).timestamp;
                if (eventTime > 0) {
                    appendMetadata(metadata, "Item time "
                            + timeFormat.format(new java.util.Date(eventTime)));
                }
            }

            if (launchStats != null && launchStats.lastLaunchTime > 0) {
                appendMetadata(metadata, "Last opened "
                        + timeFormat.format(new java.util.Date(launchStats.lastLaunchTime)));
            }

            if (result.getPojo() instanceof AppPojo) {
                AppPojo app = (AppPojo) result.getPojo();
                if (usage != null && usage.available) {
                    Long foregroundMs = usage.foregroundMsByPackage.get(app.packageName);
                    appendMetadata(metadata, "Used today "
                            + formatDuration(foregroundMs == null ? 0L : foregroundMs));
                }
            }

            int opensToday = launchStats == null ? 0 : launchStats.launchesToday;
            appendMetadata(metadata, opensToday + (opensToday == 1 ? " open today" : " opens today"));

            if (metadata.length() == 0) {
                metadataView.setText("");
                metadataView.setVisibility(View.GONE);
                continue;
            }

            metadataView.setText(metadata);
            metadataView.setVisibility(View.VISIBLE);
            SmartTextAppearance.applyHistoryMetadata(metadataView);
            configureLaunchInfoMarquee(metadataView);
        }
    }

    private void appendMetadata(StringBuilder builder, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (builder.length() > 0) builder.append(" • ");
        builder.append(value);
    }

    private TextView findMetadataView(View row) {
        View candidate = row.findViewById(R.id.item_history_meta);
        return candidate instanceof TextView ? (TextView) candidate : null;
    }

    private String formatDuration(long durationMs) {
        long totalMinutes = Math.max(0L, durationMs) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private void configureLaunchInfoMarquee(TextView subtitle) {
        subtitle.setSingleLine(true);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        subtitle.setMarqueeRepeatLimit(-1);
        subtitle.setHorizontallyScrolling(true);
        subtitle.setHorizontalFadingEdgeEnabled(true);
        subtitle.setSelected(true);
        subtitle.setFocusable(false);
        subtitle.setFocusableInTouchMode(false);
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
                if (MapLiveTileProvider.MAPS_PACKAGE.equals(app.packageName)) {
                    MapLiveTileProvider.requestFreshLocation(activity, this::refreshSoon);
                    loadLiveAppData(tile, app.packageName, square);
                } else {
                    String latest = NotificationListener.getLatestMessage(activity, app.getPackageKey());
                    if (!TextUtils.isEmpty(latest)) {
                        loadLiveAppData(tile, app.packageName, square);
                    }
                }
            } else if (result instanceof ShortcutsResult) {
                loadShortcutArtwork(tile, result);
            }
        }
    }

    private void applyDepth(FrameLayout tile, boolean square) {
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
            LiveTileDataProvider.LiveTileData data = MapLiveTileProvider.MAPS_PACKAGE.equals(packageName)
                    ? MapLiveTileProvider.latest(activity.getApplicationContext())
                    : LiveTileDataProvider.latestForPackage(activity, packageName);
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

        AutoMarqueeTextView information = new AutoMarqueeTextView(activity);
        information.setTag(TAG_LIVE_TEXT);
        information.setText(content.toString());
        information.setTextColor(Color.WHITE);
        information.setTextSize(square ? 10.5f : 11.5f);
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
