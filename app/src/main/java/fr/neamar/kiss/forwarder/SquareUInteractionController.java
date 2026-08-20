package fr.neamar.kiss.forwarder;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/**
 * Keeps Square-U labels readable without adding permanent controls to the cards.
 * The full display name comes from the adapter's Pojo, not from a prefix TextView
 * such as "Feature:". Long labels use Android marquee while the launcher is active.
 */
final class SquareUInteractionController {
    private static final String TAG = SquareUInteractionController.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SharedPreferences prefs;
    private ViewGroup squareTrack;

    SquareUInteractionController(MainActivity activity,
                                 HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() {
        resolveSquareTrack();
        refreshSoon();
    }

    void onResume() {
        resolveSquareTrack();
        refreshSoon();
        setAllMarquees(true);
    }

    void onPause() {
        setAllMarquees(false);
    }

    void onDataSetChanged() {
        refreshSoon();
    }

    void onDestroy() {
        setAllMarquees(false);
        squareTrack = null;
    }

    private void resolveSquareTrack() {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField("squareTrack");
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            squareTrack = value instanceof ViewGroup ? (ViewGroup) value : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            squareTrack = null;
            Log.e(TAG, "Unable to resolve Square-U track", e);
        }
    }

    private void refreshSoon() {
        resolveSquareTrack();
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
            if (label == null) continue;

            String fullName = activity.adapter.getItem(position).getPojo().getName();
            if (!TextUtils.isEmpty(fullName)) {
                label.setText(fullName);
                label.setContentDescription(fullName);
            }
            configureMarquee(label, activity.hasWindowFocus());
        }
    }

    private TextView findPresentationLabel(ViewGroup card) {
        // HistoryDisplayForwarder adds the presentation label after the icon/preview,
        // so search backwards and use the last non-empty TextView directly on the card.
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

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
