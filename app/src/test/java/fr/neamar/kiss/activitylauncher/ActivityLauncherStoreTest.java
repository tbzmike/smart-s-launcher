package fr.neamar.kiss.activitylauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ActivityLauncherStoreTest {
    @Test
    void stableInternalNameDependsOnTargetNotDisplayLabel() {
        String first = ActivityLauncherStore.internalNameForUri(
                "intent:#Intent;component=com.example/.HiddenActivity;end");
        String second = ActivityLauncherStore.internalNameForUri(
                "intent:#Intent;component=com.example/.HiddenActivity;end");
        assertEquals(first, second);
        assertFalse(first.contains(";"));
        assertFalse(first.contains("/"));
    }

    @Test
    void differentTargetsReceiveDifferentStableNames() {
        String first = ActivityLauncherStore.internalNameForUri(
                "intent:#Intent;component=com.example/.One;end");
        String second = ActivityLauncherStore.internalNameForUri(
                "intent:#Intent;component=com.example/.Two;end");
        assertFalse(first.equals(second));
    }
}
