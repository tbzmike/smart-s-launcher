package fr.neamar.kiss.utils;

/** Ice Box's persisted AppUIDInfo and AppShortcutModel IDs, independent of app visibility. */
public final class IceBoxShortcutIdentity {
    private static final String META_PREFIX = "App_Meta_Shortcut_";
    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final java.util.regex.Pattern USER = java.util.regex.Pattern.compile("[0-9]+");
    public final String packageName;
    /** -1 means the publishing Android user, as in older package-only IDs. */
    public final int userId;
    public final String originalShortcutId;

    private IceBoxShortcutIdentity(String packageName, int userId, String originalShortcutId) {
        this.packageName = packageName;
        this.userId = userId;
        this.originalShortcutId = originalShortcutId;
    }

    public static boolean isPackageName(String value) {
        return value != null && PACKAGE.matcher(value).matches();
    }

    public static IceBoxShortcutIdentity parse(String id) {
        if (id == null) return null;
        String appId = id;
        String original = null;
        if (id.startsWith(META_PREFIX)) {
            int separator = id.indexOf("$$$", META_PREFIX.length());
            if (separator < 0 || separator + 3 == id.length()) return null;
            appId = id.substring(META_PREFIX.length(), separator);
            original = id.substring(separator + 3);
        }
        int separator = appId.indexOf("::");
        String pkg = separator < 0 ? appId : appId.substring(0, separator);
        if (!isPackageName(pkg)) return null;
        int user = -1;
        if (separator >= 0) {
            String number = appId.substring(separator + 2);
            if (!USER.matcher(number).matches()) return null;
            try { user = Integer.parseInt(number); }
            catch (NumberFormatException e) { return null; }
        } else if (original != null) {
            return null;
        }
        return new IceBoxShortcutIdentity(pkg, user, original);
    }
}
