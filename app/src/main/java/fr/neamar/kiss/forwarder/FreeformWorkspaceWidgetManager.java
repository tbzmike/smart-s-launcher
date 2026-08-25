package fr.neamar.kiss.forwarder;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.PickAppWidgetActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.SmartAnimationEngine;
import fr.neamar.kiss.ui.WidgetHost;
import fr.neamar.kiss.utils.Log;

/**
 * Workspace-only widget host. Android AppWidgetHost remains the provider transport while Smart S
 * owns freeform placement, eight-direction resizing, widget stacks, stack swiping and persistence.
 */
final class FreeformWorkspaceWidgetManager {
    private static final String TAG = FreeformWorkspaceWidgetManager.class.getSimpleName();
    private static final int HOST_ID = 8442;
    private static final String PREF_STATE = "smart-workspace-widgets-v2";
    private static final String PREF_PENDING_ID = "smart-workspace-widget-pending-id";
    private static final String PREF_PENDING_STACK_ROOT = "smart-workspace-widget-pending-stack-root";

    private static final int LEFT = 1;
    private static final int RIGHT = 1 << 1;
    private static final int TOP = 1 << 2;
    private static final int BOTTOM = 1 << 3;

    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final AppWidgetManager appWidgetManager;
    private final WidgetHost host;
    private final LinearLayout legacyWidgetArea;
    private final FrameLayout surface;
    private final List<WidgetState> states = new ArrayList<>();

    private ActivityResultLauncher<Intent> pickerLauncher;
    private ActivityResultLauncher<Intent> bindLauncher;
    private ActivityResultLauncher<Intent> configureLauncher;
    private FreeformWidgetFrame editingFrame;
    private FreeformWidgetFrame pendingStackFrame;
    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int pendingStackRootId = AppWidgetManager.INVALID_APPWIDGET_ID;

    FreeformWorkspaceWidgetManager(MainActivity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
        this.appWidgetManager = AppWidgetManager.getInstance(activity);
        this.host = new WidgetHost(activity, HOST_ID, this::restoreWidgets);
        this.legacyWidgetArea = activity.findViewById(R.id.widgetLayout);
        this.surface = new FrameLayout(activity);
    }

    void onCreate() {
        configureSurface();
        registerActivityResults();
        restorePendingId();
        restoreWidgets();
        resolvePendingStackFrame();
        host.startListening();
    }

    void onStart() {
        host.startListening();
    }

    void onDestroy() {
        host.stopListening();
    }

    void startAddWidget() {
        beginWidgetPick(null);
    }

    private void startAddWidgetToStack(FreeformWidgetFrame frame) {
        beginWidgetPick(frame);
    }

    private void beginWidgetPick(FreeformWidgetFrame stackFrame) {
        if (pickerLauncher == null) return;
        exitEditMode();
        pendingStackFrame = stackFrame;
        pendingStackRootId = stackFrame == null
                ? AppWidgetManager.INVALID_APPWIDGET_ID : stackFrame.state.primaryId();
        pendingWidgetId = host.allocateAppWidgetId();
        SharedPreferences.Editor editor = prefs.edit().putInt(PREF_PENDING_ID, pendingWidgetId);
        if (pendingStackRootId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            editor.remove(PREF_PENDING_STACK_ROOT);
        } else {
            editor.putInt(PREF_PENDING_STACK_ROOT, pendingStackRootId);
        }
        editor.apply();
        Intent pickIntent = new Intent(activity, PickAppWidgetActivity.class);
        pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        pickerLauncher.launch(pickIntent);
    }

