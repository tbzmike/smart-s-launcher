package fr.neamar.kiss.forwarder;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.ResizeTarget;
import fr.neamar.kiss.utils.Log;

/**
 * Keeps Square-U labels readable and adds temporary, widget-style resize handles.
 * Resize controls are never permanent: they appear only after the existing long-press
 * popup's Resize action is chosen and disappear when Done is pressed.
 */
final class SquareUInteractionController {
    private static final String TAG = SquareUInteractionController.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final int MIN_SIZE_PERCENT = 55;
    private static final int MAX_SIZE_PERCENT = 220;

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SharedPreferences prefs;
    private final List<View> activeDecorations = new ArrayList<>();

    private ViewGroup squareTrack;
    private FrameLayout squareRoot;
    private ScrollView notificationScroller;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private View activeResizeOwner;
    private CardResizeState activeCardState;
    private ResizeState notificationState;
    private FrameLayout notificationOverlay;

    SquareUInteractionController(MainActivity activity,
                                 HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() {
        resolveViews();
        attachLayoutObserver();
        refreshSoon();
    }

    void onResume() {
        resolveViews();
        attachLayoutObserver();
        refreshSoon();
        setAllMarquees(true);
    }

    void onPause() {
        finishResize();
        setAllMarquees(false);
    }

    void onDataSetChanged() {
        finishResize();
        refreshSoon();
    }

    void onDestroy() {
        finishResize();
        detachLayoutObserver();
        setAllMarquees(false);
        squareTrack = null;
        squareRoot = null;
        notificationScroller = null;
    }

    private void resolveViews() {
        ViewGroup newTrack = readField("squareTrack", ViewGroup.class);
        FrameLayout newRoot = readField("squareRoot", FrameLayout.class);
        ScrollView newScroller = readField("notificationScroller", ScrollView.class);

        if (squareTrack != newTrack) {
            detachLayoutObserver();
            squareTrack = newTrack;
        }
        squareRoot = newRoot;
        notificationScroller = newScroller;
        attachLayoutObserver();
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

    private void attachLayoutObserver() {
        if (squareTrack == null || layoutListener != null) return;
        layoutListener = this::applyAllTransforms;
        squareTrack.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void detachLayoutObserver() {
        if (squareTrack != null && layoutListener != null) {
            ViewTreeObserver observer = squareTrack.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
    }

    private void refreshSoon() {
        resolveViews();
        if (squareTrack != null) squareTrack.post(this::refreshNow);
    }

    private void refreshNow() {
        if (squareTrack == null
                || activity.adapter == null
                || !SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"))) {
            return;
        }

        int count = Math.min(squareTrack.getChildCount(), activity.adapter.getCount());
        for (int position = 0; position < count; position++) {
            View card = squareTrack.getChildAt(position);
            if (!(card instanceof ViewGroup)) continue;

            TextView label = findPresentationLabel((ViewGroup) card);
            if (label != null) {
                String fullName = activity.adapter.getItem(position).getPojo().getName();
                if (!TextUtils.isEmpty(fullName)) {
                    label.setText(fullName);
                    label.setContentDescription(fullName);
                }
                configureMarquee(label, activity.hasWindowFocus());
            }

            long id = activity.adapter.getItem(position).getUniqueId();
            CardResizeState state = new CardResizeState(id, card);
            card.setTag(state);
            captureAndApplyCardTransform(state);
        }

        configureNotificationResize();
    }

    private void configureNotificationResize() {
        if (notificationScroller == null || squareRoot == null) return;
        if (notificationState == null) {
            notificationState = new ResizeState("smart-u-notification-live");
        }
        notificationScroller.setTag((ResizeTarget) this::beginNotificationResize);
        notificationScroller.setOnLongClickListener(v -> {
            ArrayAdapter<ListPopup.Item> adapter = new ArrayAdapter<>(activity, R.layout.popup_list_item);
            ListPopup menu = new ListPopup(activity);
            menu.setAdapter(adapter);
            menu.show(v);
            return true;
        });
        notificationScroller.post(this::applyNotificationTransform);
    }

    private TextView findPresentationLabel(ViewGroup card) {
        for (int i = card.getChildCount() - 1; i >= 0; i--) {
            View child = card.getChildAt(i);
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                if (!TextUtils.isEmpty(text.getText())) return text;
            }
        }
        return null;
    }

    private void configureMarquee(TextView label, boolean enabled) {
        label.setSingleLine(true);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        label.setMarqueeRepeatLimit(-1);
        label.setHorizontallyScrolling(true);
        label.setSelected(enabled);
        label.setFocusable(false);
        label.setFocusableInTouchMode(false);
        label.setHorizontalFadingEdgeEnabled(true);
        label.setFadingEdgeLength(dp(12));
        label.requestLayout();
        label.invalidate();
    }

    private void setAllMarquees(boolean enabled) {
        if (squareTrack == null) return;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (!(card instanceof ViewGroup)) continue;
            TextView label = findPresentationLabel((ViewGroup) card);
            if (label != null) configureMarquee(label, enabled);
        }
    }

    private void applyAllTransforms() {
        if (squareTrack == null) return;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View child = squareTrack.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof CardResizeState) captureAndApplyCardTransform((CardResizeState) tag);
        }
        applyNotificationTransform();
    }

