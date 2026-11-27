package fr.neamar.kiss.freeform;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

import fr.neamar.kiss.KissApplication;

/**
 * One serialized registry of selected Android task IDs. Minimize recovery is written BEFORE
 * hiding a task. The journal lives in noBackupFilesDir and is scoped to this boot and profile.
 * All methods other than hasJournal run off the UI thread.
 */
@RequiresApi(35)
final class FreeformSession {
    private static final String FILE = "native-freeform-session.json";
    enum OpenResult { VERIFIED, REQUESTED_UNVERIFIED, AMBIGUOUS, FAILED }

    static final class Entry {
        final int id;
        final String component;
        final Rect popup;
        boolean minimized;
        int bubbleX = -1, bubbleY = -1;
        Entry(int id, String component, Rect popup) {
            this.id = id; this.component = component; this.popup = new Rect(popup);
        }
        Entry(JSONObject row) throws org.json.JSONException {
            this(row.getInt("id"), row.getString("component"),
                    new Rect(row.getInt("l"), row.getInt("t"), row.getInt("r"), row.getInt("b")));
            minimized = row.getBoolean("minimized");
            bubbleX = row.optInt("x", -1); bubbleY = row.optInt("y", -1);
        }
        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("component", component)
                    .put("l", popup.left).put("t", popup.top).put("r", popup.right).put("b", popup.bottom)
                    .put("minimized", minimized).put("x", bubbleX).put("y", bubbleY);
        }
    }

    static final class Snapshot {
        final List<NativeTaskClient.Task> tasks;
        final Map<Integer, Entry> entries;
        Snapshot(List<NativeTaskClient.Task> tasks, Map<Integer, Entry> entries) {
            this.tasks = tasks; this.entries = entries;
        }
    }

    static boolean hasJournal(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE).exists()
                || new File(context.getNoBackupFilesDir(), FILE + ".bak").exists();
    }

    private static AtomicFile journal(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
    }

    private static int boot(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
    }

    @Nullable private static Map<Integer, Entry> load(Context context) {
        Map<Integer, Entry> entries = new LinkedHashMap<>();
        if (!hasJournal(context)) return entries;
        try {
            JSONObject doc = new JSONObject(new String(journal(context).readFully(), StandardCharsets.UTF_8));
            if (boot(context) < 0 || doc.getInt("boot") != boot(context)
                    || doc.getInt("user") != NativeTaskClient.USER) {
                // Tasks cannot survive a real reboot. Never apply saved numeric IDs after it.
                if (boot(context) >= 0 && doc.getInt("boot") != boot(context)) {
                    journal(context).delete();
                    return entries;
                }
                return null;
            }
            JSONArray rows = doc.getJSONArray("entries");
            for (int i = 0; i < rows.length(); i++) {
                Entry entry = new Entry(rows.getJSONObject(i));
                entries.put(entry.id, entry);
            }
            return entries;
        } catch (Exception invalid) { return null; }
    }

    private static boolean save(Context context, Map<Integer, Entry> entries) {
        AtomicFile file = journal(context);
        FileOutputStream stream = null;
        try {
            if (entries.isEmpty()) { file.delete(); return true; }
            int boot = boot(context);
            if (boot < 0) return false;
            JSONArray rows = new JSONArray();
            for (Entry entry : entries.values()) rows.put(entry.json());
            JSONObject doc = new JSONObject().put("boot", boot)
                    .put("user", NativeTaskClient.USER).put("entries", rows);
            stream = file.startWrite();
            stream.write(doc.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
            return true;
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            return false;
        }
    }

    @Nullable private static NativeTaskClient.Task find(List<NativeTaskClient.Task> tasks, int id) {
        if (tasks == null) return null;
        for (NativeTaskClient.Task task : tasks) if (task.id == id) return task;
        return null;
    }

    @Nullable private static NativeTaskClient.Task exact(List<NativeTaskClient.Task> tasks, Entry entry) {
        NativeTaskClient.Task task = find(tasks, entry.id);
        return task != null && task.type == 1 && task.user == NativeTaskClient.USER
                && FreeformTaskPolicy.same(entry.id, entry.component, task.id, task.component)
                ? task : null;
    }

    private static int select(List<NativeTaskClient.Task> tasks, ComponentName component) {
        List<FreeformTaskPolicy.Candidate> candidates = new ArrayList<>();
        for (NativeTaskClient.Task task : tasks) {
            if (task.type == 1) candidates.add(new FreeformTaskPolicy.Candidate(
                    task.id, task.pkg, task.component));
        }
        return FreeformTaskPolicy.select(candidates, component.getPackageName(), component.flattenToString());
    }

    static Rect fit(Rect source, Rect area, int minimum) {
        return FreeformGeometry.fit(source, area, minimum);
    }

    private static Rect defaultPopup(Rect area) { return FreeformGeometry.center(area); }

    private static Rect popupArea(Context context, Rect area) {
        return FreeformGeometry.contentArea(area, Math.round(40 * context.getResources().getDisplayMetrics().density));
    }

    private static int tolerance(Context context) {
        return Math.max(8, Math.round(8 * context.getResources().getDisplayMetrics().density));
    }

    /** Reuse an existing exact task; never launch a second task if identity is ambiguous. */
    static synchronized OpenResult open(Context context, ComponentName component, Rect area, boolean register) {
        if (component.getPackageName().equals(context.getPackageName()) || area.isEmpty()) return OpenResult.FAILED;
        Map<Integer, Entry> entries = load(context);
        if (entries == null) return OpenResult.FAILED;
        NativeTaskClient client = new NativeTaskClient(context);
        List<NativeTaskClient.Task> tasks = client.list();
        if (tasks == null) return OpenResult.FAILED; // cannot safely deduplicate
        int id = select(tasks, component);
        if (id == FreeformTaskPolicy.AMBIGUOUS) return OpenResult.AMBIGUOUS;
        if (id == FreeformTaskPolicy.NONE) {
            if (!KissApplication.getApplication(context).getRootHandler().launchFreeform(
                    component.getPackageName(), component.getClassName(), NativeTaskClient.USER)) return OpenResult.FAILED;
            // One start request only. Observe asynchronous publication, never retry the launch.
            for (int attempt = 0; attempt < 4; attempt++) {
                if (attempt != 0) SystemClock.sleep(200L * attempt);
                tasks = client.list();
                if (tasks == null) continue;
                id = select(tasks, component);
                if (id == FreeformTaskPolicy.AMBIGUOUS) return OpenResult.REQUESTED_UNVERIFIED;
                NativeTaskClient.Task candidate = find(tasks, id);
                if (candidate != null && candidate.mode == 5 && candidate.visible
                        && !candidate.bounds.isEmpty()) {
                    if (!register) return OpenResult.VERIFIED;
                    Rect wanted = defaultPopup(popupArea(context, area));
                    if (client.action("resize", candidate, wanted)) {
                        NativeTaskClient.Task placed = awaitGeometry(client, candidate.id, candidate.component,
                                5, wanted, tolerance(context), false);
                        if (placed != null) candidate = placed;
                    }
                    entries.put(candidate.id, new Entry(candidate.id, candidate.component, candidate.bounds));
                    boolean saved = save(context, entries);
                    return saved && FreeformGeometry.near(candidate.frame, wanted, tolerance(context))
                            ? OpenResult.VERIFIED : OpenResult.REQUESTED_UNVERIFIED;
                }
            }
            return OpenResult.REQUESTED_UNVERIFIED;
        }
        NativeTaskClient.Task task = find(tasks, id);
        if (task == null || (task.mode != 1 && task.mode != 5)) return OpenResult.FAILED;
        Entry entry = entries.get(id);
        if (entry != null && !FreeformTaskPolicy.same(entry.id, entry.component, task.id, task.component)) entry = null;
        Rect safe = popupArea(context, area);
        Rect popup = entry != null ? fit(entry.popup, safe, 1) : defaultPopup(safe);
        boolean restoring = task.mode != 5 || (entry != null && entry.minimized) || !task.visible
                || (register && (entry == null || !safe.contains(task.bounds)));
        if (!restoring) {
            if (!client.action("focus", task, null)) return OpenResult.FAILED;
        } else {
            if (!client.action("restore", task, popup)) return OpenResult.FAILED;
        }
        NativeTaskClient.Task observed = restoring && register
                ? awaitGeometry(client, id, task.component, 5, popup, tolerance(context), false)
                : await(client, id, task.component, 5, true);
        if (observed == null && restoring) observed = await(client, id, task.component, 5, true);
        if (observed == null || observed.bounds.isEmpty()) return OpenResult.REQUESTED_UNVERIFIED;
        if (restoring && !popup.equals(observed.bounds) && client.action("resize", observed, popup)) {
            NativeTaskClient.Task resized = await(client, id, task.component, 5, true);
            if (resized != null && !resized.bounds.isEmpty()) observed = resized;
        }
        boolean exactBounds = !restoring || (popup.equals(observed.bounds)
                && (!register || FreeformGeometry.near(observed.frame, popup, tolerance(context))));
        Entry updated = new Entry(id, observed.component, observed.bounds);
        if (entry != null) { updated.bubbleX = entry.bubbleX; updated.bubbleY = entry.bubbleY; }
        if (!register && entry == null) return exactBounds ? OpenResult.VERIFIED : OpenResult.REQUESTED_UNVERIFIED;
        entries.put(id, updated);
        return save(context, entries) && exactBounds ? OpenResult.VERIFIED : OpenResult.REQUESTED_UNVERIFIED;
    }

    @Nullable private static NativeTaskClient.Task await(NativeTaskClient client, int id,
            String component, int mode, boolean visible) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) SystemClock.sleep(180L * attempt);
            NativeTaskClient.Task task = find(client.list(), id);
            if (task != null && task.component.equals(component) && task.mode == mode
                    && task.visible == visible) return task;
        }
        return null;
    }

    @Nullable private static NativeTaskClient.Task awaitGeometry(NativeTaskClient client, int id,
            String component, int mode, Rect wanted, int tolerance, boolean fullscreen) {
        for (int i = 0; i < 6; i++) {
            if (i != 0) SystemClock.sleep(150);
            NativeTaskClient.Task task = find(client.list(), id);
            if (task == null || !component.equals(task.component) || task.mode != mode || !task.visible) continue;
            if (fullscreen ? FreeformGeometry.fullscreen(task.frame, wanted, tolerance)
                    : wanted.equals(task.bounds) && FreeformGeometry.near(task.frame, wanted, tolerance)) return task;
        }
        return null;
    }

    /** Persist only the observed final geometry, never the finger's speculative rectangle. */
    static synchronized boolean finishGesture(Context context, NativeTaskClient.Task task, Rect wanted) {
        Map<Integer, Entry> entries = load(context);
        if (entries == null) return false;
        Entry entry = entries.get(task.id);
        if (entry == null || entry.minimized || !entry.component.equals(task.component)) return false;
        NativeTaskClient.Task observed = awaitGeometry(new NativeTaskClient(context), task.id,
                task.component, 5, wanted, tolerance(context), false);
        if (observed == null) return false;
        entry.popup.set(observed.bounds);
        return save(context, entries);
    }

    /** Allocate a stable, initially non-overlapping location, saved before the task is hidden. */
    private static void placeBubble(Context context, Entry target, Map<Integer, Entry> entries, Rect area) {
        if (target.bubbleX >= 0 && target.bubbleY >= 0) return;
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.round(54 * density)), gap = Math.max(1, Math.round(8 * density));
        int step = size + gap;
        int start = Math.min(area.bottom - size, area.top + Math.round(64 * density));
        for (int x = area.right - size - gap; x >= area.left; x -= step) {
            for (int y = Math.max(area.top, start); y <= area.bottom - size; y += step) {
                Rect box = new Rect(x, y, x + size, y + size);
                boolean occupied = false;
                for (Entry other : entries.values()) {
                    if (other.id != target.id && other.minimized && other.bubbleX >= 0 && other.bubbleY >= 0) {
                        Rect used = new Rect(other.bubbleX, other.bubbleY,
                                other.bubbleX + size, other.bubbleY + size);
                        if (Rect.intersects(box, used)) { occupied = true; break; }
                    }
                }
                if (!occupied) { target.bubbleX = x; target.bubbleY = y; return; }
            }
        }
        target.bubbleX = Math.max(area.left, area.right - size);
        target.bubbleY = Math.max(area.top, start);
    }

    @Nullable static synchronized Snapshot snapshot(Context context, Rect area, boolean normalize) {
        Map<Integer, Entry> entries = load(context);
        if (entries == null) return null;
        if (entries.isEmpty()) return new Snapshot(new ArrayList<>(), entries);
        List<NativeTaskClient.Task> tasks = new NativeTaskClient(context).list();
        if (tasks == null) return null;
        boolean changed = false;
        if (normalize && !area.isEmpty()) {
            Rect safe = popupArea(context, area);
            NativeTaskClient client = new NativeTaskClient(context);
            boolean requested = false;
            for (Entry entry : entries.values()) {
                NativeTaskClient.Task task = exact(tasks, entry);
                if (task == null || entry.minimized || task.mode != 5 || !task.visible) continue;
                if (!safe.contains(task.bounds)) {
                    Rect next = fit(task.bounds, safe, 1);
                    if (client.action("resize", task, next)) requested = true;
                }
            }
            if (requested) {
                List<NativeTaskClient.Task> updated = client.list();
                if (updated != null) tasks = updated;
            }
        }
        for (Entry entry : new ArrayList<>(entries.values())) {
            NativeTaskClient.Task task = exact(tasks, entry);
            if (task == null) {
                entries.remove(entry.id); changed = true;
            } else if (!entry.minimized && task.mode == 5 && !task.bounds.isEmpty()
                    && !entry.popup.equals(task.bounds)) {
                entry.popup.set(task.bounds); changed = true;
            }
        }
        if (changed && !save(context, entries)) return null;
        return new Snapshot(tasks, entries);
    }

    static synchronized boolean operate(Context context, int id, String component, String action,
                                         @Nullable Rect requested, Rect area) {
        Map<Integer, Entry> entries = load(context);
        if (entries == null) return false;
        Entry entry = entries.get(id);
        if (entry == null || !entry.component.equals(component)) return false;
        NativeTaskClient client = new NativeTaskClient(context);
        List<NativeTaskClient.Task> tasks = client.list();
        if (tasks == null) return false;
        NativeTaskClient.Task task = exact(tasks, entry);
        if (task == null) { entries.remove(id); save(context, entries); return false; }
        if ("close".equals(action)) {
            if (!client.action("close", task, null)) return false;
            List<NativeTaskClient.Task> after = client.list();
            if (after == null || exact(after, entry) != null) return false;
            entries.remove(id); return save(context, entries);
        }
        if ("minimize".equals(action)) {
            if (entry.minimized || (task.mode != 5 && task.mode != 1) || entry.popup.isEmpty()) return false;
            if (task.mode == 5 && !task.bounds.isEmpty()) entry.popup.set(task.bounds);
            placeBubble(context, entry, entries, area);
            entry.minimized = true;
            if (!save(context, entries)) return false; // Never hide an unjournaled task.
            if (client.action("minimize", task, null)
                    && await(client, id, component, task.mode, false) != null) return true;
            // A failed/uncertain minimize must not strand a hidden task. Retain the recovery
            // record unless the same task is observed restored and visible.
            if (client.action("restore", task, fit(entry.popup, popupArea(context, area), 1))
                    && await(client, id, component, 5, true) != null) {
                entry.minimized = false; save(context, entries);
            }
            return false;
        }
        if ("restore".equals(action)) {
            Rect popup = fit(entry.popup, popupArea(context, area), 1);
            if (!client.action("restore", task, popup)) return false;
            NativeTaskClient.Task after = await(client, id, component, 5, true);
            if (after == null) return false;
            // Some ROMs apply default bounds during a mode transition. Correct the same task
            // once, then compare the observed rectangle rather than trusting binder success.
            if (!popup.equals(after.bounds) && client.action("resize", after, popup)) {
                NativeTaskClient.Task resized = await(client, id, component, 5, true);
                if (resized != null) after = resized;
            }
            entry.minimized = false; entry.popup.set(after.bounds);
            boolean saved = save(context, entries);
            return saved && popup.equals(after.bounds)
                    && FreeformGeometry.near(after.frame, popup, tolerance(context));
        }
        if ("maximize".equals(action)) {
            if (entry.minimized || task.mode != 5) return false;
            entry.popup.set(task.bounds);
            if (!save(context, entries)) return false;
            if (!client.action("maximize", task, null)) return false;
            if (awaitGeometry(client, id, component, 1, area, tolerance(context), true) != null) return true;
            // A mode flag alone is insufficient. Restore the known popup if the surface
            // never actually becomes fullscreen, so no clipped pseudo-fullscreen is retained.
            client.action("restore", task, fit(entry.popup, popupArea(context, area), 1));
            awaitGeometry(client, id, component, 5, entry.popup, tolerance(context), false);
            return false;
        }
        if ("resize".equals(action)) {
            if (entry.minimized || task.mode != 5 || requested == null) return false;
            Rect wanted = fit(requested, popupArea(context, area), 1);
            if (!client.action("resize", task, wanted)) return false;
            NativeTaskClient.Task after = awaitGeometry(client, id, component, 5, wanted, tolerance(context), false);
            if (after == null) return false;
            entry.popup.set(after.bounds);
            return save(context, entries);
        }
        return "focus".equals(action) && !entry.minimized && client.action("focus", task, null);
    }

    static synchronized boolean moveBubble(Context context, int id, String component, int x, int y) {
        Map<Integer, Entry> entries = load(context);
        if (entries == null) return false;
        Entry entry = entries.get(id);
        if (entry == null || !entry.minimized || !entry.component.equals(component)) return false;
        entry.bubbleX = x; entry.bubbleY = y;
        return save(context, entries);
    }

    /** Restore hidden tasks before intentionally stopping controls; do not close any other app. */
    static synchronized boolean restoreAllAndForget(Context context, Rect area) {
        if (area.isEmpty()) return false;
        Map<Integer, Entry> entries = load(context);
        if (entries == null) return false;
        if (entries.isEmpty()) return true;
        NativeTaskClient client = new NativeTaskClient(context);
        List<NativeTaskClient.Task> tasks = client.list();
        if (tasks == null) return false;
        for (Entry entry : new ArrayList<>(entries.values())) {
            NativeTaskClient.Task task = exact(tasks, entry);
            if (task == null || !entry.minimized) { entries.remove(entry.id); continue; }
            if (client.action("restore", task, fit(entry.popup, popupArea(context, area), 1))
                    && await(client, entry.id, entry.component, 5, true) != null) entries.remove(entry.id);
        }
        return save(context, entries) && entries.isEmpty();
    }
}
