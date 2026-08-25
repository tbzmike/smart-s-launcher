package fr.neamar.kiss.ui;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;

import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;

/**
 * Opt-in flexible launcher workspace. Two- and four-pane geometry share an explicit assignment
 * policy that keeps the existing history/results and widget containers authoritative instead of
 * cloning/replacing them.
 */
public final class SmartWorkspaceController {
    public static final String PREF_ENABLED = "smart-workspace-enabled";
    public static final String PREF_LAYOUT_MODE = "smart-workspace-layout-mode";
    public static final String PREF_ORIENTATION = "smart-workspace-orientation";
    public static final String PREF_PRIMARY_CONTENT = "smart-workspace-primary-content";
    public static final String PREF_SPLIT_PERCENT = "smart-workspace-split-percent";
    public static final String PREF_QUADRANT_COLUMN_PERCENT = "smart-workspace-quadrant-column-percent";
    public static final String PREF_QUADRANT_ROW_PERCENT = "smart-workspace-quadrant-row-percent";
    public static final String PREF_DRAGGABLE = "smart-workspace-draggable";
    public static final String PREF_EMPTY_ADD_WIDGET = "smart-workspace-empty-add-widget";
    public static final String PREF_EMPTY_GESTURES = "smart-workspace-empty-gestures";
    public static final String PREF_FREE_WIDGET_RESIZE = "smart-workspace-free-widget-resize";

    private static final String LAYOUT_TWO_PANE = "two-pane";
    private static final String LAYOUT_QUADRANTS = "quadrants";
    private static final String PREF_WIDGET_SIZE_PREFIX = "smart-workspace-widget-size-";
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
    private final boolean quadrantMode;
    private final LinearLayout topRow;
    private final LinearLayout bottomRow;
    private final View thirdPane;
    private final View fourthPane;
    private final View topColumnDivider;
    private final View bottomColumnDivider;
    private final View rowDivider;
    private final Set<Integer> resizeConfiguredWidgetIds = new HashSet<>();

    private AppWidgetHostView resizingWidget;
    private float resizeStartRawX;
    private float resizeStartRawY;
    private int resizeStartWidth;
    private int resizeStartHeight;

    private SmartWorkspaceController(MainActivity activity,
                                     SharedPreferences prefs,
                                     LinearLayout workspace,
                                     ViewGroup widgetArea,
                                     View firstPane,
                                     View secondPane,
                                     View divider,
                                     boolean horizontal,
                                     boolean quadrantMode,
                                     LinearLayout topRow,
                                     LinearLayout bottomRow,
                                     View thirdPane,
                                     View fourthPane,
                                     View topColumnDivider,
                                     View bottomColumnDivider,
                                     View rowDivider) {
        this.activity = activity;
        this.prefs = prefs;
        this.workspace = workspace;
        this.widgetArea = widgetArea;
        this.firstPane = firstPane;
        this.secondPane = secondPane;
        this.divider = divider;
        this.horizontal = horizontal;
        this.quadrantMode = quadrantMode;
        this.topRow = topRow;
        this.bottomRow = bottomRow;
        this.thirdPane = thirdPane;
        this.fourthPane = fourthPane;
        this.topColumnDivider = topColumnDivider;
        this.bottomColumnDivider = bottomColumnDivider;
        this.rowDivider = rowDivider;
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

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

        boolean legacyWidgetsFirst = "widgets".equals(
                prefs.getString(PREF_PRIMARY_CONTENT, "history"));

        String layoutMode = prefs.getString(PREF_LAYOUT_MODE, LAYOUT_TWO_PANE);
        if (LAYOUT_QUADRANTS.equals(layoutMode)) {
            int historyPosition = WorkspacePaneAssignments.readPosition(prefs,
                    WorkspacePaneAssignments.PREF_FOUR_PANE_HISTORY_POSITION,
                    legacyWidgetsFirst ? 2 : 1, 4);
            int widgetsPosition = WorkspacePaneAssignments.readPosition(prefs,
                    WorkspacePaneAssignments.PREF_FOUR_PANE_WIDGETS_POSITION,
                    legacyWidgetsFirst ? 1 : 2, 4);
            WorkspacePaneAssignments.Content[] assignments = WorkspacePaneAssignments.resolve(
                    4, historyPosition, widgetsPosition);
            View[] panes = createAssignedPanes(activity, assignments, historyPane, widgetScroller);
            return installQuadrants(activity, prefs, root, widgetArea, historyPane, widgetScroller,
                    panes, assignments, emptyView);
        }

        int historyPosition = WorkspacePaneAssignments.readPosition(prefs,
                WorkspacePaneAssignments.PREF_TWO_PANE_HISTORY_POSITION,
                legacyWidgetsFirst ? 2 : 1, 2);
        int widgetsPosition = historyPosition == 1 ? 2 : 1;
        WorkspacePaneAssignments.Content[] assignments = WorkspacePaneAssignments.resolve(
                2, historyPosition, widgetsPosition);
        View[] panes = createAssignedPanes(activity, assignments, historyPane, widgetScroller);
        return installTwoPane(activity, prefs, root, widgetArea, historyPane, widgetScroller,
                panes[0], panes[1], emptyView);
    }

