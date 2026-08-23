package fr.neamar.kiss.forwarder;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Deterministic stability layer for Square-U.
 *
 * HistoryDisplayForwarder remains responsible for the carousel and rotation offset. This class
 * normalizes the final visible geometry after layout so legacy/custom resize state cannot create
 * oversized front cards, paper-thin rear cards, runaway offsets, or a notification panel that
 * obscures the launcher controls.
 */
final class SquareUStabilityController {
    private static final String TAG = SquareUStabilityController.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final float BOTTOM_BAND = 2.0f;
    private static final float VISIBLE_RADIUS = 5.6f;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SharedPreferences prefs;

    private ViewGroup squareTrack;
    private ScrollView notificationScroller;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    SquareUStabilityController(MainActivity activity,
                               HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() {
        resolveViews();
        attachObserver();
        refreshSoon();
    }

    void onResume() {
        resolveViews();
        attachObserver();
        refreshSoon();
    }

    void onPause() {
        // No animator or polling loop is owned here.
    }

    void onDataSetChanged() {
        resolveViews();
        attachObserver();
        refreshSoon();
    }

    void onConfigurationChanged() {
        resolveViews();
        refreshSoon();
    }

    void onDestroy() {
        detachObserver();
        squareTrack = null;
        notificationScroller = null;
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void resolveViews() {
        ViewGroup newTrack = readField("squareTrack", ViewGroup.class);
        if (newTrack != squareTrack) {
            detachObserver();
            squareTrack = newTrack;
        }
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

    private void refreshSoon() {
        if (squareTrack != null) squareTrack.post(this::applyStableGeometry);
    }

    private void applyStableGeometry() {
        if (!isUStyle() || squareTrack == null || squareTrack.getChildCount() == 0) return;

        final int width = squareTrack.getWidth();
        final int height = squareTrack.getHeight();
        if (width <= 0 || height <= 0) return;

        final int count = squareTrack.getChildCount();
        final float rotationOffset = readRotationOffset();
        final float centerX = width * 0.50f;
        final float leftCenterX = width * 0.18f;
        final float rightCenterX = width * 0.82f;
        final float topCenterY = height * 0.34f;
        final float bottomCenterY = height * 0.73f;
        final float maxVisualWidth = Math.min(dp(136), width * 0.28f);
        final float maxVisualHeight = Math.min(dp(174), height * 0.18f);

        for (int i = 0; i < count; i++) {
            View card = squareTrack.getChildAt(i);
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
                desiredCenterX = centerX + normalized * (width * 0.30f);
                desiredCenterY = bottomCenterY - Math.abs(normalized) * dp(12);
                focus = 1f - Math.min(1f, absolute / BOTTOM_BAND);
                rotationY = -normalized * 20f;
                depthScale = 0.92f + 0.13f * focus;
            } else {
                float sideProgress = Math.min(1f,
                        (absolute - BOTTOM_BAND) / Math.max(0.01f, VISIBLE_RADIUS - BOTTOM_BAND));
                desiredCenterX = relative < 0f ? leftCenterX : rightCenterX;
                desiredCenterY = bottomCenterY - (bottomCenterY - topCenterY) * sideProgress;
                focus = 0f;
                rotationY = relative < 0f ? 32f : -32f;
                depthScale = 0.90f - 0.10f * sideProgress;
            }

            float normalizer = 1f;
            if (card.getWidth() > 0) normalizer = Math.min(normalizer, maxVisualWidth / card.getWidth());
            if (card.getHeight() > 0) normalizer = Math.min(normalizer, maxVisualHeight / card.getHeight());
            normalizer = Math.min(1f, normalizer);
            float scale = Math.max(0.50f, depthScale * normalizer);

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
                    ? 1f : Math.max(0.72f, 0.94f - 0.18f * ((absolute - BOTTOM_BAND) / (VISIBLE_RADIUS - BOTTOM_BAND))));
            card.setTranslationZ(dp(3) + dp(16) * focus);

            if (card instanceof ViewGroup) stabilizeCardContent((ViewGroup) card);
        }

        stabilizeNotificationPanel(width, height);
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

    private void stabilizeNotificationPanel(int width, int height) {
        if (notificationScroller == null) return;
        int maxWidth = Math.max(dp(150), width - dp(40));
        int panelWidth = Math.min(maxWidth,
                Math.max(dp(150), Math.min(dp(300), Math.round(width * 0.58f))));
        int panelHeight = Math.max(dp(120),
                Math.min(dp(210), Math.round(height * 0.18f)));
        int topMargin = Math.max(dp(90), Math.round(height * 0.42f));

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
                new int[]{Color.argb(232, 38, 43, 54), Color.argb(238, 17, 20, 28), Color.argb(242, 8, 10, 15)});
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

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
