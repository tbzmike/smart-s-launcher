from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Version bump: build only from the exact verified 3.30.100 baseline.
build_path = "app/build.gradle"
build = read(build_path)
build = replace_once(
    build,
    '        // Smart S Launcher 3.30.100 - unlocked Remove item menu in History and Search\n        versionCode 528\n        versionName "3.30.100"',
    '        // Smart S Launcher 3.30.101 - performance-aware animation safeguards\n        versionCode 529\n        versionName "3.30.101"',
    "version bump",
)
write(build_path, build)

engine_path = "app/src/main/java/fr/neamar/kiss/ui/SmartAnimationEngine.java"
engine = read(engine_path)

# Keep the engine allocation-light: only system services are consulted, and no worker/thread/timer
# is introduced. These checks are evaluated only when an animation is requested.
engine = replace_once(
    engine,
    'import android.animation.AnimatorListenerAdapter;\nimport android.app.Dialog;\nimport android.content.Context;',
    'import android.animation.AnimatorListenerAdapter;\nimport android.app.ActivityManager;\nimport android.app.Dialog;\nimport android.content.Context;\nimport android.os.PowerManager;\nimport android.provider.Settings;',
    "performance imports",
)

engine = replace_once(
    engine,
    '    private static volatile SharedPreferences cachedPreferences;\n',
    '    private static volatile SharedPreferences cachedPreferences;\n    private static final int MAX_STAGGERED_CHILDREN = 12;\n',
    "stagger cap constant",
)

engine = replace_once(
    engine,
    '''    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("smart-animations-enabled", true);
    }

    public static String getStyle(Context context, String key, String fallback) {
        Object value = readPreferenceValue(prefs(context), key);
        return value instanceof String ? (String) value : fallback;
    }

    public static long duration(Context context) {
        int base = context.getResources().getInteger(android.R.integer.config_shortAnimTime);
        SharedPreferences preferences = prefs(context);
        float speed = readSpeedMultiplier(preferences);
        speed = Math.max(0.05f, Math.min(3f, speed));
        return Math.max(80L, Math.round(base / speed));
    }
''',
    '''    public static boolean isEnabled(Context context) {
        if (!prefs(context).getBoolean("smart-animations-enabled", true)) return false;
        // Respect Android's global "remove animations" / animator-duration-scale setting.
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean isPerformanceConstrained(Context context) {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null && activityManager.isLowRamDevice()) return true;

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isPowerSaveMode();
    }

    private static boolean isHeavyStyle(String style) {
        switch (style) {
            case "whirl":
            case "orbit":
            case "elastic":
            case "bounce":
            case "parallax":
            case "swing":
            case "accordion":
            case "spiral":
            case "cube":
            case "fold":
            case "helix":
            case "fan":
                return true;
            default:
                return false;
        }
    }

    public static String getStyle(Context context, String key, String fallback) {
        Object value = readPreferenceValue(prefs(context), key);
        String style = value instanceof String ? (String) value : fallback;
        if (!isPerformanceConstrained(context) || !isHeavyStyle(style)) return style;

        // Gracefully downgrade expensive multi-axis effects while Battery Saver is active or on
        // Android low-RAM devices. User selections remain stored and return automatically later.
        if ("smart-animation-scroll".equals(key)) return "classic";
        if ("smart-animation-view-switch".equals(key)) return "crossfade";
        return "fade";
    }

    public static long duration(Context context) {
        int base = context.getResources().getInteger(android.R.integer.config_shortAnimTime);
        SharedPreferences preferences = prefs(context);
        float speed = readSpeedMultiplier(preferences);
        speed = Math.max(0.05f, Math.min(3f, speed));
        long resolved = Math.max(80L, Math.round(base / speed));
        return isPerformanceConstrained(context) ? Math.min(160L, resolved) : resolved;
    }
''',
    "performance policy",
)

engine = replace_once(
    engine,
    '''    public static void animateTileListItem(View child, int index) {
        if (child == null) return;
        child.animate().cancel();
        reset(child);
        Context context = child.getContext();
        if (!isEnabled(context)) return;

        String style = getStyle(context, "smart-animation-scroll", "classic");
''',
    '''    public static void animateTileListItem(View child, int index) {
        if (child == null) return;
        child.animate().cancel();
        reset(child);
        Context context = child.getContext();
        if (!isEnabled(context)) return;
        // Never keep a long chain of staggered ViewPropertyAnimators alive for large result lists.
        // Later/recycled rows render immediately and remain fully interactive.
        if (index >= MAX_STAGGERED_CHILDREN) return;

        String style = getStyle(context, "smart-animation-scroll", "classic");
''',
    "staggered animation cap",
)

engine = replace_once(
    engine,
    '        long delay = Math.min(220L, Math.max(0, index) * ("cascade".equals(style) ? 38L : 24L));',
    '        long delay = Math.min(140L, Math.max(0, index) * ("cascade".equals(style) ? 28L : 18L));',
    "stagger delay cap",
)

write(engine_path, engine)
