package fr.neamar.kiss.forwarder;

import android.content.SharedPreferences;
import android.view.ViewGroup;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Temporarily removes resultLayout's horizontal margins while Square-U is active so the U
 * renderer and resize handles can use the physical screen width. The original margins and clipping
 * policy are restored for every other history style and on pause/destroy.
 */
final class SquareUHostFullscreenController {
    private static final String TAG = SquareUHostFullscreenController.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SharedPreferences prefs;

    private ViewGroup host;
    private boolean captured;
    private int originalLeft;
    private int originalRight;
    private int originalStart;
    private int originalEnd;
    private boolean originalClipChildren;
    private boolean originalClipToPadding;
    private boolean expanded;

    SquareUHostFullscreenController(MainActivity activity,
                                    HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() {
        resolveHost();
        sync();
    }

    void onResume() {
        resolveHost();
        sync();
    }

    void onDataSetChanged() {
        resolveHost();
        sync();
    }

    void onConfigurationChanged() {
        restore();
        captured = false;
        host = null;
        resolveHost();
        sync();
    }

    void onPause() {
        restore();
    }

    void onDestroy() {
        restore();
        host = null;
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void resolveHost() {
        ViewGroup squareRoot = readField("squareRoot", ViewGroup.class);
        ViewGroup next = null;
        if (squareRoot != null && squareRoot.getParent() instanceof ViewGroup) {
            next = (ViewGroup) squareRoot.getParent();
        }
        if (host == next) return;
        restore();
        host = next;
        captured = false;
        captureOriginalState();
    }

    private <T> T readField(String name, Class<T> type) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Square-U field: " + name, e);
            return null;
        }
    }

    private void captureOriginalState() {
        if (host == null || captured) return;
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        originalLeft = lp.leftMargin;
        originalRight = lp.rightMargin;
        originalStart = lp.getMarginStart();
        originalEnd = lp.getMarginEnd();
        originalClipChildren = host.getClipChildren();
        originalClipToPadding = host.getClipToPadding();
        captured = true;
    }

    private void sync() {
        if (host == null) return;
        if (isUStyle()) expand();
        else restore();
    }

    private void expand() {
        captureOriginalState();
        if (!captured || host == null) return;
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        boolean marginsAlreadyZero = lp.leftMargin == 0 && lp.rightMargin == 0
                && lp.getMarginStart() == 0 && lp.getMarginEnd() == 0;
        if (!marginsAlreadyZero) {
            lp.leftMargin = 0;
            lp.rightMargin = 0;
            lp.setMarginStart(0);
            lp.setMarginEnd(0);
            host.setLayoutParams(lp);
        }
        host.setClipChildren(false);
        host.setClipToPadding(false);
        expanded = true;
        if (!marginsAlreadyZero) host.requestLayout();
    }

    private void restore() {
        if (!expanded || host == null || !captured) return;
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
            lp.leftMargin = originalLeft;
            lp.rightMargin = originalRight;
            lp.setMarginStart(originalStart);
            lp.setMarginEnd(originalEnd);
            host.setLayoutParams(lp);
        }
        host.setClipChildren(originalClipChildren);
        host.setClipToPadding(originalClipToPadding);
        host.requestLayout();
        expanded = false;
    }
}
