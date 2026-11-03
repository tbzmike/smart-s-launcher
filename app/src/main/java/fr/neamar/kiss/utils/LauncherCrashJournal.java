package fr.neamar.kiss.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import androidx.annotation.RequiresApi;

import fr.neamar.kiss.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/**
 * Opt-in shareable, app-private, last-Java-crash diagnostic. No timers, collectors,
 * background jobs, analytics or outbound network access. Android native kills/OOM
 * cannot reliably be diagnosed by a Java uncaught-exception handler.
 */
public final class LauncherCrashJournal {
    private static final String REPORT_NAME = "smart-s-last-java-crash.txt";
    private static final int MAX_TEXT_CHARS = 16_384;
    private static boolean installed;
    private static volatile String lastEvent = "Application started";
    private static volatile long lastEventAt = System.currentTimeMillis();

    private LauncherCrashJournal() { }

    /** Lightweight in-memory breadcrumb; no background collector or file writes. */
    public static void recordEvent(String event) {
        lastEvent = event;
        lastEventAt = System.currentTimeMillis();
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        final Context application = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler prior =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter writer = new StringWriter(2048);
                PrintWriter print = new PrintWriter(writer);
                print.println("Smart S Launcher Java crash at " + new Date());
                print.println("App version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
                print.println("Process: " + Process.myPid());
                print.println("Last launcher event: " + lastEvent + " at " + new Date(lastEventAt));
                print.println("Thread: " + thread.getName());
                error.printStackTrace(print);
                print.flush();
                String message = writer.toString();
                if (message.length() > MAX_TEXT_CHARS) {
                    message = message.substring(0, MAX_TEXT_CHARS);
                }
                File file = new File(application.getFilesDir(), REPORT_NAME);
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    output.write(message.getBytes("UTF-8"));
                    output.flush();
                }
            } catch (Throwable ignored) {
                // Never mask the fatal exception or compete with Android's crash handler.
            } finally {
                if (prior != null) {
                    prior.uncaughtException(thread, error);
                } else {
                    Process.killProcess(Process.myPid());
                }
            }
        });
        installed = true;
    }

    public static String readLatest(Context context) {
        File file = new File(context.getFilesDir(), REPORT_NAME);
        if (!file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_TEXT_CHARS * 4) break;
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Assemble diagnostics only when the user chooses to share them; retain the old Java log. */
    public static String buildShareReport(Context context) {
        StringBuilder report = new StringBuilder(4096);
        report.append("Smart S Launcher diagnostic report\n")
                .append("Report created: ").append(new Date()).append('\n')
                .append("Current app version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                .append("Android API: ").append(Build.VERSION.SDK_INT).append('\n')
                .append("Current process: ").append(Process.myPid()).append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            RecentExits.append(context, report);
        } else {
            report.append("\nRecent Android process exits: unavailable on this Android version.\n");
        }
        report.append("\nLast saved Java crash (may be from an earlier date or app version):\n");
        String saved = readLatest(context);
        report.append(saved == null || saved.isEmpty() ? "None saved.\n" : saved);
        return report.toString();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private static final class RecentExits {
        static void append(Context context, StringBuilder report) {
            report.append("\nRecent Android process exits (a normal stop is not necessarily a crash):\n");
            try {
                ActivityManager manager = context.getSystemService(ActivityManager.class);
                if (manager == null) {
                    report.append("Unavailable.\n");
                    return;
                }
                java.util.List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                        context.getPackageName(), 0, 5);
                if (exits == null || exits.isEmpty()) {
                    report.append("None reported by Android.\n");
                    return;
                }
                for (ApplicationExitInfo exit : exits) {
                    report.append("- ").append(new Date(exit.getTimestamp()))
                            .append(" | process: ").append(exit.getProcessName())
                            .append(" | reason: ").append(reason(exit.getReason()))
                            .append(" | status: ").append(exit.getStatus());
                    String description = exit.getDescription();
                    if (description != null && !description.isEmpty()) {
                        report.append(" | details: ").append(description.length() > 300
                                ? description.substring(0, 300) : description);
                    }
                    report.append('\n');
                }
            } catch (RuntimeException error) {
                report.append("Unavailable on this device.\n");
            }
        }

        private static String reason(int reason) {
            switch (reason) {
                case ApplicationExitInfo.REASON_CRASH: return "Java crash";
                case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
                case ApplicationExitInfo.REASON_ANR: return "app not responding";
                case ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
                case ApplicationExitInfo.REASON_USER_REQUESTED: return "user requested stop (or app update on older Android)";
                case ApplicationExitInfo.REASON_USER_STOPPED: return "device user stopped";
                case ApplicationExitInfo.REASON_EXIT_SELF: return "app exited";
                case ApplicationExitInfo.REASON_SIGNALED: return "process signal";
                default: return "Android reason " + reason;
            }
        }
    }

}
