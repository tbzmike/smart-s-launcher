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

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.utils.Log;

/**
 * Applies fresh location data and a map preview to the dedicated Vertical Cards renderer.
 * This is intentionally separate from SmartCardListForwarder so normal card construction,
 * notification actions, sizing, gestures and existing card behavior remain untouched.
 */
final class VerticalMapsCardForwarder extends Forwarder {
    private static final String TAG = "VerticalMapsCardForwarder";
    private static final int TAG_MAP_PREVIEW = 0x534D4D01;
    private static final int TAG_MAP_LOCATION = 0x534D4D02;

    private final SmartCardListForwarder smartCards;
    private final AtomicBoolean loadInFlight = new AtomicBoolean(false);

    VerticalMapsCardForwarder(MainActivity mainActivity, SmartCardListForwarder smartCards) {
        super(mainActivity);
        this.smartCards = smartCards;
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
        if (!isVerticalCardsEnabled()) return;
        View anchor = mainActivity.listContainer;
        if (anchor == null) return;
        anchor.post(() -> {
            int position = findMapsPosition();
            if (position < 0) return;
            MapLiveTileProvider.requestFreshLocation(mainActivity, this::refreshSoon);
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
            if (result != null && result.getPojo() instanceof AppPojo) {
                AppPojo app = (AppPojo) result.getPojo();
                if (MapLiveTileProvider.MAPS_PACKAGE.equals(app.packageName)) return i;
            }
        }
        return -1;
    }

    private void loadAndApply(int adapterPosition) {
        if (!loadInFlight.compareAndSet(false, true)) return;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            LiveTileDataProvider.LiveTileData data = MapLiveTileProvider.latest(mainActivity);
            mainActivity.runOnUiThread(() -> {
                loadInFlight.set(false);
                if (data == null || !isVerticalCardsEnabled()) return;
                applyToCard(adapterPosition, data);
            });
        });
    }

    private void applyToCard(int adapterPosition, LiveTileDataProvider.LiveTileData data) {
        LinearLayout column = readColumn();
        if (column == null || adapterPosition < 0 || adapterPosition >= column.getChildCount()) return;
        View wrapper = column.getChildAt(adapterPosition);
        if (!(wrapper instanceof ViewGroup)) return;
        ViewGroup wrapperGroup = (ViewGroup) wrapper;
        if (wrapperGroup.getChildCount() == 0 || !(wrapperGroup.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout card = (LinearLayout) wrapperGroup.getChildAt(0);
        if (card.getChildCount() == 0 || !(card.getChildAt(0) instanceof LinearLayout)) return;

        LinearLayout mainRow = (LinearLayout) card.getChildAt(0);
        LinearLayout center = findCenterColumn(mainRow);
        if (center != null) updateLocationText(center, data.title);

        removeTaggedChild(card, TAG_MAP_PREVIEW);
        if (data.artwork != null) {
            View preview = buildPreview(data.artwork);
            int insertAt = Math.min(1, card.getChildCount());
            card.addView(preview, insertAt, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));
        }
    }

    private LinearLayout findCenterColumn(LinearLayout mainRow) {
        for (int i = mainRow.getChildCount() - 1; i >= 0; i--) {
            View child = mainRow.getChildAt(i);
            if (child instanceof LinearLayout) return (LinearLayout) child;
        }
        return null;
    }

    private void updateLocationText(LinearLayout center, String location) {
        if (TextUtils.isEmpty(location)) return;
        TextView target = null;
        for (int i = 0; i < center.getChildCount(); i++) {
            View child = center.getChildAt(i);
            if (Integer.valueOf(TAG_MAP_LOCATION).equals(child.getTag()) && child instanceof TextView) {
                target = (TextView) child;
                break;
            }
        }
        if (target == null) {
            // The Vertical Card center has title first and subtitle second when an app subtitle exists.
            if (center.getChildCount() > 1 && center.getChildAt(1) instanceof TextView) {
                target = (TextView) center.getChildAt(1);
            } else {
                target = new TextView(mainActivity);
                target.setTextColor(Color.WHITE);
                target.setTextSize(13f);
                target.setSingleLine(true);
                target.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                target.setMarqueeRepeatLimit(-1);
                target.setSelected(true);
                center.addView(target, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));
            }
            target.setTag(TAG_MAP_LOCATION);
        }
        target.setText(location);
        target.setVisibility(View.VISIBLE);
    }

    private View buildPreview(Drawable mapDrawable) {
        FrameLayout frame = new FrameLayout(mainActivity);
        frame.setTag(TAG_MAP_PREVIEW);
        frame.setClipToOutline(true);

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
        attribution.setBackgroundColor(Color.argb(145, 0, 0, 0));
        FrameLayout.LayoutParams attributionLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), Gravity.END | Gravity.BOTTOM);
        frame.addView(attribution, attributionLp);
        return frame;
    }

    private void removeTaggedChild(ViewGroup parent, int tag) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            if (Integer.valueOf(tag).equals(parent.getChildAt(i).getTag())) parent.removeViewAt(i);
        }
    }

    private LinearLayout readColumn() {
        try {
            Field field = SmartCardListForwarder.class.getDeclaredField("column");
            field.setAccessible(true);
            Object value = field.get(smartCards);
            return value instanceof LinearLayout ? (LinearLayout) value : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to access Vertical Cards column", e);
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
