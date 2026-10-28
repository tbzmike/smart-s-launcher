package fr.neamar.kiss.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IceBoxFallbackTargetPolicyTest {
    @Test void uniqueExactIceBoxLabelFindsSavedApp() {
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put("com.catchingnow.icebox", "IceBox");
        apps.put("org.example.frozen", "My Frozen App");
        assertEquals("org.example.frozen",
                IceBoxFallbackTargetPolicy.uniqueExactPackage(
                        " my frozen app ", "com.catchingnow.icebox", apps));
    }
    @Test void ambiguousAndPrefixMatchingCannotLaunchWrongPackage() {
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put("app.one", "Messages");
        apps.put("app.two", "Messages");
        assertNull(IceBoxFallbackTargetPolicy.uniqueExactPackage(
                "Messages", "com.catchingnow.icebox", apps));
        assertNull(IceBoxFallbackTargetPolicy.uniqueExactPackage(
                "Message", "com.catchingnow.icebox", apps));
        assertNull(IceBoxFallbackTargetPolicy.uniqueExactPackage(
                "No match", "com.catchingnow.icebox", apps));
    }
}