    private static SmartWorkspaceController installTwoPane(MainActivity activity,
                                                            SharedPreferences prefs,
                                                            ViewGroup root,
                                                            ViewGroup widgetArea,
                                                            FrameLayout historyPane,
                                                            ScrollView widgetScroller,
                                                            View firstPane,
                                                            View secondPane,
                                                            View emptyView) {
        boolean horizontal = !"vertical".equals(prefs.getString(PREF_ORIENTATION, "horizontal"));
        LinearLayout workspace = new LinearLayout(activity);
        workspace.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        workspace.setWeightSum(100f);
        workspace.setClipChildren(false);
        workspace.setClipToPadding(false);
        addWorkspaceToRoot(activity, root, workspace);

        int splitPercent = clamp(prefs.getInt(PREF_SPLIT_PERCENT, 50), MIN_PANE_PERCENT, MAX_PANE_PERCENT);
        workspace.addView(firstPane, paneParams(horizontal, splitPercent));

        View divider = new View(activity);
        divider.setContentDescription("Resize Smart S workspace panes");
        workspace.addView(divider, dividerParams(activity, horizontal));
        workspace.addView(secondPane, paneParams(horizontal, 100 - splitPercent));

        SmartWorkspaceController controller = new SmartWorkspaceController(
                activity, prefs, workspace, widgetArea, firstPane, secondPane, divider, horizontal,
                false, null, null, null, null, null, null, null);
        controller.configureDivider();
        controller.configureEmptySurfaces(historyPane, emptyView, widgetScroller, widgetArea, workspace);
        controller.observeWidgetPaneSize();
        return controller;
    }

    private static SmartWorkspaceController installQuadrants(MainActivity activity,
                                                              SharedPreferences prefs,
                                                              ViewGroup root,
                                                              ViewGroup widgetArea,
                                                              FrameLayout historyPane,
                                                              ScrollView widgetScroller,
                                                              View[] panes,
                                                              WorkspacePaneAssignments.Content[] assignments,
                                                              View emptyView) {
        View firstPane = panes[0];
        View secondPane = panes[1];
        View thirdPane = panes[2];
        View fourthPane = panes[3];
        LinearLayout workspace = new LinearLayout(activity);
        workspace.setOrientation(LinearLayout.VERTICAL);
        workspace.setWeightSum(100f);
        workspace.setClipChildren(false);
        workspace.setClipToPadding(false);
        addWorkspaceToRoot(activity, root, workspace);

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setWeightSum(100f);
        topRow.setClipChildren(false);
        topRow.setClipToPadding(false);

        LinearLayout bottomRow = new LinearLayout(activity);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setWeightSum(100f);
        bottomRow.setClipChildren(false);
        bottomRow.setClipToPadding(false);

        int columnPercent = clamp(prefs.getInt(PREF_QUADRANT_COLUMN_PERCENT, 50),
                MIN_PANE_PERCENT, MAX_PANE_PERCENT);
        int rowPercent = clamp(prefs.getInt(PREF_QUADRANT_ROW_PERCENT, 50),
                MIN_PANE_PERCENT, MAX_PANE_PERCENT);

        topRow.addView(firstPane, quadrantPaneParams(columnPercent));
        View topColumnDivider = new View(activity);
        topColumnDivider.setContentDescription("Resize Smart S workspace columns");
        topRow.addView(topColumnDivider, quadrantColumnDividerParams(activity));
        topRow.addView(secondPane, quadrantPaneParams(100 - columnPercent));

        bottomRow.addView(thirdPane, quadrantPaneParams(columnPercent));
        View bottomColumnDivider = new View(activity);
        bottomColumnDivider.setContentDescription("Resize Smart S workspace columns");
        bottomRow.addView(bottomColumnDivider, quadrantColumnDividerParams(activity));
        bottomRow.addView(fourthPane, quadrantPaneParams(100 - columnPercent));

        workspace.addView(topRow, quadrantRowParams(rowPercent));
        View rowDivider = new View(activity);
        rowDivider.setContentDescription("Resize Smart S workspace rows");
        workspace.addView(rowDivider, quadrantRowDividerParams(activity));
        workspace.addView(bottomRow, quadrantRowParams(100 - rowPercent));

        SmartWorkspaceController controller = new SmartWorkspaceController(
                activity, prefs, workspace, widgetArea, firstPane, secondPane, null, false,
                true, topRow, bottomRow, thirdPane, fourthPane,
                topColumnDivider, bottomColumnDivider, rowDivider);
        controller.configureQuadrantDividers();
        controller.configureEmptySurfaces(historyPane, emptyView, widgetScroller, widgetArea, workspace);
        for (int i = 0; i < panes.length; i++) {
            if (assignments[i] == WorkspacePaneAssignments.Content.EMPTY) {
                controller.configureEmptySurfaces(panes[i]);
            }
        }
        controller.observeWidgetPaneSize();
        return controller;
    }