    private void captureAndApplyCardTransform(CardResizeState state) {
        View card = state.card;
        float observedX = card.getScaleX();
        float observedY = card.getScaleY();
        if (!state.baseCaptured
                || Math.abs(observedX - state.lastAppliedScaleX) > 0.015f
                || Math.abs(observedY - state.lastAppliedScaleY) > 0.015f) {
            state.baseScaleX = observedX;
            state.baseScaleY = observedY;
            state.baseCaptured = true;
        }
        applyCardTransform(state);
    }

    private void applyCardTransform(CardResizeState state) {
        float scaleX = state.baseScaleX * state.widthPercent / 100f;
        float scaleY = state.baseScaleY * state.heightPercent / 100f;
        state.card.setScaleX(scaleX);
        state.card.setScaleY(scaleY);
        state.card.setTranslationX(dpFloat(state.offsetXDp));
        state.card.setTranslationY(dpFloat(state.offsetYDp));
        state.lastAppliedScaleX = scaleX;
        state.lastAppliedScaleY = scaleY;
    }

    private void beginCardResize(CardResizeState state) {
        finishResize();
        activeResizeOwner = state.card;
        activeCardState = state;
        addCardResizeDecorations(state);
    }

    private void addCardResizeDecorations(CardResizeState state) {
        if (!(state.card instanceof FrameLayout)) return;
        FrameLayout card = (FrameLayout) state.card;
        card.setClipChildren(false);
        card.setClipToPadding(false);

        View border = new View(activity);
        GradientDrawable borderDrawable = new GradientDrawable();
        borderDrawable.setColor(Color.TRANSPARENT);
        borderDrawable.setStroke(dp(2), Color.rgb(95, 132, 255));
        borderDrawable.setCornerRadius(dp(16));
        border.setBackground(borderDrawable);
        FrameLayout.LayoutParams borderParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        card.addView(border, borderParams);
        activeDecorations.add(border);

        addCardHandle(card, state, Gravity.LEFT | Gravity.TOP, -1, -1);
        addCardHandle(card, state, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, -1);
        addCardHandle(card, state, Gravity.RIGHT | Gravity.TOP, 1, -1);
        addCardHandle(card, state, Gravity.LEFT | Gravity.CENTER_VERTICAL, -1, 0);
        addCardHandle(card, state, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 1, 0);
        addCardHandle(card, state, Gravity.LEFT | Gravity.BOTTOM, -1, 1);
        addCardHandle(card, state, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 1);
        addCardHandle(card, state, Gravity.RIGHT | Gravity.BOTTOM, 1, 1);

        TextView done = new TextView(activity);
        done.setText("✓");
        done.setTextColor(Color.WHITE);
        done.setTextSize(16f);
        done.setGravity(Gravity.CENTER);
        done.setContentDescription("Finish resizing");
        done.setBackground(handleBackground(true));
        done.setElevation(dp(16));
        done.setOnClickListener(v -> finishResize());
        FrameLayout.LayoutParams doneParams = new FrameLayout.LayoutParams(
                dp(34), dp(34), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        doneParams.topMargin = dp(8);
        card.addView(done, doneParams);
        activeDecorations.add(done);
    }

    private void addCardHandle(FrameLayout card, CardResizeState state,
                               int gravity, int horizontalDirection, int verticalDirection) {
        View handle = new View(activity);
        handle.setContentDescription("Resize");
        handle.setBackground(handleBackground(false));
        handle.setElevation(dp(18));
        int size = dp(22);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, gravity);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        card.addView(handle, params);
        activeDecorations.add(handle);
        handle.setOnTouchListener(new CardResizeTouchListener(state, horizontalDirection, verticalDirection));
    }

