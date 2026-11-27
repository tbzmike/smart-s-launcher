package fr.neamar.kiss.utils;

import java.util.regex.Pattern;

/** Pure, narrowly scoped command construction; never accepts shell syntax from app metadata. */
public final class FreeformLaunchCommand {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.$]+");
    private FreeformLaunchCommand() {}

    public static String build(String pkg, String activity, int userId) {
        if (userId < 0 || pkg == null || activity == null
                || !NAME.matcher(pkg).matches() || !NAME.matcher(activity).matches()) return null;
        return "am start --user " + userId + " --windowingMode 5"
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -n '" + pkg + "/" + activity + "' 2>&1";
    }

    /** A start accepted by ActivityManager is not proof that the ROM kept mode 5. */
    public static boolean accepted(String output) {
        if (output == null || !output.contains("Starting:")) return false;
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Error") || trimmed.startsWith("Exception")
                    || trimmed.startsWith("Security exception")) return false;
        }
        return true;
    }
}
