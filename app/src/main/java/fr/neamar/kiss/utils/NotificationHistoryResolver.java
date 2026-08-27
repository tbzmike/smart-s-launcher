package fr.neamar.kiss.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.ui.NotificationPopupDialog;
import fr.neamar.kiss.ui.RichNotificationHistoryDialog;

/** Resolves notification history to the real target application, never a wrapper by accident. */
public final class NotificationHistoryResolver {
    private NotificationHistoryResolver() {}

    public static boolean showForPojo(Context context, Pojo pojo) {
        if (context == null || pojo == null) return false;

        // History is authoritative when it exists: this keeps tap and long-press on the same
        // swipeable rich dialog, including album art, notification pictures and saved media.
        String packageName = resolvePackage(context, pojo);
        if (packageName != null && RichNotificationHistoryDialog.showLatest(context, packageName)) {
            return true;
        }

        // A live notification can still be opened when no persisted record exists yet.
        if (pojo instanceof NotificationPojo) {
            NotificationPojo notification = (NotificationPojo) pojo;
            boolean liveIndividual = notification.id.startsWith(NotificationListener.NOTIFICATION_SCHEME)
                    && NotificationListener.isNotificationActive(context, notification.id);
            boolean liveGroup = !NotificationListener.getGroupNotifications(
                    context, notification.groupKey).isEmpty();
            if (liveIndividual || liveGroup) {
                NotificationPopupDialog.showGroup(context, notification.groupKey);
                return true;
            }
        }
        return false;
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
        boolean iceBoxPublisher = ShortcutUtil.isIceBoxPublisher(context, shortcut.packageName);

        // Verified device format for IceBox app shortcuts:
        // shortcut://com.catchingnow.icebox/oreo-shortcut/com.openai.chatgpt
        // In this format the Oreo shortcut id itself is the real target package. This must take
        // absolute priority over the publisher and over heuristic launch-intent inspection.
        if (iceBoxPublisher && shortcut.isOreoShortcut()) {
            String idTarget = shortcut.getOreoId();
            if (looksLikePackageName(idTarget)
                    && !TextUtils.equals(idTarget, shortcut.packageName)) {
                return preferIfHasHistory(context, idTarget);
            }
        }

        // Android/Oreo wrapper shortcuts can also carry a target captured from ShortcutInfo.
        if (!TextUtils.isEmpty(shortcut.targetPackage)
                && !TextUtils.equals(shortcut.targetPackage, shortcut.packageName)) {
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
        // target cannot be verified or has no saved history, showing IceBox history is incorrect.
        if (iceBoxPublisher) return null;

        // Ordinary app-owned shortcuts legitimately map to their publisher when no distinct target exists.
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
