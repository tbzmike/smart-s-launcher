package fr.neamar.kiss.notification;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NotificationAvatarKeyPolicyTest {
    @Test void matchesConversationAcrossNotificationTitlePrefixAndCase() {
        assertEquals(
                NotificationAvatarKeyPolicy.titleKey("com.whatsapp", "Andronicah Moiketsi Mahlangu"),
                NotificationAvatarKeyPolicy.titleKey("com.whatsapp",
                        "  WhatsApp:   ANDRONICAH  MOIKETSI MAHLANGU  "));
        assertEquals(
                NotificationAvatarKeyPolicy.titleKey("org.telegram.messenger", "Alice"),
                NotificationAvatarKeyPolicy.titleKey("org.telegram.messenger", "Telegram: Alice"));
    }

    @Test void rejectsGenericAppAndNotificationTitlesThatAreNotPeople() {
        for (String title : new String[]{
                "WhatsApp", "3 new messages", "Telegram", "Facebook",
                "New message", "Notifications", "Instagram", "X"}) {
            assertEquals("", NotificationAvatarKeyPolicy.titleKey("com.whatsapp", title), title);
        }
        assertEquals("", NotificationAvatarKeyPolicy.titleKey(null, "Alice"));
        assertEquals("", NotificationAvatarKeyPolicy.titleKey("com.whatsapp", null));
    }

    @Test void neverStripsUnrelatedAppPrefixes() {
        assertEquals("whatsapp: alice",
                NotificationAvatarKeyPolicy.titleKey("org.telegram.messenger", "WhatsApp: Alice"));
        assertEquals("alice",
                NotificationAvatarKeyPolicy.titleKey("com.whatsapp", "WhatsApp: Alice"));
    }
}
