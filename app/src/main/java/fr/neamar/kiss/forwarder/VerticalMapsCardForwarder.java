package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;

/**
 * Applies fresh location data and a map preview to the dedicated Vertical Cards renderer.
 * It updates only the Maps card and never rebuilds the launcher when Home is pressed.
 */
final class VerticalMapsCardForwarder extends Forwarder {
    private static final int TAG_MAP_PREVIEW = 0x534D4D01;
    private static final int TAG_MAP_LOCATION = 0x534D4D02;

    private final SmartCardListForwarder smartCards;
    private final AtomicBoolean loadInFlight = new AtomicBoolean(false);

    VerticalMapsCardForwarder(MainActivity mainActivity, SmartCardListForwarder smartCards) {
        super(mainActivity);
        this.smartCards = smartCards;
    }

    void onCreate() {
        refreshMapsCard(true);
    }

    /** Home resume deliberately does nothing. */
    void onResume() { }

    void onDataSetChanged() {
        // Providers normally populate history after onCreate. Start the Maps request when the
        // actual Maps card first becomes available rather than depending on a later Home resume.
        refreshMapsCard(true);
    }

    private void refreshMapsCard(boolean requestFreshLocation) {
        if (!isVerticalCardsEnabled()) return;
        View anchor = mainActivity.listContainer;
        if (anchor == null) return;
        anchor.post(() -> {
            int position = findMapsPosition();
            if (position < 0) return;
            showLocatingState(position);
            if (requestFreshLocation) {
                MapLiveTileProvider.requestFreshLocation(mainActivity,
                        () -> loadAndApply(findMapsPosition()));
            }
            loadAndApply(position);
        });
    }

