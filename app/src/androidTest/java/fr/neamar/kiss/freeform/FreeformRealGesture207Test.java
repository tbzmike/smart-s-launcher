package fr.neamar.kiss.freeform;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.graphics.Insets;

import androidx.test.filters.SdkSuppress;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import fr.neamar.kiss.MainActivity;

/**
 * Real service, root Binder, SurfaceFlinger, actual touch events and actual rendered pixels.
 * The emulator grants shell root to the debug instrumentation transport only; the production
 * source set does not contain the test override. No mocked task coordinates or gestures.
 */
@SdkSuppress(minSdkVersion = 35)
public final class FreeformRealGesture207Test {
    private static final String PKG = "com.tbzmike.shortcutfixture.target";
    private static final String COMPONENT = PKG + "/com.tbzmike.shortcutfixture.FixtureFreeformActivity";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();
    private final UiAutomation ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private int id;
    private long downTime;
    private Rect usable;
    private final float density = app.getResources().getDisplayMetrics().density;

    private Rect switchBubble(String name) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 18000;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (WindowFrameLookup.Window w : WindowFrameLookup.parse(shell("dumpsys window -a windows"))) {
                if (w.name.equals(name) && w.valid()) return new Rect(w.frame[0],w.frame[1],w.frame[2],w.frame[3]);
            }
            SystemClock.sleep(120);
        }
        throw new AssertionError("Missing switcher overlay: " + name);
    }

    private void playingWhileBubbled(int taskId) throws Exception {
        long end=SystemClock.elapsedRealtime()+14000;
        int initial=-1,display=-1;
        while(SystemClock.elapsedRealtime()<end) {
            String lines=shell("logcat -d -s SmartBubbleMedia:I");
            for(String line:lines.split("\n")) {
                java.util.regex.Matcher sample=java.util.regex.Pattern.compile("tick "+taskId+" (\\d+) (\\d+)").matcher(line);
                if(!sample.find())continue;
                int d=Integer.parseInt(sample.group(1)), pos=Integer.parseInt(sample.group(2));
                if(d==0)continue;
                if(initial<0 || display!=d) { initial=pos; display=d; }
                else if(pos>=initial+500)return;
            }
            SystemClock.sleep(160);
        }
        throw new AssertionError("Media playback did not advance on the bubbled task's display: "+taskId+" "+shell("logcat -d -s SmartBubbleMedia:I | tail -15"));
    }

    private Rect longPressMenu(Rect bubble) throws Exception {
        motion(MotionEvent.ACTION_DOWN,bubble.centerX(),bubble.centerY());
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+260);
        motion(MotionEvent.ACTION_UP,bubble.centerX(),bubble.centerY());
        return switchBubble("Smart S bubble menu");
    }

    private void dropFolder(String family) throws Exception {
        Rect folder = switchBubble("Smart S " + family + " folder");
        tap(folder.centerX(),folder.centerY());
        assertFalse(switchBubble("Smart S " + family + " folder contents").isEmpty());
        tap(folder.centerX(),folder.centerY());
        motion(MotionEvent.ACTION_DOWN,folder.centerX(),folder.centerY());
        motion(MotionEvent.ACTION_MOVE,folder.centerX()+dp(25),folder.centerY()+dp(25));
        Rect x = switchBubble("Smart S " + family + " folder close target");
        motion(MotionEvent.ACTION_MOVE,x.centerX(),x.centerY());
        motion(MotionEvent.ACTION_UP,x.centerX(),x.centerY());
    }

    private void checkFullscreenSwitching() throws Exception {
        String previous = shell("settings get secure enabled_accessibility_services").trim();
        String service = app.getPackageName() + "/fr.neamar.kiss.freeform.FullscreenBubbleService";
        String enabled = "null".equals(previous) || previous.isEmpty() ? service : previous + ":" + service;
        try {
            shell("settings put secure enabled_accessibility_services " + enabled);
            shell("input keyevent KEYCODE_HOME");
            shell("am start -W --windowingMode 1 -n " + COMPONENT);
            JSONObject first = task(await("first fullscreen task", d -> task(d) != null && task(d).getBoolean("focused")));
            int firstId = first.getInt("id");
            SystemClock.sleep(850);
            shell("input keyevent KEYCODE_HOME");
            Rect firstBubble = switchBubble("Smart S fullscreen switch " + firstId);
            playingWhileBubbled(firstId);
            longPressMenu(firstBubble);
            assertTrue("Long press alone keeps task bubbled",task(state()).getInt("display")>0);
            tap(usable.centerX(),usable.bottom-dp(15));
            String second = "com.tbzmike.shortcutfixture.icebox";
            shell("am start -W --windowingMode 1 -n " + second + "/com.tbzmike.shortcutfixture.FixtureFreeformActivity");
            SystemClock.sleep(850);
            int secondId = -1;
            JSONArray rows = state().getJSONArray("tasks");
            for (int i=0;i<rows.length();i++) if (second.equals(rows.getJSONObject(i).getString("pkg")))
                secondId=rows.getJSONObject(i).getInt("id");
            assertTrue(secondId >= 0);
            tap(firstBubble.centerX(),firstBubble.centerY());
            await("same first task restored fullscreen", d -> task(d).getInt("id")==firstId && task(d).getBoolean("focused") && task(d).getInt("mode")==1);
            Rect other = switchBubble("Smart S fullscreen switch " + secondId);
            tap(other.centerX(),other.centerY());
            Rect again = switchBubble("Smart S fullscreen switch " + firstId);
            motion(MotionEvent.ACTION_DOWN,again.centerX(),again.centerY());
            motion(MotionEvent.ACTION_MOVE,again.centerX()+dp(25),again.centerY()+dp(25));
            Rect x = switchBubble("Smart S fullscreen close target");
            motion(MotionEvent.ACTION_MOVE,x.centerX(),x.centerY());
            motion(MotionEvent.ACTION_UP,x.centerX(),x.centerY());
            await("drag to X closes only first fullscreen task", d -> task(d)==null);
            shell("am start -W --windowingMode 1 -n " + COMPONENT);
            SystemClock.sleep(900);
            shell("input keyevent KEYCODE_HOME");
            switchBubble("Smart S fullscreen folder");
            SharedPreferences layouts=app.getSharedPreferences("switch-folder-layouts",Context.MODE_PRIVATE);
            assertTrue(layouts.edit().putBoolean("fullscreen",false).commit());
            switchBubble("Smart S fullscreen switch " + secondId);
            assertTrue(layouts.edit().putBoolean("fullscreen",true).commit());
            Rect folder=switchBubble("Smart S fullscreen folder");
            tap(folder.centerX(),folder.centerY());
            Rect panel=switchBubble("Smart S fullscreen folder contents");
            int itemX=panel.left+dp(38), itemY=panel.top+dp(38);
            motion(MotionEvent.ACTION_DOWN,itemX,itemY);
            motion(MotionEvent.ACTION_MOVE,itemX+dp(20),itemY+dp(20));
            Rect itemTarget=switchBubble("Smart S fullscreen folder close target");
            motion(MotionEvent.ACTION_MOVE,itemTarget.centerX(),itemTarget.centerY());
            motion(MotionEvent.ACTION_UP,itemTarget.centerX(),itemTarget.centerY());
            await("only one fullscreen member closed",d -> {
                JSONArray list=d.getJSONArray("tasks"); int remaining=0;
                for(int i=0;i<list.length();i++) {
                    String pkg=list.getJSONObject(i).getString("pkg");
                    if(PKG.equals(pkg)||second.equals(pkg)) remaining++;
                }
                return remaining==1;
            });
            boolean firstRemains=task(state())!=null;
            String relaunch=firstRemains ? second+"/com.tbzmike.shortcutfixture.FixtureFreeformActivity" : COMPONENT;
            shell("am start -W --windowingMode 1 -n "+relaunch);
            SystemClock.sleep(900);
            shell("input keyevent KEYCODE_HOME");
            dropFolder("fullscreen");
            await("bulk close removes both switcher tasks", d -> task(d)==null && !d.getJSONArray("tasks").toString().contains(second));
        } finally {
            if ("null".equals(previous) || previous.isEmpty()) shell("settings delete secure enabled_accessibility_services");
            else shell("settings put secure enabled_accessibility_services " + previous);
        }
    }

    private int dp(int value) { return Math.round(value * density); }

    private String shell(String command) throws IOException {
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // UiAutomation runs as Android's shell UID. The isolated google_apis emulator has
    // the standard userdebug su binary. Its argv form avoids reliance on shell quoting.
    private String root(String command) {
        return "su 0 " + command.replace("'", "").replace(" 2>&1", "");
    }

    private String bridgeCommand() {
        return "/system/bin/env CLASSPATH=" + app.getApplicationInfo().sourceDir
                + " /system/bin/app_process /system/bin " + NativeTaskBridge.class.getName()
                + " list " + NativeTaskClient.USER;
    }

    private JSONObject state() throws Exception {
        String output = shell(root(bridgeCommand()));
        for (String line : output.split("\n")) {
            if (!line.startsWith(NativeTaskBridge.PREFIX)) continue;
            JSONObject result = new JSONObject(line.substring(NativeTaskBridge.PREFIX.length()));
            assertTrue(output, result.getBoolean("ok"));
            return result;
        }
        throw new AssertionError("No native bridge reply: " + output);
    }

    private JSONObject task(JSONObject doc) throws Exception {
        JSONArray rows = doc.getJSONArray("tasks");
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (PKG.equals(row.getString("pkg")) && row.getInt("type") == 1) {
                assertNull("Duplicate fixture task", found);
                found = row;
            }
        }
        return found;
    }

    private Rect decoration(JSONObject doc, String kind) throws Exception {
        JSONArray rows = doc.getJSONArray("decorations");
        Rect found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!row.getString("name").endsWith("Smart S freeform " + kind + " " + id)) continue;
            assertNull("Duplicate control " + kind, found);
            found = new Rect(row.getInt("l"), row.getInt("t"), row.getInt("r"), row.getInt("b"));
        }
        return found;
    }

    private Rect frame(JSONObject task) throws Exception {
        if (task == null || !task.has("vl")) return new Rect();
        return new Rect(task.getInt("vl"), task.getInt("vt"), task.getInt("vr"), task.getInt("vb"));
    }

    private Rect bounds(JSONObject task) throws Exception {
        return new Rect(task.getInt("l"), task.getInt("t"), task.getInt("r"), task.getInt("b"));
    }

    private interface Condition { boolean ready(JSONObject doc) throws Exception; }
    private JSONObject await(String description, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 18000L;
        JSONObject last = null;
        do {
            last = state();
            if (condition.ready(last)) return last;
            SystemClock.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(description + ": " + last);
    }

    private boolean attached(JSONObject doc) throws Exception {
        JSONObject row = task(doc);
        if (row == null || row.getInt("id") != id || row.getInt("mode") != 5
                || !row.getBoolean("visible")) return false;
        Rect content = frame(row), title = decoration(doc, "title"), corner = decoration(doc, "corner");
        if (content.isEmpty() || title == null || corner == null) return false;
        Rect visible = new Rect(content);
        if (!visible.intersect(usable)) return false;
        return FreeformGeometry.near(title, FreeformGeometry.caption(visible, usable, dp(40)), dp(4))
                && FreeformGeometry.near(corner, FreeformGeometry.corner(visible, dp(30)), dp(4));
    }

    private Rect area() {
        android.hardware.display.DisplayManager dm = app.getSystemService(android.hardware.display.DisplayManager.class);
        Context wc = app.createDisplayContext(dm.getDisplay(Display.DEFAULT_DISPLAY))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        WindowManager wm = wc.getSystemService(WindowManager.class);
        android.view.WindowMetrics metrics = wm.getMaximumWindowMetrics();
        Rect result = new Rect(metrics.getBounds());
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        result.inset(insets.left, insets.top, insets.right, insets.bottom);
        return result;
    }

    private void motion(int action, int x, int y) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue("Actual touch injection failed", ui.injectInputEvent(event, true)); }
        finally { event.recycle(); }
    }

    private void tap(int x, int y) {
        motion(MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(60);
        motion(MotionEvent.ACTION_UP, x, y);
    }

    private void evidence(String filename, Rect colorArea, boolean checkColor) throws Exception {
        Bitmap bitmap = ui.takeScreenshot();
        assertNotNull("No actual display screenshot", bitmap);
        try {
            File dir = new File(app.getFilesDir(), "freeform207-evidence");
            assertTrue(dir.isDirectory() || dir.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(dir, filename))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            if (checkColor) {
                // Check rendered app pixels across the interior, not a reported windowing mode.
                int pass = 0, total = 0;
                for (int xf : new int[]{15, 50, 85}) for (int yf : new int[]{25, 50, 75}) {
                    int x = colorArea.left + colorArea.width() * xf / 100;
                    int y = colorArea.top + colorArea.height() * yf / 100;
                    if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) continue;
                    int pixel = bitmap.getPixel(x, y); total++;
                    if (Math.abs(Color.red(pixel) - 189) < 25
                            && Math.abs(Color.green(pixel) - 36) < 25
                            && Math.abs(Color.blue(pixel) - 214) < 25) pass++;
                }
                assertTrue("Actual app content is black/clipped/misaligned: " + pass + "/" + total,
                        total == 9 && pass >= 8);
            }
        } finally { bitmap.recycle(); }
    }

    private void settledEvidence(String filename, Rect bounds) throws Exception {
        // Task geometry can settle before the fullscreen transition finishes drawing.
        // Require two full-content frames and retain the last screenshot on timeout.
        long drawDeadline = SystemClock.elapsedRealtime() + 10000;
        int verifiedFrames = 0;
        while (verifiedFrames < 2) {
            try {
                evidence(filename, bounds, true);
                verifiedFrames++;
            } catch (AssertionError error) {
                if (error.getMessage() == null || !error.getMessage().startsWith("Actual app content")
                        || SystemClock.elapsedRealtime() >= drawDeadline) throw error;
                verifiedFrames = 0;
            }
            if (verifiedFrames < 2) SystemClock.sleep(120);
        }
    }

    @Test public void actualGestureKeepsAppAndControlsTogetherAndMaximizes() throws Exception {
        assertEquals("Root test transport is not available", "0", shell("su 0 id -u").trim());
        assertTrue("Overlay app-op not granted in isolated emulator", Settings.canDrawOverlays(app));
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        boolean previousRoot = prefs.getBoolean("root-mode", false);
        assertTrue(prefs.edit().putBoolean("root-mode", true).commit());
        FreeformTestTransport.install(new NativeTaskClient.Transport() {
            @Override public String read(String command) {
                try { return shell(root(command)); }
                catch (IOException e) { return ""; }
            }
            @Override public NativeTaskClient.Pipe open(String command) {
                ParcelFileDescriptor[] fds = ui.executeShellCommandRw(root(command));
                InputStream out = new ParcelFileDescriptor.AutoCloseInputStream(fds[0]);
                OutputStream in = new ParcelFileDescriptor.AutoCloseOutputStream(fds[1]);
                return new NativeTaskClient.Pipe() {
                    @Override public OutputStream input() { return in; }
                    @Override public InputStream output() { return out; }
                    @Override public boolean alive() { return true; }
                    @Override public void close() {
                        try { in.close(); } catch (IOException ignored) { }
                        try { out.close(); } catch (IOException ignored) { }
                    }
                };
            }
        });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            usable = area();
            assertFalse(usable.isEmpty());
            scenario.onActivity(activity -> assertTrue("Managed foreground service did not start",
                    FreeformOverlayService.prepare(activity)));
            shell("am force-stop " + PKG);
            String launch = shell("am start -W --windowingMode 5 -n " + COMPONENT);
            assertFalse(launch, launch.contains("Error"));
            JSONObject opened = await("fixture must actually be freeform", doc -> {
                JSONObject t = task(doc);
                return t != null && t.getInt("mode") == 5 && !frame(t).isEmpty();
            });
            id = task(opened).getInt("id");
            assertEquals("New centered geometry must be verified", FreeformOverlayService.OPEN_VERIFIED,
                    FreeformOverlayService.openManaged(app, ComponentName.unflattenFromString(COMPONENT)));
            JSONObject ready = await("centered app and attached decorations", this::attached);
            Rect initial = frame(task(ready));
            assertTrue("Initial popup must reserve caption space", FreeformGeometry.contentArea(usable, dp(40)).contains(initial));
            Rect expected = FreeformGeometry.center(FreeformGeometry.contentArea(usable, dp(40)));
            assertTrue("New popup should be centered", FreeformGeometry.near(initial, expected, dp(10)));
            settledEvidence("initial.png", initial);

            Rect title = decoration(ready, "title");
            int x = title.left + Math.min(dp(46), title.width()/4), y = title.centerY();
            int dx = Math.min(dp(34), usable.right - initial.right - dp(4));
            if (dx < dp(15)) dx = -Math.min(dp(34), initial.left - usable.left - dp(4));
            final int dragX = dx;
            assertTrue(Math.abs(dragX) >= dp(10));
            motion(MotionEvent.ACTION_DOWN, x, y);
            motion(MotionEvent.ACTION_MOVE, x + dragX, y);
            JSONObject moving;
            try {
                moving = await("app and both controls must move BEFORE ACTION_UP", doc ->
                        attached(doc) && Math.abs(frame(task(doc)).left - initial.left) >= Math.abs(dragX)/2);
                evidence("mid-drag.png", frame(task(moving)), true);
            } finally { motion(MotionEvent.ACTION_UP, x + dragX, y); }
            JSONObject moved = await("final aligned drag", doc ->
                    attached(doc) && Math.abs(frame(task(doc)).left - initial.left) >= Math.abs(dragX)/2);
            Rect preResize = frame(task(moved)), handle = decoration(moved, "corner");
            SystemClock.sleep(1300);
            int rx = handle.centerX(), ry = handle.centerY(), shrink = dp(35);
            motion(MotionEvent.ACTION_DOWN, rx, ry);
            motion(MotionEvent.ACTION_MOVE, rx - shrink, ry - shrink);
            try {
                JSONObject resized = await("app and corner must resize BEFORE ACTION_UP", doc ->
                        attached(doc) && frame(task(doc)).width() < preResize.width() - dp(15));
                evidence("mid-resize.png", frame(task(resized)), true);
            } finally { motion(MotionEvent.ACTION_UP, rx - shrink, ry - shrink); }
            JSONObject resized = await("final aligned resize", doc ->
                    attached(doc) && frame(task(doc)).width() < preResize.width() - dp(15));
            Rect saved = frame(task(resized));
            SystemClock.sleep(1300);
            title = decoration(resized, "title");
            title = decoration(await("current title at maximize tap", this::attached), "title");
            tap(title.right - dp(60), title.centerY());
            JSONObject full = await("normal fullscreen surface with no detached title or corner", doc -> {
                JSONObject t = task(doc);
                return t != null && t.getInt("id") == id && t.getInt("mode") == 1
                        && FreeformGeometry.fullscreen(frame(t), usable, dp(8))
                        && decoration(doc, "title") == null && decoration(doc, "corner") == null
                        && decoration(doc, "restore") != null;
            });
            settledEvidence("fullscreen.png", usable);
            Rect restore = decoration(full, "restore");
            tap(restore.centerX(), restore.centerY());
            JSONObject restored = await("same task restored with previous geometry", doc ->
                    attached(doc) && FreeformGeometry.near(frame(task(doc)), saved, dp(8)));
            title = decoration(restored, "title");
            SystemClock.sleep(900);
            title = decoration(await("current title at minimize tap", this::attached), "title");
            tap(title.right - dp(100), title.centerY());
            JSONObject minimized = await("real minimized bubble and no detached decorations", doc ->
                    task(doc) != null && task(doc).getInt("id") == id && task(doc).getInt("display")>0
                            && decoration(doc, "bubble") != null && decoration(doc, "title") == null
                            && decoration(doc, "corner") == null);
            playingWhileBubbled(id);
            Rect bubble = decoration(minimized, "bubble");
            int bx = bubble.centerX(), by = bubble.centerY(), mx = bx > usable.centerX() ? -dp(38) : dp(38);
            motion(MotionEvent.ACTION_DOWN, bx, by);
            motion(MotionEvent.ACTION_MOVE, bx + mx, by);
            motion(MotionEvent.ACTION_UP, bx + mx, by);
            JSONObject bubbleMoved = await("bubble really moves on the display", doc -> {
                Rect now = decoration(doc, "bubble");
                return now != null && Math.abs(now.centerX() - bx) >= dp(20);
            });
            evidence("bubble.png", usable, false);
            Rect movedBubble = decoration(bubbleMoved, "bubble");
            SystemClock.sleep(900);
            Rect action=longPressMenu(movedBubble);
            tap(action.centerX(),action.centerY());
            await("bubble restores the same popup, not a duplicate", doc ->
                    attached(doc) && decoration(doc, "bubble") == null
                            && FreeformGeometry.near(frame(task(doc)), saved, dp(8)));
            settledEvidence("restored.png", saved);

            // A real display rotation changes the coordinate space. Re-read usable bounds
            // from the rotated display and verify that the same task's title/corner follow it.
            assertTrue("Rotate the actual emulator display", ui.setRotation(UiAutomation.ROTATION_FREEZE_90));
            long rotationDeadline = SystemClock.elapsedRealtime() + 10000L;
            do {
                usable = area();
                if (usable.width() > usable.height()) break;
                SystemClock.sleep(150);
            } while (SystemClock.elapsedRealtime() < rotationDeadline);
            assertTrue("Display must actually rotate to landscape", usable.width() > usable.height());
            JSONObject rotated = await("rotation reattaches controls to same task in usable display", doc ->
                    attached(doc) && usable.contains(frame(task(doc)))
                            && usable.contains(decoration(doc, "title"))
                            && usable.contains(decoration(doc, "corner")));
            settledEvidence("rotated.png", frame(task(rotated)));
            shell("input keyevent KEYCODE_HOME");
            SystemClock.sleep(1500);
            JSONObject home = await("same floating window and controls above Home", this::attached);
            settledEvidence("above-home.png", frame(task(home)));
            String settings = shell("am start -W --windowingMode 1 -n com.tbzmike.shortcutfixture.icebox/com.tbzmike.shortcutfixture.FixtureFreeformActivity");
            assertTrue(settings, settings.contains("Status: ok"));
            SystemClock.sleep(1500);
            JSONObject overApp = await("same floating window and controls above the second fullscreen app", this::attached);
            settledEvidence("above-fullscreen.png", frame(task(overApp)));
            Rect closeTitle = decoration(overApp, "title");
            shell("logcat -c");
            tap(closeTitle.right - dp(140), closeTitle.centerY());
            await("Back delivered to exact floating fixture above the second fullscreen app", doc ->
                    attached(doc) && shell("logcat -d -s FreeformFixture:I").contains("Back received in task " + id));
            closeTitle = decoration(await("controls still attached after Back", this::attached), "title");
            tap(closeTitle.right - dp(20), closeTitle.centerY());
            await("explicit X closes the task", doc -> task(doc) == null);
            for (String pkg : new String[]{PKG,"com.tbzmike.shortcutfixture.icebox"}) {
                ComponentName comp=new ComponentName(pkg,"com.tbzmike.shortcutfixture.FixtureFreeformActivity");
                shell("am start -W --windowingMode 5 -n " + comp.flattenToString());
                assertEquals(FreeformOverlayService.OPEN_VERIFIED,FreeformOverlayService.openManaged(app,comp));
                NativeTaskClient.Task target=null;
                for (NativeTaskClient.Task item:new NativeTaskClient(app).list()) if (item.pkg.equals(pkg)) target=item;
                assertNotNull(target);
                assertTrue(FreeformSession.operate(app,target.id,target.component,"minimize",null,usable));
            }
            dropFolder("windows");
            await("both minimized windows closed", doc -> task(doc)==null && !doc.getJSONArray("tasks").toString().contains("com.tbzmike.shortcutfixture.icebox"));
            checkFullscreenSwitching();
        } finally {
            ui.setRotation(UiAutomation.ROTATION_FREEZE_0);
            ui.setRotation(UiAutomation.ROTATION_UNFREEZE);
            try { shell("am force-stop " + PKG); } catch (IOException ignored) { }
            // Stop the actual service while test transport is still installed.
            app.stopService(new android.content.Intent(app, FreeformOverlayService.class));
            instrumentation.waitForIdleSync();
            FreeformTestTransport.install(null);
            prefs.edit().putBoolean("root-mode", previousRoot).commit();
        }
    }
}
