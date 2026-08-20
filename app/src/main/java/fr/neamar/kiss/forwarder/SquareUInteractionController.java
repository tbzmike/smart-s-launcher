package fr.neamar.kiss.forwarder;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Adds direct, widget-like resizing controls to the Square-U history layout without replacing
 * the existing Settings sliders. The controls write the same preferences as Settings so both
 * entry points always stay in sync.
 *
 * This class also replaces Square-U's presentation label with an always-marquee copy. The full
 * source label is preserved and is allowed to scroll instead of being permanently truncated.
 */
final class SquareUInteractionController {
    private static final String TAG = SquareUInteractionController.class.getSimpleName();

    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String PREF_TILE_SIZE = "smart-u-tile-size-percent";
    private static final String PREF_ICON_SIZE = "smart-u-icon-size-percent";
    private static final String PREF_NOTIFICATION_SIZE = "smart-u-notification-panel-size-percent";

    private static final int KIND_TILE = 1;
    private static final int KIND_ICON = 2;
    private static final int KIND_NOTIFICATION = 3;
    private static final long LIVE_UPDATE_INTERVAL_MS = 32L;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SharedPreferences prefs;
    private final Set<View> configuredCards = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<FullMarqueeTextView> activeLabels = Collections.newSetFromMap(new WeakHashMap<>());

    private ViewGroup squareTrack;
    private ScrollView notificationScroller;
    private FrameLayout squareRoot;
    private View notificationResizeHandle;
    private Method applyNotificationPanelSizingMethod;
    private ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;

    SquareUInteractionController(MainActivity activity,
                                 HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() {
        resolveSquareUViews();
        attachObserver();
        refreshSoon();
    }

    void onResume() {
        resolveSquareUViews();
        setMarqueeEnabled(true);
        attachObserver();
        refreshSoon();
    }

    void onPause() {
        setMarqueeEnabled(false);
    }

    void onDataSetChanged() {
        refreshSoon();
    }

    void onDestroy() {
        detachObserver();
        configuredCards.clear();
        activeLabels.clear();
        notificationResizeHandle = null;
        squareTrack = null;
        notificationScroller = null;
        squareRoot = null;
    }

    private void resolveSquareUViews() {
        ViewGroup newTrack = readField("squareTrack", ViewGroup.class);
        ScrollView newNotificationScroller = readField("notificationScroller", ScrollView.class);
        FrameLayout newRoot = readField("squareRoot", FrameLayout.class);

        if (squareTrack != newTrack) {
            detachObserver();
            squareTrack = newTrack;
            configuredCards.clear();
        }
        notificationScroller = newNotificationScroller;
        squareRoot = newRoot;

        if (applyNotificationPanelSizingMethod == null) {
            try {
                applyNotificationPanelSizingMethod = HistoryDisplayForwarder.class
                        .getDeclaredMethod("applyNotificationPanelSizing");
                applyNotificationPanelSizingMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "Unable to resolve Square-U notification sizing method", e);
            }
        }
    }

