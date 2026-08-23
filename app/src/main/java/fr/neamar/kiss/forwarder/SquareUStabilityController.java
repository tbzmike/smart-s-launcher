package fr.neamar.kiss.forwarder;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.utils.Log;

/**
 * Stable Square-U geometry plus whole-U editing.
 *
 * HistoryDisplayForwarder remains responsible for carousel ordering/rotation. This class owns one
 * bounded U footprint. While the launcher UI is unlocked, long-pressing any U tile opens a
 * widget-style resize frame with eight independent edge/corner handles. The frame changes the U's
 * actual path bounds, not individual card transforms, so resizing cannot recreate the old giant
 * front-card / paper-thin side-card distortion.
 */
final class SquareUStabilityController {
    private static final String TAG = SquareUStabilityController.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";

    private static final String PREF_LEFT = "smart-u-bound-left";
    private static final String PREF_RIGHT = "smart-u-bound-right";
    private static final String PREF_TOP = "smart-u-bound-top";
    private static final String PREF_BOTTOM = "smart-u-bound-bottom";

    private static final float DEFAULT_LEFT = 0.015f;
    private static final float DEFAULT_RIGHT = 0.985f;
    private static final float DEFAULT_TOP = 0.19f;
    private static final float DEFAULT_BOTTOM = 0.90f;
    private static final float MIN_BOUND_WIDTH = 0.34f;
    private static final float MIN_BOUND_HEIGHT = 0.30f;

    private static final float BOTTOM_BAND = 2.55f;
    private static final float VISIBLE_RADIUS = 6.15f;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SharedPreferences prefs;
    private final ScaleGestureDetector scaleDetector;

    private FrameLayout squareRoot;
    private ViewGroup squareTrack;
    private ScrollView notificationScroller;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    private float boundLeft;
    private float boundRight;
    private float boundTop;
    private float boundBottom;
    private boolean scalingGesture;

    private FrameLayout resizeOverlay;
    private TextView doneButton;

