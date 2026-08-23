package fr.neamar.kiss.db;

public class ShortcutRecord {
    public int dbId;

    /** Visible name of shortcut. */
    public String name;

    /** Package name of the app that publishes/owns the shortcut. */
    public String packageName;

    /**
     * Real application package launched by this shortcut when it differs from the publisher.
     * This is populated from Android ShortcutInfo launch intents for wrapper shortcuts such as
     * IceBox-created shortcuts, and stays null when no distinct target can be verified.
     */
    public String targetPackage;

    public String intentUri;
}
