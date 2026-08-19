package fr.neamar.kiss.forwarder;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.TextView;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;

/**
 * Optional alternative presentations for the normal KISS history/results adapter.
 * All modes mirror the existing RecordAdapter instead of creating another data source.
 */
final class HistoryDisplayForwarder extends Forwarder {
    static final String PREF_LAYOUT = "smart-history-layout";
    static final String VERTICAL = "vertical";
    static final String ICONS = "horizontal_icons";
    static final String CARDS = "horizontal_cards";
    static final String NAMES = "horizontal_names";
    static final String SQUARE_U = "square_u";

    private FrameLayout container;
    private HorizontalScrollView scroller;
    private LinearLayout row;
    private FrameLayout squareRoot;
    private SquareTrackLayout squareTrack;
    private LinearLayout notificationCenter;
    private View edgeEffect;
    private String activeMode = VERTICAL;

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

        notificationCenter = new LinearLayout(mainActivity);
        notificationCenter.setOrientation(LinearLayout.VERTICAL);
        notificationCenter.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        notificationCenter.setClipChildren(false);
        notificationCenter.setClipToPadding(false);
        notificationCenter.setPadding(dp(4), dp(4), dp(4), dp(4));

        FrameLayout.LayoutParams notificationParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        notificationParams.leftMargin = dp(58);
        notificationParams.rightMargin = dp(58);
        notificationParams.topMargin = dp(54);
        notificationParams.bottomMargin = dp(82);
        squareRoot.addView(notificationCenter, notificationParams);

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
    }

    private void rebuild() {
        if (mainActivity.adapter == null) return;
        if (SQUARE_U.equals(activeMode)) {
            rebuildSquare();
            return;
        }
        rebuildHorizontal();
    }

    private void rebuildHorizontal() {
        row.removeAllViews();
        final int count = mainActivity.adapter.getCount();
        for (int position = 0; position < count; position++) {
            View source = mainActivity.adapter.getView(position, null, row);
            View tile = createTile(source, activeMode);
            bindResultInteraction(tile, position);
            row.addView(tile);
        }
        scroller.post(() -> scroller.fullScroll(View.FOCUS_RIGHT));
    }

    private void rebuildSquare() {
        squareTrack.removeAllViews();
        notificationCenter.removeAllViews();
        final int count = mainActivity.adapter.getCount();

        for (int position = 0; position < count; position++) {
            View source = mainActivity.adapter.getView(position, null, squareTrack);
            View card = createSquareCard(source);
            bindResultInteraction(card, position);
            squareTrack.addView(card);

            View notificationSource = mainActivity.adapter.getView(position, null, notificationCenter);
            View notificationRow = notificationSource.findViewById(R.id.item_notification_row);
            if (notificationRow != null && notificationRow.getVisibility() == View.VISIBLE) {
                LinearLayout.LayoutParams notificationLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                notificationLp.setMargins(dp(2), dp(2), dp(2), dp(2));
                notificationSource.setAlpha(0.96f);
                notificationCenter.addView(notificationSource, notificationLp);
            }
        }
        squareTrack.resetRotationForNewData();
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
        styleCard(card, dp(18));

        source.setAlpha(0.32f);
        source.setScaleX(1.08f);
        source.setScaleY(1.08f);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewParams.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.addView(source, previewParams);

        addForegroundIconAndName(card, source, dp(58), 15f, dp(20));
        return card;
    }

    private View createSquareCard(View source) {
        FrameLayout card = new FrameLayout(mainActivity);
        styleCard(card, dp(13));
        card.setElevation(dp(3));

        ImageView sourceIcon = source.findViewById(R.id.item_app_icon);
        if (sourceIcon != null && sourceIcon.getDrawable() != null) {
            ImageView icon = new ImageView(mainActivity);
            icon.setImageDrawable(sourceIcon.getDrawable());
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    dp(42), dp(42), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            iconParams.topMargin = dp(6);
            card.addView(icon, iconParams);
        }

        TextView sourceName = source.findViewById(R.id.item_app_name);
        if (sourceName != null) {
            TextView name = new TextView(mainActivity);
            name.setText(sourceName.getText());
            name.setTextColor(Color.WHITE);
            name.setTextSize(10.5f);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(24), Gravity.BOTTOM);
            nameParams.leftMargin = dp(3);
            nameParams.rightMargin = dp(3);
            card.addView(name, nameParams);
        }
        return card;
    }

    private void addForegroundIconAndName(FrameLayout card, View source,
                                           int iconSize, float textSize, int topMargin) {
        ImageView sourceIcon = source.findViewById(R.id.item_app_icon);
        if (sourceIcon != null && sourceIcon.getDrawable() != null) {
            ImageView icon = new ImageView(mainActivity);
            icon.setImageDrawable(sourceIcon.getDrawable());
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    iconSize, iconSize, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            iconParams.topMargin = topMargin;
            card.addView(icon, iconParams);
        }

        TextView sourceName = source.findViewById(R.id.item_app_name);
        if (sourceName != null) {
            TextView name = new TextView(mainActivity);
            name.setText(sourceName.getText());
            name.setTextColor(Color.WHITE);
            name.setTextSize(textSize);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setPadding(dp(8), 0, dp(8), dp(8));
            FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38), Gravity.BOTTOM);
            card.addView(name, nameParams);
        }
    }

    private void styleCard(FrameLayout card, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(96, 24, 24, 24));
        background.setCornerRadius(radius);
        background.setStroke(dp(1), Color.argb(125, 255, 255, 255));
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
     * A continuous inverted square-U track. Children stay clickable; drag interception begins only
     * after touch slop so a normal tap anywhere on a card still launches its result.
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
        }

        void resetRotationForNewData() {
            if (settleAnimator != null) settleAnimator.cancel();
            rotationOffset = 0f;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);

            int count = Math.max(1, getChildCount());
            int baseWidth = Math.round(Math.min(dp(84), Math.max(dp(54), width / 4.7f)));
            int baseHeight = Math.round(baseWidth * 0.82f);
            if (count > 16) {
                baseWidth = Math.max(dp(48), Math.round(baseWidth * 0.86f));
                baseHeight = Math.round(baseWidth * 0.82f);
            }
            int childWidthSpec = MeasureSpec.makeMeasureSpec(baseWidth, MeasureSpec.EXACTLY);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(baseHeight, MeasureSpec.EXACTLY);
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
            float left = dp(8);
            float right = Math.max(left, width - childWidth - dp(8));
            float bottom = Math.max(dp(8), height - childHeight - dp(8));
            float top = dp(8);

            float bottomLength = Math.max(1f, right - left);
            float sideLength = Math.max(1f, bottom - top);
            float totalPath = bottomLength + (2f * sideLength);
            float centerDistance = bottomLength / 2f;
            float spacing = totalPath / Math.max(1, count);

            for (int i = 0; i < count; i++) {
                // Adapter's newest history item is at the end. Keep it at bottom-center by mapping
                // newest -> sequence zero, then distribute older items around the square-U.
                int recencyIndex = (count - 1) - i;
                float signedStep;
                if (recencyIndex == 0) {
                    signedStep = 0f;
                } else {
                    int ring = (recencyIndex + 1) / 2;
                    signedStep = (recencyIndex % 2 == 1 ? 1f : -1f) * ring;
                }
                float distance = centerDistance + ((signedStep + rotationOffset) * spacing);
                PathPoint point = pointOnU(distance, left, right, top, bottom,
                        bottomLength, sideLength, totalPath);

                View child = getChildAt(i);
                int childLeft = Math.round(point.x);
                int childTop = Math.round(point.y);
                child.layout(childLeft, childTop,
                        childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());

                child.setRotation(point.rotation);
                child.setScaleX(point.scale);
                child.setScaleY(point.scale);
                child.setAlpha(point.alpha);
                child.setTranslationZ(dp(2) + (point.scale * dp(4)));
            }
        }

        private PathPoint pointOnU(float distance, float left, float right, float top, float bottom,
                                   float bottomLength, float sideLength, float totalPath) {
            float wrapped = distance % totalPath;
            if (wrapped < 0f) wrapped += totalPath;

            // Path starts at bottom-left -> bottom-right -> right side up -> jump through virtual
            // top edge -> left side down. The virtual wrap is hidden by the side overlap.
            if (wrapped <= bottomLength) {
                float progress = wrapped / bottomLength;
                float centerBias = 1f - Math.abs((progress * 2f) - 1f);
                return new PathPoint(
                        left + (bottomLength * progress),
                        bottom,
                        0f,
                        0.88f + (0.12f * centerBias),
                        0.88f + (0.12f * centerBias));
            }

            float afterBottom = wrapped - bottomLength;
            if (afterBottom <= sideLength) {
                float progress = afterBottom / sideLength;
                return new PathPoint(right, bottom - (sideLength * progress),
                        -4f, 0.82f, 0.82f);
            }

            float progress = (afterBottom - sideLength) / sideLength;
            return new PathPoint(left, top + (sideLength * progress),
                    4f, 0.82f, 0.82f);
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
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX();
                    float delta = x - lastX;
                    lastX = x;
                    rotationOffset += delta / Math.max(dp(52f), getWidth() / 6f);
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
            if (Math.abs(target - rotationOffset) < 0.001f) {
                rotationOffset = target;
                requestLayout();
                return;
            }
            settleAnimator = ValueAnimator.ofFloat(rotationOffset, target);
            settleAnimator.setDuration(210L);
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
        final float rotation;
        final float scale;
        final float alpha;

        PathPoint(float x, float y, float rotation, float scale, float alpha) {
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.scale = scale;
            this.alpha = alpha;
        }
    }
}
