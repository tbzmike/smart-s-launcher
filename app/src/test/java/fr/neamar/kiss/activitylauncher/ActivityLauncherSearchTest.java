package fr.neamar.kiss.activitylauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

class ActivityLauncherSearchTest {
    @Test
    void termsAreCaseInsensitiveAndDeduplicated() {
        List<String> terms = ActivityLauncherSearch.terms("  Display DISPLAY settings ");
        assertEquals(Arrays.asList("display", "settings"), terms);
    }

    @Test
    void eachKeywordCanMatchADifferentField() {
        List<String> terms = ActivityLauncherSearch.terms("display advanced");
        assertTrue(ActivityLauncherSearch.matchesAll(terms,
                "Advanced settings", "com.example.DisplayActivity"));
        assertFalse(ActivityLauncherSearch.matchesAll(terms,
                "Advanced settings", "com.example.OtherActivity"));
    }

    @Test
    void savedIntentUriIsSearchable() {
        List<String> terms = ActivityLauncherSearch.terms("hiddenactivity");
        assertTrue(ActivityLauncherSearch.matchesAll(terms,
                "My renamed shortcut",
                "intent:#Intent;component=com.example/.HiddenActivity;end"));
    }
}
