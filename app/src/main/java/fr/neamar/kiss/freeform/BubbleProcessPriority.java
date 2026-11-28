package fr.neamar.kiss.freeform;

import android.app.ActivityManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.Binder;
import android.os.Process;
import androidx.annotation.RequiresApi;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/** One event-driven root Binder lease for all live bubbles; no OOM file writes, polling
 * loop, global memory policy or persistent package whitelist. Binder death drops priority. */
@RequiresApi(35)
final class BubbleProcessPriority {
    private static final Map<Integer, NativeTaskClient.Task> wanted = new LinkedHashMap<>();
    private static final ExecutorService worker = new ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());
    private static Context context;
    // Access only on the worker.
    private static NativeTaskClient.Pipe pipe;
    private static boolean queued;

    private BubbleProcessPriority() {}

    static synchronized void hold(Context app, NativeTaskClient.Task task) {
        context = app.getApplicationContext();
        wanted.put(task.id, task);
        schedule();
    }
    static synchronized void drop(int id) {
        if (wanted.remove(id) != null) schedule();
    }
    private static synchronized void schedule() {
        if (queued) return;
        queued = true;
        worker.execute(BubbleProcessPriority::sync);
    }
    private static void sync() {
        Map<Integer, NativeTaskClient.Task> snapshot;
        Context app;
        synchronized (BubbleProcessPriority.class) {
            queued = false;
            snapshot = new LinkedHashMap<>(wanted);
            app = context;
        }
        if (snapshot.isEmpty()) {
            closePipe();
            return;
        }
        try {
            if (pipe == null || !pipe.alive()) {
                closePipe();
                pipe = new NativeTaskClient(app).priorityPipe();
            }
            StringBuilder line = new StringBuilder("sync");
            for (NativeTaskClient.Task task : snapshot.values()) {
                if (task.user != NativeTaskClient.USER || task.type != 1
                        || !task.component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) continue;
                line.append(' ').append(task.id).append(' ').append(task.component);
            }
            pipe.input().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            pipe.input().flush();
            // Root acknowledges each snapshot. Bounded wait, never on UI or surface thread.
            long end = android.os.SystemClock.elapsedRealtime() + 2500L;
            StringBuilder response = new StringBuilder();
            while (android.os.SystemClock.elapsedRealtime() < end && pipe.alive()) {
                while (pipe.output().available() > 0) {
                    int c = pipe.output().read();
                    if (c < 0) break;
                    if (c == '\n') {
                        if ("SMART_S_PRIORITY:OK".contentEquals(response)) return;
                        if ("SMART_S_PRIORITY:ERROR".contentEquals(response)) { closePipe(); return; }
                        response.setLength(0);
                    } else if (response.length() < 2048) response.append((char)c);
                }
                android.os.SystemClock.sleep(10L);
            }
        } catch (Exception ignored) { }
        closePipe();
    }
    private static void closePipe() {
        if (pipe == null) return;
        try { pipe.input().close(); } catch (java.io.IOException ignored) { }
        pipe.close();
        pipe = null;
    }

    // Only the authorized root app_process can obtain the privileged ActivityManager lease.
    static void runRoot(int user) {
        if (Process.myUid() != 0 || user < 0) return;
        try {
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Object vm = runtime.getDeclaredMethod("getRuntime").invoke(null);
            runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(vm,
                    (Object) new String[]{"Landroid/os/ServiceManager;", "Landroid/app/IActivityManager;",
                            "Landroid/app/IActivityTaskManager;", "Landroid/app/TaskInfo;",
                            "Landroid/app/ActivityManager$RunningTaskInfo;"});
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Class<?> am = Class.forName("android.app.IActivityManager");
            Class<?> atm = Class.forName("android.app.IActivityTaskManager");
            Object service = Class.forName("android.app.IActivityManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null,
                            sm.getMethod("getService", String.class).invoke(null, "activity"));
            Object taskService = Class.forName("android.app.IActivityTaskManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null,
                            sm.getMethod("getService", String.class).invoke(null, "activity_task"));
            Method important = am.getMethod("setProcessImportant", IBinder.class, int.class, boolean.class, String.class);
            Method processes = am.getMethod("getRunningAppProcesses");
            Method tasks = atm.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
            IBinder token = new Binder();
            Map<Integer, ActivityManager.RunningAppProcessInfo> held = new HashMap<>();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    try {
                        String[] parts = line.split(" ");
                        if (!"sync".equals(parts[0]) || parts.length % 2 != 1) break;
                        Map<Integer, String> requested = new HashMap<>();
                        for (int i = 1; i < parts.length; i += 2) requested.put(Integer.parseInt(parts[i]), parts[i+1]);
                        List<?> running = (List<?>) processes.invoke(service);
                        List<?> rows = (List<?>) tasks.invoke(taskService, 100, false, false, -1);
                        if (running == null || rows == null) throw new IllegalStateException("No process/task snapshot");
                        Map<Integer, ActivityManager.RunningAppProcessInfo> desired = new HashMap<>();
                        for (Object row : rows) {
                            Class<?> cls = row.getClass();
                            int id = cls.getField("taskId").getInt(row);
                            String component = requested.get(id);
                            if (component == null || cls.getField("userId").getInt(row) != user
                                    || (Integer) cls.getMethod("getActivityType").invoke(row) != 1
                                    || cls.getField("displayId").getInt(row) <= 0) continue;
                            ComponentName base = (ComponentName) cls.getField("baseActivity").get(row);
                            ActivityInfo top = (ActivityInfo) cls.getField("topActivityInfo").get(row);
                            if (base == null || !component.equals(base.flattenToString()) || top == null
                                    || top.applicationInfo == null || !base.getPackageName().equals(top.packageName)) continue;
                            for (Object object : running) {
                                ActivityManager.RunningAppProcessInfo proc = (ActivityManager.RunningAppProcessInfo) object;
                                if (proc.pid > 0 && proc.uid == top.applicationInfo.uid
                                        && top.processName.equals(proc.processName) && proc.pkgList != null
                                        && Arrays.asList(proc.pkgList).contains(top.packageName)) desired.put(proc.pid, proc);
                            }
                        }
                        for (Integer pid : new ArrayList<>(held.keySet())) {
                            if (desired.containsKey(pid)) continue;
                            ActivityManager.RunningAppProcessInfo prior = held.remove(pid);
                            for (Object object : running) {
                                ActivityManager.RunningAppProcessInfo proc = (ActivityManager.RunningAppProcessInfo) object;
                                if (proc.pid == pid && proc.uid == prior.uid && proc.processName.equals(prior.processName)) {
                                    important.invoke(service, token, pid, false, "Smart S live bubble");
                                    break;
                                }
                            }
                        }
                        for (Map.Entry<Integer, ActivityManager.RunningAppProcessInfo> entry : desired.entrySet()) {
                            if (held.containsKey(entry.getKey())) continue;
                            important.invoke(service, token, entry.getKey(), true, "Smart S live bubble");
                            held.put(entry.getKey(), entry.getValue());
                        }
                        System.out.println("SMART_S_PRIORITY:OK");
                        System.out.flush();
                    } catch (Exception failure) {
                        System.out.println("SMART_S_PRIORITY:ERROR");
                        System.out.flush();
                        break;
                    }
                }
            }
            // Exiting kills the Binder token and releases any remaining importance requests.
        } catch (Exception unavailable) {
            System.out.println("SMART_S_PRIORITY:ERROR");
            System.out.flush();
        }
    }
}
