package fr.neamar.kiss.forwarder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationTimelineState;
import fr.neamar.kiss.utils.Log;

class Notification extends Forwarder {
    private static final String TAG = Notification.class.getSimpleName();
    private static final int PERSISTED_CATCH_UP_BATCH = 256;
    private static final long FIRST_SCAN_LOOKBACK_MS = 24L * 60L * 60L * 1000L;

    private final SharedPreferences notificationPreferences;
    private final SharedPreferences detailPreferences;
    private final Set<String> pendingTimelineIds = new HashSet<>();

    private final Runnable flushTimeline = this::flushPendingTimeline;

    private final SharedPreferences.OnSharedPreferenceChangeListener onNotificationDisplayed =
            (sharedPreferences, packageKey) -> {
                if (packageKey == null) return;
                final ListView list = mainActivity.list;

                // Keep the legacy notification-dot update cheap: only currently rendered rows.
                updateDots(list,
                        list.getLastVisiblePosition() - list.getFirstVisiblePosition() + 1,
                        packageKey);
                updateDots(mainActivity.favoritesBar,
                        mainActivity.favoritesBar.getChildCount(), packageKey);
            };

    private final SharedPreferences.OnSharedPreferenceChangeListener onNotificationDetailChanged =
            (sharedPreferences, key) -> {
                if (key == null || !key.endsWith("|post")) return;
                String id = key.substring(0, key.length() - "|post".length());
                if (!id.startsWith(NotificationListener.NOTIFICATION_SCHEME)) return;
                queueTimelineId(id);
            };

