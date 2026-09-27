package fr.neamar.kiss.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import fr.neamar.kiss.utils.LauncherScrollWorkGate;

/**
 * Backfills active media artwork on ordinary launcher notification refreshes. Heavy bitmap work is
 * delegated to MediaHistoryCoordinator's single background executor.
 */
public final class MediaNotificationCaptureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (LauncherScrollWorkGate.isScrolling()) return;
        MediaHistoryCoordinator.refresh(context, false);
    }
}
