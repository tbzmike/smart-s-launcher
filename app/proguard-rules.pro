-dontobfuscate
-keep class fr.neamar.kiss.ExcludeAppSettingsFragment
-optimizations !code/allocation/variable
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}
# Invoked by the existing authorized root shell through app_process.
-keep class fr.neamar.kiss.shortcut.ShortcutRouteBridge { *; }
-keep class fr.neamar.kiss.shortcut.ShortcutRouteCodec { *; }
