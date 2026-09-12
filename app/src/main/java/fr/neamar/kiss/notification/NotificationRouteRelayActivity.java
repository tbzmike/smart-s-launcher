package fr.neamar.kiss.notification;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.utils.SavedNotificationDestinationResolver;

/** Visible-user-action relay that replays the exact PendingIntent captured from a notification. */
public final class NotificationRouteRelayActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent wrapper = getIntent();
        PendingIntent target = readTarget(wrapper);
        String notificationId = wrapper == null ? null
                : wrapper.getStringExtra(NotificationPendingIntentStore.EXTRA_NOTIFICATION_ID);
        long postTime = wrapper == null ? 0L
                : wrapper.getLongExtra(NotificationPendingIntentStore.EXTRA_POST_TIME, 0L);
        String routeToken = wrapper == null ? null
                : wrapper.getStringExtra(NotificationPendingIntentStore.EXTRA_ROUTE_TOKEN);

        boolean opened = target != null
                && NotificationPendingIntentStore.sendTarget(this, target);
        if (opened) {
            if (notificationId != null && !notificationId.isEmpty()) {
                NotificationUnreadStore.markRead(this, notificationId);
            }
        } else {
            NotificationHistoryRecord record =
                    NotificationTimelineStore.findByPendingIntentToken(this, routeToken);
            if (record == null && notificationId != null && !notificationId.isEmpty()
                    && postTime > 0L) {
                record = NotificationTimelineStore.findExact(this, notificationId, postTime);
            }
            NotificationPendingIntentStore.discard(this, routeToken);
            SmartStateStore.clearNotificationPendingIntentToken(this, routeToken);
            boolean fallbackOpened = NotificationListener.openExactActiveNotification(this, record)
                    || SavedNotificationDestinationResolver.openDurableFallback(this, record);
            if (fallbackOpened && record != null) {
                if (record.notificationId != null && !record.notificationId.isEmpty()) {
                    NotificationUnreadStore.markRead(this, record.notificationId);
                }
            } else {
                Toast.makeText(this, "The originating app expired this notification route.",
                        Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static PendingIntent readTarget(@Nullable Intent wrapper) {
        if (wrapper == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return wrapper.getParcelableExtra(
                    NotificationPendingIntentStore.EXTRA_TARGET, PendingIntent.class);
        }
        return wrapper.getParcelableExtra(NotificationPendingIntentStore.EXTRA_TARGET);
    }
}
