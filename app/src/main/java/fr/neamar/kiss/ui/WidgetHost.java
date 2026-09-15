package fr.neamar.kiss.ui;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.res.Resources;

import fr.neamar.kiss.utils.Log;

public class WidgetHost extends AppWidgetHost {

    private static final String TAG = WidgetHost.class.getSimpleName();

    private final WidgetProvidersUpdateCallback mWidgetsUpdateCallback;
    private boolean listening;

    public WidgetHost(Context context, int hostId, WidgetProvidersUpdateCallback widgetProvidersUpdateCallback) {
        super(context, hostId);
        this.mWidgetsUpdateCallback = widgetProvidersUpdateCallback;
    }

    @Override
    protected AppWidgetHostView onCreateView(Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
        // We need to create a custom view to handle long click events
        return new WidgetView(context);
    }

    @Override
    public void startListening() {
        // Workspace restore/onCreate/onStart can all converge during a HOME transition. The Android
        // host only needs one active registration; repeating the binder registration adds avoidable
        // main-thread work exactly while the launcher is trying to draw its first frame.
        if (listening) return;
        try {
            super.startListening();
            listening = true;
            Log.d(TAG, "Start listening");
        } catch (Resources.NotFoundException e) {
            listening = false;
            Log.d(TAG, "Start listening failed", e);
            // Widgets app was just updated?
            // See https://github.com/Neamar/KISS/issues/959
        }
    }

    @Override
    public void stopListening() {
        // If stopListening is called during onDestroy the workaround for https://github.com/Neamar/KISS/issues/744 is not needed any more.
        // This was necessary because stopListening cleared remote views until https://android.googlesource.com/platform/frameworks/base/+/2857f1c783e69461735a51159f9abdb85378e210
        // which is ok for calls during onDestroy()
        if (listening) {
            try {
                super.stopListening();
                Log.d(TAG, "Stop listening");
            } catch (NullPointerException e) {
                // Ignore, happens on some shitty widget down the stack trace.
                Log.d(TAG, "Stop listening failed", e);
            } finally {
                listening = false;
            }
        }
        clearViews();
    }

    @Override
    protected void onProvidersChanged() {
        super.onProvidersChanged();
        Log.d(TAG, "Providers changed");
        if (mWidgetsUpdateCallback != null) {
            mWidgetsUpdateCallback.onProvidersUpdated();
        }
    }

    /**
     * Callback interface for packages list update.
     */
    @FunctionalInterface
    public interface WidgetProvidersUpdateCallback {
        /**
         * Gets called when widgets providers list changes
         */
        void onProvidersUpdated();
    }

}