    private void configureSurface() {
        legacyWidgetArea.removeAllViews();
        legacyWidgetArea.setPadding(0, 0, 0, 0);
        legacyWidgetArea.setGravity(Gravity.NO_GRAVITY);

        surface.setClipChildren(false);
        surface.setClipToPadding(false);
        surface.setBackgroundColor(Color.TRANSPARENT);
        surface.setLongClickable(true);
        surface.setOnLongClickListener(v -> {
            if (!prefs.getBoolean("smart-workspace-empty-add-widget", true)) return false;
            if (prefs.getBoolean("smart-ui-locked", false)) return false;
            startAddWidget();
            return true;
        });
        if (prefs.getBoolean("smart-workspace-empty-gestures", true)) {
            surface.setOnTouchListener((v, event) -> activity.onTouch(v, event));
        }

        LinearLayout.LayoutParams surfaceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, activity.getResources().getDisplayMetrics().heightPixels));
        legacyWidgetArea.addView(surface, surfaceParams);

        legacyWidgetArea.post(() -> {
            View parent = legacyWidgetArea.getParent() instanceof View
                    ? (View) legacyWidgetArea.getParent() : null;
            int viewportHeight = parent == null ? 0 : parent.getHeight();
            if (viewportHeight > 0) {
                ViewGroup.LayoutParams params = surface.getLayoutParams();
                params.height = viewportHeight;
                surface.setLayoutParams(params);
            }
            applyAllBounds();
        });

        surface.addOnLayoutChangeListener((v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)
                    || (bottom - top) != (oldBottom - oldTop)) {
                applyAllBounds();
            }
        });
    }

    private void registerActivityResults() {
        pickerLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != Activity.RESULT_OK || data == null) {
                        abandonPendingWidget();
                        return;
                    }
                    int id = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
                    if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
                        abandonPendingWidget();
                        return;
                    }
                    pendingWidgetId = id;
                    prefs.edit().putInt(PREF_PENDING_ID, id).apply();
                    if (data.getBooleanExtra(PickAppWidgetActivity.EXTRA_WIDGET_BIND_ALLOWED, false)) {
                        configureOrAdd(id);
                        return;
                    }

                    Intent bindIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
                    bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    if (data.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER)) {
                        bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
                                (android.os.Parcelable) data.getParcelableExtra(
                                        AppWidgetManager.EXTRA_APPWIDGET_PROVIDER));
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                            && data.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE)) {
                        bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,
                                (android.os.Parcelable) data.getParcelableExtra(
                                        AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE));
                    }
                    bindLauncher.launch(bindIntent);
                });

        bindLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        configureOrAdd(pendingWidgetId);
                    } else {
                        abandonPendingWidget();
                    }
                });

        configureLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        addNewWidget(pendingWidgetId);
                    } else {
                        abandonPendingWidget();
                    }
                });
    }

    private void restorePendingId() {
        pendingWidgetId = prefs.getInt(PREF_PENDING_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        pendingStackRootId = prefs.getInt(PREF_PENDING_STACK_ROOT,
                AppWidgetManager.INVALID_APPWIDGET_ID);
    }

    private void resolvePendingStackFrame() {
        if (pendingStackRootId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            pendingStackFrame = null;
            return;
        }
        pendingStackFrame = findFrameContaining(pendingStackRootId);
    }

    private FreeformWidgetFrame findFrameContaining(int appWidgetId) {
        for (int i = 0; i < surface.getChildCount(); i++) {
            View child = surface.getChildAt(i);
            if (child instanceof FreeformWidgetFrame) {
                FreeformWidgetFrame frame = (FreeformWidgetFrame) child;
                if (frame.state.appWidgetIds.contains(appWidgetId)) return frame;
            }
        }
        return null;
    }

    private void configureOrAdd(int appWidgetId) {
        AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(appWidgetId);
        if (info == null) {
            abandonPendingWidget();
            return;
        }
        if (info.configure == null) {
            addNewWidget(appWidgetId);
            return;
        }
        Intent configureIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
        configureIntent.setComponent(info.configure);
        configureIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        try {
            configureLauncher.launch(configureIntent);
        } catch (RuntimeException e) {
            Log.w(TAG, "Widget configuration activity could not be opened", e);
            addNewWidget(appWidgetId);
        }
    }

    private void addNewWidget(int appWidgetId) {
        AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(appWidgetId);
        if (info == null) {
            abandonPendingWidget();
            return;
        }

        resolvePendingStackFrame();
        if (pendingStackFrame != null && pendingStackFrame.getParent() == surface) {
            pendingStackFrame.addWidgetToStack(appWidgetId, info);
            saveState();
            clearPendingId();
            pendingStackFrame = null;
            pendingStackRootId = AppWidgetManager.INVALID_APPWIDGET_ID;
            return;
        }

        int surfaceWidth = Math.max(1, surface.getWidth());
        int surfaceHeight = Math.max(1, surface.getHeight());
        int minWidth = providerMinWidth(info);
        int minHeight = providerMinHeight(info);
        int initialWidth = clamp(Math.max(minWidth, Math.round(surfaceWidth * 0.62f)),
                Math.min(minWidth, surfaceWidth), surfaceWidth);
        int initialHeight = clamp(Math.max(minHeight, Math.round(surfaceHeight * 0.28f)),
                Math.min(minHeight, surfaceHeight), surfaceHeight);
        int left = Math.max(0, (surfaceWidth - initialWidth) / 2);
        int top = Math.max(0, (surfaceHeight - initialHeight) / 2);

        WidgetState state = WidgetState.fromPixels(appWidgetId, left, top,
                initialWidth, initialHeight, surfaceWidth, surfaceHeight);
        states.add(state);
        addFrame(state, true);
        saveState();
        clearPendingId();
    }

    private void abandonPendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                host.deleteAppWidgetId(pendingWidgetId);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to discard pending workspace widget id", e);
            }
        }
        pendingStackFrame = null;
        pendingStackRootId = AppWidgetManager.INVALID_APPWIDGET_ID;
        clearPendingId();
    }

    private void clearPendingId() {
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        prefs.edit().remove(PREF_PENDING_ID).remove(PREF_PENDING_STACK_ROOT).apply();
    }

    private void restoreWidgets() {
        readState();
        surface.removeAllViews();
        editingFrame = null;
        Set<Integer> usedIds = new HashSet<>();
        List<WidgetState> invalidStates = new ArrayList<>();
        boolean changed = false;

        for (WidgetState state : states) {
            Iterator<Integer> iterator = state.appWidgetIds.iterator();
            while (iterator.hasNext()) {
                int id = iterator.next();
                if (appWidgetManager.getAppWidgetInfo(id) == null) {
                    iterator.remove();
                    changed = true;
                } else {
                    usedIds.add(id);
                }
            }
            if (state.appWidgetIds.isEmpty()) {
                invalidStates.add(state);
                continue;
            }
            state.activeIndex = clamp(state.activeIndex, 0, state.appWidgetIds.size() - 1);
            addFrame(state, false);
        }

        if (!invalidStates.isEmpty()) {
            states.removeAll(invalidStates);
            changed = true;
        }
        if (changed) saveState();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (int hostId : host.getAppWidgetIds()) {
                if (!usedIds.contains(hostId) && hostId != pendingWidgetId) {
                    host.deleteAppWidgetId(hostId);
                }
            }
        }
        resolvePendingStackFrame();
        host.startListening();
    }

    private void addFrame(WidgetState state, boolean enterEdit) {
        List<AppWidgetHostView> hostViews = new ArrayList<>();
        for (int id : state.appWidgetIds) {
            AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(id);
            if (info == null) continue;
            AppWidgetHostView hostView = host.createView(activity, id, info);
            hostView.setAppWidget(id, info);
            hostViews.add(hostView);
        }
        if (hostViews.isEmpty()) return;

        FreeformWidgetFrame frame = new FreeformWidgetFrame(state, hostViews);
        surface.addView(frame, new FrameLayout.LayoutParams(1, 1));
        frame.post(() -> {
            applyBounds(frame);
            updateProviderSize(frame);
            if (enterEdit) enterEditMode(frame);
        });
    }

    private void enterEditMode(FreeformWidgetFrame frame) {
        if (prefs.getBoolean("smart-ui-locked", false)) return;
        if (!prefs.getBoolean("smart-workspace-free-widget-resize", true)) return;
        if (editingFrame != null && editingFrame != frame) editingFrame.setEditing(false);
        editingFrame = frame;
        frame.setEditing(true);
        bringFrameToFront(frame);
    }

    private void bringFrameToFront(FreeformWidgetFrame frame) {
        boolean changed = WidgetLayerOrder.bringToFront(states, frame.state);
        frame.bringToFront();
        if (changed) saveState();
    }

    private void sendFrameToBack(FreeformWidgetFrame frame) {
        if (!WidgetLayerOrder.sendToBack(states, frame.state)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) frame.getLayoutParams();
        surface.removeView(frame);
        surface.addView(frame, 0, params);
        saveState();
    }

    private void exitEditMode() {
        if (editingFrame != null) editingFrame.setEditing(false);
        editingFrame = null;
    }

    private void removeWidget(FreeformWidgetFrame frame) {
        states.remove(frame.state);
        surface.removeView(frame);
        for (int id : new ArrayList<>(frame.state.appWidgetIds)) {
            try {
                host.deleteAppWidgetId(id);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to remove workspace widget id", e);
            }
        }
        if (editingFrame == frame) editingFrame = null;
        saveState();
    }

    private void applyAllBounds() {
        for (int i = 0; i < surface.getChildCount(); i++) {
            View child = surface.getChildAt(i);
            if (child instanceof FreeformWidgetFrame) {
                FreeformWidgetFrame frame = (FreeformWidgetFrame) child;
                applyBounds(frame);
                updateProviderSize(frame);
            }
        }
    }

    private void applyBounds(FreeformWidgetFrame frame) {
        int sw = Math.max(1, surface.getWidth());
        int sh = Math.max(1, surface.getHeight());
        int width = clamp(Math.round(frame.state.width * sw), 1, sw);
        int height = clamp(Math.round(frame.state.height * sh), 1, sh);
        int left = clamp(Math.round(frame.state.left * sw), 0, Math.max(0, sw - width));
        int top = clamp(Math.round(frame.state.top * sh), 0, Math.max(0, sh - height));

        int minWidth = Math.min(frame.stackMinWidth(), sw);
        int minHeight = Math.min(frame.stackMinHeight(), sh);
        width = Math.max(width, minWidth);
        height = Math.max(height, minHeight);
        if (left + width > sw) left = Math.max(0, sw - width);
        if (top + height > sh) top = Math.max(0, sh - height);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) frame.getLayoutParams();
        params.width = width;
        params.height = height;
        frame.setLayoutParams(params);
        frame.setX(left);
        frame.setY(top);
    }

    private void saveFrameBounds(FreeformWidgetFrame frame) {
        int sw = Math.max(1, surface.getWidth());
        int sh = Math.max(1, surface.getHeight());
        frame.state.left = clamp01(frame.getX() / sw);
        frame.state.top = clamp01(frame.getY() / sh);
        frame.state.width = clamp01(frame.getWidth() / (float) sw);
        frame.state.height = clamp01(frame.getHeight() / (float) sh);
        saveState();
    }

    private void updateProviderSize(FreeformWidgetFrame frame) {
        float density = activity.getResources().getDisplayMetrics().density;
        int widthDp = Math.max(1, Math.round(frame.getWidth() / density));
        int heightDp = Math.max(1, Math.round(frame.getHeight() / density));
        for (AppWidgetHostView hostView : frame.hostViews) {
            hostView.updateAppWidgetSize(new Bundle(), widthDp, heightDp, widthDp, heightDp);
        }
    }

    private int providerMinWidth(AppWidgetProviderInfo info) {
        if (info == null) return dp(48);
        int value = info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
        return Math.max(dp(48), value);
    }

    private int providerMinHeight(AppWidgetProviderInfo info) {
        if (info == null) return dp(48);
        int value = info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
        return Math.max(dp(48), value);
    }

    private void readState() {
        states.clear();
        String raw = prefs.getString(PREF_STATE, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                WidgetState state = WidgetState.fromJson(object);
                if (state != null) states.add(state);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Unable to parse freeform widget state", e);
        }
    }

    private void saveState() {
        JSONArray array = new JSONArray();
        for (WidgetState state : states) array.put(state.toJson());
        prefs.edit().putString(PREF_STATE, array.toString()).apply();
    }

    private int accentColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            return value.data;
        }
        return Color.WHITE;
    }

    private GradientDrawable handleDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(accentColor());
        drawable.setStroke(dp(1), Color.WHITE);
        return drawable;
    }

    private GradientDrawable editBorderDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(dp(2), accentColor());
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable darkControlBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(205, 38, 38, 38));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.argb(190, 255, 255, 255));
        return bg;
    }

    private final class FreeformWidgetFrame extends FrameLayout {
        final WidgetState state;
        final List<AppWidgetHostView> hostViews = new ArrayList<>();
        final List<View> editControls = new ArrayList<>();
        final FrameLayout stackLayer;
        final TextView pageIndicator;
        final TextView removeFromStackControl;
        private final int touchSlop;
        private float swipeDownX;
        private float swipeDownY;
        private boolean interceptingStackSwipe;

        FreeformWidgetFrame(WidgetState state, List<AppWidgetHostView> initialViews) {
            super(activity);
            this.state = state;
            this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
            setClipChildren(false);
            setClipToPadding(false);

            stackLayer = new FrameLayout(activity);
            stackLayer.setClipChildren(true);
            stackLayer.setClipToPadding(true);
            addView(stackLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            hostViews.addAll(initialViews);
            for (int i = 0; i < hostViews.size(); i++) {
                AppWidgetHostView hostView = hostViews.get(i);
                stackLayer.addView(hostView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                hostView.setVisibility(i == state.activeIndex ? View.VISIBLE : View.GONE);
                hostView.setLongClickable(true);
                hostView.setOnLongClickListener(v -> {
                    enterEditMode(this);
                    return !prefs.getBoolean("smart-ui-locked", false);
                });
            }

            pageIndicator = new TextView(activity);
            pageIndicator.setTextColor(Color.WHITE);
            pageIndicator.setTextSize(11f);
            pageIndicator.setGravity(Gravity.CENTER);
            pageIndicator.setPadding(dp(7), dp(2), dp(7), dp(2));
            pageIndicator.setBackground(darkControlBackground());
            FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            indicatorParams.bottomMargin = dp(6);
            addView(pageIndicator, indicatorParams);

            setLongClickable(true);
            setOnLongClickListener(v -> {
                enterEditMode(this);
                return !prefs.getBoolean("smart-ui-locked", false);
            });

            addResizeHandle(Gravity.LEFT | Gravity.TOP, LEFT | TOP);
            addResizeHandle(Gravity.CENTER_HORIZONTAL | Gravity.TOP, TOP);
            addResizeHandle(Gravity.RIGHT | Gravity.TOP, RIGHT | TOP);
            addResizeHandle(Gravity.LEFT | Gravity.CENTER_VERTICAL, LEFT);
            addResizeHandle(Gravity.RIGHT | Gravity.CENTER_VERTICAL, RIGHT);
            addResizeHandle(Gravity.LEFT | Gravity.BOTTOM, LEFT | BOTTOM);
            addResizeHandle(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, BOTTOM);
            addResizeHandle(Gravity.RIGHT | Gravity.BOTTOM, RIGHT | BOTTOM);
            addMoveControl();
            addStackControl();
            addLayerControls();
            removeFromStackControl = addRemoveFromStackControl();
            addRemoveControl();
            updateStackChrome();
            setEditing(false);
        }

        AppWidgetHostView activeHostView() {
            if (hostViews.isEmpty()) return null;
            int index = clamp(state.activeIndex, 0, hostViews.size() - 1);
            return hostViews.get(index);
        }

        int stackMinWidth() {
            int result = dp(48);
            for (AppWidgetHostView view : hostViews) {
                result = Math.max(result, providerMinWidth(view.getAppWidgetInfo()));
            }
            return result;
        }

        int stackMinHeight() {
            int result = dp(48);
            for (AppWidgetHostView view : hostViews) {
                result = Math.max(result, providerMinHeight(view.getAppWidgetInfo()));
            }
            return result;
        }

        void addWidgetToStack(int appWidgetId, AppWidgetProviderInfo info) {
            AppWidgetHostView hostView = host.createView(activity, appWidgetId, info);
            hostView.setAppWidget(appWidgetId, info);
            hostView.setVisibility(View.GONE);
            hostView.setLongClickable(true);
            hostView.setOnLongClickListener(v -> {
                enterEditMode(this);
                return !prefs.getBoolean("smart-ui-locked", false);
            });
            stackLayer.addView(hostView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            hostViews.add(hostView);
            state.appWidgetIds.add(appWidgetId);
            int oldIndex = state.activeIndex;
            state.activeIndex = hostViews.size() - 1;
            updateProviderSize(this);
            showWidget(oldIndex, state.activeIndex, 1);
            updateStackChrome();
            saveState();
        }

        private void addResizeHandle(int gravity, int directions) {
            View handle = new View(activity);
            int size = dp(20);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, gravity);
            handle.setLayoutParams(params);
            handle.setBackground(handleDrawable());
            handle.setOnTouchListener(new ResizeTouchListener(this, directions));
            addView(handle);
            editControls.add(handle);
        }

        private void addMoveControl() {
            TextView move = new TextView(activity);
            move.setText("↕↔");
            move.setTextColor(Color.WHITE);
            move.setGravity(Gravity.CENTER);
            move.setTextSize(12f);
            move.setBackground(darkControlBackground());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(28),
                    Gravity.LEFT | Gravity.BOTTOM);
            params.leftMargin = dp(24);
            params.bottomMargin = dp(24);
            addView(move, params);
            move.setOnTouchListener(new MoveTouchListener(this));
            editControls.add(move);
        }

        private void addStackControl() {
            TextView add = new TextView(activity);
            add.setText("＋▤");
            add.setContentDescription("Add widget to stack");
            add.setTextColor(Color.WHITE);
            add.setGravity(Gravity.CENTER);
            add.setTextSize(13f);
            add.setBackground(darkControlBackground());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(28),
                    Gravity.LEFT | Gravity.TOP);
            params.leftMargin = dp(24);
            params.topMargin = dp(24);
            addView(add, params);
            add.setOnClickListener(v -> startAddWidgetToStack(this));
            editControls.add(add);
        }

        private void addLayerControls() {
            LinearLayout layers = new LinearLayout(activity);
            layers.setOrientation(LinearLayout.HORIZONTAL);
            layers.setGravity(Gravity.CENTER);

            TextView forward = layerButton("↑", "Bring widget to front");
            forward.setOnClickListener(v -> bringFrameToFront(this));
            TextView behind = layerButton("↓", "Send widget behind others");
            behind.setOnClickListener(v -> sendFrameToBack(this));
            layers.addView(forward, new LinearLayout.LayoutParams(dp(38), dp(28)));
            layers.addView(behind, new LinearLayout.LayoutParams(dp(38), dp(28)));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(76), dp(28),
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            params.topMargin = dp(24);
            addView(layers, params);
            editControls.add(layers);
        }

        private TextView layerButton(String symbol, String description) {
            TextView control = new TextView(activity);
            control.setText(symbol);
            control.setContentDescription(description);
            control.setTextColor(Color.WHITE);
            control.setGravity(Gravity.CENTER);
            control.setTextSize(17f);
            control.setBackground(darkControlBackground());
            control.setClickable(true);
            control.setFocusable(true);
            return control;
        }

        private TextView addRemoveFromStackControl() {
            TextView removeCurrent = new TextView(activity);
            removeCurrent.setText("−▤");
            removeCurrent.setContentDescription("Remove current widget from stack");
            removeCurrent.setTextColor(Color.WHITE);
            removeCurrent.setGravity(Gravity.CENTER);
            removeCurrent.setTextSize(13f);
            removeCurrent.setBackground(darkControlBackground());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(28),
                    Gravity.RIGHT | Gravity.BOTTOM);
            params.rightMargin = dp(24);
            params.bottomMargin = dp(24);
            addView(removeCurrent, params);
            removeCurrent.setOnClickListener(v -> removeCurrentFromStack());
            editControls.add(removeCurrent);
            return removeCurrent;
        }

        private void addRemoveControl() {
            TextView remove = new TextView(activity);
            remove.setText("×");
            remove.setTextColor(Color.WHITE);
            remove.setGravity(Gravity.CENTER);
            remove.setTextSize(20f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(210, 170, 30, 30));
            bg.setShape(GradientDrawable.OVAL);
            remove.setBackground(bg);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(30), dp(30),
                    Gravity.RIGHT | Gravity.TOP);
            params.rightMargin = dp(24);
            params.topMargin = dp(24);
            addView(remove, params);
            remove.setOnClickListener(v -> removeWidget(this));
            editControls.add(remove);
        }

        void setEditing(boolean editing) {
            setBackground(editing ? editBorderDrawable() : null);
            for (View control : editControls) {
                control.setVisibility(editing ? View.VISIBLE : View.GONE);
            }
            if (editing && removeFromStackControl != null) {
                removeFromStackControl.setVisibility(hostViews.size() > 1 ? View.VISIBLE : View.GONE);
            }
            updateStackChrome();
        }

        private void updateStackChrome() {
            if (pageIndicator == null) return;
            if (hostViews.size() <= 1) {
                pageIndicator.setVisibility(View.GONE);
            } else {
                pageIndicator.setVisibility(View.VISIBLE);
                pageIndicator.setText(stackIndicatorText());
            }
            if (removeFromStackControl != null && editingFrame == this) {
                removeFromStackControl.setVisibility(hostViews.size() > 1 ? View.VISIBLE : View.GONE);
            }
        }

        private String stackIndicatorText() {
            int count = hostViews.size();
            int active = state.activeIndex;
            if (count <= 6) {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    if (i > 0) builder.append(' ');
                    builder.append(i == active ? '●' : '○');
                }
                return builder.toString();
            }
            return (active + 1) + " / " + count;
        }

        private void removeCurrentFromStack() {
            if (hostViews.size() <= 1) return;
            int index = clamp(state.activeIndex, 0, hostViews.size() - 1);
            int id = state.appWidgetIds.remove(index);
            AppWidgetHostView view = hostViews.remove(index);
            stackLayer.removeView(view);
            try {
                host.deleteAppWidgetId(id);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to remove widget from stack", e);
            }
            state.activeIndex = Math.min(index, hostViews.size() - 1);
            for (int i = 0; i < hostViews.size(); i++) {
                hostViews.get(i).setVisibility(i == state.activeIndex ? View.VISIBLE : View.GONE);
            }
            updateStackChrome();
            updateProviderSize(this);
            saveState();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && !prefs.getBoolean("smart-ui-locked", false)
                    && prefs.getBoolean("smart-workspace-free-widget-resize", true)) {
                bringFrameToFront(this);
            }
            if (hostViews.size() <= 1) return super.onInterceptTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeDownX = event.getX();
                    swipeDownY = event.getY();
                    interceptingStackSwipe = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - swipeDownX;
                    float dy = event.getY() - swipeDownY;
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.25f) {
                        int direction = dy < 0 ? 1 : -1;
                        boolean edgeGesture = swipeDownX < dp(28) || swipeDownX > getWidth() - dp(28);
                        AppWidgetHostView active = activeHostView();
                        if (!edgeGesture && active != null && canScrollVerticallyDeep(active, direction)) {
                            return false;
                        }
                        interceptingStackSwipe = true;
                        requestNoIntercept(this, true);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    requestNoIntercept(this, false);
                    interceptingStackSwipe = false;
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (hostViews.size() <= 1) return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    return interceptingStackSwipe;
                case MotionEvent.ACTION_UP:
                    if (!interceptingStackSwipe) return super.onTouchEvent(event);
                    float dy = event.getY() - swipeDownY;
                    requestNoIntercept(this, false);
                    interceptingStackSwipe = false;
                    if (Math.abs(dy) >= Math.max(dp(36), touchSlop * 2)) {
                        switchBy(dy < 0 ? 1 : -1);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    requestNoIntercept(this, false);
                    interceptingStackSwipe = false;
                    return true;
                default:
                    return true;
            }
        }

        private boolean canScrollVerticallyDeep(View view, int direction) {
            if (view.canScrollVertically(direction)) return true;
            if (!(view instanceof ViewGroup)) return false;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE
                        && canScrollVerticallyDeep(child, direction)) return true;
            }
            return false;
        }

        private void switchBy(int delta) {
            int count = hostViews.size();
            if (count <= 1) return;
            int oldIndex = state.activeIndex;
            int nextIndex = (oldIndex + delta + count) % count;
            if (nextIndex == oldIndex) return;
            state.activeIndex = nextIndex;
            showWidget(oldIndex, nextIndex, delta);
            updateStackChrome();
            saveState();
        }

        private void showWidget(int oldIndex, int newIndex, int direction) {
            if (hostViews.isEmpty()) return;
            oldIndex = clamp(oldIndex, 0, hostViews.size() - 1);
            newIndex = clamp(newIndex, 0, hostViews.size() - 1);
            AppWidgetHostView outgoing = hostViews.get(oldIndex);
            AppWidgetHostView incoming = hostViews.get(newIndex);
            if (outgoing == incoming) {
                incoming.setVisibility(View.VISIBLE);
                return;
            }

            incoming.animate().cancel();
            outgoing.animate().cancel();
            incoming.setVisibility(View.VISIBLE);
            incoming.bringToFront();
            pageIndicator.bringToFront();
            for (View control : editControls) control.bringToFront();

            if (!SmartAnimationEngine.isEnabled(activity)) {
                outgoing.setVisibility(View.GONE);
                SmartAnimationEngine.reset(outgoing);
                SmartAnimationEngine.reset(incoming);
                return;
            }

            long duration = Math.max(120L, SmartAnimationEngine.duration(activity));
            String style = SmartAnimationEngine.getStyle(activity,
                    "smart-animation-view-switch", "slide");
            float distance = Math.max(dp(48), getHeight() * 0.32f);
            SmartAnimationEngine.reset(incoming);
            SmartAnimationEngine.reset(outgoing);
            incoming.setAlpha(0f);

            if ("zoom".equals(style)) {
                incoming.setScaleX(0.86f);
                incoming.setScaleY(0.86f);
                outgoing.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f)
                        .setDuration(duration).start();
            } else if ("depth".equals(style)) {
                incoming.setScaleX(0.94f);
                incoming.setScaleY(0.94f);
                incoming.setTranslationY(direction > 0 ? distance * 0.55f : -distance * 0.55f);
                outgoing.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                        .translationY(direction > 0 ? -distance * 0.35f : distance * 0.35f)
                        .setDuration(duration).start();
            } else if ("crossfade".equals(style)) {
                outgoing.animate().alpha(0f).setDuration(duration).start();
            } else {
                incoming.setTranslationY(direction > 0 ? distance : -distance);
                outgoing.animate().alpha(0f)
                        .translationY(direction > 0 ? -distance : distance)
                        .setDuration(duration).start();
            }

            incoming.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(duration)
                    .withEndAction(() -> {
                        outgoing.setVisibility(View.GONE);
                        SmartAnimationEngine.reset(outgoing);
                        SmartAnimationEngine.reset(incoming);
                    }).start();
        }
    }

    private final class ResizeTouchListener implements View.OnTouchListener {
        private final FreeformWidgetFrame frame;
        private final int directions;
        private float startRawX;
        private float startRawY;
        private float startX;
        private float startY;
        private int startWidth;
        private int startHeight;

        ResizeTouchListener(FreeformWidgetFrame frame, int directions) {
            this.frame = frame;
            this.directions = directions;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    enterEditMode(frame);
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startX = frame.getX();
                    startY = frame.getY();
                    startWidth = frame.getWidth();
                    startHeight = frame.getHeight();
                    requestNoIntercept(frame, true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    resizeFromTouch(event, false);
                    return true;
                case MotionEvent.ACTION_UP:
                    resizeFromTouch(event, true);
                    requestNoIntercept(frame, false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    requestNoIntercept(frame, false);
                    return true;
                default:
                    return false;
            }
        }

        private void resizeFromTouch(MotionEvent event, boolean persist) {
            int sw = Math.max(1, surface.getWidth());
            int sh = Math.max(1, surface.getHeight());
            int minWidth = Math.min(frame.stackMinWidth(), sw);
            int minHeight = Math.min(frame.stackMinHeight(), sh);
            float dx = event.getRawX() - startRawX;
            float dy = event.getRawY() - startRawY;

            float left = startX;
            float top = startY;
            float right = startX + startWidth;
            float bottom = startY + startHeight;
            if ((directions & LEFT) != 0) left += dx;
            if ((directions & RIGHT) != 0) right += dx;
            if ((directions & TOP) != 0) top += dy;
            if ((directions & BOTTOM) != 0) bottom += dy;

            left = clampFloat(left, 0, right - minWidth);
            right = clampFloat(right, left + minWidth, sw);
            top = clampFloat(top, 0, bottom - minHeight);
            bottom = clampFloat(bottom, top + minHeight, sh);

            int width = Math.max(minWidth, Math.round(right - left));
            int height = Math.max(minHeight, Math.round(bottom - top));
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) frame.getLayoutParams();
            params.width = width;
            params.height = height;
            frame.setLayoutParams(params);
            frame.setX(left);
            frame.setY(top);
            updateProviderSize(frame);
            if (persist) saveFrameBounds(frame);
        }
    }

    private final class MoveTouchListener implements View.OnTouchListener {
        private final FreeformWidgetFrame frame;
        private float startRawX;
        private float startRawY;
        private float startX;
        private float startY;

        MoveTouchListener(FreeformWidgetFrame frame) {
            this.frame = frame;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    enterEditMode(frame);
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startX = frame.getX();
                    startY = frame.getY();
                    requestNoIntercept(frame, true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    move(event, false);
                    return true;
                case MotionEvent.ACTION_UP:
                    move(event, true);
                    requestNoIntercept(frame, false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    requestNoIntercept(frame, false);
                    return true;
                default:
                    return false;
            }
        }

        private void move(MotionEvent event, boolean persist) {
            int sw = Math.max(1, surface.getWidth());
            int sh = Math.max(1, surface.getHeight());
            float x = startX + event.getRawX() - startRawX;
            float y = startY + event.getRawY() - startRawY;
            x = clampFloat(x, 0, Math.max(0, sw - frame.getWidth()));
            y = clampFloat(y, 0, Math.max(0, sh - frame.getHeight()));
            frame.setX(x);
            frame.setY(y);
            if (persist) saveFrameBounds(frame);
        }
    }

    private static final class WidgetState {
        final List<Integer> appWidgetIds = new ArrayList<>();
        int activeIndex;
        float left;
        float top;
        float width;
        float height;

        WidgetState(List<Integer> ids, int activeIndex,
                    float left, float top, float width, float height) {
            if (ids != null) appWidgetIds.addAll(ids);
            this.activeIndex = Math.max(0, activeIndex);
            this.left = clamp01(left);
            this.top = clamp01(top);
            this.width = clamp01(width);
            this.height = clamp01(height);
        }

        int primaryId() {
            return appWidgetIds.isEmpty()
                    ? AppWidgetManager.INVALID_APPWIDGET_ID : appWidgetIds.get(0);
        }

        static WidgetState fromPixels(int appWidgetId, int left, int top, int width, int height,
                                      int surfaceWidth, int surfaceHeight) {
            List<Integer> ids = new ArrayList<>();
            ids.add(appWidgetId);
            return new WidgetState(ids, 0,
                    left / (float) Math.max(1, surfaceWidth),
                    top / (float) Math.max(1, surfaceHeight),
                    width / (float) Math.max(1, surfaceWidth),
                    height / (float) Math.max(1, surfaceHeight));
        }

        static WidgetState fromJson(JSONObject object) {
            List<Integer> ids = new ArrayList<>();
            JSONArray idArray = object.optJSONArray("ids");
            if (idArray != null) {
                for (int i = 0; i < idArray.length(); i++) {
                    int id = idArray.optInt(i, AppWidgetManager.INVALID_APPWIDGET_ID);
                    if (id != AppWidgetManager.INVALID_APPWIDGET_ID) ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                int legacyId = object.optInt("id", AppWidgetManager.INVALID_APPWIDGET_ID);
                if (legacyId != AppWidgetManager.INVALID_APPWIDGET_ID) ids.add(legacyId);
            }
            if (ids.isEmpty()) return null;
            return new WidgetState(ids, object.optInt("active", 0),
                    (float) object.optDouble("x", 0.05),
                    (float) object.optDouble("y", 0.05),
                    (float) object.optDouble("w", 0.6),
                    (float) object.optDouble("h", 0.3));
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", primaryId());
                JSONArray ids = new JSONArray();
                for (int id : appWidgetIds) ids.put(id);
                object.put("ids", ids);
                object.put("active", activeIndex);
                object.put("x", left);
                object.put("y", top);
                object.put("w", width);
                object.put("h", height);
            } catch (JSONException ignored) {
                // Primitive values above cannot normally fail JSON encoding.
            }
            return object;
        }
    }

    private void requestNoIntercept(View view, boolean disallow) {
        ViewParentLoop.disallow(view, disallow);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class ViewParentLoop {
        private ViewParentLoop() {}

        static void disallow(View view, boolean disallow) {
            android.view.ViewParent parent = view.getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(disallow);
                parent = parent.getParent();
            }
        }
    }
}