    SquareUStabilityController(MainActivity activity,
                               HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        readBounds();
        this.scaleDetector = new ScaleGestureDetector(activity,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        if (!isUStyle() || UiEditLock.isLocked(activity) || resizeOverlay != null) {
                            return false;
                        }
                        scalingGesture = true;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (!scalingGesture || UiEditLock.isLocked(activity)) return false;
                        float factor = detector.getScaleFactor();
                        if (!Float.isFinite(factor) || factor <= 0f) return false;
                        resizeBoundsAroundCenter(factor);
                        applyStableGeometry();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        if (scalingGesture) persistBounds();
                        scalingGesture = false;
                    }
                });
    }

    void onCreate() {
        resolveViews();
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onResume() {
        readBounds();
        resolveViews();
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onPause() {
        scalingGesture = false;
        finishResizeMode(true);
    }

    void onDataSetChanged() {
        resolveViews();
        attachObserver();
        installPinchResize();
        refreshSoon();
    }

    void onConfigurationChanged() {
        finishResizeMode(true);
        resolveViews();
        installPinchResize();
        refreshSoon();
    }

    void onDestroy() {
        finishResizeMode(false);
        detachObserver();
        if (squareTrack != null) squareTrack.setOnTouchListener(null);
        squareRoot = null;
        squareTrack = null;
        notificationScroller = null;
        scalingGesture = false;
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void readBounds() {
        boundLeft = clamp(prefs.getFloat(PREF_LEFT, DEFAULT_LEFT), 0f, 0.95f);
        boundRight = clamp(prefs.getFloat(PREF_RIGHT, DEFAULT_RIGHT), 0.05f, 1f);
        boundTop = clamp(prefs.getFloat(PREF_TOP, DEFAULT_TOP), 0f, 0.95f);
        boundBottom = clamp(prefs.getFloat(PREF_BOTTOM, DEFAULT_BOTTOM), 0.05f, 1f);
        normalizeBounds();
    }

    private void persistBounds() {
        normalizeBounds();
        prefs.edit()
                .putFloat(PREF_LEFT, boundLeft)
                .putFloat(PREF_RIGHT, boundRight)
                .putFloat(PREF_TOP, boundTop)
                .putFloat(PREF_BOTTOM, boundBottom)
                .apply();
    }

    private void normalizeBounds() {
        boundLeft = clamp(boundLeft, 0f, 1f - MIN_BOUND_WIDTH);
        boundRight = clamp(boundRight, boundLeft + MIN_BOUND_WIDTH, 1f);
        boundTop = clamp(boundTop, 0f, 1f - MIN_BOUND_HEIGHT);
        boundBottom = clamp(boundBottom, boundTop + MIN_BOUND_HEIGHT, 1f);
    }

    private void resolveViews() {
        FrameLayout newRoot = readField("squareRoot", FrameLayout.class);
        ViewGroup newTrack = readField("squareTrack", ViewGroup.class);
        if (newTrack != squareTrack) {
            if (squareTrack != null) squareTrack.setOnTouchListener(null);
            detachObserver();
            squareTrack = newTrack;
        }
        squareRoot = newRoot;
        notificationScroller = readField("notificationScroller", ScrollView.class);
    }

    private <T> T readField(String name, Class<T> type) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Square-U field: " + name, e);
            return null;
        }
    }

    private void attachObserver() {
        if (squareTrack == null || layoutListener != null) return;
        layoutListener = this::applyStableGeometry;
        squareTrack.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void detachObserver() {
        if (squareTrack != null && layoutListener != null) {
            ViewTreeObserver observer = squareTrack.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
    }

    /**
     * Pinch remains as a quick symmetrical whole-footprint resize. Long-press resize mode is the
     * precise editor and can move each edge independently.
     */
    private void installPinchResize() {
        if (squareTrack == null) return;
        squareTrack.setOnTouchListener((view, event) -> {
            if (!isUStyle()) return false;
            if (UiEditLock.isLocked(activity)) {
                scalingGesture = false;
                if (resizeOverlay != null) finishResizeMode(true);
                return false;
            }
            if (resizeOverlay != null) return false;
            scaleDetector.onTouchEvent(event);
            boolean multiTouch = event.getPointerCount() > 1;
            boolean consume = scalingGesture || multiTouch;
            if ((event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && !multiTouch) {
                scalingGesture = false;
            }
            return consume;
        });
    }

    private void resizeBoundsAroundCenter(float factor) {
        float cx = (boundLeft + boundRight) * 0.5f;
        float cy = (boundTop + boundBottom) * 0.5f;
        float halfW = (boundRight - boundLeft) * 0.5f * factor;
        float halfH = (boundBottom - boundTop) * 0.5f * factor;
        halfW = clamp(halfW, MIN_BOUND_WIDTH * 0.5f, 0.50f);
        halfH = clamp(halfH, MIN_BOUND_HEIGHT * 0.5f, 0.50f);

        float maxHalfW = Math.min(cx, 1f - cx);
        float maxHalfH = Math.min(cy, 1f - cy);
        halfW = Math.min(halfW, maxHalfW);
        halfH = Math.min(halfH, maxHalfH);

        boundLeft = cx - halfW;
        boundRight = cx + halfW;
        boundTop = cy - halfH;
        boundBottom = cy + halfH;
        normalizeBounds();
    }

    private void refreshSoon() {
        if (squareTrack != null) squareTrack.post(this::applyStableGeometry);
    }

    private void applyStableGeometry() {
        if (!isUStyle() || squareTrack == null || squareTrack.getChildCount() == 0) {
            if (resizeOverlay != null) finishResizeMode(true);
            return;
        }
        if (UiEditLock.isLocked(activity) && resizeOverlay != null) finishResizeMode(true);

        final int width = squareTrack.getWidth();
        final int height = squareTrack.getHeight();
        if (width <= 0 || height <= 0) return;

        final int count = squareTrack.getChildCount();
        final float rotationOffset = readRotationOffset();

        final float outerLeft = width * boundLeft;
        final float outerRight = width * boundRight;
        final float outerTop = height * boundTop;
        final float outerBottom = height * boundBottom;

        final float maxVisualWidth = Math.min(dp(142), width * 0.235f);
        final float maxVisualHeight = Math.min(dp(180), height * 0.175f);
        final float halfCardW = maxVisualWidth * 0.48f;
        final float halfCardH = maxVisualHeight * 0.46f;

        final float leftCenterX = Math.min(outerRight - halfCardW,
                Math.max(halfCardW, outerLeft + halfCardW));
        final float rightCenterX = Math.max(outerLeft + halfCardW,
                Math.min(width - halfCardW, outerRight - halfCardW));
        final float topCenterY = Math.min(outerBottom - halfCardH,
                Math.max(halfCardH, outerTop + halfCardH));
        final float bottomCenterY = Math.max(outerTop + halfCardH,
                Math.min(height - halfCardH, outerBottom - halfCardH));
        final float centerX = (leftCenterX + rightCenterX) * 0.5f;
        final float bottomHalfSpan = Math.max(dp(70),
                (rightCenterX - leftCenterX) * 0.50f);

        for (int i = 0; i < count; i++) {
            View card = squareTrack.getChildAt(i);
            int adapterPosition = i;
            installWholeUResizeTrigger(card, adapterPosition);

            int recencyIndex = (count - 1) - i;
            float relative = cyclicRelative(recencyIndex + rotationOffset, count);
            float absolute = Math.abs(relative);

            if (absolute > VISIBLE_RADIUS) {
                card.setVisibility(View.INVISIBLE);
                continue;
            }
            card.setVisibility(View.VISIBLE);

            float desiredCenterX;
            float desiredCenterY;
            float rotationY;
            float focus;
            float depthScale;

            if (absolute <= BOTTOM_BAND) {
                float normalized = relative / BOTTOM_BAND;
                desiredCenterX = centerX + normalized * bottomHalfSpan;
                desiredCenterY = bottomCenterY - Math.abs(normalized) * dp(18);
                focus = 1f - Math.min(1f, absolute / BOTTOM_BAND);
                rotationY = -normalized * 17f;
                depthScale = 0.94f + 0.11f * focus;
            } else {
                float sideProgress = Math.min(1f,
                        (absolute - BOTTOM_BAND) / Math.max(0.01f,
                                VISIBLE_RADIUS - BOTTOM_BAND));
                desiredCenterX = relative < 0f ? leftCenterX : rightCenterX;
                desiredCenterY = bottomCenterY
                        - (bottomCenterY - topCenterY) * sideProgress;
                focus = 0f;
                rotationY = relative < 0f ? 27f : -27f;
                depthScale = 0.91f - 0.085f * sideProgress;
            }

            float normalizer = 1f;
            if (card.getWidth() > 0) {
                normalizer = Math.min(normalizer, maxVisualWidth / card.getWidth());
            }
            if (card.getHeight() > 0) {
                normalizer = Math.min(normalizer, maxVisualHeight / card.getHeight());
            }
            normalizer = Math.min(1f, normalizer);
            float scale = Math.max(0.54f, depthScale * normalizer);

            float desiredLeft = desiredCenterX - card.getWidth() / 2f;
            float desiredTop = desiredCenterY - card.getHeight() / 2f;
            card.setTranslationX(desiredLeft - card.getLeft());
            card.setTranslationY(desiredTop - card.getTop());
            card.setPivotX(card.getWidth() / 2f);
            card.setPivotY(card.getHeight() / 2f);
            card.setRotationX(0f);
            card.setRotationY(rotationY);
            card.setScaleX(scale);
            card.setScaleY(scale);
            card.setAlpha(absolute <= BOTTOM_BAND
                    ? 1f : Math.max(0.76f, 0.96f - 0.16f
                    * ((absolute - BOTTOM_BAND) / (VISIBLE_RADIUS - BOTTOM_BAND))));
            card.setTranslationZ(dp(3) + dp(16) * focus);

            if (card instanceof ViewGroup) stabilizeCardContent((ViewGroup) card);
        }

        stabilizeNotificationPanel(width, height, topCenterY, bottomCenterY,
                outerLeft, outerRight);
        updateResizeOverlayBounds(width, height);
    }

    private void installWholeUResizeTrigger(View card, int adapterPosition) {
        card.setOnLongClickListener(v -> {
            if (!isUStyle()) return false;
            if (!UiEditLock.isLocked(activity)) {
                beginResizeMode();
                return true;
            }
            if (activity.adapter != null
                    && adapterPosition >= 0 && adapterPosition < activity.adapter.getCount()) {
                activity.adapter.onLongClick(adapterPosition, v);
                return true;
            }
            return false;
        });
    }

    private void beginResizeMode() {
        if (!isUStyle() || UiEditLock.isLocked(activity) || squareRoot == null
                || squareTrack == null) return;
        if (resizeOverlay != null) {
            updateResizeOverlayBounds(squareTrack.getWidth(), squareTrack.getHeight());
            resizeOverlay.bringToFront();
            return;
        }

        resizeOverlay = new FrameLayout(activity);
        resizeOverlay.setClipChildren(false);
        resizeOverlay.setClipToPadding(false);
        resizeOverlay.setElevation(dp(50));
        resizeOverlay.setContentDescription("Resize Smart U");

        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.argb(20, 90, 135, 255));
        border.setCornerRadius(dp(18));
        border.setStroke(dp(2), Color.rgb(112, 158, 255));
        resizeOverlay.setBackground(border);

        addResizeHandle(Gravity.LEFT | Gravity.TOP, -1, -1);
        addResizeHandle(Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, -1);
        addResizeHandle(Gravity.RIGHT | Gravity.TOP, 1, -1);
        addResizeHandle(Gravity.LEFT | Gravity.CENTER_VERTICAL, -1, 0);
        addResizeHandle(Gravity.RIGHT | Gravity.CENTER_VERTICAL, 1, 0);
        addResizeHandle(Gravity.LEFT | Gravity.BOTTOM, -1, 1);
        addResizeHandle(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 1);
        addResizeHandle(Gravity.RIGHT | Gravity.BOTTOM, 1, 1);

        doneButton = new TextView(activity);
        doneButton.setText("✓");
        doneButton.setTextColor(Color.WHITE);
        doneButton.setTextSize(18f);
        doneButton.setGravity(Gravity.CENTER);
        doneButton.setContentDescription("Finish resizing Smart U");
        doneButton.setElevation(dp(54));
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setShape(GradientDrawable.OVAL);
        doneBg.setColor(Color.rgb(32, 42, 67));
        doneBg.setStroke(dp(2), Color.rgb(112, 158, 255));
        doneButton.setBackground(doneBg);
        doneButton.setOnClickListener(v -> finishResizeMode(true));
        FrameLayout.LayoutParams doneLp = new FrameLayout.LayoutParams(
                dp(38), dp(38), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        doneLp.topMargin = dp(10);
        resizeOverlay.addView(doneButton, doneLp);

        squareRoot.addView(resizeOverlay, new FrameLayout.LayoutParams(dp(100), dp(100)));
        updateResizeOverlayBounds(squareTrack.getWidth(), squareTrack.getHeight());
        resizeOverlay.bringToFront();
        Toast.makeText(activity, "Resize Smart U • drag edges or corners • tap ✓ when done",
                Toast.LENGTH_SHORT).show();
    }

    private void addResizeHandle(int gravity, int horizontalDirection, int verticalDirection) {
        if (resizeOverlay == null) return;
        View handle = new View(activity);
        handle.setContentDescription("Resize Smart U");
        handle.setElevation(dp(55));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(Color.rgb(235, 240, 255));
        drawable.setStroke(dp(2), Color.rgb(80, 125, 245));
        handle.setBackground(drawable);

        int size = dp(28);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, gravity);
        lp.setMargins(-dp(4), -dp(4), -dp(4), -dp(4));
        resizeOverlay.addView(handle, lp);
        handle.setOnTouchListener(new BoundResizeTouchListener(
                horizontalDirection, verticalDirection));
    }

    private void updateResizeOverlayBounds(int width, int height) {
        if (resizeOverlay == null || width <= 0 || height <= 0) return;
        int left = Math.round(width * boundLeft);
        int right = Math.round(width * boundRight);
        int top = Math.round(height * boundTop);
        int bottom = Math.round(height * boundBottom);
        int overlayWidth = Math.max(dp(120), right - left);
        int overlayHeight = Math.max(dp(120), bottom - top);

        ViewGroup.LayoutParams raw = resizeOverlay.getLayoutParams();
        FrameLayout.LayoutParams lp = raw instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) raw
                : new FrameLayout.LayoutParams(overlayWidth, overlayHeight);
        if (lp.width != overlayWidth || lp.height != overlayHeight) {
            lp.width = overlayWidth;
            lp.height = overlayHeight;
            resizeOverlay.setLayoutParams(lp);
        }
        resizeOverlay.setX(left);
        resizeOverlay.setY(top);
        resizeOverlay.bringToFront();
    }

    private void finishResizeMode(boolean save) {
        if (save) persistBounds();
        if (resizeOverlay != null && resizeOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) resizeOverlay.getParent()).removeView(resizeOverlay);
        }
        resizeOverlay = null;
        doneButton = null;
    }

    private void stabilizeCardContent(ViewGroup card) {
        card.setClipChildren(true);
        card.setClipToPadding(true);
        for (int i = card.getChildCount() - 1; i >= 0; i--) {
            View child = card.getChildAt(i);
            if (child instanceof TextView) {
                TextView label = (TextView) child;
                if (TextUtils.isEmpty(label.getText())) continue;
                label.setVisibility(View.VISIBLE);
                label.setSingleLine(true);
                label.setMaxLines(1);
                label.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                label.setMarqueeRepeatLimit(-1);
                label.setHorizontallyScrolling(true);
                label.setSelected(activity.hasWindowFocus());
                label.setFocusable(false);
                label.setFocusableInTouchMode(false);
                label.bringToFront();
                break;
            }
        }
    }

    private void stabilizeNotificationPanel(int width, int height,
                                            float topCenterY, float bottomCenterY,
                                            float outerLeft, float outerRight) {
        if (notificationScroller == null) return;

        int innerWidth = Math.max(dp(180), Math.round(outerRight - outerLeft));
        int panelWidth = Math.min(Math.max(dp(220), Math.round(innerWidth * 0.48f)),
                Math.min(dp(340), width - dp(60)));
        int availableInnerHeight = Math.max(dp(150), Math.round(bottomCenterY - topCenterY));
        int panelHeight = Math.min(dp(220),
                Math.max(dp(150), Math.round(availableInnerHeight * 0.42f)));
        int topMargin = Math.round(topCenterY
                + (bottomCenterY - topCenterY) * 0.34f);

        ViewGroup.LayoutParams raw = notificationScroller.getLayoutParams();
        boolean needsLayout = !(raw instanceof FrameLayout.LayoutParams);
        FrameLayout.LayoutParams lp;
        if (raw instanceof FrameLayout.LayoutParams) {
            lp = (FrameLayout.LayoutParams) raw;
            needsLayout = lp.width != panelWidth || lp.height != panelHeight
                    || lp.gravity != (Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    || lp.topMargin != topMargin;
        } else {
            lp = new FrameLayout.LayoutParams(panelWidth, panelHeight);
        }
        if (needsLayout) {
            lp.width = panelWidth;
            lp.height = panelHeight;
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = topMargin;
            lp.bottomMargin = 0;
            notificationScroller.setLayoutParams(lp);
        }

        notificationScroller.setScaleX(1f);
        notificationScroller.setScaleY(1f);
        notificationScroller.setTranslationX(0f);
        notificationScroller.setTranslationY(0f);
        notificationScroller.setElevation(dp(10));

        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(232, 38, 43, 54),
                        Color.argb(238, 17, 20, 28), Color.argb(242, 8, 10, 15)});
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), Color.argb(210, 205, 218, 242));
        notificationScroller.setBackground(background);
    }

    private float readRotationOffset() {
        if (squareTrack == null) return 0f;
        try {
            Field field = squareTrack.getClass().getDeclaredField("rotationOffset");
            field.setAccessible(true);
            return field.getFloat(squareTrack);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return 0f;
        }
    }

    private float cyclicRelative(float value, int count) {
        if (count <= 1) return 0f;
        float half = count / 2f;
        while (value > half) value -= count;
        while (value < -half) value += count;
        return value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class BoundResizeTouchListener implements View.OnTouchListener {
        private final int horizontalDirection;
        private final int verticalDirection;
        private float startRawX;
        private float startRawY;
        private float startLeft;
        private float startRight;
        private float startTop;
        private float startBottom;

        BoundResizeTouchListener(int horizontalDirection, int verticalDirection) {
            this.horizontalDirection = horizontalDirection;
            this.verticalDirection = verticalDirection;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (squareTrack == null || UiEditLock.isLocked(activity)) {
                finishResizeMode(true);
                return false;
            }
            int width = Math.max(1, squareTrack.getWidth());
            int height = Math.max(1, squareTrack.getHeight());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startLeft = boundLeft;
                    startRight = boundRight;
                    startTop = boundTop;
                    startBottom = boundBottom;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = (event.getRawX() - startRawX) / width;
                    float dy = (event.getRawY() - startRawY) / height;
                    if (horizontalDirection < 0) {
                        boundLeft = clamp(startLeft + dx, 0f,
                                boundRight - MIN_BOUND_WIDTH);
                    } else if (horizontalDirection > 0) {
                        boundRight = clamp(startRight + dx,
                                boundLeft + MIN_BOUND_WIDTH, 1f);
                    }
                    if (verticalDirection < 0) {
                        boundTop = clamp(startTop + dy, 0f,
                                boundBottom - MIN_BOUND_HEIGHT);
                    } else if (verticalDirection > 0) {
                        boundBottom = clamp(startBottom + dy,
                                boundTop + MIN_BOUND_HEIGHT, 1f);
                    }
                    normalizeBounds();
                    applyStableGeometry();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    persistBounds();
                    applyStableGeometry();
                    return true;

                default:
                    return true;
            }
        }
    }
}
