package fr.neamar.kiss.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class FrozenShortcutLaunchPolicyTest {
    @Test void frozenVerifiedIceBoxAppMustBeEnabled() {
        assertTrue(FrozenShortcutLaunchPolicy.shouldEnableVerifiedTarget(true, true,
                "com.catchingnow.icebox", "com.example.target", true, false));
    }

    @Test void doNotUnfreezePublisherOrGuessMissingApp() {
        assertFalse(FrozenShortcutLaunchPolicy.shouldEnableVerifiedTarget(true, true,
                "com.catchingnow.icebox", null, true, false));
        assertFalse(FrozenShortcutLaunchPolicy.shouldEnableVerifiedTarget(true, true,
                "com.catchingnow.icebox", "com.catchingnow.icebox", true, false));
        assertFalse(FrozenShortcutLaunchPolicy.shouldEnableVerifiedTarget(true, true,
                "com.catchingnow.icebox", "com.example.uninstalled", false, false));
    }

    @Test void normalShortcutsAndDifferentProfilesMustKeepExactRoute() {
        assertFalse(FrozenShortcutLaunchPolicy.shouldEnableVerifiedTarget(false, true,
                "com.example.publisher", "com.example.target", true, false));
        assertFalse(FrozenShortcutLaunchPolicy.shouldEnableVerifiedTarget(true, false,
                "com.catchingnow.icebox", "com.example.target", true, false));
        assertFalse(FrozenShortcutLaunchPolicy.shouldEnableVerifiedTarget(true, true,
                "com.catchingnow.icebox", "com.example.target", true, true));
    }
}
