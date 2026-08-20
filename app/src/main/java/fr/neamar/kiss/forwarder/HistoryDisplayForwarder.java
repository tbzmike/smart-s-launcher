package fr.neamar.kiss.forwarder;

import android.animation.ValueAnimator;
import android.graphics.Color;
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

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.SmartAnimationEngine;

/**
 * Optional presentations for the normal KISS history/results adapter.
 *
 * The existing RecordAdapter remains the single source of truth. This class changes presentation
 * only, so launching, frozen apps, settings entries, shortcuts and notification actions continue
 * through the normal KISS/Smart S result pipeline.
 */
final class HistoryDisplayForwarder extends Forwarder {
    static final String PREF_LAYOUT = "smart-history-layout";
    static final String VERTICAL = "vertical";
    static final String ICONS = "horizontal_icons";
    static final String CARDS = "horizontal_cards";
    static final String NAMES = "horizontal_names";
    static final String SQUARE_U = "square_u";

    private static final int CARD_WIDTH_DP = 190;
    private static final int CARD_HEIGHT_DP = 132;

    // The approved Square-U visual is intentionally portrait-like and spacious. Only a window of
    // history is visible at once; the rest appears as the carousel rotates.
    private static final int SQUARE_CARD_WIDTH_DP = 124;
    private static final int SQUARE_CARD_HEIGHT_DP = 158;
    private static final float SQUARE_VISIBLE_RADIUS = 6.15f;
    private static final float SQUARE_BOTTOM_BAND = 2.05f;

    private FrameLayout container;
    private HorizontalScrollView scroller;
    private LinearLayout row;
    private FrameLayout squareRoot;
    private SquareTrackLayout squareTrack;
    private ScrollView notificationScroller;
    private LinearLayout notificationCenter;
    private View edgeEffect;
    private String activeMode = VERTICAL;
    private boolean squareHasBeenEntered;
    private String lastSquareQuery = "";
    private long lastSquarePriorityId = Long.MIN_VALUE;

    HistoryDisplayForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        if (!(mainActivity.listContainer instanceof FrameLayout)) return;
        container = (FrameLayout) mainActivity.listContainer;
        edgeEffect = mainActivity.findViewById(R.id.listEdgeEffect);
        createHorizontalRenderer();
        createSquareRenderer();
        applyMode(true);
    }

    void onResume() {
        applyMode(false);
    }

    void onDataSetChanged() {
        if (!VERTICAL.equals(activeMode)) rebuild();
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

        FrameLayout.LayoutParams notificationParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(286), Gravity.CENTER);
        // Keep the middle panel compact and leave the 3D side cards clearly visible.
        notificationParams.leftMargin = dp(58);
        notificationParams.rightMargin = dp(58);
        notificationParams.topMargin = dp(80);
        notificationParams.bottomMargin = dp(150);
        squareRoot.addView(notificationScroller, notificationParams);

        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM);
        container.addView(squareRoot, rootParams);
    }

    private void applyMode(boolean force) {
        if (container == null) return;
        String requested = prefs.getString(PREF_LAYOUT, VERTICAL);
        if (requested == null) requested = VERTICAL;
        if (!force && requested.equals(activeMode)) return;

        activeMode = requested;
        boolean vertical = VERTICAL.equals(activeMode);
        boolean square = SQUARE_U.equals(activeMode);
        boolean horizontal = !vertical && !square;

        mainActivity.list.setVisibility(vertical ? View.VISIBLE : View.GONE);
        if (edgeEffect != null) edgeEffect.setVisibility(vertical ? View.VISIBLE : View.GONE);
        scroller.setVisibility(horizontal ? View.VISIBLE : View.GONE);
        squareRoot.setVisibility(square ? View.VISIBLE : View.GONE);

        if (!vertical) rebuild();

        View incoming = vertical ? mainActivity.list : (square ? squareRoot : scroller);
        if (incoming != null && incoming.getVisibility() == View.VISIBLE) {
            SmartAnimationEngine.animateWindowSwitch(null, incoming);
        }

        if (square && !squareHasBeenEntered) {
            squareTrack.resetForFirstEntry();
            squareHasBeenEntered = true;
        }
    }

    private void rebuild() {
        if (mainActivity.adapter == null) return;
        if (SQUARE_U.equals(activeMode)) {
            rebuildSquare();
        } else {
            rebuildHorizontal();
        }
    }

    private void rebuildHorizontal() {
        row.removeAllViews();
        final int count = mainActivity.adapter.getCount();
        for (int position = 0; position < count; position++) {
            View source = mainActivity.adapter.getView(position, null, row);
            View tile = createTile(source, activeMode);
            bindResultInteraction(tile, position);
            row.addView(tile);
            animateHistoryItemIn(tile, position);
        }
        scroller.post(() -> scroller.fullScroll(View.FOCUS_RIGHT));
    }

    private void rebuildSquare() {
        final int count = mainActivity.adapter.getCount();
        String currentQuery = mainActivity.searchEditText == null
                ? ""
                : mainActivity.searchEditText.getText().toString().trim();
        long currentPriorityId = count > 0
                ? mainActivity.adapter.getItem(count - 1).getUniqueId()
                : Long.MIN_VALUE;
        boolean refocusBottom = !currentQuery.equals(lastSquareQuery)
                || currentPriorityId != lastSquarePriorityId;

        squareTrack.removeAllViews();
        notificationCenter.removeAllViews();

        int visibleNotifications = 0;

        for (int position = 0; position < count; position++) {
            // Build each adapter result only once. The previous Square-U created a second full view
            // per result just to inspect notifications, which repeated async icon binding work.
            View source = mainActivity.adapter.getView(position, null, squareTrack);
            View notificationRow = source.findViewById(R.id.item_notification_row);
            boolean hasNotification = notificationRow != null
                    && notificationRow.getVisibility() == View.VISIBLE;

            View card = createSquareCard(source, hasNotification);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notificationCenter.addView(title, lp);
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

        notificationSource.setAlpha(1f);
        notificationSource.setScaleX(0.92f);
        notificationSource.setScaleY(0.92f);
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

    private View createTile(View source, String mode) {
        if (ICONS.equals(mode)) return createIconTile(source);
        if (NAMES.equals(mode)) return createNameTile(source);
        if (CARDS.equals(mode)) return createCardTile(source);
        return source;
    }

    private View createIconTile(View source) {
        FrameLayout tile = baseTile(dp(112), dp(102));
        Drawable iconDrawable = extractIcon(source);
        CharSequence label = extractLabel(source);

        if (iconDrawable != null) {
            ImageView icon = new ImageView(mainActivity);
            icon.setImageDrawable(iconDrawable);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    dp(52), dp(52), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            iconParams.topMargin = dp(5);
            tile.addView(icon, iconParams);
        }

        addFullLabel(tile, label, 12f, dp(44), 2);
        return tile;
    }

    private View createNameTile(View source) {
        FrameLayout tile = baseTile(dp(190), dp(76));
        TextView name = new TextView(mainActivity);
        name.setText(extractLabel(source));
        name.setTextColor(resolveTextColor());
        name.setTextSize(16f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(3);
        name.setSingleLine(false);
        name.setEllipsize(null);
        name.setPadding(dp(10), dp(7), dp(10), dp(7));
        tile.addView(name, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return tile;
    }

    private View createCardTile(View source) {
        FrameLayout card = baseTile(dp(CARD_WIDTH_DP), dp(CARD_HEIGHT_DP));
        styleCard(card, dp(18), false);

        Drawable iconDrawable = extractIcon(source);
        CharSequence label = extractLabel(source);
        addMutedPreview(card, source);
        addForegroundIconAndLabel(card, iconDrawable, label, dp(58), 14f, dp(14));
        return card;
    }

    /**
     * Square-U cards keep a live relationship with the adapter's icon view. AppResult loads many
     * icons asynchronously, so copying a drawable only once can leave a permanent blank card.
     */
    private View createSquareCard(View source, boolean preserveSourceForNotification) {
        FrameLayout card = new FrameLayout(mainActivity);
        styleCard(card, dp(18), true);
        card.setElevation(dp(4));
        card.setClipChildren(false);
        card.setClipToPadding(false);

        CharSequence label = extractLabel(source);
        ImageView sourceIcon = findIconView(source);

        if (sourceIcon != null && !preserveSourceForNotification) {
            // Reparent the real ImageView. Async drawable delivery now updates the card directly.
            if (sourceIcon.getParent() instanceof ViewGroup) {
                ((ViewGroup) sourceIcon.getParent()).removeView(sourceIcon);
            }
            sourceIcon.setClickable(false);
            sourceIcon.setFocusable(false);
            sourceIcon.setAlpha(1f);
            sourceIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    dp(72), dp(72), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            iconParams.topMargin = dp(15);
            card.addView(sourceIcon, iconParams);
        } else if (sourceIcon != null) {
            // Notification rows must retain their original icon. Mirror it without forcing a second
            // adapter bind, then refresh after the normal async icon loader has had time to finish.
            ImageView mirror = new ImageView(mainActivity);
            mirror.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mirror.setAlpha(1f);
            syncMirroredIcon(mirror, sourceIcon);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    dp(72), dp(72), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            iconParams.topMargin = dp(15);
            card.addView(mirror, iconParams);
            scheduleIconMirror(card, mirror, sourceIcon);
        }

        addFullLabel(card, label, 14f, dp(58), 3);
        return card;
    }

    private void scheduleIconMirror(View owner, ImageView target, ImageView source) {
        owner.post(() -> syncMirroredIcon(target, source));
        owner.postDelayed(() -> syncMirroredIcon(target, source), 90L);
        owner.postDelayed(() -> syncMirroredIcon(target, source), 260L);
        owner.postDelayed(() -> syncMirroredIcon(target, source), 700L);
    }

    private void syncMirroredIcon(ImageView target, ImageView source) {
        Drawable drawable = source.getDrawable();
        if (drawable != null && target.getDrawable() != drawable) {
            target.setImageDrawable(drawable);
        }
    }

    private void addMutedPreview(FrameLayout card, View source) {
        source.setAlpha(0.28f);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewParams.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.addView(source, previewParams);
    }

    private void addForegroundIconAndLabel(FrameLayout card, Drawable iconDrawable,
                                           CharSequence label, int iconSize,
                                           float textSize, int topMargin) {
        if (iconDrawable != null) {
            ImageView icon = new ImageView(mainActivity);
            icon.setImageDrawable(iconDrawable);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    iconSize, iconSize, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            iconParams.topMargin = topMargin;
            card.addView(icon, iconParams);
        }
        addFullLabel(card, label, textSize, dp(52), 3);
    }

    private void addFullLabel(FrameLayout card, CharSequence label, float textSize,
                              int labelHeight, int maxLines) {
        TextView name = new TextView(mainActivity);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setAlpha(1f);
        name.setTextSize(textSize);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(maxLines);
        name.setSingleLine(false);
        name.setEllipsize(null);
        name.setHorizontallyScrolling(false);
        name.setPadding(dp(6), dp(2), dp(6), dp(5));
        name.setShadowLayer(dp(2), 0f, dp(1), Color.BLACK);

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
        if (text == null || text.getVisibility() != View.VISIBLE || TextUtils.isEmpty(text.getText())) {
            return false;
        }
        int id = text.getId();
        if (id == R.id.item_notification_text || id == R.id.item_notification_read) return false;
        String normalized = text.getText().toString().trim();
        return !normalized.isEmpty()
                && !"Mark read".equalsIgnoreCase(normalized)
                && !"Open notification".equalsIgnoreCase(normalized)
                && !"Reply".equalsIgnoreCase(normalized);
    }

    private Drawable extractIcon(View source) {
        ImageView iconView = findIconView(source);
        return iconView == null ? null : iconView.getDrawable();
    }

    private ImageView findIconView(View source) {
        ImageView appIcon = source.findViewById(R.id.item_app_icon);
        if (appIcon != null) return appIcon;
        return findFirstVisibleImage(source);
    }

    private ImageView findFirstVisibleImage(View view) {
        if (view instanceof ImageView && view.getVisibility() == View.VISIBLE) {
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

    private void styleCard(FrameLayout card, float radius, boolean square) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(square ? Color.argb(238, 22, 24, 30) : Color.argb(112, 22, 22, 24));
        background.setCornerRadius(radius);
        background.setStroke(dp(1), square
                ? Color.argb(205, 218, 228, 242)
                : Color.argb(135, 255, 255, 255));
        card.setBackground(background);
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
        String style = SmartAnimationEngine.getStyle(
                mainActivity, "smart-animation-scroll", "depth");
        if ("none".equals(style)) return;

        view.animate().cancel();
        view.setAlpha(0f);
        switch (style) {
            case "wave":
                view.setTranslationY((position % 2 == 0 ? 1 : -1) * dp(14));
                break;
            case "slide":
                view.setTranslationX(dp(24));
                break;
            case "zoom":
                view.setScaleX(0.84f);
                view.setScaleY(0.84f);
                break;
            case "tilt":
                view.setRotationY(position % 2 == 0 ? -12f : 12f);
                break;
            case "stack":
                view.setTranslationX(dp(16));
                view.setScaleX(0.92f);
                view.setScaleY(0.92f);
                break;
            case "cascade":
                view.setTranslationY(dp(10 + Math.min(28, position * 2)));
                break;
            case "focus":
            case "depth":
            default:
                view.setScaleX(0.92f);
                view.setScaleY(0.92f);
                view.setTranslationY(dp(9));
                break;
        }

        view.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .rotationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SmartAnimationEngine.duration(mainActivity))
                .setStartDelay(Math.min(140L, position * 12L))
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateNotificationIn(View view, int index) {
        if (!SmartAnimationEngine.isEnabled(mainActivity)) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(8));
        view.setScaleX(0.98f);
        view.setScaleY(0.98f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(Math.min(120L, index * 22L))
                .setDuration(SmartAnimationEngine.duration(mainActivity))
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateNotificationCenterRefresh() {
        if (!SmartAnimationEngine.isEnabled(mainActivity)
                || notificationScroller.getVisibility() != View.VISIBLE) return;
        notificationScroller.animate().cancel();
        notificationScroller.setAlpha(0.90f);
        notificationScroller.setScaleX(0.99f);
        notificationScroller.setScaleY(0.99f);
        notificationScroller.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(Math.max(80L, SmartAnimationEngine.duration(mainActivity) / 2))
                .setInterpolator(new DecelerateInterpolator())
                .start();
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

    /**
     * Spacious 3D Square-U carousel. A fixed number of cards are visible at once; older history is
     * intentionally kept off-track until the user scrolls. This prevents the crowding caused by
     * dividing the entire perimeter by the full history count.
     */
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

            int cardWidth = Math.min(dp(SQUARE_CARD_WIDTH_DP), Math.max(dp(88), width - dp(20)));
            int cardHeight = Math.round(cardWidth
                    * (SQUARE_CARD_HEIGHT_DP / (float) SQUARE_CARD_WIDTH_DP));

            int childWidthSpec = MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(childWidthSpec, childHeightSpec);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int count = getChildCount();
            if (count == 0) return;

            int width = r - l;
            int height = b - t;
            int childWidth = getChildAt(0).getMeasuredWidth();
            int childHeight = getChildAt(0).getMeasuredHeight();

            // Side cards are deliberately allowed to overhang their layout bounds. Their 3D Y
            // rotation narrows the visible footprint, so this makes the rendered U reach the real
            // left/right screen edges instead of looking pinched inward.
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
                        childLeft + child.getMeasuredWidth(),
                        childTop + child.getMeasuredHeight());

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
                                       float top, float bottom, float centerX,
                                       boolean searching) {
            float absolute = Math.abs(relative);
            float sign = relative < 0f ? -1f : 1f;

            // The bottom/front band is the priority zone. During search the adapter is already
            // relevance-ordered, with its highest-relevance result at the end; recency history uses
            // the same end-of-list anchor. Rebuild refocusing keeps that priority item at center.
            if (absolute <= SQUARE_BOTTOM_BAND) {
                float normalized = relative / SQUARE_BOTTOM_BAND;
                float halfSpan = Math.max(1f, (right - left) / 2f);
                float x = centerX + normalized * halfSpan;
                float focus = 1f - Math.min(1f, absolute / (SQUARE_BOTTOM_BAND + 0.25f));
                float scale = (searching ? 0.88f : 0.85f)
                        + ((searching ? 0.17f : 0.16f) * focus);
                float alpha = 1f;
                float rotationY = -normalized * 18f;
                return new PathPoint(x, bottom, rotationY, 0f, scale, alpha,
                        dp(5) + dp(14) * focus);
            }

            // Older/non-priority items keep the 3D depth by becoming progressively dimmer and
            // slightly smaller as they climb the two sides.
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
                    downX = event.getX();
                    lastX = downX;
                    dragging = false;
                    if (settleAnimator != null) settleAnimator.cancel();
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > touchSlop) {
                        dragging = true;
                        lastX = event.getX();
                        return true;
                    }
                    return false;

                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    lastX = downX;
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

                default:
                    return true;
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
