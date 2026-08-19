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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.PickAppWidgetActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.WidgetHost;
import fr.neamar.kiss.utils.Log;

/**
 * Workspace-only widget host. This intentionally does not use the legacy KISS line-based widget
 * sizing/persistence model. Android AppWidgetHost is retained only as the provider transport;
 * placement, movement, eight-direction resizing and persistence belong to Smart S.
 */
final class FreeformWorkspaceWidgetManager {
    private static final String TAG = FreeformWorkspaceWidgetManager.class.getSimpleName();
    private static final int HOST_ID = 8442;
    private static final String PREF_STATE = "smart-workspace-widgets-v2";
    private static final String PREF_PENDING_ID = "smart-workspace-widget-pending-id";

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
    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

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
        host.startListening();
    }

    void onStart() {
        host.startListening();
    }

    void onDestroy() {
        host.stopListening();
    }

    void startAddWidget() {
        if (pickerLauncher == null) return;
        exitEditMode();
        pendingWidgetId = host.allocateAppWidgetId();
        prefs.edit().putInt(PREF_PENDING_ID, pendingWidgetId).apply();
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
            startAddWidget();
            return true;
        });
        if (prefs.getBoolean("smart-workspace-empty-gestures", true)) {
            surface.setOnTouchListener((v, event) -> activity.onTouch(v, event));
        }

        LinearLayout.LayoutParams surfaceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, activity.getResources().getDisplayMetrics().heightPixels));
        legacyWidgetArea.addView(surface, surfaceParams);

        legacyWidgetArea.post(() -> {
            View parent = legacyWidgetArea.getParent() instanceof View ? (View) legacyWidgetArea.getParent() : null;
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
                                data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER));
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                            && data.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE)) {
                        bindIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,
                                data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE));
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
        clearPendingId();
    }

    private void clearPendingId() {
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        prefs.edit().remove(PREF_PENDING_ID).apply();
    }

    private void restoreWidgets() {
        readState();
        surface.removeAllViews();
        editingFrame = null;
        Set<Integer> usedIds = new HashSet<>();
        List<WidgetState> invalid = new ArrayList<>();
        for (WidgetState state : states) {
            if (appWidgetManager.getAppWidgetInfo(state.appWidgetId) == null) {
                invalid.add(state);
                continue;
            }
            usedIds.add(state.appWidgetId);
            addFrame(state, false);
        }
        if (!invalid.isEmpty()) {
            states.removeAll(invalid);
            saveState();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (int hostId : host.getAppWidgetIds()) {
                if (!usedIds.contains(hostId) && hostId != pendingWidgetId) {
                    host.deleteAppWidgetId(hostId);
                }
            }
        }
        host.startListening();
    }

    private void addFrame(WidgetState state, boolean enterEdit) {
        AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(state.appWidgetId);
        if (info == null) return;
        AppWidgetHostView hostView = host.createView(activity, state.appWidgetId, info);
        hostView.setAppWidget(state.appWidgetId, info);
        FreeformWidgetFrame frame = new FreeformWidgetFrame(state, hostView);
        surface.addView(frame, new FrameLayout.LayoutParams(1, 1));
        frame.post(() -> {
            applyBounds(frame);
            updateProviderSize(frame);
            if (enterEdit) enterEditMode(frame);
        });
    }

    private void enterEditMode(FreeformWidgetFrame frame) {
        if (!prefs.getBoolean("smart-workspace-free-widget-resize", true)) return;
        if (editingFrame != null && editingFrame != frame) editingFrame.setEditing(false);
        editingFrame = frame;
        frame.setEditing(true);
        frame.bringToFront();
    }

    private void exitEditMode() {
        if (editingFrame != null) editingFrame.setEditing(false);
        editingFrame = null;
    }

    private void removeWidget(FreeformWidgetFrame frame) {
        states.remove(frame.state);
        surface.removeView(frame);
        try {
            host.deleteAppWidgetId(frame.state.appWidgetId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to remove workspace widget id", e);
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

        AppWidgetProviderInfo info = frame.hostView.getAppWidgetInfo();
        int minWidth = Math.min(providerMinWidth(info), sw);
        int minHeight = Math.min(providerMinHeight(info), sh);
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
        frame.hostView.updateAppWidgetSize(new Bundle(), widthDp, heightDp, widthDp, heightDp);
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

    private final class FreeformWidgetFrame extends FrameLayout {
        final WidgetState state;
        final AppWidgetHostView hostView;
        final List<View> editControls = new ArrayList<>();

        FreeformWidgetFrame(WidgetState state, AppWidgetHostView hostView) {
            super(activity);
            this.state = state;
            this.hostView = hostView;
            setClipChildren(false);
            setClipToPadding(false);
            addView(hostView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            hostView.setLongClickable(true);
            hostView.setOnLongClickListener(v -> {
                enterEditMode(this);
                return true;
            });
            setLongClickable(true);
            setOnLongClickListener(v -> {
                enterEditMode(this);
                return true;
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
            addRemoveControl();
            setEditing(false);
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
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(190, 40, 40, 40));
            bg.setCornerRadius(dp(10));
            move.setBackground(bg);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(28),
                    Gravity.LEFT | Gravity.BOTTOM);
            params.leftMargin = dp(24);
            params.bottomMargin = dp(24);
            addView(move, params);
            move.setOnTouchListener(new MoveTouchListener(this));
            editControls.add(move);
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
            int minWidth = Math.min(providerMinWidth(frame.hostView.getAppWidgetInfo()), sw);
            int minHeight = Math.min(providerMinHeight(frame.hostView.getAppWidgetInfo()), sh);
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
        final int appWidgetId;
        float left;
        float top;
        float width;
        float height;

        WidgetState(int appWidgetId, float left, float top, float width, float height) {
            this.appWidgetId = appWidgetId;
            this.left = clamp01(left);
            this.top = clamp01(top);
            this.width = clamp01(width);
            this.height = clamp01(height);
        }

        static WidgetState fromPixels(int appWidgetId, int left, int top, int width, int height,
                                      int surfaceWidth, int surfaceHeight) {
            return new WidgetState(appWidgetId,
                    left / (float) Math.max(1, surfaceWidth),
                    top / (float) Math.max(1, surfaceHeight),
                    width / (float) Math.max(1, surfaceWidth),
                    height / (float) Math.max(1, surfaceHeight));
        }

        static WidgetState fromJson(JSONObject object) {
            int id = object.optInt("id", AppWidgetManager.INVALID_APPWIDGET_ID);
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null;
            return new WidgetState(id,
                    (float) object.optDouble("x", 0.05),
                    (float) object.optDouble("y", 0.05),
                    (float) object.optDouble("w", 0.6),
                    (float) object.optDouble("h", 0.3));
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", appWidgetId);
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