    private static View[] createAssignedPanes(
            MainActivity activity,
            WorkspacePaneAssignments.Content[] assignments,
            FrameLayout historyPane,
            ScrollView widgetScroller) {
        View[] panes = new View[assignments.length];
        for (int i = 0; i < assignments.length; i++) {
            switch (assignments[i]) {
                case APPS_AND_HISTORY:
                    panes[i] = historyPane;
                    break;
                case WIDGETS:
                    panes[i] = widgetScroller;
                    break;
                default:
                    panes[i] = createEmptyPane(activity,
                            "Smart S workspace pane " + (i + 1) + " · Empty");
                    break;
            }
        }
        return panes;
    }

    private static FrameLayout createEmptyPane(MainActivity activity, String description) {
        FrameLayout pane = new FrameLayout(activity);
        pane.setClipChildren(false);
        pane.setClipToPadding(false);
        pane.setContentDescription(description);
        pane.setFocusable(true);
        return pane;
    }

    private static void addWorkspaceToRoot(MainActivity activity, ViewGroup root, LinearLayout workspace) {
        RelativeLayout.LayoutParams workspaceParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        workspaceParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        workspaceParams.addRule(RelativeLayout.ABOVE, R.id.externalFavoriteBar);
        int margin = dp(activity, 10);
        workspaceParams.setMargins(margin, margin, margin, margin);
        root.addView(workspace, 0, workspaceParams);
    }

    private void configureEmptySurfaces(View... surfaces) {
        boolean gesturesEnabled = prefs.getBoolean(PREF_EMPTY_GESTURES, true);
        boolean longPressWidgetsEnabled = prefs.getBoolean(PREF_EMPTY_ADD_WIDGET, true);
        for (View surface : surfaces) {
            if (surface == null) continue;
            if (gesturesEnabled) surface.setOnTouchListener(activity);
            if (longPressWidgetsEnabled) activity.registerForContextMenu(surface);
        }
    }

