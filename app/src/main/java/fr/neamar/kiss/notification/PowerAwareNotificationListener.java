package fr.neamar.kiss.notification;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService.RankingMap;

/**
 * Power-aware notification listener.
 *
 * Posted/removed callbacks already maintain Smart S notification state. Android can emit ranking
 * callbacks very frequently even when notification content is unchanged, and the legacy listener
 * responds by rescanning every active notification. This subclass coalesces ranking-only churn into
 * an occasional refresh while preserving all inherited notification-history functionality.
 */
public final class PowerAwareNotificationListener extends NotificationListener {
    private static final long MIN_RANKING_REFRESH_MS = 60_000L;
    private static final long RANKING_DEBOUNCE_MS = 3_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRankingRefreshElapsed;
    private boolean rankingRefreshPending;

    private final Runnable rankingRefresh = new Runnable() {
        @Override public void run() {
            rankingRefreshPending = false;
            long now = SystemClock.elapsedRealtime();
            if (now - lastRankingRefreshElapsed < MIN_RANKING_REFRESH_MS) return;
            lastRankingRefreshElapsed = now;
            // Invoke the existing full reconciliation only occasionally. Normal posted/removed
            // events continue to update state immediately through the inherited implementation.
            PowerAwareNotificationListener.super.onNotificationRankingUpdate(null);
        }
    };

    @Override public void onNotificationRankingUpdate(RankingMap rankingMap) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRankingRefreshElapsed < MIN_RANKING_REFRESH_MS || rankingRefreshPending) return;
        rankingRefreshPending = true;
        handler.postDelayed(rankingRefresh, RANKING_DEBOUNCE_MS);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
