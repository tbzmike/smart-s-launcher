package fr.neamar.kiss.activitylauncher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActivityLauncherPrivilegeBridgeTest {
    @Test
    void packageAndClassValidationAcceptAndroidIdentifiers() {
        assertTrue(ActivityLauncherPrivilegeBridge.isSafePackageName("android"));
        assertTrue(ActivityLauncherPrivilegeBridge.isSafePackageName("com.example.app"));
        assertTrue(ActivityLauncherPrivilegeBridge.isSafeClassName(".HiddenActivity"));
        assertTrue(ActivityLauncherPrivilegeBridge.isSafeClassName("com.example.Outer$Inner"));
    }

    @Test
    void shellValidationRejectsCommandInjectionCharacters() {
        assertFalse(ActivityLauncherPrivilegeBridge.isSafePackageName("com.example;id"));
        assertFalse(ActivityLauncherPrivilegeBridge.isSafePackageName("com.example app"));
        assertFalse(ActivityLauncherPrivilegeBridge.isSafeClassName("Activity;reboot"));
        assertFalse(ActivityLauncherPrivilegeBridge.isSafeClassName("Activity$(id)"));
    }
}