    private void configureDivider() {
        boolean draggable = prefs.getBoolean(PREF_DRAGGABLE, true);
        if (!draggable) {
            divider.setClickable(false);
            divider.setOnTouchListener(null);
            divider.setBackgroundColor(Color.TRANSPARENT);
            divider.setVisibility(View.GONE);
            return;
        }

        divider.setVisibility(View.VISIBLE);
        divider.setBackgroundColor(Color.argb(105, 255, 255, 255));
        divider.setClickable(true);
        divider.setOnTouchListener((view, event) -> {
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

    private void configureQuadrantDividers() {
        boolean draggable = prefs.getBoolean(PREF_DRAGGABLE, true);
        if (!draggable) {
            hideDivider(topColumnDivider);
            hideDivider(bottomColumnDivider);
            hideDivider(rowDivider);
            return;
        }

        configureColumnDivider(topColumnDivider);
        configureColumnDivider(bottomColumnDivider);
        configureRowDivider(rowDivider);
    }

    private void hideDivider(View view) {
        if (view == null) return;
        view.setClickable(false);
        view.setOnTouchListener(null);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setVisibility(View.GONE);
    }

    private void configureColumnDivider(View view) {
        view.setVisibility(View.VISIBLE);
        view.setBackgroundColor(Color.argb(105, 255, 255, 255));
        view.setClickable(true);
        view.setOnTouchListener((dividerView, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    workspace.requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateQuadrantColumnFromTouch(event);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    workspace.requestDisallowInterceptTouchEvent(false);
                    updateQuadrantColumnFromTouch(event);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void configureRowDivider(View view) {
        view.setVisibility(View.VISIBLE);
        view.setBackgroundColor(Color.argb(105, 255, 255, 255));
        view.setClickable(true);
        view.setOnTouchListener((dividerView, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    workspace.requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateQuadrantRowFromTouch(event);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    workspace.requestDisallowInterceptTouchEvent(false);
                    updateQuadrantRowFromTouch(event);
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

    private void updateQuadrantColumnFromTouch(MotionEvent event) {
        int[] location = new int[2];
        workspace.getLocationOnScreen(location);
        int dividerSize = Math.max(topColumnDivider.getWidth(), bottomColumnDivider.getWidth());
        int usable = Math.max(1, workspace.getWidth() - dividerSize);
        float coordinate = event.getRawX() - location[0];
        int percent = clamp(Math.round((coordinate / usable) * 100f), MIN_PANE_PERCENT, MAX_PANE_PERCENT);
        applyQuadrantColumnSplit(percent);
        prefs.edit().putInt(PREF_QUADRANT_COLUMN_PERCENT, percent).apply();
    }

    private void updateQuadrantRowFromTouch(MotionEvent event) {
        int[] location = new int[2];
        workspace.getLocationOnScreen(location);
        int usable = Math.max(1, workspace.getHeight() - rowDivider.getHeight());
        float coordinate = event.getRawY() - location[1];
        int percent = clamp(Math.round((coordinate / usable) * 100f), MIN_PANE_PERCENT, MAX_PANE_PERCENT);
        applyQuadrantRowSplit(percent);
        prefs.edit().putInt(PREF_QUADRANT_ROW_PERCENT, percent).apply();
    }

    private void applySplit(int firstPercent) {
        firstPane.setLayoutParams(paneParams(horizontal, firstPercent));
        secondPane.setLayoutParams(paneParams(horizontal, 100 - firstPercent));
        workspace.requestLayout();
        workspace.post(this::updateWidgetSizeHints);
    }

    private void applyQuadrantColumnSplit(int leftPercent) {
        if (!quadrantMode) return;
        firstPane.setLayoutParams(quadrantPaneParams(leftPercent));
        secondPane.setLayoutParams(quadrantPaneParams(100 - leftPercent));
        thirdPane.setLayoutParams(quadrantPaneParams(leftPercent));
        fourthPane.setLayoutParams(quadrantPaneParams(100 - leftPercent));
        topRow.requestLayout();
        bottomRow.requestLayout();
        workspace.post(this::updateWidgetSizeHints);
    }

    private void applyQuadrantRowSplit(int topPercent) {
        if (!quadrantMode) return;
        topRow.setLayoutParams(quadrantRowParams(topPercent));
        bottomRow.setLayoutParams(quadrantRowParams(100 - topPercent));
        workspace.requestLayout();
        workspace.post(this::updateWidgetSizeHints);
    }

    private void observeWidgetPaneSize() {
        widgetArea.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)
                    || (bottom - top) != (oldBottom - oldTop)) {
                v.post(this::updateWidgetSizeHints);
            }
        });
        widgetArea.post(this::updateWidgetSizeHints);
    }

    private void updateWidgetSizeHints() {
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < widgetArea.getChildCount(); i++) {
            View child = widgetArea.getChildAt(i);
            if (!(child instanceof AppWidgetHostView)) continue;
            AppWidgetHostView hostView = (AppWidgetHostView) child;
            configureFreeWidgetResize(hostView);

            int widthPx = child.getWidth() > 0 ? child.getWidth() : child.getMeasuredWidth();
            int heightPx = child.getHeight() > 0 ? child.getHeight() : child.getMeasuredHeight();
            int widthDp = Math.max(1, Math.round(widthPx / density));
            int heightDp = Math.max(1, Math.round(heightPx / density));
            hostView.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp);
        }
    }

    private void configureFreeWidgetResize(AppWidgetHostView hostView) {
        if (!prefs.getBoolean(PREF_FREE_WIDGET_RESIZE, true)) return;
        int appWidgetId = hostView.getAppWidgetId();
        if (!resizeConfiguredWidgetIds.add(appWidgetId)) return;

        applySavedWidgetSize(hostView);
        hostView.setOnTouchListener((view, event) -> {
            AppWidgetHostView widget = (AppWidgetHostView) view;
            int handle = dp(activity, 34);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (event.getX() < widget.getWidth() - handle
                            || event.getY() < widget.getHeight() - handle) {
                        return false;
                    }
                    resizingWidget = widget;
                    resizeStartRawX = event.getRawX();
                    resizeStartRawY = event.getRawY();
                    resizeStartWidth = Math.max(1, widget.getWidth());
                    resizeStartHeight = Math.max(1, widget.getHeight());
                    ViewParentUtils.disallowIntercept(widget, true);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (resizingWidget != widget) return false;
                    resizeWidget(widget,
                            resizeStartWidth + Math.round(event.getRawX() - resizeStartRawX),
                            resizeStartHeight + Math.round(event.getRawY() - resizeStartRawY));
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (resizingWidget != widget) return false;
                    resizeWidget(widget,
                            resizeStartWidth + Math.round(event.getRawX() - resizeStartRawX),
                            resizeStartHeight + Math.round(event.getRawY() - resizeStartRawY));
                    saveWidgetSize(widget);
                    ViewParentUtils.disallowIntercept(widget, false);
                    resizingWidget = null;
                    return true;

                default:
                    return false;
            }
        });
    }

    private void resizeWidget(AppWidgetHostView hostView, int requestedWidth, int requestedHeight) {
        AppWidgetProviderInfo info = hostView.getAppWidgetInfo();
        int paneWidth = Math.max(1, widgetArea.getWidth());
        int minWidth = dp(activity, 48);
        int minHeight = dp(activity, 48);
        int maxWidth = paneWidth;
        int maxHeight = Math.max(workspace.getHeight() * 3, dp(activity, 200));

        if (info != null) {
            int providerMinWidth = info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
            int providerMinHeight = info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
            minWidth = Math.max(minWidth, providerMinWidth);
            minHeight = Math.max(minHeight, providerMinHeight);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (info.maxResizeWidth > 0) maxWidth = Math.min(maxWidth, info.maxResizeWidth);
                if (info.maxResizeHeight > 0) maxHeight = Math.min(maxHeight, info.maxResizeHeight);
            }
        }

        int width = clamp(requestedWidth, Math.min(minWidth, maxWidth), Math.max(minWidth, maxWidth));
        int height = clamp(requestedHeight, Math.min(minHeight, maxHeight), Math.max(minHeight, maxHeight));

        ViewGroup.LayoutParams raw = hostView.getLayoutParams();
        LinearLayout.LayoutParams params;
        if (raw instanceof LinearLayout.LayoutParams) {
            params = (LinearLayout.LayoutParams) raw;
        } else {
            params = new LinearLayout.LayoutParams(width, height);
        }
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER_HORIZONTAL;
        hostView.setLayoutParams(params);
        hostView.requestLayout();
        hostView.post(this::updateWidgetSizeHints);
    }

    private void saveWidgetSize(AppWidgetHostView hostView) {
        float density = activity.getResources().getDisplayMetrics().density;
        int widthDp = Math.max(1, Math.round(hostView.getWidth() / density));
        int heightDp = Math.max(1, Math.round(hostView.getHeight() / density));
        prefs.edit().putString(widgetSizeKey(hostView.getAppWidgetId()), widthDp + "x" + heightDp).apply();
    }

    private void applySavedWidgetSize(AppWidgetHostView hostView) {
        String saved = prefs.getString(widgetSizeKey(hostView.getAppWidgetId()), "");
        if (saved == null || saved.isEmpty()) return;
        String[] parts = saved.split("x", 2);
        if (parts.length != 2) return;
        try {
            float density = activity.getResources().getDisplayMetrics().density;
            int width = Math.round(Integer.parseInt(parts[0]) * density);
            int height = Math.round(Integer.parseInt(parts[1]) * density);
            hostView.post(() -> resizeWidget(hostView, width, height));
        } catch (NumberFormatException ignored) {
            // Ignore malformed legacy/user preference values and keep provider default sizing.
        }
    }

    private static String widgetSizeKey(int appWidgetId) {
        return PREF_WIDGET_SIZE_PREFIX + appWidgetId;
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

    private static LinearLayout.LayoutParams quadrantPaneParams(int weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
    }

    private static LinearLayout.LayoutParams quadrantRowParams(int weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private static LinearLayout.LayoutParams quadrantColumnDividerParams(MainActivity activity) {
        return new LinearLayout.LayoutParams(dp(activity, 8), ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams quadrantRowDividerParams(MainActivity activity) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 8));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(MainActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class ViewParentUtils {
        private ViewParentUtils() {}

        static void disallowIntercept(View view, boolean disallow) {
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        }
    }
}
