package fr.neamar.kiss.forwarder;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.utils.NotificationHistoryResolver;

/** Keeps notification-history long press authoritative on Square-U cards that own saved history. */
final class UNotificationHistoryLongPressForwarder extends Forwarder {
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private ViewGroup squareTrack;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private final Set<Integer> historyPositions = new HashSet<>();

    UNotificationHistoryLongPressForwarder(MainActivity activity,
                                           HistoryDisplayForwarder historyDisplayForwarder) {
        super(activity);
        this.historyDisplayForwarder = historyDisplayForwarder;
    }

    void onCreate() { refresh(); }
    void onResume() { refresh(); }
    void onDataSetChanged() { refresh(); }
    void onConfigurationChanged() { refresh(); }

    void onPause() { detach(); }
    void onDestroy() { detach(); historyPositions.clear(); }

    private boolean isUStyle() {
        return HistoryDisplayForwarder.SQUARE_U.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void refresh() {
        if (!isUStyle() || !UiEditLock.isLocked(mainActivity)) {
            detach();
            historyPositions.clear();
            return;
        }
        resolveTrack();
        rebuildHistoryPositions();
        attach();
        if (squareTrack != null) squareTrack.post(this::applyHistoryLongPress);
    }

    private void rebuildHistoryPositions() {
        historyPositions.clear();
        if (mainActivity.adapter == null) return;
        for (int i = 0; i < mainActivity.adapter.getCount(); i++) {
            if (NotificationHistoryResolver.resolvePackage(
                    mainActivity, mainActivity.adapter.getItem(i).getPojo()) != null) {
                historyPositions.add(i);
            }
        }
    }

    private void resolveTrack() {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField("squareTrack");
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            ViewGroup newTrack = value instanceof ViewGroup ? (ViewGroup) value : null;
            if (newTrack != squareTrack) {
                detach();
                squareTrack = newTrack;
            }
        } catch (ReflectiveOperationException ignored) {
            detach();
            squareTrack = null;
        }
    }

    private void attach() {
        if (squareTrack == null || layoutListener != null) return;
        layoutListener = this::applyHistoryLongPress;
        squareTrack.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void detach() {
        if (squareTrack != null && layoutListener != null) {
            ViewTreeObserver observer = squareTrack.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
    }

    private void applyHistoryLongPress() {
        if (!isUStyle() || !UiEditLock.isLocked(mainActivity)
                || squareTrack == null || mainActivity.adapter == null) return;
        int count = Math.min(squareTrack.getChildCount(), mainActivity.adapter.getCount());
        for (int i = 0; i < count; i++) {
            if (!historyPositions.contains(i)) continue;
            final int adapterPosition = i;
            View card = squareTrack.getChildAt(i);
            card.setOnLongClickListener(v ->
                    mainActivity.adapter.showNotificationHistoryIfAvailable(adapterPosition, v));
        }
    }
}
