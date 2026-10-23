package fr.neamar.kiss.backup;

/** Pure parsing of existing tag record IDs, without normalising names or guessing targets. */
public final class TagLinkIdentity {
    private TagLinkIdentity() { }

    public static String appComponent(String id) {
        if (id == null || !id.startsWith("app://")) return "";
        String rest = id.substring("app://".length());
        int suffix = rest.lastIndexOf('#');
        if (suffix > 0 && digits(rest.substring(suffix + 1))) return rest.substring(0, suffix);
        return rest;
    }

    public static boolean hasAppProfile(String id) {
        return id != null && id.startsWith("app://")
                && !appComponent(id).equals(id.substring("app://".length()));
    }

    public static boolean hasShortcutProfile(String id, String publisher, String intentUri) {
        if (!belongsToShortcut(id, publisher, intentUri, null)) return false;
        String base = "shortcut://" + publisher + "/" + intentUri;
        return id.length() > base.length()
                && id.charAt(base.length()) == '/'
                && digits(id.substring(base.length() + 1));
    }

    public static boolean belongsToShortcut(String id, String publisher, String intentUri, String legacyName) {
        if (id == null || publisher == null || intentUri == null) return false;
        String base = "shortcut://" + publisher + "/" + intentUri;
        if (base.equals(id)) return true;
        if (id.startsWith(base + "/") && digits(id.substring(base.length() + 1))) return true;
        return legacyName != null && id.equals("shortcut://" + legacyName.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean digits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }
}
