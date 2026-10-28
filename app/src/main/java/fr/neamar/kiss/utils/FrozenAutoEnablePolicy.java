package fr.neamar.kiss.utils;

import java.util.function.BooleanSupplier;

/**
 * User-initiated unfreezing is governed by its own explicit settings switch, not by
 * the separate launcher-wide root-mode option. Actual OS root authorisation is
 * still mandatory. Never probe privilege if auto-enable is turned off.
 */
public final class FrozenAutoEnablePolicy {
    private FrozenAutoEnablePolicy() { }

    public static boolean canEnable(boolean autoEnableSelected, BooleanSupplier rootGranted) {
        return autoEnableSelected && rootGranted != null && rootGranted.getAsBoolean();
    }
}
