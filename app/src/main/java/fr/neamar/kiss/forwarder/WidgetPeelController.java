package fr.neamar.kiss.forwarder;

import android.appwidget.AppWidgetHostView;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.SmartAnimationEngine;

/**
 * Gives widgets a paper-like corner lift only when visible search-result rows physically approach
 * or overlap them. Keyboard visibility is deliberately not an input: all motion is derived from
 * real screen-space geometry.
 */
final class WidgetPeelController {
    private static final float MAX_ROTATION_X = 11f;
    private static final float MAX_ROTATION_Y = 14f;
    private static final float MAX_ROTATION_Z = 2.75f;

    private final MainActivity activity;
    private final Map<View, PeelState> states = new IdentityHashMap<>();
    private final Rect scratchWidgetRect = new Rect();
    private final Rect scratchResultRect = new Rect();
    private final Rect scratchHorizontal = new Rect();

    private View root;
    private ViewGroup widgetArea;
    private View resultLayout;
    private boolean installed;
    private boolean refreshPosted;

    private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener =
            this::scheduleRefresh;
    private final ViewTreeObserver.OnScrollChangedListener scrollChangedListener =
            this::scheduleRefresh;

    WidgetPeelController(MainActivity activity) {
        this.activity = activity;
    }

    void onCreate() {
        root = activity.findViewById(android.R.id.content);
        widgetArea = activity.findViewById(R.id.widgetLayout);
        resultLayout = activity.findViewById(R.id.resultLayout);
        if (root == null || widgetArea == null || resultLayout == null) return;

        ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(globalLayoutListener);
        observer.addOnScrollChangedListener(scrollChangedListener);
        installed = true;
        scheduleRefresh();
    }

    void onDataSetChanged() {
        scheduleRefresh();
    }

    void onConfigurationChanged() {
        restoreAll();
        scheduleRefresh();
    }