    Notification(MainActivity mainActivity) {
        super(mainActivity);
        SharedPreferences notifsPrefBuilder = null;
        SharedPreferences detailPrefBuilder = null;

        try {
            String allowedApps = Settings.Secure.getString(
                    mainActivity.getContentResolver(), "enabled_notification_listeners");
            if (allowedApps != null && allowedApps.contains(mainActivity.getPackageName())) {
                notifsPrefBuilder = mainActivity.getSharedPreferences(
                        NotificationListener.NOTIFICATION_PREFERENCES_NAME, Context.MODE_PRIVATE);
                detailPrefBuilder = mainActivity.getSharedPreferences(
                        NotificationListener.DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
            } else {
                // Avoid ghost notification UI when notification-listener permission is absent.
                mainActivity.getSharedPreferences(
                        NotificationListener.NOTIFICATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit().clear().apply();
                mainActivity.getSharedPreferences(
                        NotificationListener.DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit().clear().apply();
            }
        } catch (Error e) {
            Log.d(TAG, "Unable to check for notification access", e);
        }
        notificationPreferences = notifsPrefBuilder;
        detailPreferences = detailPrefBuilder;
    }

    void onResume() {
        if (notificationPreferences != null) {
            notificationPreferences.registerOnSharedPreferenceChangeListener(onNotificationDisplayed);
        }
        if (detailPreferences != null && isTimelineEnabled()) {
            detailPreferences.registerOnSharedPreferenceChangeListener(onNotificationDetailChanged);
            queueAllActiveTimelineIds();
            catchUpPersistedTimelineAsync();
        }
    }

    void onPause() {
        if (notificationPreferences != null) {
            notificationPreferences.unregisterOnSharedPreferenceChangeListener(onNotificationDisplayed);
        }
        if (detailPreferences != null) {
            detailPreferences.unregisterOnSharedPreferenceChangeListener(onNotificationDetailChanged);
        }
        if (mainActivity.list != null) mainActivity.list.removeCallbacks(flushTimeline);
        synchronized (pendingTimelineIds) {
            pendingTimelineIds.clear();
        }
    }

    private boolean isTimelineEnabled() {
        return prefs.getBoolean("enable-notification-history", false);
    }

    private void queueAllActiveTimelineIds() {
        if (detailPreferences == null || !isTimelineEnabled()) return;
        for (String id : NotificationListener.getVerifiedActiveNotificationIds()) {
            queueTimelineId(id);
        }
    }

    private void queueTimelineId(String id) {
        if (!isTimelineEnabled() || id == null
                || !id.startsWith(NotificationListener.NOTIFICATION_SCHEME)
                || mainActivity.list == null) return;
        synchronized (pendingTimelineIds) {
            pendingTimelineIds.add(id);
        }
        // NotificationListener writes package/group history immediately after its detail cache.
        // Coalescing briefly guarantees the individual timeline id wins and lets us remove the
        // obsolete grouped history record in one refresh instead of rebuilding twice.
        mainActivity.list.removeCallbacks(flushTimeline);
        mainActivity.list.postDelayed(flushTimeline, 90L);
    }

    private void flushPendingTimeline() {
        if (!isTimelineEnabled() || detailPreferences == null || mainActivity.isFinishing()) return;
        List<String> ids;
        synchronized (pendingTimelineIds) {
            if (pendingTimelineIds.isEmpty()) return;
            ids = new ArrayList<>(pendingTimelineIds);
            pendingTimelineIds.clear();
        }

        boolean changed = false;
        long newestPost = 0L;
        for (String id : ids) {
            long postTime = detailPreferences.getLong(id + "|post", 0L);
            if (postTime <= 0L) continue;
            newestPost = Math.max(newestPost, postTime);

            if (NotificationTimelineState.recordIncoming(mainActivity, id, postTime)) {
                KissApplication.getApplication(mainActivity)
                        .getDataHandler().addToHistory(id);
                changed = true;
            }

            String groupKey = detailPreferences.getString(id + "|group", "");
            if (groupKey != null && !groupKey.isEmpty()) {
                // New launcher timeline is one-notification-per-card. Remove the legacy grouped
                // history entry so users do not see both a group tile and an individual tile.
                DBHelper.removeFromHistory(mainActivity, NotificationListener.getGroupId(groupKey));
            }
        }

        if (newestPost > 0L) {
            long previous = NotificationTimelineState.getLastPersistedScan(mainActivity);
            if (newestPost > previous) {
                NotificationTimelineState.setLastPersistedScan(mainActivity, newestPost);
            }
        }
        // Returning Home with the same active notifications must not rebuild history.
        if (changed) mainActivity.sendBroadcast(new Intent(MainActivity.LOAD_OVER));
    }

    /** Catch notifications that arrived while the launcher Activity was paused. */
    private void catchUpPersistedTimelineAsync() {
        if (!isTimelineEnabled()) return;
        long lastScan = NotificationTimelineState.getLastPersistedScan(mainActivity);
        if (lastScan <= 0L) lastScan = System.currentTimeMillis() - FIRST_SCAN_LOOKBACK_MS;
        final long scanAfter = Math.max(0L, lastScan - 1L);
        final long scanStartedAt = System.currentTimeMillis();

        Thread worker = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            List<NotificationHistoryRecord> records = NotificationTimelineStore.queryAfter(
                    mainActivity.getApplicationContext(), scanAfter, PERSISTED_CATCH_UP_BATCH);
            if (mainActivity.list != null) {
                if (records.isEmpty()) {
                    mainActivity.list.post(() -> NotificationTimelineState.setLastPersistedScan(
                            mainActivity, Math.max(0L, scanStartedAt - 5000L)));
                } else {
                    mainActivity.list.post(() -> indexPersistedRecords(records));
                }
            }
        }, "notification-timeline-catchup");
        worker.start();
    }

    private void indexPersistedRecords(List<NotificationHistoryRecord> records) {
        if (!isTimelineEnabled() || records == null || records.isEmpty()
                || mainActivity.isFinishing()) return;
        boolean changed = false;
        long newestPost = 0L;
        for (NotificationHistoryRecord record : records) {
            if (record == null || record.notificationId == null
                    || !record.notificationId.startsWith(NotificationListener.NOTIFICATION_SCHEME)) {
                continue;
            }
            newestPost = Math.max(newestPost, record.postTime);
            if (!NotificationTimelineState.recordIncoming(
                    mainActivity, record.notificationId, record.postTime)) {
                continue;
            }
            KissApplication.getApplication(mainActivity)
                    .getDataHandler().addToHistory(record.notificationId);
            changed = true;
        }
        if (newestPost > 0L) NotificationTimelineState.setLastPersistedScan(mainActivity, newestPost);
        if (changed) mainActivity.sendBroadcast(new Intent(MainActivity.LOAD_OVER));
    }

    private void updateDots(ViewGroup vg, int childCount, String packageKey) {
        if (vg == null || notificationPreferences == null) return;
        int count = Math.max(0, Math.min(childCount, vg.getChildCount()));
        for (int i = 0; i < count; i++) {
            View v = vg.getChildAt(i);
            if (v == null) continue;
            final View notificationDot = v.findViewById(R.id.item_notification_dot);
            if (notificationDot != null && packageKey.equals(notificationDot.getTag())) {
                boolean hasNotification = notificationPreferences.contains(packageKey);
                animateDot(notificationDot, hasNotification);
            }
        }
    }

    private void animateDot(final View notificationDot, boolean hasNotification) {
        int currentVisibility = notificationDot.getVisibility();

        if (currentVisibility != View.VISIBLE && hasNotification) {
            notificationDot.setVisibility(View.VISIBLE);
            notificationDot.setScaleX(0);
            notificationDot.setScaleY(0);
            notificationDot.animate().scaleX(1).scaleY(1).setListener(null);
        } else if (currentVisibility == View.VISIBLE && !hasNotification) {
            notificationDot.animate().scaleX(0).scaleY(0)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            notificationDot.setVisibility(View.GONE);
                            notificationDot.setScaleX(1);
                            notificationDot.setScaleY(1);
                        }
                    });
        }
    }
}
