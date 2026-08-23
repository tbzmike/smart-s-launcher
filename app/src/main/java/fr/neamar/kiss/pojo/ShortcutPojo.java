package fr.neamar.kiss.pojo;

import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

public final class ShortcutPojo extends PojoWithTags {

    public static final String SCHEME = "shortcut://";
    public static final String OREO_PREFIX = "oreo-shortcut/";

    /** Publisher/owner of the shortcut (for IceBox shortcuts this can be IceBox). */
    public final String packageName;
    /** Verified real app launched by the shortcut when different from the publisher. */
    public final String targetPackage;
    public final String intentUri;// TODO: 15/10/18 Use boolean instead of prefix for Oreo shortcuts
    private final String componentName;
    private final boolean pinned;
    private final boolean dynamic;
    private final boolean disabled;
    private final UserHandle userHandle;

    public ShortcutPojo(UserHandle userHandle, ShortcutRecord shortcutRecord, String componentName,
                        boolean pinned, boolean dynamic, boolean disabled) {
        super(ShortcutUtil.generateShortcutId(userHandle, shortcutRecord));
        this.packageName = shortcutRecord.packageName;
        this.targetPackage = shortcutRecord.targetPackage;
        this.intentUri = shortcutRecord.intentUri;
        this.componentName = componentName;
        this.pinned = pinned;
        this.dynamic = dynamic;
        this.disabled = disabled;
        this.userHandle = userHandle;
    }

    public boolean isOreoShortcut() {
        return intentUri.contains(ShortcutPojo.OREO_PREFIX);
    }

    public String getOreoId() {
        return intentUri.replace(ShortcutPojo.OREO_PREFIX, "");
    }

    public String getComponentName() { return componentName; }
    public boolean isPinned() { return pinned; }
    public boolean isDynamic() { return dynamic; }
    @Override public boolean isDisabled() { return disabled; }
    @Override public UserHandle getUserHandle() { return userHandle; }
}