    void onDestroy() {
        if (installed && root != null) {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(globalLayoutListener);
                observer.removeOnScrollChangedListener(scrollChangedListener);
            }
        }
        installed = false;
        refreshPosted = false;
        restoreAll();
    }

    private void scheduleRefresh() {
        if (!installed || root == null || refreshPosted) return;
        refreshPosted = true;
        root.postOnAnimation(() -> {
            refreshPosted = false;
            refresh();
        });
    }

    private void refresh() {
        if (!installed || widgetArea == null || resultLayout == null) return;
        if (!SmartAnimationEngine.isEnabled(activity)) {
            restoreAll();
            return;
        }

        List<View> targets = collectWidgetTargets();
        Set<View> liveTargets = Collections.newSetFromMap(new IdentityHashMap<>());
        liveTargets.addAll(targets);
        restoreDetachedTargets(liveTargets);

        boolean resultsVisible = resultLayout.getVisibility() == View.VISIBLE
                && activity.list != null
                && activity.list.getVisibility() == View.VISIBLE
                && activity.list.getChildCount() > 0;

        for (View target : targets) {
            if (target.getVisibility() != View.VISIBLE || target.getWidth() <= 0 || target.getHeight() <= 0) {
                restoreAndForget(target);
                continue;
            }

            PeelState activeState = states.get(target);
            Rect widgetRect;
            if (activeState != null) {
                widgetRect = activeState.baselineRect;
            } else {
                if (!target.getGlobalVisibleRect(scratchWidgetRect) || scratchWidgetRect.isEmpty()) continue;
                widgetRect = new Rect(scratchWidgetRect);
            }

            Pressure pressure = resultsVisible ? pressureFor(widgetRect) : Pressure.NONE;
            if (pressure.amount <= 0f) {
                restoreAndForget(target);
                continue;
            }

            PeelState state = activeState;
            if (state == null) {
                state = new PeelState(target, widgetRect);
                states.put(target, state);
            }
            applyPeel(target, state, pressure);
        }
    }

    private Pressure pressureFor(Rect widgetRect) {
        float best = 0f;
        float bestCenterX = widgetRect.exactCenterX();
        float anticipationDistance = dp(28);
        float fullPeelDepth = Math.max(dp(72), Math.min(widgetRect.height(), dp(128)));

        for (int i = 0; i < activity.list.getChildCount(); i++) {
            View row = activity.list.getChildAt(i);
            if (row == null || row.getVisibility() != View.VISIBLE || row.getAlpha() <= 0.05f) continue;
            if (!row.getGlobalVisibleRect(scratchResultRect) || scratchResultRect.isEmpty()) continue;

            int horizontalLeft = Math.max(widgetRect.left, scratchResultRect.left);
            int horizontalRight = Math.min(widgetRect.right, scratchResultRect.right);
            if (horizontalRight <= horizontalLeft) continue;

            float amount = 0f;
            int overlapTop = Math.max(widgetRect.top, scratchResultRect.top);
            int overlapBottom = Math.min(widgetRect.bottom, scratchResultRect.bottom);
            int overlap = overlapBottom - overlapTop;
            if (overlap > 0) {
                amount = Math.min(1f, overlap / fullPeelDepth);
            } else if (scratchResultRect.top >= widgetRect.bottom) {
                int gap = scratchResultRect.top - widgetRect.bottom;
                if (gap < anticipationDistance) {
                    amount = 0.18f * (1f - gap / anticipationDistance);
                }
            }

            if (amount > best) {
                best = amount;
                bestCenterX = (horizontalLeft + horizontalRight) * 0.5f;
            }
        }

        if (best <= 0f) return Pressure.NONE;
        return new Pressure(clamp01(best), bestCenterX);
    }

    private void applyPeel(View target, PeelState state, Pressure pressure) {
        float eased = smoothStep(pressure.amount);
        boolean peelRight;
        if (Math.abs(pressure.centerX - state.baselineRect.exactCenterX()) > dp(2)) {
            peelRight = pressure.centerX >= state.baselineRect.exactCenterX();
        } else {
            int screenMid = activity.getResources().getDisplayMetrics().widthPixels / 2;
            peelRight = state.baselineRect.centerX() >= screenMid;
        }

        target.animate().cancel();
        target.setPivotX(peelRight ? target.getWidth() : 0f);
        target.setPivotY(target.getHeight());
        target.setCameraDistance(Math.max(state.cameraDistance, dp(8000)));
        target.setRotationX(state.rotationX - MAX_ROTATION_X * eased);
        target.setRotationY(state.rotationY + (peelRight ? -MAX_ROTATION_Y : MAX_ROTATION_Y) * eased);
        target.setRotation(state.rotation + (peelRight ? MAX_ROTATION_Z : -MAX_ROTATION_Z) * eased);
    }

    private List<View> collectWidgetTargets() {
        List<View> targets = new ArrayList<>();
        for (int i = 0; i < widgetArea.getChildCount(); i++) {
            View child = widgetArea.getChildAt(i);
            if (child instanceof AppWidgetHostView) {
                targets.add(child);
                continue;
            }
            if (!(child instanceof ViewGroup)) continue;

            ViewGroup possibleSurface = (ViewGroup) child;
            boolean foundNestedFrames = false;
            for (int j = 0; j < possibleSurface.getChildCount(); j++) {
                View nested = possibleSurface.getChildAt(j);
                if (containsAppWidget(nested)) {
                    targets.add(nested);
                    foundNestedFrames = true;
                }
            }
            if (!foundNestedFrames && containsAppWidget(child)) targets.add(child);
        }
        return targets;
    }

    private static boolean containsAppWidget(View view) {
        if (view instanceof AppWidgetHostView) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsAppWidget(group.getChildAt(i))) return true;
        }
        return false;
    }

    private void restoreDetachedTargets(Set<View> liveTargets) {
        Iterator<Map.Entry<View, PeelState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<View, PeelState> entry = iterator.next();
            if (!liveTargets.contains(entry.getKey())) {
                entry.getValue().restore(entry.getKey());
                iterator.remove();
            }
        }
    }

    private void restoreAndForget(View target) {
        PeelState state = states.remove(target);
        if (state != null) state.restore(target);
    }

    private void restoreAll() {
        for (Map.Entry<View, PeelState> entry : states.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        states.clear();
    }

    private float dp(float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }

    private static float smoothStep(float value) {
        float x = clamp01(value);
        return x * x * (3f - 2f * x);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class Pressure {
        static final Pressure NONE = new Pressure(0f, 0f);
        final float amount;
        final float centerX;

        Pressure(float amount, float centerX) {
            this.amount = amount;
            this.centerX = centerX;
        }
    }

    private static final class PeelState {
        final Rect baselineRect;
        final float pivotX;
        final float pivotY;
        final float rotation;
        final float rotationX;
        final float rotationY;
        final float cameraDistance;

        PeelState(View view, Rect baselineRect) {
            this.baselineRect = new Rect(baselineRect);
            this.pivotX = view.getPivotX();
            this.pivotY = view.getPivotY();
            this.rotation = view.getRotation();
            this.rotationX = view.getRotationX();
            this.rotationY = view.getRotationY();
            this.cameraDistance = view.getCameraDistance();
        }

        void restore(View view) {
            view.animate().cancel();
            view.setPivotX(pivotX);
            view.setPivotY(pivotY);
            view.setRotation(rotation);
            view.setRotationX(rotationX);
            view.setRotationY(rotationY);
            view.setCameraDistance(cameraDistance);
        }
    }
}
