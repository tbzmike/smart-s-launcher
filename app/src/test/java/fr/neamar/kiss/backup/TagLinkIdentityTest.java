package fr.neamar.kiss.backup;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TagLinkIdentityTest {
    @Test void appIdentityPreservesOwnerAndParsesWorkSerial() {
        assertEquals("com.sample/.Main", TagLinkIdentity.appComponent("app://com.sample/.Main"));
        assertEquals("com.sample/.Main", TagLinkIdentity.appComponent("app://com.sample/.Main#18"));
        assertFalse(TagLinkIdentity.hasAppProfile("app://com.sample/.Main"));
        assertTrue(TagLinkIdentity.hasAppProfile("app://com.sample/.Main#18"));
    }
    @Test void shortcutIdentityMustMatchPublisherAndExactShortcutKey() {
        assertTrue(TagLinkIdentity.belongsToShortcut("shortcut://com.icebox/oreo-shortcut/a1", "com.icebox", "oreo-shortcut/a1", "A"));
        assertTrue(TagLinkIdentity.belongsToShortcut("shortcut://com.icebox/oreo-shortcut/a1/15", "com.icebox", "oreo-shortcut/a1", "A"));
        assertFalse(TagLinkIdentity.belongsToShortcut("shortcut://com.icebox/oreo-shortcut/a11", "com.icebox", "oreo-shortcut/a1", "A"));
        assertFalse(TagLinkIdentity.belongsToShortcut("shortcut://com.other/oreo-shortcut/a1", "com.icebox", "oreo-shortcut/a1", "A"));
        assertTrue(TagLinkIdentity.hasShortcutProfile("shortcut://com.icebox/oreo-shortcut/a1/15", "com.icebox", "oreo-shortcut/a1"));
        assertFalse(TagLinkIdentity.hasShortcutProfile("shortcut://com.icebox/oreo-shortcut/123", "com.icebox", "oreo-shortcut/123"));
    }
}
