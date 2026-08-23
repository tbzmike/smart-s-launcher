package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.ui.LockedNotificationHistoryDialog;

/** Resolves notification history to the real target application, not a launcher/wrapper app. */
public final class NotificationHistoryResolver {
    private NotificationHistoryResolver() {}

    public static boolean showForPojo(Context context, Pojo pojo) {
        String packageName = resolvePackage(context, pojo);
        return packageName != null && LockedNotificationHistoryDialog.showLatest(context, packageName);
    }

    public static String resolvePackage(Context context, Pojo pojo) {
        if (context == null || pojo == null) return null;
        if (pojo instanceof DisabledAppPojo) return preferIfHasHistory(context, ((DisabledAppPojo) pojo).targetPackage);
        if (pojo instanceof NotificationPojo) return preferIfHasHistory(context, ((NotificationPojo) pojo).packageName);
        if (pojo instanceof AppPojo) return preferIfHasHistory(context, ((AppPojo) pojo).packageName);
        if (pojo instanceof ShortcutPojo) return resolveShortcutPackage(context, (ShortcutPojo) pojo);
        return null;
    }

    private static String resolveShortcutPackage(Context context, ShortcutPojo shortcut) {
        Set<String> candidates = new LinkedHashSet<>();
        if (!shortcut.isOreoShortcut()) {
            try {
                Intent intent = Intent.parseUri(shortcut.intentUri, 0);
                collectStringExtras(intent.getExtras(), candidates);
                if (intent.getPackage() != null) candidates.add(intent.getPackage());
                if (intent.getComponent() != null) candidates.add(intent.getComponent().getPackageName());
            } catch (URISyntaxException | RuntimeException ignored) {
                // Shortcut owner remains the final fallback below.
            }
        }

        // Wrapper-owned shortcuts (for example IceBox) may encode the real target app in extras.
        // Always prefer a different history-owning package before the shortcut owner itself.
        for (String candidate : candidates) {
            if (!shortcut.packageName.equals(candidate) && hasHistory(context, candidate)) return candidate;
        }
        if (hasHistory(context, shortcut.packageName)) return shortcut.packageName;
        return null;
    }

    private static void collectStringExtras(Bundle extras, Set<String> candidates) {
        if (extras == null) return;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof String) {
                String candidate = ((String) value).trim();
                if (looksLikePackageName(candidate)) candidates.add(candidate);
            }
        }
    }

    private static boolean looksLikePackageName(String value) {
        if (value == null || value.length() < 3 || value.indexOf('.') <= 0 || value.contains(" ")) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_')) return false;
        }
        return true;
    }

    private static String preferIfHasHistory(Context context, String packageName) {
        return hasHistory(context, packageName) ? packageName : null;
    }

    public static boolean hasHistory(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        return !SmartStateStore.queryNotifications(context, packageName, null, 1).isEmpty();
    }
}