    private <T> T readField(String name, Class<T> expectedType) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return expectedType.isInstance(value) ? expectedType.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Square-U field: " + name, e);
            return null;
        }
    }

    private void attachObserver() {
        if (squareTrack == null || globalLayoutListener != null) return;
        globalLayoutListener = this::refreshNow;
        squareTrack.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
    }

    private void detachObserver() {
        if (squareTrack != null && globalLayoutListener != null) {
            ViewTreeObserver observer = squareTrack.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(globalLayoutListener);
        }
        globalLayoutListener = null;
    }

    private void refreshSoon() {
        if (squareTrack != null) squareTrack.post(this::refreshNow);
    }

    private void refreshNow() {
        if (squareTrack == null || !SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"))) {
            updateNotificationHandleVisibility();
            return;
        }

        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (!(card instanceof FrameLayout) || configuredCards.contains(card)) continue;
            configureCard((FrameLayout) card);
            configuredCards.add(card);
        }

        ensureNotificationResizeHandle();
        positionNotificationResizeHandle();
    }

    private void configureCard(FrameLayout card) {
        replaceLabelWithFullMarquee(card);

        TextView iconHandle = createResizeHandle("I", "Resize Square-U icons");
        FrameLayout.LayoutParams iconHandleParams = new FrameLayout.LayoutParams(
                dp(25), dp(25), Gravity.TOP | Gravity.END);
        iconHandleParams.topMargin = dp(5);
        iconHandleParams.rightMargin = dp(5);
        card.addView(iconHandle, iconHandleParams);
        iconHandle.setOnTouchListener(new ResizeTouchListener(KIND_ICON));

        TextView tileHandle = createResizeHandle("T", "Resize Square-U tiles");
        FrameLayout.LayoutParams tileHandleParams = new FrameLayout.LayoutParams(
                dp(25), dp(25), Gravity.BOTTOM | Gravity.END);
        tileHandleParams.bottomMargin = dp(7);
        tileHandleParams.rightMargin = dp(5);
        card.addView(tileHandle, tileHandleParams);
        tileHandle.setOnTouchListener(new ResizeTouchListener(KIND_TILE));
    }

    private void replaceLabelWithFullMarquee(FrameLayout card) {
        TextView original = null;
        for (int i = card.getChildCount() - 1; i >= 0; i--) {
            View child = card.getChildAt(i);
            if (!(child instanceof TextView) || child instanceof FullMarqueeTextView) continue;
            TextView candidate = (TextView) child;
            if (!TextUtils.isEmpty(candidate.getText())) {
                original = candidate;
                break;
            }
        }
        if (original == null) return;

        FullMarqueeTextView full = new FullMarqueeTextView();
        full.setText(original.getText());
        full.setContentDescription(original.getText());
        full.setTextColor(original.getCurrentTextColor());
        full.setTextSize(TypedValue.COMPLEX_UNIT_PX, original.getTextSize());
        full.setGravity(original.getGravity());
        full.setPadding(original.getPaddingLeft(), original.getPaddingTop(),
                original.getPaddingRight(), original.getPaddingBottom());
        full.setShadowLayer(original.getShadowRadius(), original.getShadowDx(),
                original.getShadowDy(), original.getShadowColor());
        full.setSingleLine(true);
        full.setMaxLines(1);
        full.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        full.setMarqueeRepeatLimit(-1);
        full.setHorizontallyScrolling(true);
        full.setSelected(true);
        full.setFocusable(false);
        full.setFocusableInTouchMode(false);
        full.setClickable(false);
        full.setHorizontalFadingEdgeEnabled(true);
        full.setFadingEdgeLength(dp(12));

        Drawable originalBackground = original.getBackground();
        if (originalBackground != null) {
            Drawable.ConstantState state = originalBackground.getConstantState();
            full.setBackground(state == null ? originalBackground : state.newDrawable().mutate());
        }

        ViewGroup.LayoutParams rawParams = original.getLayoutParams();
        FrameLayout.LayoutParams params;
        if (rawParams instanceof FrameLayout.LayoutParams) {
            params = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) rawParams);
        } else {
            params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42), Gravity.BOTTOM);
            params.leftMargin = dp(5);
            params.rightMargin = dp(5);
            params.bottomMargin = dp(5);
        }

        original.setVisibility(View.GONE);
        card.addView(full, params);
        activeLabels.add(full);
    }

    private TextView createResizeHandle(String glyph, String contentDescription) {
        TextView handle = new TextView(activity);
        handle.setText(glyph);
        handle.setTextColor(Color.WHITE);
        handle.setTextSize(11f);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(contentDescription);
        handle.setClickable(true);
        handle.setFocusable(true);
        handle.setElevation(dp(12));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(205, 22, 25, 33));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.argb(225, 235, 240, 255));
        handle.setBackground(background);
        return handle;
    }

    private void ensureNotificationResizeHandle() {
        if (notificationScroller == null || squareRoot == null) return;
        if (notificationResizeHandle != null && notificationResizeHandle.getParent() == squareRoot) {
            updateNotificationHandleVisibility();
            return;
        }

        TextView handle = createResizeHandle("N", "Resize Square-U notification box");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(30), dp(30));
        squareRoot.addView(handle, params);
        handle.setOnTouchListener(new ResizeTouchListener(KIND_NOTIFICATION));
        notificationResizeHandle = handle;
        updateNotificationHandleVisibility();
    }

    private void positionNotificationResizeHandle() {
        if (notificationResizeHandle == null || notificationScroller == null) return;
        notificationResizeHandle.setX(notificationScroller.getX()
                + Math.max(0, notificationScroller.getWidth() - notificationResizeHandle.getWidth() - dp(4)));
        notificationResizeHandle.setY(notificationScroller.getY()
                + Math.max(0, notificationScroller.getHeight() - notificationResizeHandle.getHeight() - dp(4)));
        notificationResizeHandle.bringToFront();
        updateNotificationHandleVisibility();
    }

    private void updateNotificationHandleVisibility() {
        if (notificationResizeHandle == null || notificationScroller == null) return;
        boolean visible = SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"))
                && notificationScroller.getVisibility() == View.VISIBLE;
        notificationResizeHandle.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateSquareIconSize(int percent) {
        if (squareTrack == null) return;
        int size = dp(72) * percent / 100;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (!(card instanceof ViewGroup)) continue;
            ImageView icon = findDirectIcon((ViewGroup) card);
            if (icon == null) continue;
            ViewGroup.LayoutParams raw = icon.getLayoutParams();
            raw.width = size;
            raw.height = size;
            icon.setLayoutParams(raw);
            icon.requestLayout();
        }
    }

    private ImageView findDirectIcon(ViewGroup card) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof ImageView) return (ImageView) child;
        }
        return null;
    }

    private void invokeNotificationPanelSizing() {
        if (applyNotificationPanelSizingMethod == null) return;
        try {
            applyNotificationPanelSizingMethod.invoke(historyDisplayForwarder);
            if (notificationScroller != null) notificationScroller.requestLayout();
            if (squareRoot != null) squareRoot.post(this::positionNotificationResizeHandle);
        } catch (IllegalAccessException | InvocationTargetException e) {
            Log.e(TAG, "Unable to apply live Square-U notification box size", e);
        }
    }

    private void applyLiveValue(int kind, int value, boolean persist) {
        switch (kind) {
            case KIND_TILE:
                if (persist) prefs.edit().putInt(PREF_TILE_SIZE, value).apply();
                else putLivePreference(PREF_TILE_SIZE, value);
                if (squareTrack != null) squareTrack.requestLayout();
                break;
            case KIND_ICON:
                updateSquareIconSize(value);
                if (persist) prefs.edit().putInt(PREF_ICON_SIZE, value).apply();
                break;
            case KIND_NOTIFICATION:
                if (persist) prefs.edit().putInt(PREF_NOTIFICATION_SIZE, value).apply();
                else putLivePreference(PREF_NOTIFICATION_SIZE, value);
                invokeNotificationPanelSizing();
                break;
            default:
                break;
        }
    }

    private void putLivePreference(String key, int value) {
        // SharedPreferences updates its in-memory map immediately. apply() then flushes the same
        // value asynchronously, which lets the existing renderer consume the new size in its next
        // layout pass without blocking touch input.
        prefs.edit().putInt(key, value).apply();
    }

    private int currentValue(int kind) {
        switch (kind) {
            case KIND_TILE:
                return safeInt(PREF_TILE_SIZE, 100, 70, 150);
            case KIND_ICON:
                return safeInt(PREF_ICON_SIZE, 100, 60, 160);
            case KIND_NOTIFICATION:
                return safeInt(PREF_NOTIFICATION_SIZE, 100, 55, 150);
            default:
                return 100;
        }
    }

    private int minValue(int kind) {
        if (kind == KIND_TILE) return 70;
        if (kind == KIND_ICON) return 60;
        return 55;
    }

    private int maxValue(int kind) {
        if (kind == KIND_TILE) return 150;
        if (kind == KIND_ICON) return 160;
        return 150;
    }

    private int safeInt(String key, int fallback, int min, int max) {
        Object raw = prefs.getAll().get(key);
        int value = fallback;
        if (raw instanceof Number) {
            value = Math.round(((Number) raw).floatValue());
        } else if (raw instanceof String) {
            try {
                value = Math.round(Float.parseFloat((String) raw));
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private void setMarqueeEnabled(boolean enabled) {
        for (FullMarqueeTextView label : activeLabels) {
            if (label == null) continue;
            label.setSelected(enabled);
            label.requestLayout();
            label.invalidate();
        }
    }

    private void disallowParentIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class ResizeTouchListener implements View.OnTouchListener {
        private final int kind;
        private float startRawX;
        private float startRawY;
        private int startValue;
        private int lastValue;
        private long lastLiveUpdateAt;

        ResizeTouchListener(int kind) {
            this.kind = kind;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startValue = currentValue(kind);
                    lastValue = startValue;
                    lastLiveUpdateAt = 0L;
                    disallowParentIntercept(view, true);
                    view.setScaleX(1.15f);
                    view.setScaleY(1.15f);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int liveValue = valueFromMotion(event);
                    long now = android.os.SystemClock.uptimeMillis();
                    if (liveValue != lastValue && now - lastLiveUpdateAt >= LIVE_UPDATE_INTERVAL_MS) {
                        lastValue = liveValue;
                        lastLiveUpdateAt = now;
                        applyLiveValue(kind, liveValue, false);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    int finalValue = valueFromMotion(event);
                    lastValue = finalValue;
                    applyLiveValue(kind, finalValue, true);
                    disallowParentIntercept(view, false);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    if (kind == KIND_NOTIFICATION) view.post(SquareUInteractionController.this::positionNotificationResizeHandle);
                    return true;

                default:
                    return false;
            }
        }

        private int valueFromMotion(MotionEvent event) {
            float dx = event.getRawX() - startRawX;
            float dy = event.getRawY() - startRawY;
            float dominant = Math.abs(dx) >= Math.abs(dy) ? dx : dy;
            int deltaPercent = Math.round(dominant / Math.max(1f, dp(2)));
            int requested = startValue + deltaPercent;
            return Math.max(minValue(kind), Math.min(maxValue(kind), requested));
        }
    }

    private final class FullMarqueeTextView extends TextView {
        FullMarqueeTextView() {
            super(activity);
        }

        @Override
        public boolean isFocused() {
            // Android's marquee only advances while a TextView is focused/selected. Tying this to
            // the Activity's window focus keeps the full-label animation active on the launcher,
            // but naturally stops it when Smart S is backgrounded.
            return activity.hasWindowFocus() && isShown();
        }
    }
}
