package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;

/**
 * Optional horizontal presentation for the normal KISS history/results adapter.
 * It mirrors the existing RecordAdapter instead of creating a second data source, so search,
 * history ordering and launching keep using the proven KISS result pipeline.
 */
final class HistoryDisplayForwarder extends Forwarder {
    static final String PREF_LAYOUT = "smart-history-layout";
    static final String VERTICAL = "vertical";
    static final String ICONS = "horizontal_icons";
    static final String CARDS = "horizontal_cards";
    static final String NAMES = "horizontal_names";

    private FrameLayout container;
    private HorizontalScrollView scroller;
    private LinearLayout row;
    private View edgeEffect;
    private String activeMode = VERTICAL;

    HistoryDisplayForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        if (!(mainActivity.listContainer instanceof FrameLayout)) return;
        container = (FrameLayout) mainActivity.listContainer;
        edgeEffect = mainActivity.findViewById(R.id.listEdgeEffect);

        scroller = new HorizontalScrollView(mainActivity);
        scroller.setFillViewport(false);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setClipToPadding(false);
        scroller.setPadding(dp(4), dp(4), dp(4), dp(4));

        row = new LinearLayout(mainActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        container.addView(scroller, params);
        applyMode(true);
    }

    void onResume() {
        applyMode(false);
    }

    void onDataSetChanged() {
        if (!VERTICAL.equals(activeMode)) rebuild();
    }

    private void applyMode(boolean force) {
        if (container == null) return;
        String requested = prefs.getString(PREF_LAYOUT, VERTICAL);
        if (requested == null) requested = VERTICAL;
        if (!force && requested.equals(activeMode)) return;
        activeMode = requested;

        boolean horizontal = !VERTICAL.equals(activeMode);
        mainActivity.list.setVisibility(horizontal ? View.GONE : View.VISIBLE);
        if (edgeEffect != null) edgeEffect.setVisibility(horizontal ? View.GONE : View.VISIBLE);
        scroller.setVisibility(horizontal ? View.VISIBLE : View.GONE);
        if (horizontal) rebuild();
    }

    private void rebuild() {
        if (row == null || mainActivity.adapter == null) return;
        row.removeAllViews();
        final int count = mainActivity.adapter.getCount();
        for (int position = 0; position < count; position++) {
            View source = mainActivity.adapter.getView(position, null, row);
            View tile = createTile(source, activeMode);
            final int adapterPosition = position;
            tile.setOnClickListener(v -> mainActivity.adapter.onClick(adapterPosition, v));
            tile.setOnLongClickListener(v -> {
                mainActivity.adapter.onLongClick(adapterPosition, v);
                return true;
            });
            row.addView(tile);
        }
        scroller.post(() -> scroller.fullScroll(View.FOCUS_RIGHT));
    }

    private View createTile(View source, String mode) {
        if (ICONS.equals(mode)) return createIconTile(source);
        if (NAMES.equals(mode)) return createNameTile(source);
        if (CARDS.equals(mode)) return createCardTile(source);
        return source;
    }

    private View createIconTile(View source) {
        FrameLayout tile = baseTile(dp(76), dp(76));
        ImageView sourceIcon = source.findViewById(R.id.item_app_icon);
        if (sourceIcon != null && sourceIcon.getDrawable() != null) {
            ImageView icon = new ImageView(mainActivity);
            icon.setImageDrawable(sourceIcon.getDrawable());
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        } else {
            source.setAlpha(0.95f);
            tile.addView(source, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));
        }
        return tile;
    }

    private View createNameTile(View source) {
        FrameLayout tile = baseTile(dp(170), dp(64));
        TextView sourceName = source.findViewById(R.id.item_app_name);
        if (sourceName == null) {
            source.setAlpha(0.9f);
            tile.addView(source, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return tile;
        }

        TextView name = new TextView(mainActivity);
        name.setText(sourceName.getText());
        name.setTextColor(resolveTextColor());
        name.setTextSize(17f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setPadding(dp(12), dp(8), dp(12), dp(8));
        tile.addView(name, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return tile;
    }

    private View createCardTile(View source) {
        FrameLayout card = baseTile(dp(190), dp(132));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(82, 32, 32, 32));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.argb(110, 255, 255, 255));
        card.setBackground(background);
        card.setClipToOutline(true);

        // Android does not expose arbitrary live app-screen thumbnails to third-party launchers.
        // Keep the real result rendering as a muted safe preview underneath the app foreground.
        source.setAlpha(0.32f);
        source.setScaleX(1.08f);
        source.setScaleY(1.08f);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewParams.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.addView(source, previewParams);

        ImageView sourceIcon = source.findViewById(R.id.item_app_icon);
        if (sourceIcon != null && sourceIcon.getDrawable() != null) {
            ImageView icon = new ImageView(mainActivity);
            icon.setImageDrawable(sourceIcon.getDrawable());
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    dp(58), dp(58), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            iconParams.topMargin = dp(20);
            card.addView(icon, iconParams);
        }

        TextView sourceName = source.findViewById(R.id.item_app_name);
        if (sourceName != null) {
            TextView name = new TextView(mainActivity);
            name.setText(sourceName.getText());
            name.setTextColor(Color.WHITE);
            name.setTextSize(15f);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setPadding(dp(8), 0, dp(8), dp(8));
            FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38), Gravity.BOTTOM);
            card.addView(name, nameParams);
        }
        return card;
    }

    private FrameLayout baseTile(int width, int height) {
        FrameLayout tile = new FrameLayout(mainActivity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.setMargins(dp(5), dp(4), dp(5), dp(4));
        tile.setLayoutParams(lp);
        tile.setClickable(true);
        tile.setFocusable(true);
        return tile;
    }

    private int resolveTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (mainActivity.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)
                && value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return Color.WHITE;
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
