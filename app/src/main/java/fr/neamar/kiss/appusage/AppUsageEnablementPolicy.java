package fr.neamar.kiss.appusage;

/** Pure migration policy kept separate so the one-time repair is unit-testable. */
final class AppUsageEnablementPolicy {
    private AppUsageEnablementPolicy() { }

    static boolean shouldRestoreDefault(boolean repairAlreadyApplied, boolean currentlyEnabled) {
        return !repairAlreadyApplied && !currentlyEnabled;
    }
}
