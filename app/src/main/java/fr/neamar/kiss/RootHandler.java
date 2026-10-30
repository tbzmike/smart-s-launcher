package fr.neamar.kiss;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;

import fr.neamar.kiss.utils.Log;

public class RootHandler {
    private static final String TAG = RootHandler.class.getSimpleName();
    private Boolean isRootAvailable = null;
    private long lastRootProbeElapsed;
    private static final long DENIED_ROOT_RETRY_MS = 5_000L;
    private Boolean isRootActivated = null;
    private final Context appContext;

    RootHandler(Context ctx) {
        appContext = ctx.getApplicationContext();
        resetRootHandler(ctx);
    }

    // Read the current setting on a user click instead of trusting the value that
    // happened to be loaded when RootHandler was created.
    public boolean isRootActivated() {
        return PreferenceManager.getDefaultSharedPreferences(appContext)
                .getBoolean("root-mode", false);
    }
    void resetRootHandler(Context ctx) { isRootActivated = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("root-mode", false); }

    public boolean isRootAvailable() {
        // A denied/missing su permission must not be cached forever. Root permission can
        // become available after a prompt, a profile switch, or a freezer state change.
        long now = SystemClock.elapsedRealtime();
        if (isRootAvailable == null || (!isRootAvailable
                && now - lastRootProbeElapsed >= DENIED_ROOT_RETRY_MS)) {
            lastRootProbeElapsed = now;
            try { isRootAvailable = executeRootShell("test \"$(id -u)\" -eq 0"); }
            catch (Exception e) { isRootAvailable = false; }
        }
        return isRootAvailable;
    }

    public boolean hibernateApp(String packageName) {
        try { return executeRootShell("am force-stop " + packageName); }
        catch (Exception e) { return false; }
    }

    public boolean enableApp(String packageName, int userId) {
        if (!isSafeName(packageName)) return false;
        // IceBox can use pm hide as well as disable-user. pm enable alone leaves a hidden
        // package unavailable. AppLaunchUtils verifies the actual state after this operation.
        try { return executeRootShell("pm unhide --user " + userId + " '" + packageName
                + "'\npm unsuspend --user " + userId + " '" + packageName
                + "'\npm enable --user " + userId + " '" + packageName + "'"); }
        catch (Exception e) { return false; }
    }

    public boolean enableComponent(String packageName, String className, int userId) {
        if (!isSafeName(packageName) || !isSafeName(className)) return false;
        try {
            return executeRootShell("pm enable --user " + userId + " '" + packageName + "/" + className + "'");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSafeName(String value) {
        return value != null && value.matches("[A-Za-z0-9_\\.$]+");
    }

    /** Bounded read used only for the selected shortcut's root-authorized exact route. */
    public String readPrivilegedOutput(String command) {
        return isRootAvailable() ? executeRootRead(command) : "";
    }

    protected String executeRootRead(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long deadline = SystemClock.elapsedRealtime() + 10000L;
            while (SystemClock.elapsedRealtime() < deadline) {
                while (process.getInputStream().available() > 0) {
                    int read = process.getInputStream().read(buffer);
                    if (read < 0) break;
                    output.write(buffer, 0, read);
                    if (output.size() > 524288) return "";
                }
                try {
                    int exit = process.exitValue();
                    int read;
                    while ((read = process.getInputStream().read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                        if (output.size() > 524288) return "";
                    }
                    return exit == 0 ? output.toString("UTF-8") : "";
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(20L);
                }
            }
        } catch (Exception failure) {
            Log.w(TAG, "Selected shortcut route bridge unavailable");
        } finally {
            if (process != null) process.destroy();
        }
        return "";
    }

    protected boolean executeRootShell(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            if (command != null && !command.trim().isEmpty()) {
                p.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
                // exit without the preceding command's status always reported success for
                // a failed 'pm enable' inside a successfully spawned su shell.
                p.getOutputStream().write("exit $?\n".getBytes(StandardCharsets.UTF_8));
            } else {
                p.getOutputStream().write("exit\n".getBytes(StandardCharsets.UTF_8));
            }
            p.getOutputStream().flush();
            p.getOutputStream().close();
            // Process.waitFor(timeout, TimeUnit) requires API 26; Smart S supports API 21.
            // Bounded exitValue polling preserves the same timeout on older Android too.
            long deadline = SystemClock.elapsedRealtime() + 10_000L;
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    int result = p.exitValue();
                    if (result != 0) throw new Exception("Command execution failed " + result);
                    return true;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(50L);
                }
            }
            throw new Exception("Root command timed out");
        } catch (Exception e) {
            Log.d(TAG, "Unable to execute root shell", e);
        } finally {
            if (p != null) p.destroy();
        }
        return false;
    }
}