    private GradientDrawable handleBackground(boolean done) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(done ? Color.rgb(35, 42, 62) : Color.rgb(225, 231, 255));
        drawable.setStroke(dp(2), Color.rgb(95, 132, 255));
        return drawable;
    }

    private void beginNotificationResize() {
        if (notificationScroller == null || squareRoot == null || notificationState == null) return;
        finishResize();
        activeResizeOwner = notificationScroller;

        notificationOverlay = new FrameLayout(activity);
        notificationOverlay.setClipChildren(false);
        notificationOverlay.setClipToPadding(false);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(2), Color.rgb(95, 132, 255));
        border.setCornerRadius(dp(18));
        notificationOverlay.setBackground(border);
        squareRoot.addView(notificationOverlay, new FrameLayout.LayoutParams(dp(100), dp(100)));
        activeDecorations.add(notificationOverlay);

        addNotificationHandle(Gravity.LEFT | Gravity.TOP, -1, -1);
        addNotificationHandle(Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, -1);
        addNotificationHandle(Gravity.RIGHT | Gravity.TOP, 1, -1);
        addNotificationHandle(Gravity.LEFT | Gravity.CENTER_VERTICAL, -1, 0);
        addNotificationHandle(Gravity.RIGHT | Gravity.CENTER_VERTICAL, 1, 0);
        addNotificationHandle(Gravity.LEFT | Gravity.BOTTOM, -1, 1);
        addNotificationHandle(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 1);
        addNotificationHandle(Gravity.RIGHT | Gravity.BOTTOM, 1, 1);

        TextView done = new TextView(activity);
        done.setText("✓");
        done.setTextColor(Color.WHITE);
        done.setTextSize(16f);
        done.setGravity(Gravity.CENTER);
        done.setContentDescription("Finish resizing");
        done.setBackground(handleBackground(true));
        done.setOnClickListener(v -> finishResize());
        FrameLayout.LayoutParams doneParams = new FrameLayout.LayoutParams(
                dp(34), dp(34), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        doneParams.topMargin = dp(8);
        notificationOverlay.addView(done, doneParams);
        updateNotificationOverlayBounds();
    }

    private void addNotificationHandle(int gravity, int horizontalDirection, int verticalDirection) {
        if (notificationOverlay == null) return;
        View handle = new View(activity);
        handle.setContentDescription("Resize");
        handle.setBackground(handleBackground(false));
        int size = dp(22);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, gravity);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        notificationOverlay.addView(handle, params);
        handle.setOnTouchListener(new NotificationResizeTouchListener(horizontalDirection, verticalDirection));
    }

    private void applyNotificationTransform() {
        if (notificationScroller == null || notificationState == null) return;
        notificationScroller.setScaleX(notificationState.widthPercent / 100f);
        notificationScroller.setScaleY(notificationState.heightPercent / 100f);
        notificationScroller.setTranslationX(dpFloat(notificationState.offsetXDp));
        notificationScroller.setTranslationY(dpFloat(notificationState.offsetYDp));
        if (notificationOverlay != null) updateNotificationOverlayBounds();
    }

    private void updateNotificationOverlayBounds() {
        if (notificationOverlay == null || notificationScroller == null) return;
        float scaleX = notificationScroller.getScaleX();
        float scaleY = notificationScroller.getScaleY();
        int width = Math.max(dp(80), Math.round(notificationScroller.getWidth() * scaleX));
        int height = Math.max(dp(80), Math.round(notificationScroller.getHeight() * scaleY));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        notificationOverlay.setLayoutParams(params);
        float left = notificationScroller.getX() + notificationScroller.getTranslationX()
                + (notificationScroller.getWidth() - width) / 2f;
        float top = notificationScroller.getY() + notificationScroller.getTranslationY()
                + (notificationScroller.getHeight() - height) / 2f;
        notificationOverlay.setX(left);
        notificationOverlay.setY(top);
        notificationOverlay.bringToFront();
    }

    private void finishResize() {
        if (activeCardState != null) activeCardState.persist();
        if (notificationState != null && activeResizeOwner == notificationScroller) notificationState.persist();

        for (int i = activeDecorations.size() - 1; i >= 0; i--) {
            View decoration = activeDecorations.get(i);
            ViewParent parent = decoration.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(decoration);
        }
        activeDecorations.clear();
        notificationOverlay = null;
        activeResizeOwner = null;
        activeCardState = null;
    }

    private void disallowParentIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private float dpFloat(int value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }

    private int pxToDp(float value) {
        return Math.round(value / activity.getResources().getDisplayMetrics().density);
    }

    private final class CardResizeState implements ResizeTarget {
        final long id;
        final View card;
        int widthPercent;
        int heightPercent;
        int offsetXDp;
        int offsetYDp;
        float baseScaleX = 1f;
        float baseScaleY = 1f;
        float lastAppliedScaleX = Float.NaN;
        float lastAppliedScaleY = Float.NaN;
        boolean baseCaptured;

        CardResizeState(long id, View card) {
            this.id = id;
            this.card = card;
            String prefix = "smart-u-item-" + id + "-";
            widthPercent = readInt(prefix + "width", 100);
            heightPercent = readInt(prefix + "height", 100);
            offsetXDp = readInt(prefix + "offset-x", 0);
            offsetYDp = readInt(prefix + "offset-y", 0);
        }

        @Override
        public void beginResize() {
            beginCardResize(this);
        }

        void persist() {
            String prefix = "smart-u-item-" + id + "-";
            prefs.edit()
                    .putInt(prefix + "width", widthPercent)
                    .putInt(prefix + "height", heightPercent)
                    .putInt(prefix + "offset-x", offsetXDp)
                    .putInt(prefix + "offset-y", offsetYDp)
                    .apply();
        }
    }

    private class ResizeState {
        final String prefix;
        int widthPercent;
        int heightPercent;
        int offsetXDp;
        int offsetYDp;

        ResizeState(String prefix) {
            this.prefix = prefix + "-";
            widthPercent = readInt(this.prefix + "width", 100);
            heightPercent = readInt(this.prefix + "height", 100);
            offsetXDp = readInt(this.prefix + "offset-x", 0);
            offsetYDp = readInt(this.prefix + "offset-y", 0);
        }

        void persist() {
            prefs.edit()
                    .putInt(prefix + "width", widthPercent)
                    .putInt(prefix + "height", heightPercent)
                    .putInt(prefix + "offset-x", offsetXDp)
                    .putInt(prefix + "offset-y", offsetYDp)
                    .apply();
        }
    }

    private int readInt(String key, int fallback) {
        Object raw = prefs.getAll().get(key);
        if (raw instanceof Number) return Math.round(((Number) raw).floatValue());
        if (raw instanceof String) {
            try {
                return Math.round(Float.parseFloat((String) raw));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private final class CardResizeTouchListener implements View.OnTouchListener {
        private final CardResizeState state;
        private final int horizontalDirection;
        private final int verticalDirection;
        private float startRawX;
        private float startRawY;
        private int startWidth;
        private int startHeight;
        private int startOffsetX;
        private int startOffsetY;

        CardResizeTouchListener(CardResizeState state, int horizontalDirection, int verticalDirection) {
            this.state = state;
            this.horizontalDirection = horizontalDirection;
            this.verticalDirection = verticalDirection;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startWidth = state.widthPercent;
                    startHeight = state.heightPercent;
                    startOffsetX = state.offsetXDp;
                    startOffsetY = state.offsetYDp;
                    disallowParentIntercept(view, true);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (horizontalDirection != 0) {
                        float baseWidth = Math.max(1f, state.card.getWidth() * state.baseScaleX);
                        int deltaPercent = Math.round((event.getRawX() - startRawX)
                                * horizontalDirection * 100f / baseWidth);
                        int newWidth = clamp(startWidth + deltaPercent, MIN_SIZE_PERCENT, MAX_SIZE_PERCENT);
                        float visualDelta = baseWidth * (newWidth - startWidth) / 100f;
                        state.widthPercent = newWidth;
                        state.offsetXDp = startOffsetX + pxToDp(horizontalDirection * visualDelta / 2f);
                    }
                    if (verticalDirection != 0) {
                        float baseHeight = Math.max(1f, state.card.getHeight() * state.baseScaleY);
                        int deltaPercent = Math.round((event.getRawY() - startRawY)
                                * verticalDirection * 100f / baseHeight);
                        int newHeight = clamp(startHeight + deltaPercent, MIN_SIZE_PERCENT, MAX_SIZE_PERCENT);
                        float visualDelta = baseHeight * (newHeight - startHeight) / 100f;
                        state.heightPercent = newHeight;
                        state.offsetYDp = startOffsetY + pxToDp(verticalDirection * visualDelta / 2f);
                    }
                    applyCardTransform(state);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    disallowParentIntercept(view, false);
                    state.persist();
                    return true;

                default:
                    return false;
            }
        }
    }

    private final class NotificationResizeTouchListener implements View.OnTouchListener {
        private final int horizontalDirection;
        private final int verticalDirection;
        private float startRawX;
        private float startRawY;
        private int startWidth;
        private int startHeight;
        private int startOffsetX;
        private int startOffsetY;

        NotificationResizeTouchListener(int horizontalDirection, int verticalDirection) {
            this.horizontalDirection = horizontalDirection;
            this.verticalDirection = verticalDirection;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (notificationScroller == null || notificationState == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startWidth = notificationState.widthPercent;
                    startHeight = notificationState.heightPercent;
                    startOffsetX = notificationState.offsetXDp;
                    startOffsetY = notificationState.offsetYDp;
                    disallowParentIntercept(view, true);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (horizontalDirection != 0) {
                        float baseWidth = Math.max(1f, notificationScroller.getWidth());
                        int deltaPercent = Math.round((event.getRawX() - startRawX)
                                * horizontalDirection * 100f / baseWidth);
                        int newWidth = clamp(startWidth + deltaPercent, MIN_SIZE_PERCENT, MAX_SIZE_PERCENT);
                        float visualDelta = baseWidth * (newWidth - startWidth) / 100f;
                        notificationState.widthPercent = newWidth;
                        notificationState.offsetXDp = startOffsetX
                                + pxToDp(horizontalDirection * visualDelta / 2f);
                    }
                    if (verticalDirection != 0) {
                        float baseHeight = Math.max(1f, notificationScroller.getHeight());
                        int deltaPercent = Math.round((event.getRawY() - startRawY)
                                * verticalDirection * 100f / baseHeight);
                        int newHeight = clamp(startHeight + deltaPercent, MIN_SIZE_PERCENT, MAX_SIZE_PERCENT);
                        float visualDelta = baseHeight * (newHeight - startHeight) / 100f;
                        notificationState.heightPercent = newHeight;
                        notificationState.offsetYDp = startOffsetY
                                + pxToDp(verticalDirection * visualDelta / 2f);
                    }
                    applyNotificationTransform();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    disallowParentIntercept(view, false);
                    notificationState.persist();
                    return true;

                default:
                    return false;
            }
        }
    }
}
