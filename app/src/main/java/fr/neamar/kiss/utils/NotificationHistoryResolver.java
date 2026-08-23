package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

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

/** Resolves notification history to the real target application, never a wrapper by accident. */
public final class NotificationHistoryResolver {
    private NotificationHistoryResolver() {}

    public static boolean showForPojo(Context context, Pojo pojo) {
        String packageName = resolvePackage(context, pojo);
        return packageName != null && LockedNotificationHistoryDialog.showLatest(context, packageName);
    }

    public static String resolvePackage(Context context, Pojo pojo) {
        if (context == null || pojo == null) return null;
        if (pojo instanceof DisabledAppPojo) {
            return preferIfHasHistory(context, ((DisabledAppPojo) pojo).targetPackage);
        }
        if (pojo instanceof NotificationPojo) {
            return preferIfHasHistory(context, ((NotificationPojo) pojo).packageName);
        }
        if (pojo instanceof AppPojo) {
            return preferIfHasHistory(context, ((AppPojo) pojo).packageName);
        }
        if (pojo instanceof ShortcutPojo) {
            return resolveShortcutPackage(context, (ShortcutPojo) pojo);
        }
        return null;
    }

    private static String resolveShortcutPackage(Context context, ShortcutPojo shortcut) {
        // Android/Oreo wrapper shortcuts are resolved while ShortcutInfo still exposes the actual
        // launch intents. Once a real target was captured, it is authoritative.
        if (!TextUtils.isEmpty(shortcut.targetPackage)) {
            return preferIfHasHistory(context, shortcut.targetPackage);
        }

        Set<String> candidates = new LinkedHashSet<>();
        if (!shortcut.isOreoShortcut()) {
            try {
                Intent intent = Intent.parseUri(shortcut.intentUri, 0);
                collectStringExtras(intent.getExtras(), candidates);
                if (intent.getPackage() != null) candidates.add(intent.getPackage());
                if (intent.getComponent() != null) candidates.add(intent.getComponent().getPackageName());
            } catch (URISyntaxException | RuntimeException ignored) {
                // Handled below. IceBox wrappers are deliberately not allowed to fall back.
            }
        }

        for (String candidate : candidates) {
            if (!shortcut.packageName.equals(candidate) && hasHistory(context, candidate)) {
                return candidate;
            }
        }

        // Hard rule: an IceBox-published shortcut represents the frozen app it launches. If that
        // target cannot be verified, showing IceBox history would be incorrect, so show nothing.
        if (ShortcutUtil.isIceBoxPublisher(context, shortcut.packageName)) return null;

        // Ordinary app-owned shortcuts (e.g. a Chrome shortcut owned by Chrome) legitimately map
        // to their publisher when no distinct target exists.
        return hasHistory(context, shortcut.packageName) ? shortcut.packageName : null;
    }

    private static void collectStringExtras(Bundle extras, Set<String> candidates) {
        if (extras == null) return;
        for (String key : extras.keySet()) {
            Object value;
            try { value = extras.get(key); }
            catch (RuntimeException ignored) { continue; }
            if (value instanceof Intent) {
                Intent nested = (Intent) value;
                if (nested.getPackage() != null) candidates.add(nested.getPackage());
                if (nested.getComponent() != null) candidates.add(nested.getComponent().getPackageName());
                collectStringExtras(nested.getExtras(), candidates);
            } else if (value instanceof String) {
                addCandidateString((String) value, candidates);
            }
        }
    }

    private static void addCandidateString(String raw, Set<String> candidates) {
        if (raw == null) return;
        String value = raw.trim();
        if (looksLikePackageName(value)) {
            candidates.add(value);
            return;
        }
        int slash = value.indexOf('/');
        if (slash > 0) {
            String prefix = value.substring(0, slash);
            if (looksLikePackageName(prefix)) candidates.add(prefix);
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
        if (context == null || TextUtils.isEmpty(packageName)) return false;
        return !SmartStateStore.queryNotifications(context, packageName, null, 1).isEmpty();
    }
}
