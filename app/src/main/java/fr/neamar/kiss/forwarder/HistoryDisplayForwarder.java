package fr.neamar.kiss.forwarder;

import android.text.TextUtils;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.ScrollIdleGate;
import fr.neamar.kiss.ui.SmartAnimationEngine;

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
    private TextView wheelOlder;
    private TextView wheelNewer;
    private final WheelHistoryWindow wheelWindow = new WheelHistoryWindow();
    private int lastWheelWindowStart = -1;
    private final ArrayList<Integer> wheelViewTypes = new ArrayList<>();
    private final Runnable rebuildWheelWhenIdle = this::rebuildWheelNow;
    private boolean wheelTransformFramePosted;
    private boolean wheelRebuildPosted;
    private boolean programmaticWheelScroll;
    private View edgeEffect;
    private String activeMode = VERTICAL;
    private final Runnable flushWheelRebuild = () -> {
        wheelRebuildPosted = false;
        if (WHEEL_3D.equals(activeMode)) runWhenScrollIdle(rebuildWheelWhenIdle);
    };
    private boolean wheelHasBeenEntered;
    private String lastWheelQuery = "";
    private long lastWheelPriorityId = Long.MIN_VALUE;
    private final Set<Long> animatedVerticalItems = new HashSet<>();
    private String lastVerticalAnimationQuery = null;
    private String lastVerticalAnimationSignature = null;
    private final Runnable animateVerticalRowsWhenIdle = this::animateVisibleVerticalRows;
    private final Set<Long> animatedWheelItems = new HashSet<>();
    private String lastWheelAnimationSignature = null;

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
        if (WHEEL_3D.equals(activeMode)) {
            rebuildWheel();
        } else if (VERTICAL.equals(activeMode)) {
            scheduleVisibleVerticalAnimations();
        }
    }

    void onDestroy() {
        if (mainActivity.list != null) {
            mainActivity.list.cancelWhenScrollIdle(animateVerticalRowsWhenIdle);
        }
        if (wheelScroller != null) {
            wheelScroller.removeCallbacks(flushWheelRebuild);
            wheelScroller.scrollIdleGate.cancel(rebuildWheelWhenIdle);
            wheelScroller.scrollIdleGate.destroy();
        }
        animatedVerticalItems.clear();
        animatedWheelItems.clear();
        container = null;
        wheelScroller = null;
        wheelColumn = null;
        wheelViewTypes.clear();
        wheelWindow.reset();
        wheelRebuildPosted = false;
        wheelOlder = null;
        wheelNewer = null;
    }

    void onDataSetChanged() {
        if (WHEEL_3D.equals(activeMode)) {
            rebuildWheel();
        } else if (VERTICAL.equals(activeMode)) {
            scheduleVisibleVerticalAnimations();
        }
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

    void cancelWhenScrollIdle(Runnable work) {
        if (WHEEL_3D.equals(activeMode) && wheelScroller != null) {
            wheelScroller.scrollIdleGate.cancel(work);
        } else if (mainActivity.list != null) {
            mainActivity.list.cancelWhenScrollIdle(work);
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

        // Navigation is a lightweight overlay, NOT an extra ListView adapter item.
        // The expensive wheel hierarchy stays bounded, while every older result
        // remains reachable through explicit page controls.
        wheelOlder = createWheelPageControl("↑ Older history", Gravity.TOP,
                () -> changeWheelPage(true));
        wheelNewer = createWheelPageControl("↓ Newer history", Gravity.BOTTOM,
                () -> changeWheelPage(false));
    }

    private TextView createWheelPageControl(String label, int gravity, Runnable onClick) {
        TextView control = new TextView(mainActivity);
        control.setText(label);
        control.setTextSize(13f);
        control.setTextColor(Color.WHITE);
        control.setGravity(Gravity.CENTER);
        control.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(58, 64, 75));
        background.setCornerRadius(dp(18));
        control.setBackground(background);
        control.setElevation(dp(5));
        control.setVisibility(View.GONE);
        control.setOnClickListener(v -> onClick.run());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity | Gravity.CENTER_HORIZONTAL);
        if (gravity == Gravity.TOP) lp.topMargin = dp(8);
        else lp.bottomMargin = dp(8);
        container.addView(control, lp);
        return control;
    }

    private void changeWheelPage(boolean older) {
        if (!WHEEL_3D.equals(activeMode) || mainActivity.adapter == null) return;
        int total = mainActivity.adapter.getCount();
        if (older) wheelWindow.older(total);
        else wheelWindow.newer(total);
        wheelHasBeenEntered = false;
        rebuildWheelNow();
    }

    private void updateWheelPaging(int total) {
        if (wheelOlder != null) wheelOlder.setVisibility(WHEEL_3D.equals(activeMode)
                && wheelWindow.hasOlder(total) ? View.VISIBLE : View.GONE);
        if (wheelNewer != null) wheelNewer.setVisibility(WHEEL_3D.equals(activeMode)
                && wheelWindow.hasNewer(total) ? View.VISIBLE : View.GONE);
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

        String previousMode = activeMode;
        activeMode = normalized;
        boolean vertical = VERTICAL.equals(activeMode);
        boolean wheel = WHEEL_3D.equals(activeMode);
        mainActivity.list.setVisibility(vertical ? View.VISIBLE : View.GONE);
        if (edgeEffect != null) edgeEffect.setVisibility(vertical ? View.VISIBLE : View.GONE);
        if (wheelScroller != null) wheelScroller.setVisibility(wheel ? View.VISIBLE : View.GONE);
        if (!wheel) clearHiddenWheelTree();
        updateWheelPaging(mainActivity.adapter == null ? 0 : mainActivity.adapter.getCount());

        if (!TextUtils.equals(previousMode, activeMode)) {
            animatedVerticalItems.clear();
            lastVerticalAnimationQuery = null;
            lastVerticalAnimationSignature = null;
            View incoming = vertical ? mainActivity.list : (wheel ? wheelScroller : null);
            if (incoming != null) {
                SmartAnimationEngine.animateWindowSwitch(null, incoming);
            }
        }
    }

    private void clearHiddenWheelTree() {
        if (wheelColumn == null) return;
        if (wheelScroller != null) wheelScroller.removeCallbacks(flushWheelRebuild);
        wheelRebuildPosted = false;
        resetWheelTransforms();
        if (wheelColumn.getChildCount() > 0) wheelColumn.removeAllViews();
        wheelViewTypes.clear();
        animatedWheelItems.clear();
        lastWheelAnimationSignature = null;
        wheelHasBeenEntered = false;
        lastWheelQuery = "";
        lastWheelPriorityId = Long.MIN_VALUE;
        wheelWindow.reset();
        lastWheelWindowStart = -1;
    }

    private boolean visibilityMatches(String mode) {
        if (wheelScroller == null || mainActivity.list == null) return false;
        boolean vertical = VERTICAL.equals(mode);
        boolean wheel = WHEEL_3D.equals(mode);
        return (mainActivity.list.getVisibility() == View.VISIBLE) == vertical
                && (wheelScroller.getVisibility() == View.VISIBLE) == wheel;
    }

    private void rebuildWheel() {
        // Providers can publish multiple result/metadata snapshots in one frame.
        // Coalesce rather than repeatedly inflate and remeasure the entire 3D hierarchy.
        if (!WHEEL_3D.equals(activeMode) || wheelScroller == null || wheelRebuildPosted) return;
        wheelRebuildPosted = true;
        wheelScroller.postDelayed(flushWheelRebuild, 64L);
    }

    private void rebuildWheelNow() {
        if (!WHEEL_3D.equals(activeMode) || wheelColumn == null || mainActivity.adapter == null) {
            return;
        }

        final int total = mainActivity.adapter.getCount();
        String currentQuery = mainActivity.searchEditText == null
                ? "" : mainActivity.searchEditText.getText().toString().trim();
        if (!currentQuery.equals(lastWheelQuery)) wheelWindow.reset();
        final int start = wheelWindow.start(total);
        final int end = wheelWindow.end(total);
        final int count = end - start;
        final boolean pageChanged = lastWheelWindowStart != start;
        long currentPriorityId = count > 0
                ? mainActivity.adapter.getItem(end - 1).getUniqueId() : Long.MIN_VALUE;
        boolean refocusFront = pageChanged || !wheelHasBeenEntered
                || !currentQuery.equals(lastWheelQuery)
                || currentPriorityId != lastWheelPriorityId;
        String wheelAnimationSignature =
                SmartAnimationEngine.listAnimationSignature(mainActivity);
        boolean animationConfigChanged =
                !TextUtils.equals(lastWheelAnimationSignature, wheelAnimationSignature);
        if (!TextUtils.equals(currentQuery, lastWheelQuery) || animationConfigChanged) {
            animatedWheelItems.clear();
            lastWheelAnimationSignature = wheelAnimationSignature;
        }

        for (int local = 0; local < count; local++) {
            int position = start + local;
            int viewType = mainActivity.adapter.getItemViewType(position);
            View existingNode = local < wheelColumn.getChildCount()
                    ? wheelColumn.getChildAt(local) : null;
            int oldType = local < wheelViewTypes.size() ? wheelViewTypes.get(local) : -1;

            View reusable = oldType == viewType ? wheelContent(existingNode) : null;
            View source = mainActivity.adapter.getView(position, reusable, mainActivity.list);
            boolean newVisual = source != reusable || !(existingNode instanceof WheelItemShell);

            ViewGroup.LayoutParams rebound = source.getLayoutParams();
            int reboundHeight = rebound == null
                    ? ViewGroup.LayoutParams.WRAP_CONTENT : rebound.height;
            LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, reboundHeight);
            wheelParams.setMargins(dp(3), dp(4), dp(3), dp(4));
            wheelParams.gravity = Gravity.CENTER_HORIZONTAL;

            WheelItemShell shell;
            if (existingNode instanceof WheelItemShell && oldType == viewType) {
                shell = (WheelItemShell) existingNode;
                shell.setLayoutParams(wheelParams);
            } else {
                if (existingNode != null && existingNode.getParent() == wheelColumn) {
                    wheelColumn.removeViewAt(local);
                }
                shell = new WheelItemShell();
                wheelColumn.addView(shell, local, wheelParams);
            }

            if (source.getParent() != shell) {
                if (source.getParent() instanceof ViewGroup) {
                    ((ViewGroup) source.getParent()).removeView(source);
                }
                shell.removeAllViews();
                shell.addView(source, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (!(source.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                // RecordAdapter.getView() is shared with native ListView. Rebinding a
                // retained Wheel row can replace its FrameLayout.LayoutParams with
                // AbsListView.LayoutParams. FrameLayout.measureChildWithMargins then
                // throws ClassCastException before any History item can be painted.
                // Normalize ONLY the Wheel child: never alter the adapter or ListView.
                source.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            while (wheelViewTypes.size() <= local) wheelViewTypes.add(-1);
            wheelViewTypes.set(local, viewType);
            resetWheelTransform(shell);
            shell.setCameraDistance(
                    mainActivity.getResources().getDisplayMetrics().density * 8000f);
            bindResultInteraction(shell, position);

            // 3D transforms run on the shell; never stack a second tile animation on
            // each nested content view during an initial/history rebuild. That many
            // hardware layers caused severe churn on GPU-constrained devices.
            if (newVisual) SmartAnimationEngine.reset(source);
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
        lastWheelWindowStart = start;
        updateWheelPaging(total);
        wheelScroller.post(() -> {
            if (refocusFront && count > 0) centerWheelItem(count - 1);
            scheduleWheelTransforms();

            // Reused wheel rows are not "newVisual". When the user changes animation style, speed
            // or the master animation toggle in Settings, explicitly replay the visible wheel rows
            // so the selected setting takes effect immediately on return to Home.
            if (animationConfigChanged) animateVisibleWheelRows();
        });
    }

    private View wheelContent(View node) {
        if (node instanceof WheelItemShell) {
            WheelItemShell shell = (WheelItemShell) node;
            return shell.getChildCount() > 0 ? shell.getChildAt(0) : null;
        }
        return node;
    }

    private final class WheelItemShell extends FrameLayout {
        WheelItemShell() {
            super(mainActivity);
            setClipChildren(false);
            setClipToPadding(false);
        }
    }

    void onScrollIdleAnimations() {
        if (VERTICAL.equals(activeMode)) {
            animateVisibleVerticalRows();
        } else if (WHEEL_3D.equals(activeMode)) {
            animateVisibleWheelRows();
        }
    }

    private void animateVisibleWheelRows() {
        // Wheel shells already animate geometrically as they scroll. Independently
        // animating every nested TextView/row here caused repeated allocations and
        // competing hardware layers. Keep Wheel animation to one bounded transform pass.
        if (WHEEL_3D.equals(activeMode)) scheduleWheelTransforms();
    }

    private void scheduleVisibleVerticalAnimations() {
        if (!VERTICAL.equals(activeMode) || mainActivity.list == null) return;
        mainActivity.list.postOnAnimation(() -> {
            if (!VERTICAL.equals(activeMode) || mainActivity.list == null) return;
            if (mainActivity.list.isScrollInProgress()) return;
            animateVisibleVerticalRows();
        });
    }

    private void animateVisibleVerticalRows() {
        if (!VERTICAL.equals(activeMode) || mainActivity.list == null
                || mainActivity.adapter == null || mainActivity.list.isScrollInProgress()) {
            return;
        }

        if (!SmartAnimationEngine.isEnabled(mainActivity)) {
            resetVisibleVerticalAnimations();
            return;
        }

        String query = mainActivity.searchEditText == null
                ? "" : mainActivity.searchEditText.getText().toString();
        String animationSignature =
                SmartAnimationEngine.listAnimationSignature(mainActivity);
        if (!TextUtils.equals(lastVerticalAnimationQuery, query)
                || !TextUtils.equals(lastVerticalAnimationSignature, animationSignature)) {
            animatedVerticalItems.clear();
            lastVerticalAnimationQuery = query;
            lastVerticalAnimationSignature = animationSignature;
        }

        int first = mainActivity.list.getFirstVisiblePosition();
        int childCount = mainActivity.list.getChildCount();
        for (int i = 0; i < childCount; i++) {
            int position = first + i;
            if (position < 0 || position >= mainActivity.adapter.getCount()) continue;
            long id = mainActivity.adapter.getItem(position).getUniqueId();
            if (!animatedVerticalItems.add(id)) continue;
            View child = mainActivity.list.getChildAt(i);
            if (child == null) continue;
            int animationIndex = Math.max(0, childCount - 1 - i);
            SmartAnimationEngine.animateTileListItem(child, animationIndex);
        }
    }

    private void resetVisibleVerticalAnimations() {
        if (mainActivity.list == null) return;
        int childCount = mainActivity.list.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mainActivity.list.getChildAt(i);
            if (child != null) SmartAnimationEngine.reset(child);
        }
    }

    private void resetVisibleWheelContentAnimations() {
        if (wheelScroller == null || wheelColumn == null) return;
        int viewportTop = wheelScroller.getScrollY();
        int viewportBottom = viewportTop + wheelScroller.getHeight();
        for (int i = 0; i < wheelColumn.getChildCount(); i++) {
            View shell = wheelColumn.getChildAt(i);
            if (shell.getBottom() < viewportTop || shell.getTop() > viewportBottom) continue;
            View content = wheelContent(shell);
            if (content != null) SmartAnimationEngine.reset(content);
        }
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
        programmaticWheelScroll = true;
        try {
            wheelScroller.scrollTo(0, Math.max(0, Math.min(max, target)));
        } finally {
            programmaticWheelScroll = false;
        }
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
        String configuredScroll = SmartAnimationEngine.isLaunchAnimationEnabled(mainActivity)
                ? SmartAnimationEngine.getStyle(mainActivity, "smart-animation-scroll", "classic")
                : "none";
        WheelScrollStylePolicy.Profile motion = WheelScrollStylePolicy.forStyle(configuredScroll);

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
            child.setRotationX(-normalized * motion.tilt);
            child.setRotationY(-normalized * motion.yaw);
            child.setRotation(-normalized * motion.twist);
            float scale = 1f - motion.depth * distance;
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(Math.max(0.22f, 1f - motion.fade * distance));
            child.setTranslationX(normalized * distance * dp(Math.round(motion.sideways)));
            child.setTranslationY(-normalized * distance * dp(Math.round(motion.lift)));
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
            // Centering an item programmatically is not a user scroll. Sending it
            // through the global scroll gate cancels in-flight History searches,
            // leaving Wheel blank after returning Home.
            if (!programmaticWheelScroll) scrollIdleGate.onScrollChanged();
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
