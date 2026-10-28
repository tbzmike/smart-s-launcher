package fr.neamar.kiss.utils;

import android.content.Context;
import android.os.Process;

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

    private LauncherCrashJournal() { }

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
}
