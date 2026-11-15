package fr.neamar.kiss.power;

/** Pure Auto Data decisions; independent of Android, root access, and receivers. */
public final class AutoDataPolicy {
    private AutoDataPolicy() { }

    public static int delayMinutes(int minutes) {
        return Math.max(1, Math.min(30, minutes));
    }

    public static boolean shouldSchedule(boolean enabled, boolean interactive,
                                         boolean powerOrUsbConnected) {
        return enabled && !interactive && !powerOrUsbConnected;
    }

    public static boolean shouldDisable(boolean enabled, boolean interactive,
                                        boolean powerOrUsbConnected, boolean dataEnabled,
                                        boolean alreadyDisabledByUs) {
        return shouldSchedule(enabled, interactive, powerOrUsbConnected)
                && dataEnabled && !alreadyDisabledByUs;
    }

    public static boolean shouldRestore(boolean disabledByUs, boolean enabled,
                                        boolean interactive, boolean powerOrUsbConnected) {
        return disabledByUs && (!enabled || interactive || powerOrUsbConnected);
    }
}
