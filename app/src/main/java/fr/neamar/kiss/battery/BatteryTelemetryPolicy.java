package fr.neamar.kiss.battery;

/** The live internal battery status is telemetry, not a separate notification-history event. */
public final class BatteryTelemetryPolicy {
    public static final int LIVE_ID = 8450;
    public static final String LIVE_CHANNEL = "smart_battery_live";

    private BatteryTelemetryPolicy() { }

    public static boolean isOwnLiveNotification(String ownPackage, String postedByPackage,
                                               int notificationId, String channelId) {
        return ownPackage != null && ownPackage.equals(postedByPackage)
                && notificationId == LIVE_ID && LIVE_CHANNEL.equals(channelId);
    }
}
