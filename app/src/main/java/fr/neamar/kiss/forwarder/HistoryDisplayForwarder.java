package fr.neamar.kiss.forwarder;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.ScrollIdleGate;

/** Owns the native Vertical List and the retained 3D Wheel history renderers. */
final class HistoryDisplayForwarder extends Forwarder {
    static final String PREF_LAYOUT = "smart-history-layout";
    static final String VERTICAL = HistoryLayoutMode.VERTICAL;
    static final String VERTICAL_CARDS = HistoryLayoutMode.VERTICAL_CARDS;
    static final String WHEEL_3D = HistoryLayoutMode.WHEEL_3D;
    private static final String PREF_HISTORY_WIDTH = "smart-list-card-width-percent";

    private FrameLayout container;
    private WheelScrollView wheelScroller;
    private LinearLayout wheelColumn;
    private final ArrayList<Integer> wheelViewTypes = new ArrayList<>();
    private final Runnable rebuildWheelWhenIdle = this::rebuildWheelNow;
    private boolean wheelTransformFramePosted;
    private View edgeEffect;
    private String activeMode = VERTICAL;
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
        createWheelRenderer();
        applyMode();
        if (WHEEL_3D.equals(activeMode)) rebuildWheel();
    }

    void onResume() {
        applyMode();
        if (WHEEL_3D.equals(activeMode)) rebuildWheel();
    }

    void onDestroy() {
        if (wheelScroller != null) {
            wheelScroller.scrollIdleGate.cancel(rebuildWheelWhenIdle);
            wheelScroller.scrollIdleGate.destroy();
        }
        container = null;
        wheelScroller = null;
        wheelColumn = null;
        wheelViewTypes.clear();
    }

    void onDataSetChanged() {
        if (WHEEL_3D.equals(activeMode)) rebuildWheel();
    }

    void onSharedWidthChanged() {
        if (VERTICAL.equals(activeMode)) {
            if (mainActivity.list != null) {
                mainActivity.list.invalidateViews();
                mainActivity.list.requestLayout();
            }
        } else if (WHEEL_3D.equals(activeMode)) {
            runWhenScrollIdle(() -> {
                applyWheelWidth();
                rebuildWheelNow();
            });
        }
    }

    void onHistoryMetadataLoaded() {
        if (WHEEL_3D.equals(activeMode)) rebuildWheel();
    }

    boolean isScrollInProgress() {
        if (WHEEL_3D.equals(activeMode) && wheelScroller != null) {
            return wheelScroller.scrollIdleGate.isScrolling();
        }
        return mainActivity.list != null && mainActivity.list.isScrollInProgress();
    }

    void runWhenScrollIdle(Runnable work) {
        if (WHEEL_3D.equals(activeMode) && wheelScroller != null) {
            wheelScroller.scrollIdleGate.runWhenIdle(work);
        } else if (mainActivity.list != null) {
            mainActivity.list.runWhenScrollIdle(work);
        } else {
            work.run();
        }
    }

    void addScrollStartedListener(Runnable listener) {
        if (mainActivity.list != null) mainActivity.list.addScrollStartedListener(listener);
        if (wheelScroller != null) wheelScroller.scrollIdleGate.addScrollStartedListener(listener);
    }

    void removeScrollStartedListener(Runnable listener) {
        if (mainActivity.list != null) mainActivity.list.removeScrollStartedListener(listener);
        if (wheelScroller != null) wheelScroller.scrollIdleGate.removeScrollStartedListener(listener);
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM);
        container.addView(wheelScroller, params);
    }

    private void applyMode() {
        String stored = prefs.getString(PREF_LAYOUT, VERTICAL);
        String normalized = HistoryLayoutMode.normalize(stored);
        if (!TextUtils.equals(stored, normalized)) {
            // Users upgrading from a removed Horizontal/Square-U mode land on a valid renderer.
            prefs.edit().putString(PREF_LAYOUT, normalized).apply();
        }
        if (TextUtils.equals(activeMode, normalized)
                && visibilityMatches(normalized)) return;

        activeMode = normalized;
        boolean vertical = VERTICAL.equals(activeMode);
        boolean wheel = WHEEL_3D.equals(activeMode);
        mainActivity.list.setVisibility(vertical ? View.VISIBLE : View.GONE);
        if (edgeEffect != null) edgeEffect.setVisibility(vertical ? View.VISIBLE : View.GONE);
        if (wheelScroller != null) wheelScroller.setVisibility(wheel ? View.VISIBLE : View.GONE);
        if (!wheel) resetWheelTransforms();
    }

    private boolean visibilityMatches(String mode) {
        if (wheelScroller == null || mainActivity.list == null) return false;
        boolean vertical = VERTICAL.equals(mode);
        boolean wheel = WHEEL_3D.equals(mode);
        return (mainActivity.list.getVisibility() == View.VISIBLE) == vertical
                && (wheelScroller.getVisibility() == View.VISIBLE) == wheel;
    }

    private void rebuildWheel() {
        runWhenScrollIdle(rebuildWheelWhenIdle);
    }

    private void rebuildWheelNow() {
        if (!WHEEL_3D.equals(activeMode) || wheelColumn == null || mainActivity.adapter == null) {
            return;
        }

        final int count = mainActivity.adapter.getCount();
        String currentQuery = mainActivity.searchEditText == null
                ? "" : mainActivity.searchEditText.getText().toString().trim();
        long currentPriorityId = count > 0
                ? mainActivity.adapter.getItem(count - 1).getUniqueId() : Long.MIN_VALUE;
        boolean refocusFront = !wheelHasBeenEntered
                || !currentQuery.equals(lastWheelQuery)
                || currentPriorityId != lastWheelPriorityId;

        for (int position = 0; position < count; position++) {
            int viewType = mainActivity.adapter.getItemViewType(position);
            View existing = position < wheelColumn.getChildCount()
                    ? wheelColumn.getChildAt(position) : null;
            int oldType = position < wheelViewTypes.size() ? wheelViewTypes.get(position) : -1;
            View reusable = existing != null && oldType == viewType ? existing : null;
            View source = mainActivity.adapter.getView(position, reusable, mainActivity.list);

            ViewGroup.LayoutParams rebound = source.getLayoutParams();
            int reboundHeight = rebound == null
                    ? ViewGroup.LayoutParams.WRAP_CONTENT : rebound.height;
            LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, reboundHeight);
            wheelParams.setMargins(dp(3), dp(4), dp(3), dp(4));
            wheelParams.gravity = Gravity.CENTER_HORIZONTAL;

            if (source != existing) {
                if (existing != null) wheelColumn.removeViewAt(position);
                wheelColumn.addView(source, position, wheelParams);
            } else {
                source.setLayoutParams(wheelParams);
            }

            while (wheelViewTypes.size() <= position) wheelViewTypes.add(-1);
            wheelViewTypes.set(position, viewType);
            resetWheelTransform(source);
            source.setCameraDistance(
                    mainActivity.getResources().getDisplayMetrics().density * 8000f);
            bindResultInteraction(source, position);
        }

        while (wheelColumn.getChildCount() > count) {
            wheelColumn.removeViewAt(wheelColumn.getChildCount() - 1);
        }
        while (wheelViewTypes.size() > count) {
            wheelViewTypes.remove(wheelViewTypes.size() - 1);
        }

        applyWheelWidth();
        wheelHasBeenEntered = true;
        lastWheelQuery = currentQuery;
        lastWheelPriorityId = currentPriorityId;
        wheelScroller.post(() -> {
            if (refocusFront && count > 0) centerWheelItem(count - 1);
            scheduleWheelTransforms();
        });
    }

    private void bindResultInteraction(View row, int adapterPosition) {
        row.setOnClickListener(v -> mainActivity.adapter.onClick(adapterPosition, v));
        row.setOnLongClickListener(v -> {
            mainActivity.adapter.onLongClick(adapterPosition, v);
            return true;
        });
        row.setClickable(true);
        row.setFocusable(true);
    }

    private void applyWheelWidth() {
        if (wheelColumn == null) return;
        int widthPercent = historyWidthPercent();
        int sidePadding = HistoryEdgeWidthPolicy.insetForPercent(dp(4), widthPercent);
        int top = wheelColumn.getPaddingTop();
        int bottom = wheelColumn.getPaddingBottom();
        if (wheelColumn.getPaddingLeft() != sidePadding
                || wheelColumn.getPaddingRight() != sidePadding) {
            wheelColumn.setPadding(sidePadding, top, sidePadding, bottom);
        }
        int available = wheelColumn.getWidth() - sidePadding * 2;
        if (available <= 0) return;
        int rowMargin = HistoryEdgeWidthPolicy.insetForPercent(dp(3), widthPercent);
        int targetRowWidth = HistoryEdgeWidthPolicy.targetWidth(
                available, available, widthPercent);
        for (int i = 0; i < wheelColumn.getChildCount(); i++) {
            View child = wheelColumn.getChildAt(i);
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            int desiredWidth = widthPercent >= 100 && widthPercent <= 200
                    ? ViewGroup.LayoutParams.MATCH_PARENT : targetRowWidth;
            if (lp.width == desiredWidth && lp.leftMargin == rowMargin
                    && lp.rightMargin == rowMargin
                    && lp.gravity == Gravity.CENTER_HORIZONTAL) continue;
            lp.width = desiredWidth;
            lp.leftMargin = rowMargin;
            lp.rightMargin = rowMargin;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            child.setLayoutParams(lp);
        }
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
        if (wheelScroller == null || wheelTransformFramePosted
                || !WHEEL_3D.equals(activeMode)) return;
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

        int childCount = wheelColumn.getChildCount();
        int first = firstWheelChildNear(viewportTop - overscan);
        for (int i = first; i < childCount; i++) {
            View child = wheelColumn.getChildAt(i);
            if (child.getTop() > viewportBottom + overscan) break;
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

    private int firstWheelChildNear(float minimumBottom) {
        if (wheelColumn == null) return 0;
        int count = wheelColumn.getChildCount();
        int low = 0;
        int high = count - 1;
        int result = count;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            View child = wheelColumn.getChildAt(mid);
            if (child.getBottom() >= minimumBottom) {
                result = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return Math.max(0, Math.min(count, result));
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

    private int historyWidthPercent() {
        return safePrefInt(PREF_HISTORY_WIDTH, 100, 48, 400);
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

    private final class WheelScrollView extends ScrollView {
        final ScrollIdleGate scrollIdleGate;

        WheelScrollView() {
            super(mainActivity);
            scrollIdleGate = new ScrollIdleGate(this);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            scrollIdleGate.onTouchEvent(event);
            return super.dispatchTouchEvent(event);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            scrollIdleGate.onScrollChanged();
            scheduleWheelTransforms();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (wheelColumn != null && h > 0) {
                int verticalPadding = Math.max(dp(80), h / 3);
                int sidePadding = HistoryEdgeWidthPolicy.insetForPercent(
                        dp(4), historyWidthPercent());
                wheelColumn.setPadding(sidePadding, verticalPadding, sidePadding, verticalPadding);
                applyWheelWidth();
            }
            post(() -> {
                if (WHEEL_3D.equals(activeMode)) scheduleWheelTransforms();
            });
        }
    }
}
