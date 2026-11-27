package fr.neamar.kiss.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreeformLaunchCommandTest {
    @Test void exactCurrentProfileComponentUsesNativeModeFive() {
        assertEquals("am start --user 10 --windowingMode 5 -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER -n 'com.example/com.example.Main$Alias' 2>&1",
                FreeformLaunchCommand.build("com.example", "com.example.Main$Alias", 10));
    }
    @Test void refusesShellInjectionAndInvalidUser() {
        assertNull(FreeformLaunchCommand.build("com.example;id", "Main", 0));
        assertNull(FreeformLaunchCommand.build("com.example", "Main' ; id", 0));
        assertNull(FreeformLaunchCommand.build("com.example", "Main", -1));
        assertNull(FreeformLaunchCommand.build(null, "Main", 0));
    }
    @Test void onlyRecordsAcceptedStartRequests() {
        assertTrue(FreeformLaunchCommand.accepted("Starting: Intent { act=android.intent.action.MAIN }"));
        assertFalse(FreeformLaunchCommand.accepted("Starting: Intent {}\nError type 3"));
        assertFalse(FreeformLaunchCommand.accepted("Starting: Intent {}\nSecurity exception: denied"));
        assertFalse(FreeformLaunchCommand.accepted(""));
        assertFalse(FreeformLaunchCommand.accepted(null));
    }
}
