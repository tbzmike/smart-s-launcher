package fr.neamar.kiss.utils;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class IceBoxShortcutIdentityTest {
    @Test public void readsRealAppAndMetaShortcutFormatsIncludingProfile() {
        IceBoxShortcutIdentity app = IceBoxShortcutIdentity.parse("com.openai.chatgpt::0");
        assertEquals("com.openai.chatgpt", app.packageName);
        assertEquals(0, app.userId);
        assertNull(app.originalShortcutId);
        IceBoxShortcutIdentity meta = IceBoxShortcutIdentity.parse(
                "App_Meta_Shortcut_com.openai.chatgpt::10$$$camera$$$original");
        assertEquals("com.openai.chatgpt", meta.packageName);
        assertEquals(10, meta.userId);
        assertEquals("camera$$$original", meta.originalShortcutId);
    }

    @Test public void keepsLegacyPackageIdsAndRejectsNonAppActions() {
        assertEquals(-1, IceBoxShortcutIdentity.parse("com.openai.chatgpt").userId);
        for (String id : new String[]{null, "", "freeze_all", "icebox://action/freeze_all",
                "com.openai.chatgpt::-1", "com.openai.chatgpt::abc", "com.openai.chatgpt::",
                "com.openai.chatgpt::99999999999", "App_Meta_Shortcut_com.openai.chatgpt::0$$$",
                "App_Meta_Shortcut_com.openai.chatgpt$$$camera", "com.openai/chatgpt"}) {
            assertNull(IceBoxShortcutIdentity.parse(id), id);
        }
    }
}
