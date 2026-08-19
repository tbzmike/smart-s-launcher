package fr.neamar.kiss.ui;

import android.appwidget.AppWidgetHostView;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;

import androidx.preference.PreferenceManager;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;

/**
 * Opt-in flexible launcher workspace. It reuses the existing history/results and widget containers
 * and only reparents them when the feature is enabled. The normal KISS layout remains untouched
 * when the workspace preference is disabled.
 */
public final class SmartWorkspaceController {
    public static final String PREF_ENABLED = "smart-workspace-enabled";
    public static final String PREF_ORIENTATION = "smart-workspace-orientation";
    public static final String PREF_PRIMARY_CONTENT = "smart-workspace-primary-content";
    public static final String PREF_SPLIT_PERCENT = "smart-workspace-split-percent";
    public static final String PREF_DRAGGABLE = "smart-workspace-draggable";

    private static final int MIN_PANE_PERCENT = 15;
    private static final int MAX_PANE_PERCENT = 85;

    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final LinearLayout workspace;
    private final ViewGroup widgetArea;
    private final View firstPane;
    private final View secondPane;
    private final View divider;
    private final boolean horizontal;

    private SmartWorkspaceController(MainActivity activity,
                                     SharedPreferences prefs,
                                     LinearLayout workspace,
                                     ViewGroup widgetArea,
                                     View firstPane,
                                     View secondPane,
                                     View divider,
                                     boolean horizontal) {
        this.activity = activity;
        this.prefs = prefs;
        this.workspace = workspace;
        this.widgetArea = widgetArea;
        this.firstPane = firstPane;
        this.secondPane = secondPane;
        this.divider = divider;
        this.horizontal = horizontal;
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    /**
     * Install the split workspace once for this Activity. Safe no-op when disabled or if the
     * expected legacy layout is not present.
     */
    public static SmartWorkspaceController install(MainActivity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!isEnabled(prefs)) return null;

        View resultLayout = activity.findViewById(R.id.resultLayout);
        View emptyView = activity.findViewById(android.R.id.empty);
        View widgetView = activity.findViewById(R.id.widgetLayout);
        if (!(widgetView instanceof ViewGroup) || resultLayout == null || emptyView == null) return null;

        ViewGroup root = (ViewGroup) resultLayout.getParent();
        if (!(root instanceof RelativeLayout)
                || emptyView.getParent() != root
                || widgetView.getParent() != root) {
            return null;
        }

        ViewGroup widgetArea = (ViewGroup) widgetView;
        root.removeView(resultLayout);
        root.removeView(emptyView);
        root.removeView(widgetView);

        FrameLayout historyPane = new FrameLayout(activity);
        historyPane.setClipChildren(false);
        historyPane.setClipToPadding(false);
        historyPane.addView(resultLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        historyPane.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView widgetScroller = new ScrollView(activity);
        widgetScroller.setFillViewport(true);
        widgetScroller.setClipToPadding(false);
        widgetScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        widgetArea.setClipChildren(false);
        widgetArea.setClipToPadding(false);
        widgetScroller.addView(widgetArea, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean horizontal = !"vertical".equals(prefs.getString(PREF_ORIENTATION, "horizontal"));
        LinearLayout workspace = new LinearLayout(activity);
        workspace.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        workspace.setWeightSum(100f);
        workspace.setClipChildren(false);
        workspace.setClipToPadding(false);

        RelativeLayout.LayoutParams workspaceParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        workspaceParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        workspaceParams.addRule(RelativeLayout.ABOVE, R.id.externalFavoriteBar);
        int margin = dp(activity, 10);
        workspaceParams.setMargins(margin, margin, margin, margin);
        root.addView(workspace, 0, workspaceParams);

        boolean widgetsFirst = "widgets".equals(prefs.getString(PREF_PRIMARY_CONTENT, "history"));
        View firstPane = widgetsFirst ? widgetScroller : historyPane;
        View secondPane = widgetsFirst ? historyPane : widgetScroller;

        int splitPercent = clamp(prefs.getInt(PREF_SPLIT_PERCENT, 50), MIN_PANE_PERCENT, MAX_PANE_PERCENT);
        workspace.addView(firstPane, paneParams(horizontal, splitPercent));

        View divider = new View(activity);
        divider.setBackgroundColor(Color.argb(105, 255, 255, 255));
        divider.setContentDescription("Resize Smart S workspace panes");
        workspace.addView(divider, dividerParams(activity, horizontal));
        workspace.addView(secondPane, paneParams(horizontal, 100 - splitPercent));

        SmartWorkspaceController controller = new SmartWorkspaceController(
                activity, prefs, workspace, widgetArea, firstPane, secondPane, divider, horizontal);
        controller.configureDivider();
        controller.observeWidgetPaneSize();
        return controller;
    }

    private void configureDivider() {
        divider.setClickable(prefs.getBoolean(PREF_DRAGGABLE, true));
        divider.setOnTouchListener((view, event) -> {
            if (!prefs.getBoolean(PREF_DRAGGABLE, true)) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateSplitFromTouch(event);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    updateSplitFromTouch(event);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void updateSplitFromTouch(MotionEvent event) {
        int[] location = new int[2];
        workspace.getLocationOnScreen(location);
        int total = horizontal ? workspace.getWidth() : workspace.getHeight();
        int dividerSize = horizontal ? divider.getWidth() : divider.getHeight();
        int usable = Math.max(1, total - dividerSize);
        float coordinate = horizontal
                ? event.getRawX() - location[0]
                : event.getRawY() - location[1];
        int percent = clamp(Math.round((coordinate / usable) * 100f), MIN_PANE_PERCENT, MAX_PANE_PERCENT);
        applySplit(percent);
        prefs.edit().putInt(PREF_SPLIT_PERCENT, percent).apply();
    }

    private void applySplit(int firstPercent) {
        firstPane.setLayoutParams(paneParams(horizontal, firstPercent));
        secondPane.setLayoutParams(paneParams(horizontal, 100 - firstPercent));
        workspace.requestLayout();
        workspace.post(this::updateWidgetSizeHints);
    }

    private void observeWidgetPaneSize() {
        widgetArea.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                v.post(this::updateWidgetSizeHints);
            }
        });
        widgetArea.post(this::updateWidgetSizeHints);
    }

    /**
     * Tell widget providers their real pane width after every split resize. Host views already use
     * MATCH_PARENT, while updateAppWidgetSize lets responsive widgets choose the correct layout.
     */
    private void updateWidgetSizeHints() {
        float density = activity.getResources().getDisplayMetrics().density;
        int availableWidthDp = Math.max(1, Math.round(widgetArea.getWidth() / density));
        for (int i = 0; i < widgetArea.getChildCount(); i++) {
            View child = widgetArea.getChildAt(i);
            if (!(child instanceof AppWidgetHostView)) continue;
            AppWidgetHostView hostView = (AppWidgetHostView) child;
            int heightPx = child.getHeight() > 0 ? child.getHeight() : child.getMeasuredHeight();
            int heightDp = Math.max(1, Math.round(heightPx / density));
            hostView.updateAppWidgetSize(null,
                    availableWidthDp, heightDp,
                    availableWidthDp, heightDp);
        }
    }

    private static LinearLayout.LayoutParams paneParams(boolean horizontal, int weight) {
        if (horizontal) {
            return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        }
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private static LinearLayout.LayoutParams dividerParams(MainActivity activity, boolean horizontal) {
        int size = dp(activity, 8);
        if (horizontal) {
            return new LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(MainActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