    private boolean isVerticalCardsEnabled() {
        return "vertical_cards".equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private int findMapsPosition() {
        if (mainActivity.adapter == null) return -1;
        for (int i = 0; i < mainActivity.adapter.getCount(); i++) {
            Result<?> result = mainActivity.adapter.getItem(i);
            if (isMapsResult(result)) return i;
        }
        return -1;
    }

    private boolean isMapsResult(Result<?> result) {
        if (result == null) return false;
        Pojo pojo = result.getPojo();
        if (pojo instanceof AppPojo
                && MapLiveTileProvider.MAPS_PACKAGE.equals(((AppPojo) pojo).packageName)) return true;
        String id = pojo.id == null ? "" : pojo.id.toLowerCase(Locale.ROOT);
        if (id.contains(MapLiveTileProvider.MAPS_PACKAGE)) return true;
        String name = pojo.getName();
        return name != null && "maps".equals(name.trim().toLowerCase(Locale.ROOT));
    }

    private void loadAndApply(int adapterPosition) {
        if (adapterPosition < 0 || !loadInFlight.compareAndSet(false, true)) return;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            LiveTileDataProvider.LiveTileData data = MapLiveTileProvider.latest(mainActivity);
            mainActivity.runOnUiThread(() -> {
                loadInFlight.set(false);
                if (!isVerticalCardsEnabled()) return;
                if (data == null) {
                    if (!MapLiveTileProvider.hasLocationPermission(mainActivity)) {
                        updateStatus(adapterPosition, "Allow location for live Maps preview");
                    } else {
                        updateStatus(adapterPosition, "Locating current position…");
                    }
                    return;
                }
                applyToCard(adapterPosition, data);
            });
        });
    }

    private void showLocatingState(int adapterPosition) {
        if (MapLiveTileProvider.hasLocationPermission(mainActivity)) {
            updateStatus(adapterPosition, "Locating current position…");
        } else {
            updateStatus(adapterPosition, "Location permission required");
        }
    }

    private void updateStatus(int adapterPosition, String text) {
        LinearLayout center = resolveCenter(adapterPosition);
        if (center == null) return;
        TextView target = ensureLocationLine(center);
        hideStaleMapText(center, target);
        target.setText(text);
        target.setVisibility(View.VISIBLE);
    }

    private void applyToCard(int adapterPosition, LiveTileDataProvider.LiveTileData data) {
        LinearLayout card = resolveCard(adapterPosition);
        LinearLayout center = resolveCenter(adapterPosition);
        if (card == null || center == null) return;

        TextView location = ensureLocationLine(center);
        hideStaleMapText(center, location);
        location.setText(TextUtils.isEmpty(data.title) ? "Current location" : data.title);
        location.setVisibility(View.VISIBLE);

        if (!TextUtils.isEmpty(data.text)) {
            AutoMarqueeTextView coordinates = new AutoMarqueeTextView(mainActivity);
            coordinates.setTag(TAG_MAP_LOCATION + 1);
            coordinates.setText(data.text);
            coordinates.setTextColor(Color.argb(205, 255, 255, 255));
            coordinates.setTextSize(11f);
            center.addView(coordinates, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(23)));
        }

        removeTaggedChild(card, TAG_MAP_PREVIEW);
        if (data.artwork != null) {
            View preview = buildPreview(data.artwork);
            int insertAt = Math.min(1, card.getChildCount());
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
            previewLp.topMargin = dp(8);
            previewLp.bottomMargin = dp(6);
            card.addView(preview, insertAt, previewLp);
        }
    }

    private TextView ensureLocationLine(LinearLayout center) {
        for (int i = 0; i < center.getChildCount(); i++) {
            View child = center.getChildAt(i);
            if (Integer.valueOf(TAG_MAP_LOCATION).equals(child.getTag()) && child instanceof TextView) {
                return (TextView) child;
            }
        }
        AutoMarqueeTextView target = new AutoMarqueeTextView(mainActivity);
        target.setTag(TAG_MAP_LOCATION);
        target.setTextColor(Color.WHITE);
        target.setTextSize(13f);
        target.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        center.addView(target, Math.min(1, center.getChildCount()), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        return target;
    }

    private void hideStaleMapText(LinearLayout center, TextView keep) {
        for (int i = center.getChildCount() - 1; i >= 0; i--) {
            View child = center.getChildAt(i);
            if (child == keep) continue;
            Object tag = child.getTag();
            if (Integer.valueOf(TAG_MAP_LOCATION + 1).equals(tag)) {
                center.removeViewAt(i);
                continue;
            }
            // Keep the first title line. All other TextViews on a Maps card are old subtitle or
            // notification-derived location strings and must not override the live location.
            if (child instanceof TextView && i > 0) child.setVisibility(View.GONE);
        }
    }

    private View buildPreview(Drawable mapDrawable) {
        FrameLayout frame = new FrameLayout(mainActivity);
        frame.setTag(TAG_MAP_PREVIEW);
        frame.setClipChildren(true);
        frame.setClipToPadding(true);

        ImageView map = new ImageView(mainActivity);
        map.setImageDrawable(mapDrawable);
        map.setScaleType(ImageView.ScaleType.CENTER_CROP);
        map.setContentDescription("Current location map preview");
        frame.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView attribution = new TextView(mainActivity);
        attribution.setText("© OpenStreetMap contributors");
        attribution.setTextColor(Color.WHITE);
        attribution.setTextSize(9f);
        attribution.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        attribution.setPadding(dp(5), 0, dp(5), 0);
        attribution.setBackgroundColor(Color.argb(160, 0, 0, 0));
        FrameLayout.LayoutParams attributionLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), Gravity.END | Gravity.BOTTOM);
        frame.addView(attribution, attributionLp);
        return frame;
    }

    private LinearLayout resolveCard(int adapterPosition) {
        LinearLayout column = readColumn();
        if (column == null || adapterPosition < 0 || adapterPosition >= column.getChildCount()) return null;
        View wrapper = column.getChildAt(adapterPosition);
        if (!(wrapper instanceof ViewGroup)) return null;
        ViewGroup wrapperGroup = (ViewGroup) wrapper;
        if (wrapperGroup.getChildCount() == 0 || !(wrapperGroup.getChildAt(0) instanceof LinearLayout)) return null;
        return (LinearLayout) wrapperGroup.getChildAt(0);
    }

    private LinearLayout resolveCenter(int adapterPosition) {
        LinearLayout card = resolveCard(adapterPosition);
        if (card == null || card.getChildCount() == 0 || !(card.getChildAt(0) instanceof LinearLayout)) return null;
        LinearLayout mainRow = (LinearLayout) card.getChildAt(0);
        for (int i = mainRow.getChildCount() - 1; i >= 0; i--) {
            View child = mainRow.getChildAt(i);
            if (child instanceof LinearLayout) return (LinearLayout) child;
        }
        return null;
    }

    private void removeTaggedChild(ViewGroup parent, int tag) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            if (Integer.valueOf(tag).equals(parent.getChildAt(i).getTag())) parent.removeViewAt(i);
        }
    }

    private LinearLayout readColumn() {
        return smartCards.getColumn();
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
