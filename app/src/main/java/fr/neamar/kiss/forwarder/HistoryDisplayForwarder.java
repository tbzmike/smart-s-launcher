package fr.neamar.kiss.forwarder;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.SmartAnimationEngine;

final class HistoryDisplayForwarder extends Forwarder {
    static final String PREF_LAYOUT = "smart-history-layout";
    static final String VERTICAL = "vertical";
    static final String VERTICAL_CARDS = "vertical_cards";
    static final String ICONS = "horizontal_icons";
    static final String CARDS = "horizontal_cards";
    static final String NAMES = "horizontal_names";
    static final String SQUARE_U = "square_u";
    static final String WHEEL_3D = "wheel_3d";

    private static final int CARD_WIDTH_DP = 190;
    private static final int CARD_HEIGHT_DP = 132;
    private static final int SQUARE_CARD_WIDTH_DP = 124;
    private static final int SQUARE_CARD_HEIGHT_DP = 158;
    private static final float SQUARE_VISIBLE_RADIUS = 6.15f;
    private static final float SQUARE_BOTTOM_BAND = 2.05f;
    private static final int ACCENT_SAMPLE_SIZE = 10;
    private static final int ACCENT_CACHE_SIZE = 256;

    private final Map<Long, Integer> accentCache = new LinkedHashMap<Long, Integer>(
            ACCENT_CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > ACCENT_CACHE_SIZE;
        }
    };

    private FrameLayout container;
    private HorizontalScrollView scroller;
    private LinearLayout row;
    private FrameLayout squareRoot;
    private SquareTrackLayout squareTrack;
    private WheelScrollView wheelScroller;
    private LinearLayout wheelColumn;
    private final ArrayList<Integer> wheelViewTypes = new ArrayList<>();
    private boolean wheelTransformFramePosted;
    private ScrollView notificationScroller;
    private LinearLayout notificationCenter;
    private View edgeEffect;
    private String activeMode = VERTICAL;
    private boolean squareHasBeenEntered;
    private String lastSquareQuery = "";
    private long lastSquarePriorityId = Long.MIN_VALUE;
    private boolean wheelHasBeenEntered;
    private String lastWheelQuery = "";
    private long lastWheelPriorityId = Long.MIN_VALUE;

    HistoryDisplayForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        if (!(mainActivity.listContainer instanceof FrameLayout)) return;
        container = (FrameLayout) mainActivity.listContainer;
        edgeEffect = mainActivity.findViewById(R.id.listEdgeEffect);
        createHorizontalRenderer();
        createWheelRenderer();
        createSquareRenderer();
        applyMode(true);
    }

    void onResume() {
        applyMode(false);
        if (WHEEL_3D.equals(activeMode)) {
            rebuildWheel();
        } else if (SQUARE_U.equals(activeMode)) {
            applyNotificationPanelSizing();
            rebuildSquare();
        } else if (!VERTICAL.equals(activeMode) && !VERTICAL_CARDS.equals(activeMode)) {
            rebuildHorizontal();
        }
    }

    void onDataSetChanged() {
        if (!VERTICAL.equals(activeMode) && !VERTICAL_CARDS.equals(activeMode)) rebuild();
    }

    private void createHorizontalRenderer() {
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
    }

    private void createWheelRenderer() {
        wheelScroller = new WheelScrollView();
        wheelScroller.setFillViewport(true);
        wheelScroller.setVerticalScrollBarEnabled(false);
        wheelScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        wheelScroller.setClipChildren(false);
        wheelScroller.setClipToPadding(false);
        wheelScroller.setVisibility(View.GONE);

        wheelColumn = new LinearLayout(mainActivity);
        wheelColumn.setOrientation(LinearLayout.VERTICAL);
        wheelColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        wheelColumn.setClipChildren(false);
        wheelColumn.setClipToPadding(false);
        wheelColumn.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if (WHEEL_3D.equals(activeMode)) scheduleWheelTransforms();
        });
        wheelScroller.addView(wheelColumn, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM);
        container.addView(wheelScroller, params);
    }

    private void rebuildWheel() {
        if (wheelColumn == null || mainActivity.adapter == null) return;

        final int count = mainActivity.adapter.getCount();
        String currentQuery = mainActivity.searchEditText == null
                ? "" : mainActivity.searchEditText.getText().toString().trim();
        long currentPriorityId = count > 0
                ? mainActivity.adapter.getItem(count - 1).getUniqueId() : Long.MIN_VALUE;
        boolean refocusFront = !wheelHasBeenEntered
                || !currentQuery.equals(lastWheelQuery)
                || currentPriorityId != lastWheelPriorityId;

        // Rebind through the real List View adapter, but recycle rows by view type instead of
        // destroying the whole wheel for every history/search dataset update. This keeps every
        // List View feature while avoiding repeated inflate/layout/GC churn.
        for (int position = 0; position < count; position++) {
            int viewType = mainActivity.adapter.getItemViewType(position);
            View existing = position < wheelColumn.getChildCount()
                    ? wheelColumn.getChildAt(position) : null;
            int oldType = position < wheelViewTypes.size() ? wheelViewTypes.get(position) : -1;
            View reusable = existing != null && oldType == viewType ? existing : null;
            View source = mainActivity.adapter.getView(position, reusable, mainActivity.list);

            if (source != existing) {
                if (existing != null) wheelColumn.removeViewAt(position);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(3), dp(4), dp(3), dp(4));
                wheelColumn.addView(source, position, lp);
            }

            while (wheelViewTypes.size() <= position) wheelViewTypes.add(-1);
            wheelViewTypes.set(position, viewType);
            resetWheelTransform(source);
            source.setCameraDistance(mainActivity.getResources().getDisplayMetrics().density * 8000f);
            bindResultInteraction(source, position);
        }

        while (wheelColumn.getChildCount() > count) {
            wheelColumn.removeViewAt(wheelColumn.getChildCount() - 1);
        }
        while (wheelViewTypes.size() > count) {
            wheelViewTypes.remove(wheelViewTypes.size() - 1);
        }

        wheelHasBeenEntered = true;
        lastWheelQuery = currentQuery;
        lastWheelPriorityId = currentPriorityId;
        wheelScroller.post(() -> {
            if (refocusFront && count > 0) centerWheelItem(count - 1);
            scheduleWheelTransforms();
        });
    }

    private void centerWheelItem(int position) {
        if (wheelScroller == null || wheelColumn == null
                || position < 0 || position >= wheelColumn.getChildCount()) return;
        View child = wheelColumn.getChildAt(position);
        int target = Math.round(child.getTop() + child.getHeight() / 2f
                - wheelScroller.getHeight() / 2f);
        int max = Math.max(0, wheelColumn.getHeight() - wheelScroller.getHeight());
        wheelScroller.scrollTo(0, Math.max(0, Math.min(max, target)));
    }

    private void scheduleWheelTransforms() {
        if (wheelScroller == null || wheelTransformFramePosted || !WHEEL_3D.equals(activeMode)) return;
        wheelTransformFramePosted = true;
        wheelScroller.postOnAnimation(() -> {
            wheelTransformFramePosted = false;
            if (WHEEL_3D.equals(activeMode)) updateWheelTransforms();
        });
    }

    private void updateWheelTransforms() {
        if (wheelScroller == null || wheelColumn == null || wheelScroller.getHeight() <= 0) return;

        float viewportTop = wheelScroller.getScrollY();
        float viewportBottom = viewportTop + wheelScroller.getHeight();
        float viewportCenter = viewportTop + wheelScroller.getHeight() / 2f;
        float radius = Math.max(dp(160), wheelScroller.getHeight() * 0.52f);
        float overscan = wheelScroller.getHeight() * 0.35f;
        float density = mainActivity.getResources().getDisplayMetrics().density;

        for (int i = 0; i < wheelColumn.getChildCount(); i++) {
            View child = wheelColumn.getChildAt(i);
            if (child.getBottom() < viewportTop - overscan
                    || child.getTop() > viewportBottom + overscan) {
                // Keep off-screen rows laid out for ScrollView geometry, but skip perspective work
                // until they approach the viewport.
                child.setAlpha(0.12f);
                child.setRotationX(0f);
                child.setScaleX(0.82f);
                child.setScaleY(0.82f);
                child.setTranslationY(0f);
                child.setTranslationZ(0f);
                continue;
            }
            float childCenter = child.getTop() + child.getHeight() / 2f;
            float normalized = (childCenter - viewportCenter) / radius;
            normalized = Math.max(-1f, Math.min(1f, normalized));
            float distance = Math.abs(normalized);

            child.setPivotX(child.getWidth() / 2f);
            child.setPivotY(child.getHeight() / 2f);
            child.setCameraDistance(density * 8000f);
            child.setRotationX(-normalized * 72f);

            float scale = 1f - 0.20f * distance;
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(Math.max(0.22f, 1f - 0.78f * distance));
            child.setTranslationY(-normalized * distance * dp(22));
            child.setTranslationZ((1f - distance) * dp(18));
        }
    }

    private void resetWheelTransforms() {
        if (wheelColumn == null) return;
        for (int i = 0; i < wheelColumn.getChildCount(); i++) {
            resetWheelTransform(wheelColumn.getChildAt(i));
        }
    }

    private void resetWheelTransform(View child) {
        if (child == null) return;
        child.setRotationX(0f);
        child.setRotationY(0f);
        child.setScaleX(1f);
        child.setScaleY(1f);
        child.setAlpha(1f);
        child.setTranslationX(0f);
        child.setTranslationY(0f);
        child.setTranslationZ(0f);
    }

    private final class WheelScrollView extends ScrollView {
        WheelScrollView() {
            super(mainActivity);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            scheduleWheelTransforms();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (wheelColumn != null && h > 0) {
                int verticalPadding = Math.max(dp(80), h / 3);
                wheelColumn.setPadding(dp(4), verticalPadding, dp(4), verticalPadding);
            }
            post(() -> {
                if (WHEEL_3D.equals(activeMode)) scheduleWheelTransforms();
            });
        }
    }

    private void createSquareRenderer() {
        squareRoot = new FrameLayout(mainActivity);
        squareRoot.setClipChildren(false);
        squareRoot.setClipToPadding(false);

        squareTrack = new SquareTrackLayout();
        squareRoot.addView(squareTrack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        notificationScroller = new ScrollView(mainActivity);
        notificationScroller.setFillViewport(false);
        notificationScroller.setVerticalScrollBarEnabled(true);
        notificationScroller.setScrollbarFadingEnabled(true);
        notificationScroller.setFadingEdgeLength(dp(18));
        notificationScroller.setVerticalFadingEdgeEnabled(true);
        notificationScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        notificationScroller.setClipToPadding(false);
        notificationScroller.setPadding(dp(6), dp(6), dp(6), dp(6));
        notificationScroller.setElevation(dp(6));
        notificationScroller.setVisibility(View.GONE);

        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.argb(205, 8, 9, 12));
        panel.setCornerRadius(dp(20));
        panel.setStroke(dp(2), Color.argb(190, 78, 105, 255));
        notificationScroller.setBackground(panel);

        notificationCenter = new LinearLayout(mainActivity);
        notificationCenter.setOrientation(LinearLayout.VERTICAL);
        notificationCenter.setGravity(Gravity.CENTER_HORIZONTAL);
        notificationCenter.setClipChildren(false);
        notificationCenter.setClipToPadding(false);
        notificationCenter.setPadding(dp(5), dp(5), dp(5), dp(5));
        notificationScroller.addView(notificationCenter, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        squareRoot.addView(notificationScroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(286), Gravity.CENTER));
        applyNotificationPanelSizing();

        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM);
        container.addView(squareRoot, rootParams);
    }

    private void applyNotificationPanelSizing() {
        if (notificationScroller == null) return;
        int sizePercent = safePrefInt("smart-u-notification-panel-size-percent", 100, 55, 150);
        int gapDp = safePrefInt("smart-u-notification-gap-dp", 28, 8, 96);
        int screenWidth = mainActivity.getResources().getDisplayMetrics().widthPixels;
        int baseWidth = Math.max(dp(180), screenWidth - dp(116));
        int width = Math.min(screenWidth - dp(24), Math.max(dp(150), baseWidth * sizePercent / 100));
        int height = Math.max(dp(150), dp(286) * sizePercent / 100);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
        lp.topMargin = dp(50 + gapDp / 2);
        lp.bottomMargin = dp(138 + gapDp);
        notificationScroller.setLayoutParams(lp);
    }

    private void applyMode(boolean force) {
        if (container == null) return;
        String requested = prefs.getString(PREF_LAYOUT, VERTICAL);
        if (requested == null) requested = VERTICAL;
        if (!force && requested.equals(activeMode)) return;

        boolean leavingWheel = WHEEL_3D.equals(activeMode) && !WHEEL_3D.equals(requested);
        if (leavingWheel) resetWheelTransforms();

        activeMode = requested;
        boolean vertical = VERTICAL.equals(activeMode);
        boolean verticalCards = VERTICAL_CARDS.equals(activeMode);
        boolean square = SQUARE_U.equals(activeMode);
        boolean wheel = WHEEL_3D.equals(activeMode);
        boolean horizontal = !vertical && !verticalCards && !square && !wheel;

        mainActivity.list.setVisibility(vertical ? View.VISIBLE : View.GONE);
        if (edgeEffect != null) edgeEffect.setVisibility(vertical ? View.VISIBLE : View.GONE);
        scroller.setVisibility(horizontal ? View.VISIBLE : View.GONE);
        squareRoot.setVisibility(square ? View.VISIBLE : View.GONE);
        wheelScroller.setVisibility(wheel ? View.VISIBLE : View.GONE);

        if (!vertical && !verticalCards) rebuild();

        View incoming = vertical ? mainActivity.list
                : (wheel ? wheelScroller : (square ? squareRoot : (horizontal ? scroller : null)));
        if (incoming != null && incoming.getVisibility() == View.VISIBLE) {
            SmartAnimationEngine.animateWindowSwitch(null, incoming);
        }

        if (square && !squareHasBeenEntered) {
            squareTrack.resetForFirstEntry();
            squareHasBeenEntered = true;
        }
    }

    private void rebuild() {
        if (mainActivity.adapter == null || VERTICAL_CARDS.equals(activeMode)) return;
        if (WHEEL_3D.equals(activeMode)) rebuildWheel();
        else if (SQUARE_U.equals(activeMode)) rebuildSquare();
        else rebuildHorizontal();
    }

    private void rebuildHorizontal() {
        row.removeAllViews();
        final int count = mainActivity.adapter.getCount();
        for (int position = 0; position < count; position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            View source = mainActivity.adapter.getView(position, null, row);
            View tile = createTile(source, activeMode, result);
            bindResultInteraction(tile, position);
            row.addView(tile);
            if (position >= Math.max(0, count - 18)) {
                animateHistoryItemIn(tile, position - Math.max(0, count - 18));
            }
        }
        scroller.post(() -> scroller.fullScroll(View.FOCUS_RIGHT));
    }

    private void rebuildSquare() {
        applyNotificationPanelSizing();
        final int count = mainActivity.adapter.getCount();
        String currentQuery = mainActivity.searchEditText == null
                ? "" : mainActivity.searchEditText.getText().toString().trim();
        long currentPriorityId = count > 0
                ? mainActivity.adapter.getItem(count - 1).getUniqueId() : Long.MIN_VALUE;
        boolean refocusBottom = !currentQuery.equals(lastSquareQuery)
                || currentPriorityId != lastSquarePriorityId;

        squareTrack.removeAllViews();
        notificationCenter.removeAllViews();
        int visibleNotifications = 0;

        for (int position = 0; position < count; position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            View source = mainActivity.adapter.getView(position, null, squareTrack);
            View notificationRow = source.findViewById(R.id.item_notification_row);
            boolean hasNotification = notificationRow != null
                    && notificationRow.getVisibility() == View.VISIBLE;

            View card = createSquareCard(source, result);
            bindResultInteraction(card, position);
            squareTrack.addView(card);

            if (hasNotification) {
                if (visibleNotifications == 0) addNotificationHeader();
                View shell = createNotificationShell(source);
                LinearLayout.LayoutParams notificationLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                notificationLp.setMargins(dp(2), dp(2), dp(2), dp(2));
                notificationCenter.addView(shell, notificationLp);
                animateNotificationIn(shell, visibleNotifications);
                visibleNotifications++;
            }
        }

        notificationScroller.setVisibility(visibleNotifications > 0 ? View.VISIBLE : View.GONE);
        if (visibleNotifications > 0) notificationScroller.scrollTo(0, 0);
        squareTrack.onDataRebuilt(refocusBottom);
        lastSquareQuery = currentQuery;
        lastSquarePriorityId = currentPriorityId;
        animateNotificationCenterRefresh();
    }

    private void addNotificationHeader() {
        TextView title = new TextView(mainActivity);
        title.setText("Notifications");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15.5f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(6), dp(2), dp(6), dp(5));
        notificationCenter.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View createNotificationShell(View notificationSource) {
        FrameLayout shell = new FrameLayout(mainActivity);
        shell.setClipChildren(false);
        shell.setClipToPadding(false);
        shell.setPadding(dp(4), dp(2), dp(4), dp(2));
        shell.setElevation(dp(2));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(225, 25, 26, 30));
        background.setCornerRadius(dp(13));
        background.setStroke(dp(1), Color.argb(68, 255, 255, 255));
        shell.setBackground(background);

        float contentScale = safePrefInt("smart-u-notification-content-size-percent", 100, 65, 140) / 100f;
        notificationSource.setAlpha(1f);
        notificationSource.setScaleX(0.92f * contentScale);
        notificationSource.setScaleY(0.92f * contentScale);
        FrameLayout.LayoutParams sourceParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.gravity = Gravity.CENTER;
        shell.addView(notificationSource, sourceParams);
        return shell;
    }

    private void bindResultInteraction(View tile, int adapterPosition) {
        tile.setOnClickListener(v -> mainActivity.adapter.onClick(adapterPosition, v));
        tile.setOnLongClickListener(v -> {
            mainActivity.adapter.onLongClick(adapterPosition, v);
            return true;
        });
        tile.setClickable(true);
        tile.setFocusable(true);
    }

    private View createTile(View source, String mode, Result<?> result) {
        if (ICONS.equals(mode)) return createIconTile(source, result);
        if (NAMES.equals(mode)) return createNameTile(source, result);
        if (CARDS.equals(mode)) return createCardTile(source, result);
        return source;
    }

    private int horizontalTilePercent() {
        return safePrefInt("smart-horizontal-tile-size-percent", 100, 65, 160);
    }

    private int horizontalIconPercent() {
        return safePrefInt("smart-horizontal-icon-size-percent", 100, 60, 170);
    }

    private View createIconTile(View source, Result<?> result) {
        int tilePercent = horizontalTilePercent();
        int iconPercent = horizontalIconPercent();
        FrameLayout tile = baseTile(dp(112) * tilePercent / 100, dp(102) * tilePercent / 100);
        Drawable iconDrawable = resolveIcon(source);
        styleCard(tile, dp(16), false, accentFor(result, iconDrawable));
        CharSequence label = extractLabel(source);

        ImageView icon = new ImageView(mainActivity);
        icon.setImageDrawable(iconDrawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = dp(52) * iconPercent / 100;
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                iconSize, iconSize, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        iconParams.topMargin = dp(5);
        tile.addView(icon, iconParams);
        bindRenderedIcon(result, icon, tile, dp(16), false);

        addFullLabel(tile, label, 12f, dp(44) * tilePercent / 100, 1);
        return tile;
    }

    private View createNameTile(View source, Result<?> result) {
        int tilePercent = horizontalTilePercent();
        int iconPercent = horizontalIconPercent();
        FrameLayout tile = baseTile(dp(190) * tilePercent / 100, dp(76) * tilePercent / 100);
        Drawable iconDrawable = resolveIcon(source);
        styleCard(tile, dp(16), false, accentFor(result, iconDrawable));

        int iconSize = dp(42) * iconPercent / 100;
        ImageView icon = new ImageView(mainActivity);
        icon.setImageDrawable(iconDrawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                iconSize, iconSize, Gravity.START | Gravity.CENTER_VERTICAL);
        iconParams.leftMargin = dp(8);
        tile.addView(icon, iconParams);
        bindRenderedIcon(result, icon, tile, dp(16), false);

        TextView name = buildMarqueeLabel(extractLabel(source), 16f);
        name.setPadding(dp(8), dp(7), dp(8), dp(7));
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        nameParams.leftMargin = iconSize + dp(14);
        nameParams.rightMargin = dp(6);
        tile.addView(name, nameParams);
        return tile;
    }

    private View createCardTile(View source, Result<?> result) {
        int tilePercent = horizontalTilePercent();
        int iconPercent = horizontalIconPercent();
        FrameLayout card = baseTile(dp(CARD_WIDTH_DP) * tilePercent / 100,
                dp(CARD_HEIGHT_DP) * tilePercent / 100);
        Drawable iconDrawable = resolveIcon(source);
        styleCard(card, dp(18), false, accentFor(result, iconDrawable));
        CharSequence label = extractLabel(source);

        View notificationRow = source.findViewById(R.id.item_notification_row);
        boolean hasRichPreview = notificationRow != null
                && notificationRow.getVisibility() == View.VISIBLE;
        if (hasRichPreview) addMutedPreview(card, source);

        ImageView icon = addForegroundIconAndLabel(card, iconDrawable, label,
                dp(58) * iconPercent / 100, 14f, dp(14));
        bindRenderedIcon(result, icon, card, dp(18), false);
        return card;
    }

    private View createSquareCard(View source, Result<?> result) {
        FrameLayout card = new FrameLayout(mainActivity);
        Drawable iconDrawable = resolveIcon(source);
        styleCard(card, dp(18), true, accentFor(result, iconDrawable));
        card.setClipChildren(false);
        card.setClipToPadding(false);

        CharSequence label = extractLabel(source);
        int iconPercent = safePrefInt("smart-u-icon-size-percent", 100, 60, 160);
        int iconSize = dp(72) * iconPercent / 100;

        ImageView icon = new ImageView(mainActivity);
        icon.setImageDrawable(iconDrawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setAlpha(1f);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                iconSize, iconSize, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        iconParams.topMargin = dp(15);
        card.addView(icon, iconParams);
        bindRenderedIcon(result, icon, card, dp(18), true);

        addFullLabel(card, label, 14f, dp(42), 1);
        return card;
    }

    private void addMutedPreview(FrameLayout card, View source) {
        source.setAlpha(0.28f);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewParams.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.addView(source, previewParams);
    }

    private ImageView addForegroundIconAndLabel(FrameLayout card, Drawable iconDrawable,
                                                CharSequence label, int iconSize,
                                                float textSize, int topMargin) {
        ImageView icon = new ImageView(mainActivity);
        icon.setImageDrawable(iconDrawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                iconSize, iconSize, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        iconParams.topMargin = topMargin;
        card.addView(icon, iconParams);
        addFullLabel(card, label, textSize, dp(42), 1);
        return icon;
    }

    private void bindRenderedIcon(Result<?> result, ImageView icon, FrameLayout card,
                                  float radius, boolean square) {
        result.bindDrawable(icon, drawable -> {
            long key = result.getUniqueId();
            Integer cached = accentCache.get(key);
            int accent = cached == null ? sampleAccent(drawable) : cached;
            if (cached == null) accentCache.put(key, accent);
            styleCard(card, radius, square, accent);
        });
    }

    private TextView buildMarqueeLabel(CharSequence label, float textSize) {
        AutoMarqueeTextView name = new AutoMarqueeTextView(mainActivity);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setAlpha(1f);
        name.setTextSize(textSize);
        name.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        name.setShadowLayer(dp(2), 0f, dp(1), Color.BLACK);
        return name;
    }

    private void addFullLabel(FrameLayout card, CharSequence label, float textSize,
                              int labelHeight, int maxLines) {
        TextView name = buildMarqueeLabel(label, textSize);
        name.setPadding(dp(6), dp(2), dp(6), dp(5));
        GradientDrawable labelBackground = new GradientDrawable();
        labelBackground.setColor(Color.argb(138, 0, 0, 0));
        labelBackground.setCornerRadius(dp(10));
        name.setBackground(labelBackground);

        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, labelHeight, Gravity.BOTTOM);
        nameParams.leftMargin = dp(5);
        nameParams.rightMargin = dp(5);
        nameParams.bottomMargin = dp(5);
        card.addView(name, nameParams);
    }

    private CharSequence extractLabel(View source) {
        TextView appName = source.findViewById(R.id.item_app_name);
        if (isUsefulLabel(appName)) return appName.getText();
        TextView discovered = findPrimaryText(source);
        if (discovered != null) return discovered.getText();
        CharSequence description = source.getContentDescription();
        if (!TextUtils.isEmpty(description)) return description;
        return "Item";
    }

    private TextView findPrimaryText(View view) {
        if (view instanceof TextView && !(view instanceof android.widget.Button)) {
            TextView text = (TextView) view;
            if (isUsefulLabel(text)) return text;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView candidate = findPrimaryText(group.getChildAt(i));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private boolean isUsefulLabel(TextView text) {
        if (text == null || text.getVisibility() != View.VISIBLE || TextUtils.isEmpty(text.getText())) return false;
        int id = text.getId();
        if (id == R.id.item_notification_text || id == R.id.item_notification_read) return false;
        String normalized = text.getText().toString().trim();
        return !normalized.isEmpty()
                && !"Mark read".equalsIgnoreCase(normalized)
                && !"Open notification".equalsIgnoreCase(normalized)
                && !"Reply".equalsIgnoreCase(normalized);
    }

    private Drawable resolveIcon(View source) {
        Drawable drawable = extractIcon(source);
        // A cold adapter row uses android.R.color.transparent until its async load finishes.
        // Never copy that one-time placeholder into a renderer-owned tile.
        if (!isRenderableIcon(drawable)) {
            drawable = mainActivity.getPackageManager().getDefaultActivityIcon();
        }
        return drawable;
    }

    private Drawable extractIcon(View source) {
        ImageView iconView = findIconView(source);
        return iconView == null ? null : iconView.getDrawable();
    }

    private ImageView findIconView(View source) {
        ImageView appIcon = source.findViewById(R.id.item_app_icon);
        if (appIcon != null && isRenderableIcon(appIcon.getDrawable())) return appIcon;
        return findFirstVisibleImage(source);
    }

    private ImageView findFirstVisibleImage(View view) {
        if (view instanceof ImageView
                && view.getVisibility() == View.VISIBLE
                && isRenderableIcon(((ImageView) view).getDrawable())) {
            return (ImageView) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView candidate = findFirstVisibleImage(group.getChildAt(i));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private boolean isRenderableIcon(Drawable drawable) {
        boolean transparentColorPlaceholder = drawable instanceof ColorDrawable
                && Color.alpha(((ColorDrawable) drawable).getColor()) == 0;
        return HistoryIconPolicy.isRenderable(
                drawable != null, drawable == null ? 0 : drawable.getAlpha(),
                transparentColorPlaceholder);
    }

    private int accentFor(Result<?> result, Drawable drawable) {
        long key = result.getUniqueId();
        Integer cached = accentCache.get(key);
        if (cached != null) return cached;
        // Do not cache this provisional value: on a cold renderer it may come from the generic
        // placeholder. bindRenderedIcon() stores the first verified result-owned drawable.
        return sampleAccent(drawable);
    }

    private int sampleAccent(Drawable drawable) {
        if (drawable == null) return Color.rgb(64, 84, 118);
        Bitmap bitmap = Bitmap.createBitmap(
                ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        int oldLeft = drawable.getBounds().left;
        int oldTop = drawable.getBounds().top;
        int oldRight = drawable.getBounds().right;
        int oldBottom = drawable.getBounds().bottom;
        drawable.setBounds(0, 0, ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE);
        drawable.draw(canvas);
        drawable.setBounds(oldLeft, oldTop, oldRight, oldBottom);

        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int count = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < ACCENT_SAMPLE_SIZE; y++) {
            for (int x = 0; x < ACCENT_SAMPLE_SIZE; x++) {
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
        if (count == 0) return Color.rgb(64, 84, 118);

        int result = Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
        Color.colorToHSV(result, hsv);
        hsv[1] = Math.max(0.34f, Math.min(0.82f, hsv[1]));
        hsv[2] = Math.max(0.40f, Math.min(0.80f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private int tone(int color, float valueMultiplier, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * valueMultiplier));
        return Color.HSVToColor(alpha, hsv);
    }

    private void styleCard(FrameLayout card, float radius, boolean square, int accent) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        tone(accent, 1.28f, square ? 242 : 220),
                        tone(accent, 0.92f, square ? 246 : 226),
                        tone(accent, 0.55f, square ? 250 : 235)
                });
        background.setCornerRadius(radius);
        background.setStroke(dp(square ? 2 : 1), tone(accent, 1.55f, square ? 220 : 175));
        card.setBackground(background);
        card.setElevation(dp(square ? 7 : 4));
        card.setClipToOutline(true);
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

    private void animateHistoryItemIn(View view, int position) {
        if (!SmartAnimationEngine.isEnabled(mainActivity)) return;
        String style = SmartAnimationEngine.getStyle(mainActivity, "smart-animation-scroll", "depth");
        if ("none".equals(style)) return;
        view.animate().cancel();
        view.setAlpha(0f);
        switch (style) {
            case "wave": view.setTranslationY((position % 2 == 0 ? 1 : -1) * dp(14)); break;
            case "slide": view.setTranslationX(dp(24)); break;
            case "zoom": view.setScaleX(0.84f); view.setScaleY(0.84f); break;
            case "tilt": view.setRotationY(position % 2 == 0 ? -12f : 12f); break;
            case "stack": view.setTranslationX(dp(16)); view.setScaleX(0.92f); view.setScaleY(0.92f); break;
            case "cascade": view.setTranslationY(dp(10 + Math.min(28, position * 2))); break;
            case "focus":
            case "depth":
            default: view.setScaleX(0.92f); view.setScaleY(0.92f); view.setTranslationY(dp(9)); break;
        }
        view.animate().alpha(1f).translationX(0f).translationY(0f).rotationY(0f)
                .scaleX(1f).scaleY(1f)
                .setDuration(SmartAnimationEngine.duration(mainActivity))
                .setStartDelay(Math.min(140L, position * 12L))
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void animateNotificationIn(View view, int index) {
        if (!SmartAnimationEngine.isEnabled(mainActivity)) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(8));
        view.setScaleX(0.98f);
        view.setScaleY(0.98f);
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(Math.min(120L, index * 22L))
                .setDuration(SmartAnimationEngine.duration(mainActivity))
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void animateNotificationCenterRefresh() {
        if (!SmartAnimationEngine.isEnabled(mainActivity)
                || notificationScroller.getVisibility() != View.VISIBLE) return;
        notificationScroller.animate().cancel();
        notificationScroller.setAlpha(0.90f);
        notificationScroller.setScaleX(0.99f);
        notificationScroller.setScaleY(0.99f);
        notificationScroller.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Math.max(80L, SmartAnimationEngine.duration(mainActivity) / 2))
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private int resolveTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (mainActivity.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)
                && value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) return value.data;
        return Color.WHITE;
    }

    private Object readPreferenceValue(String key) {
        if (!prefs.contains(key)) return null;
        try { return prefs.getString(key, null); } catch (ClassCastException ignored) { }
        try { return prefs.getInt(key, 0); } catch (ClassCastException ignored) { }
        try { return prefs.getFloat(key, 0f); } catch (ClassCastException ignored) { }
        try { return prefs.getLong(key, 0L); } catch (ClassCastException ignored) { }
        return null;
    }

    private int safePrefInt(String key, int fallback, int min, int max) {
        Object raw = readPreferenceValue(key);
        int value = fallback;
        if (raw instanceof Number) value = Math.round(((Number) raw).floatValue());
        else if (raw instanceof String) {
            try { value = Math.round(Float.parseFloat((String) raw)); }
            catch (NumberFormatException ignored) { value = fallback; }
        }
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }

    private final class SquareTrackLayout extends ViewGroup {
        private final int touchSlop;
        private float downX;
        private float lastX;
        private float rotationOffset;
        private boolean dragging;
        private ValueAnimator settleAnimator;

        SquareTrackLayout() {
            super(mainActivity);
            touchSlop = ViewConfiguration.get(mainActivity).getScaledTouchSlop();
            setClipChildren(false);
            setClipToPadding(false);
            setWillNotDraw(false);
            setCameraDistance(dp(2400));
        }

        void resetForFirstEntry() {
            if (settleAnimator != null) settleAnimator.cancel();
            rotationOffset = 0f;
            requestLayout();
        }

        void onDataRebuilt(boolean refocusBottom) {
            if (refocusBottom) {
                if (settleAnimator != null) settleAnimator.cancel();
                rotationOffset = 0f;
            }
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            int tilePercent = safePrefInt("smart-u-tile-size-percent", 100, 70, 150);
            int baseWidth = dp(SQUARE_CARD_WIDTH_DP) * tilePercent / 100;
            int cardWidth = Math.min(baseWidth, Math.max(dp(88), width - dp(20)));
            int cardHeight = Math.round(cardWidth * (SQUARE_CARD_HEIGHT_DP / (float) SQUARE_CARD_WIDTH_DP));
            int childWidthSpec = MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(childWidthSpec, childHeightSpec);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int count = getChildCount();
            if (count == 0) return;
            int width = r - l;
            int height = b - t;
            int childWidth = getChildAt(0).getMeasuredWidth();
            int childHeight = getChildAt(0).getMeasuredHeight();
            float left = -dp(34);
            float right = Math.max(left, width - childWidth + dp(34));
            float top = dp(28);
            float bottom = Math.max(top, height - childHeight - dp(10));
            float centerX = (left + right) / 2f;
            boolean searching = mainActivity.searchEditText != null
                    && mainActivity.searchEditText.getText().length() > 0;

            for (int i = 0; i < count; i++) {
                int recencyIndex = (count - 1) - i;
                float relative = cyclicRelative(recencyIndex + rotationOffset, count);
                View child = getChildAt(i);
                if (Math.abs(relative) > SQUARE_VISIBLE_RADIUS + 0.35f) {
                    child.setVisibility(View.INVISIBLE);
                    continue;
                }
                child.setVisibility(View.VISIBLE);
                PathPoint point = pointOnOpenU(relative, left, right, top, bottom, centerX, searching);
                int childLeft = Math.round(point.x);
                int childTop = Math.round(point.y);
                child.layout(childLeft, childTop,
                        childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
                child.setPivotX(child.getMeasuredWidth() / 2f);
                child.setPivotY(child.getMeasuredHeight() / 2f);
                child.setCameraDistance(dp(1900));
                child.setRotationY(point.rotationY);
                child.setRotationX(point.rotationX);
                child.setScaleX(point.scale);
                child.setScaleY(point.scale);
                child.setAlpha(point.alpha);
                child.setTranslationZ(point.z);
            }
        }

        private float cyclicRelative(float value, int count) {
            if (count <= 1) return 0f;
            float half = count / 2f;
            while (value > half) value -= count;
            while (value < -half) value += count;
            return value;
        }

        private PathPoint pointOnOpenU(float relative, float left, float right,
                                       float top, float bottom, float centerX, boolean searching) {
            float absolute = Math.abs(relative);
            float sign = relative < 0f ? -1f : 1f;
            if (absolute <= SQUARE_BOTTOM_BAND) {
                float normalized = relative / SQUARE_BOTTOM_BAND;
                float halfSpan = Math.max(1f, (right - left) / 2f);
                float x = centerX + normalized * halfSpan;
                float focus = 1f - Math.min(1f, absolute / (SQUARE_BOTTOM_BAND + 0.25f));
                float scale = (searching ? 0.88f : 0.85f)
                        + ((searching ? 0.17f : 0.16f) * focus);
                float rotationY = -normalized * 18f;
                return new PathPoint(x, bottom, rotationY, 0f, scale, 1f,
                        dp(5) + dp(14) * focus);
            }
            float sideRange = SQUARE_VISIBLE_RADIUS - SQUARE_BOTTOM_BAND;
            float sideProgress = Math.min(1f,
                    (absolute - SQUARE_BOTTOM_BAND) / Math.max(0.01f, sideRange));
            float x = sign > 0f ? right : left;
            float y = bottom - (bottom - top) * sideProgress;
            float rotationY = sign > 0f ? -55f : 55f;
            float scale = 0.86f - (0.08f * sideProgress);
            float alpha = 0.92f - (0.14f * sideProgress);
            return new PathPoint(x, y, rotationY, -2f, scale, alpha,
                    dp(2) + dp(3) * (1f - sideProgress));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(); lastX = downX; dragging = false;
                    if (settleAnimator != null) settleAnimator.cancel();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > touchSlop) {
                        dragging = true; lastX = event.getX(); return true;
                    }
                    return false;
                default: return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(); lastX = downX;
                    if (settleAnimator != null) settleAnimator.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX();
                    float delta = x - lastX;
                    lastX = x;
                    rotationOffset += delta / Math.max(dp(78), getWidth() / 5.5f);
                    requestLayout();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) settleToNearestSlot();
                    dragging = false;
                    return true;
                default: return true;
            }
        }

        private void settleToNearestSlot() {
            float target = Math.round(rotationOffset);
            if (!SmartAnimationEngine.isEnabled(mainActivity)
                    || "none".equals(SmartAnimationEngine.getStyle(
                            mainActivity, "smart-animation-scroll", "depth"))) {
                rotationOffset = target;
                requestLayout();
                return;
            }
            if (Math.abs(target - rotationOffset) < 0.001f) {
                rotationOffset = target;
                requestLayout();
                return;
            }
            settleAnimator = ValueAnimator.ofFloat(rotationOffset, target);
            settleAnimator.setDuration(SmartAnimationEngine.duration(mainActivity));
            settleAnimator.setInterpolator(new DecelerateInterpolator());
            settleAnimator.addUpdateListener(animation -> {
                rotationOffset = (float) animation.getAnimatedValue();
                requestLayout();
            });
            settleAnimator.start();
        }
    }

    private static final class PathPoint {
        final float x;
        final float y;
        final float rotationY;
        final float rotationX;
        final float scale;
        final float alpha;
        final float z;

        PathPoint(float x, float y, float rotationY, float rotationX,
                  float scale, float alpha, float z) {
            this.x = x;
            this.y = y;
            this.rotationY = rotationY;
            this.rotationX = rotationX;
            this.scale = scale;
            this.alpha = alpha;
            this.z = z;
        }
    }
}
