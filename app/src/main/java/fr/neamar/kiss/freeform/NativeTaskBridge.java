package fr.neamar.kiss.freeform;

import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Process;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Short-lived, root-authorized app_process entry point. Signatures match AOSP 35/36
 * IActivityTaskManager, TaskInfo and WindowContainerTransaction. No package force-stop,
 * global settings, raw binder transaction numbers or simulated activity restarts.
 */
@RequiresApi(35)
public final class NativeTaskBridge {
    public static final String PREFIX = "SMART_S_FREEFORM:";
    private NativeTaskBridge() {}

    public static void main(String[] args) {
        // The helper stays alive only for the active gesture. No organizer is registered and no
        // SystemUI task leash is stolen. EOF closes the helper when the launcher dies.
        if (args.length == 2 && "priority".equals(args[0])) {
            BubbleProcessPriority.runRoot(Integer.parseInt(args[1]));
        } else if (args.length == 5 && "liveInput".equals(args[0])) {
            runLiveInput(args);
        } else if (args.length == 4 && "gesture".equals(args[0])) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("end".equals(line)) break;
                    String[] parts = line.split(" ");
                    if (parts.length != 5 || !("live".equals(parts[0]) || "resize".equals(parts[0]))) break;
                    run(new String[]{parts[0], args[1], args[2], args[3],
                            parts[1], parts[2], parts[3], parts[4]});
                }
            } catch (Exception ignored) { }
        } else run(args);
        System.exit(0);
    }

    // A bounded, per-touch stream scoped to one exact launcher-owned virtual display.
    // Preserve the complete pointer data, including multi-touch, without polling or restarting the app.
    private static void runLiveInput(String[] args) {
        android.view.MotionEvent last=null;
        Object input=null;
        Method inject=null;
        int uid=-1;
        try {
            if (Process.myUid()!=0) throw new SecurityException("Root required");
            Class<?> runtime=Class.forName("dalvik.system.VMRuntime");
            Object vm=runtime.getDeclaredMethod("getRuntime").invoke(null);
            runtime.getDeclaredMethod("setHiddenApiExemptions",String[].class).invoke(vm,(Object)new String[]{
                    "Landroid/os/ServiceManager;", "Landroid/app/IActivityTaskManager", "Landroid/app/TaskInfo;",
                    "Landroid/app/ActivityManager$RunningTaskInfo;", "Landroid/content/res/Configuration;->windowConfiguration",
                    "Landroid/app/WindowConfiguration;", "Landroid/hardware/display/", "Landroid/view/DisplayInfo;",
                    "Landroid/hardware/input/", "Landroid/view/InputEvent;", "Landroid/view/MotionEvent;"});
            int user=Integer.parseInt(args[1]), id=Integer.parseInt(args[2]), display=Integer.parseInt(args[4]);
            if (display<=0 || !liveDisplay(display,id)) throw new SecurityException("Invalid target display");
            Class<?> sm=Class.forName("android.os.ServiceManager");
            Class<?> atm=Class.forName("android.app.IActivityTaskManager");
            Object binder=sm.getMethod("getService",String.class).invoke(null,"activity_task");
            Object service=Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            for (Object item:tasks(atm,service)) {
                JSONObject row=describe(item);
                if (row==null || row.getInt("id")!=id || row.getInt("user")!=user || row.getInt("display")!=display
                        || !args[3].equals(row.getString("component")) || row.getInt("type")!=1) continue;
                android.content.pm.ActivityInfo info=(android.content.pm.ActivityInfo)item.getClass().getField("topActivityInfo").get(item);
                if(info!=null && info.applicationInfo!=null)uid=info.applicationInfo.uid;
                break;
            }
            if(uid<0)throw new SecurityException("Task identity changed");
            Object ib=sm.getMethod("getService",String.class).invoke(null,"input");
            input=Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,ib);
            inject=Class.forName("android.hardware.input.IInputManager").getMethod("injectInputEventToTarget",android.view.InputEvent.class,int.class,int.class);
            Method setDisplay=android.view.InputEvent.class.getMethod("setDisplayId",int.class);
            try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))){
                String line;
                while((line=reader.readLine())!=null && !line.equals("end")){
                    if(line.length()>65536)break;
                    byte[] bytes=android.util.Base64.decode(line,android.util.Base64.NO_WRAP);
                    android.os.Parcel parcel=android.os.Parcel.obtain();
                    android.view.MotionEvent event=null;
                    try{
                        parcel.unmarshall(bytes,0,bytes.length);parcel.setDataPosition(0);
                        event=android.view.MotionEvent.CREATOR.createFromParcel(parcel);
                        int action=event.getActionMasked();
                        if((last==null && action!=android.view.MotionEvent.ACTION_DOWN) || event.getPointerCount()>10)break;
                        if(!liveDisplay(display,id))break;
                        setDisplay.invoke(event,display);
                        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                        if(!(Boolean)inject.invoke(input,event,0,uid))break;
                        if(last!=null){last.recycle();last=null;}
                        if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL)break;
                        last=android.view.MotionEvent.obtainNoHistory(event);
                    }finally{if(event!=null)event.recycle();parcel.recycle();}
                }
            }
        } catch(Exception failure){android.util.Log.w("SmartMini","Live input unavailable",failure);}
        finally{
            if(last!=null){
                try{last.setAction(android.view.MotionEvent.ACTION_CANCEL);if(inject!=null && input!=null)inject.invoke(input,last,0,uid);}
                catch(Exception ignored){}finally{last.recycle();}
            }
        }
    }

    private static void run(String[] args) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root required");
            if (args.length < 2) throw new IllegalArgumentException("Missing action or profile");
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Object vm = runtime.getDeclaredMethod("getRuntime").invoke(null);
            runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(vm,
                    (Object) new String[]{"Landroid/os/ServiceManager;", "Landroid/app/IActivityTaskManager",
                            "Landroid/app/ActivityOptions;", "Landroid/app/TaskInfo;", "Landroid/app/ActivityManager$RunningTaskInfo;",
                            "Landroid/content/res/Configuration;->windowConfiguration",
                            "Landroid/hardware/display/", "Landroid/view/DisplayInfo;", "Landroid/view/IWindowManager", "Landroid/view/InputEvent;", "Landroid/view/KeyEvent;", "Landroid/view/MotionEvent;", "Landroid/hardware/input/", "Landroid/app/WindowConfiguration;", "Landroid/window/"});
            Class<?> atm = Class.forName("android.app.IActivityTaskManager");
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "activity_task");
            Object service = Class.forName("android.app.IActivityTaskManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            int user = Integer.parseInt(args[1]);
            if (user < 0) throw new IllegalArgumentException("Invalid user");
            String action = args[0];
            List<?> tasks = tasks(atm, service);
            if ("identities".equals(action)) {
                JSONArray rows = new JSONArray();
                for (Object item : tasks) {
                    JSONObject info = describe(item);
                    if (info != null && info.getInt("user") == user) rows.put(info);
                }
                emit(new JSONObject().put("ok", true).put("tasks", rows));
                return;
            }
            if ("list".equals(action) || "frames".equals(action)) {
                JSONArray rows = new JSONArray();
                List<?> windows = visibleWindows();
                List<WindowFrameLookup.Window> layout = null;
                for (Object item : tasks) {
                    JSONObject info = describe(item);
                    if (info != null && info.getInt("user") == user) {
                        if (info.getInt("display") != 0) { rows.put(info); continue; }
                        addFrame(info, item, tasks, windows);
                        if (!info.has("vl") && info.getBoolean("visible")
                                && info.getInt("type") == 1) {
                            if (layout == null) layout = windowLayouts();
                            addLayoutFrame(info, layout, windows);
                        }
                        rows.put(info);
                    }
                }
                if ("frames".equals(action)) {
                    emit(new JSONObject().put("ok", true).put("tasks", rows));
                    return;
                }
                JSONArray decorations = decorationFrames(windows);
                if (decorations.length() == 0) {
                    if (layout == null) layout = windowLayouts();
                    decorations = layoutDecorations(layout);
                }
                emit(new JSONObject().put("ok", true).put("tasks", rows)
                        .put("decorations", decorations));
                return;
            }
            if (args.length != 8) throw new IllegalArgumentException("Malformed task operation");
            int id = Integer.parseInt(args[2]);
            String component = args[3];
            Object selected = null;
            JSONObject info = null;
            for (Object item : tasks) {
                JSONObject row = describe(item);
                if (row != null && row.getInt("id") == id && row.getInt("user") == user
                        && component.equals(row.getString("component"))
                        && row.getInt("type") == 1) {
                    selected = item;
                    info = row;
                    break;
                }
            }
            if (selected == null || info == null) throw new IllegalStateException("Task identity changed");
            int mode = info.getInt("mode");
            if (mode != 1 && mode != 5) throw new IllegalStateException("Task is not fullscreen/freeform");
            int display = info.getInt("display");
            if (action.equals("parkFullscreen") || action.equals("parkPopup") || action.equals("parkLive")) {
                int destination=Integer.parseInt(args[4]);
                if (display!=0 || destination<=0 || !liveDisplay(destination,id)
                        || (action.equals("parkFullscreen") && (mode!=1 || info.getBoolean("focused"))))
                    throw new IllegalStateException("Task is not eligible for bubbling");
                if ("parkLive".equals(action)) {
                    // The virtual display must host a fullscreen task, not a native
                    // freeform task nested inside the TextureView. Set its own policy
                    // only (never the phone display), then collect the exact existing
                    // task in a single destination-aware Shell transition.
                    setLiveDisplayFullscreen(destination,id);
                    Object tx=newFullscreenTransaction(selected,id,destination);
                    Object organizer=atm.getMethod("getWindowOrganizerController").invoke(service);
                    Class<?> wt=Class.forName("android.window.WindowContainerTransaction");
                    Class.forName("android.window.IWindowOrganizerController").getMethod("startNewTransition",int.class,wt)
                            .invoke(organizer,3,tx);
                    JSONObject row=awaitFullscreenTask(atm,service,id,component,user,destination,2000);
                    if(row==null){
                        // Some ROMs ignore a launch display option on an already
                        // running task. Move only if it is still on the phone, then
                        // explicitly normalize the mode of the same task on its new display.
                        Object current=exactTask(atm,service,id,component,user);
                        if(current!=null){
                            JSONObject state=describe(current);
                            if(state.getInt("display")==0){
                                atm.getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(service,id,destination);
                                for(int retry=0;retry<25;retry++){
                                    current=exactTask(atm,service,id,component,user);
                                    if(current!=null&&describe(current).getInt("display")==destination)break;
                                    android.os.SystemClock.sleep(40);
                                }
                            }
                            if(current!=null&&describe(current).getInt("display")==destination){
                                Object normalize=newFullscreenTransaction(current,id,destination);
                                Class.forName("android.window.IWindowOrganizerController").getMethod("startNewTransition",int.class,wt)
                                        .invoke(organizer,6,normalize);
                                row=awaitFullscreenTask(atm,service,id,component,user,destination,2000);
                            }
                        }
                    }
                    if(row==null)android.util.Log.e("SmartMini","Fullscreen virtual task not verified: "+id+" display "+destination);
                    emit(new JSONObject().put("ok",row!=null));
                    return;
                }
                atm.getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(service,id,destination);
                for (int attempt = 0; attempt < 10; attempt++) {
                    for (Object item : tasks(atm, service)) {
                        JSONObject moved = describe(item);
                        if (moved != null && moved.getInt("id") == id && moved.getInt("user") == user
                                && component.equals(moved.getString("component")) && moved.getInt("display") == destination) {
                            emit(new JSONObject().put("ok", true));
                            return;
                        }
                    }
                    android.os.SystemClock.sleep(32);
                }
                emit(new JSONObject().put("ok", false));
                return;
            }
            if ("liveBack".equals(action)) {
                if (display<=0 || !liveDisplay(display,id)) throw new SecurityException("Invalid live display");
                Object inputBinder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"input");
                Object input=Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,inputBinder);
                Method inject=Class.forName("android.hardware.input.IInputManager").getMethod("injectInputEvent",android.view.InputEvent.class,int.class);
                Method setDisplay=android.view.InputEvent.class.getMethod("setDisplayId",int.class);
                long time=android.os.SystemClock.uptimeMillis();
                for (int keyAction : new int[]{android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP}) {
                    android.view.KeyEvent key=new android.view.KeyEvent(time,time,keyAction,android.view.KeyEvent.KEYCODE_BACK,0);
                    setDisplay.invoke(key,display);
                    if (!(Boolean)inject.invoke(input,key,2)) throw new IllegalStateException("Back delivery failed");
                }
                emit(new JSONObject().put("ok",true));
                return;
            }
            if (display!=0 && !action.equals("close") && !action.equals("closeFullscreen")) {
                if (!(action.startsWith("restore") || action.equals("switchFullscreen") || action.equals("maximize")))
                    throw new IllegalStateException("Unsupported live task operation");
                // Collect the existing task BEFORE changing displays. A raw ATM
                // move followed by WCT changes left its SurfaceFlinger parent on
                // the virtual display even though TaskInfo reported display zero.
                boolean popup = action.startsWith("restore");
                Rect wanted = new Rect(Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                        Integer.parseInt(args[6]), Integer.parseInt(args[7]));
                if (popup && wanted.isEmpty()) throw new IllegalArgumentException("Missing restore bounds");
                Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
                Class<?> txClass = Class.forName("android.window.WindowContainerTransaction");
                Object tx = txClass.getConstructor().newInstance();
                Object token = selected.getClass().getField("token").get(selected);
                if (token == null) throw new IllegalStateException("Missing task token");
                txClass.getMethod("setHidden", tokenClass, boolean.class).invoke(tx, token, false);
                txClass.getMethod("setWindowingMode", tokenClass, int.class).invoke(tx, token, popup ? 5 : 1);
                txClass.getMethod("setBounds", tokenClass, Rect.class).invoke(tx, token, popup ? wanted : new Rect());
                txClass.getMethod("setAlwaysOnTop", tokenClass, boolean.class).invoke(tx, token, action.endsWith("Pinned"));
                android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                options.setLaunchDisplayId(0);
                android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                        .invoke(options, popup ? 5 : 1);
                if (popup) options.setLaunchBounds(wanted);
                // WCT startTask is an existing-task launch, not a new activity.
                // It is performed inside the same Shell transition as these changes.
                txClass.getMethod("startTask", int.class, android.os.Bundle.class)
                        .invoke(tx, id, options.toBundle());
                Object organizer = atm.getMethod("getWindowOrganizerController").invoke(service);
                Class.forName("android.window.IWindowOrganizerController")
                        .getMethod("startNewTransition", int.class, txClass).invoke(organizer, 3, tx);
                if (popup) {
                    emit(observe(atm, service, id, component, user, wanted, false));
                } else {
                    boolean restored = false;
                    for (int attempt = 0; attempt < 25 && !restored; attempt++) {
                        for (Object fresh : tasks(atm, service)) {
                            JSONObject row = describe(fresh);
                            if (row != null && row.getInt("id") == id && row.getInt("user") == user
                                    && component.equals(row.getString("component")) && row.getInt("display") == 0
                                    && row.getInt("mode") == 1 && row.getBoolean("visible")
                                    && row.getBoolean("focused")) {
                                restored = true;
                                break;
                            }
                        }
                        if (!restored) android.os.SystemClock.sleep(40);
                    }
                    emit(new JSONObject().put("ok", restored));
                }
                return;
            }
            if ("switchFullscreen".equals(action) || "closeFullscreen".equals(action)) {
                if (display == 0 && (mode != 1 || ("closeFullscreen".equals(action) && info.getBoolean("focused")))) throw new IllegalStateException("Task no longer eligible");
                action = "switchFullscreen".equals(action) ? "focus" : "close";
            }
            if ("back".equals(action)) {
                if (mode != 5 || !info.getBoolean("visible")) throw new IllegalStateException("Window unavailable");
                if (!info.getBoolean("focused")) atm.getMethod("setFocusedTask", int.class).invoke(service, id);
                android.content.pm.ActivityInfo target = null;
                for (int attempt = 0; attempt < 12 && target == null; attempt++) {
                    for (Object item : tasks(atm, service)) {
                        JSONObject row = describe(item);
                        if (row != null && row.getInt("id") == id && row.getInt("user") == user
                                && component.equals(row.getString("component")) && row.getBoolean("focused")
                                && row.getBoolean("visible")) {
                            target = (android.content.pm.ActivityInfo) item.getClass().getField("topActivityInfo").get(item);
                            break;
                        }
                    }
                    if (target == null) android.os.SystemClock.sleep(16);
                }
                if (target == null || target.applicationInfo == null) throw new IllegalStateException("No focused target");
                Object inputBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                        .invoke(null, "input");
                Object input = Class.forName("android.hardware.input.IInputManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, inputBinder);
                Method inject = Class.forName("android.hardware.input.IInputManager")
                        .getMethod("injectInputEventToTarget", android.view.InputEvent.class, int.class, int.class);
                long down = android.os.SystemClock.uptimeMillis();
                for (int keyAction : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
                    android.view.KeyEvent event = new android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(),
                            keyAction, android.view.KeyEvent.KEYCODE_BACK, 0, 0, -1, 0,
                            android.view.KeyEvent.FLAG_FROM_SYSTEM, android.view.InputDevice.SOURCE_KEYBOARD);
                    if (!(Boolean) inject.invoke(input, event, 2, target.applicationInfo.uid))
                        throw new IllegalStateException("Back delivery failed");
                }
                emit(new JSONObject().put("ok", true));
                return;
            }
            if ("close".equals(action)) {
                boolean removed = (Boolean) atm.getMethod("removeTask", int.class).invoke(service, id);
                emit(new JSONObject().put("ok", removed));
                return;
            }
            Rect bounds = new Rect(Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                    Integer.parseInt(args[6]), Integer.parseInt(args[7]));
            Class<?> tokenType = Class.forName("android.window.WindowContainerToken");
            Class<?> transactionType = Class.forName("android.window.WindowContainerTransaction");
            Object token = selected.getClass().getField("token").get(selected);
            if (token == null) throw new IllegalStateException("Task has no container token");
            Object transaction = transactionType.getConstructor().newInstance();
            Method hidden = transactionType.getMethod("setHidden", tokenType, boolean.class);
            Method reorder = transactionType.getMethod("reorder", tokenType, boolean.class);
            Method windowMode = transactionType.getMethod("setWindowingMode", tokenType, int.class);
            Method setBounds = transactionType.getMethod("setBounds", tokenType, Rect.class);
            boolean keepAbove = action.endsWith("Pinned");
            if (keepAbove) action = action.substring(0, action.length() - "Pinned".length());
            if (keepAbove || "unpin".equals(action) || "minimize".equals(action) || "maximize".equals(action)) {
                transactionType.getMethod("setAlwaysOnTop", tokenType, boolean.class)
                        .invoke(transaction, token, keepAbove);
            }
            boolean focus = false;
            switch (action) {
                case "unpin":
                    break;
                case "live":
                    if (mode != 5 || bounds.isEmpty()) throw new IllegalArgumentException("Not a resizable popup");
                    // Transition-aware resize path updates actual task surfaces, not just the
                    // requested override configuration. SystemUI retains its organizer.
                    atm.getMethod("resizeTask", int.class, Rect.class, int.class)
                            .invoke(service, id, bounds, 1 /* RESIZE_MODE_USER */);
                    emit(observe(atm, service, id, component, user, bounds, true));
                    return;
                case "minimize":
                    hidden.invoke(transaction, token, true);
                    reorder.invoke(transaction, token, false);
                    break;
                case "restore":
                    if (bounds.isEmpty()) throw new IllegalArgumentException("Missing restore bounds");
                    hidden.invoke(transaction, token, false);
                    windowMode.invoke(transaction, token, 5);
                    setBounds.invoke(transaction, token, bounds);
                    reorder.invoke(transaction, token, true);
                    focus = true;
                    break;
                case "maximize":
                    // Fullscreen is a real existing-task launch, not a change of
                    // logical bounds alone. The launch transition lets Shell update
                    // the previous freeform surface crop and client configuration.
                    transaction = newFullscreenTransaction(selected,id,0);
                    hidden.invoke(transaction, token, false);
                    // On the phone's ordinary fullscreen display, inherit its mode
                    // as AOSP DesktopTasksController does. Explicit mode 1 can retain
                    // a freeform organizer override. Clear the requested bounds.
                    windowMode.invoke(transaction, token, 1);
                    setBounds.invoke(transaction, token, new Rect());
                    reorder.invoke(transaction, token, true);
                    focus = true;
                    break;
                case "resize":
                    if (mode != 5 || bounds.isEmpty()) throw new IllegalArgumentException("Not a resizable popup");
                    // Match the already working live-resize operation. A mode-change
                    // transition for ordinary resizing can leave the organizer's crop stale.
                    atm.getMethod("resizeTask", int.class, Rect.class, int.class)
                            .invoke(service, id, bounds, 3 /* RESIZE_MODE_USER_FORCED */);
                    if (keepAbove) organizerApply(atm, service, transactionType, transaction);
                    emit(observe(atm, service, id, component, user, bounds, false));
                    return;
                case "focus":
                    reorder.invoke(transaction, token, true);
                    focus = true;
                    break;
                default: throw new IllegalArgumentException("Unknown task operation");
            }
            Object organizer = atm.getMethod("getWindowOrganizerController").invoke(service);
            Class<?> organizerType = Class.forName("android.window.IWindowOrganizerController");
            // Shell transitions update crop/position of organized task surfaces as well as
            // configuration. AOSP falls back to legacy apply when no transition player exists.
            if ("minimize".equals(action) || "restore".equals(action) || "maximize".equals(action) || "resize".equals(action)) {
                int transition = "minimize".equals(action) ? 4 /* TRANSIT_TO_BACK */
                        : "maximize".equals(action) || ("restore".equals(action) && display == 0 && !info.getBoolean("visible")) ? 3 /* TRANSIT_TO_FRONT */
                        : 6 /* TRANSIT_CHANGE */;
                organizerType.getMethod("startNewTransition", int.class, transactionType)
                        .invoke(organizer, transition, transaction);
            } else {
                organizerType.getMethod("applyTransaction", transactionType).invoke(organizer, transaction);
            }
            if (focus) {
                // The transaction itself succeeded; focus is advisory on ROMs with desktop policy.
                try { if (display != 0 || (!"restore".equals(action) && !"maximize".equals(action) && !"resize".equals(action)))
                    atm.getMethod("setFocusedTask", int.class).invoke(service, id); }
                catch (ReflectiveOperationException ignored) { }
            }
            if ("restore".equals(action)) {
                emit(observe(atm, service, id, component, user, bounds, false));
            } else if ("resize".equals(action)) {
                emit(observe(atm, service, id, component, user, bounds, false));
            } else emit(new JSONObject().put("ok", true));
        } catch (Throwable failure) {
            try { emit(new JSONObject().put("ok", false).put("error", failure.toString())); }
            catch (Exception ignored) { System.exit(1); }
        }
    }

    private static void organizerApply(Class<?> atm, Object service, Class<?> txClass, Object tx) throws Exception {
        Object organizer = atm.getMethod("getWindowOrganizerController").invoke(service);
        Class.forName("android.window.IWindowOrganizerController").getMethod("applyTransaction", txClass)
                .invoke(organizer, tx);
    }

    private static Object exactTask(Class<?> atm,Object service,int id,String component,int user) throws Exception {
        for(Object item:tasks(atm,service)){
            JSONObject row=describe(item);
            if(row!=null&&row.getInt("id")==id&&row.getInt("user")==user&&row.getInt("type")==1
                    &&component.equals(row.getString("component")))return item;
        }
        return null;
    }

    private static Object newFullscreenTransaction(Object task,int id,int destination) throws Exception {
        Class<?> tt=Class.forName("android.window.WindowContainerToken");
        Class<?> wt=Class.forName("android.window.WindowContainerTransaction");
        Object token=task.getClass().getField("token").get(task);
        Object tx=wt.getConstructor().newInstance();
        wt.getMethod("setWindowingMode",tt,int.class).invoke(tx,token,1);
        // Remove any explicit freeform mode applied to child activities.
        wt.getMethod("setActivityWindowingMode",tt,int.class).invoke(tx,token,0);
        wt.getMethod("setBounds",tt,Rect.class).invoke(tx,token,new Rect());
        wt.getMethod("setAlwaysOnTop",tt,boolean.class).invoke(tx,token,false);
        wt.getMethod("setHidden",tt,boolean.class).invoke(tx,token,false);
        wt.getMethod("reorder",tt,boolean.class).invoke(tx,token,true);
        android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic();
        options.setLaunchDisplayId(destination);
        android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode",int.class).invoke(options,1);
        wt.getMethod("startTask",int.class,android.os.Bundle.class).invoke(tx,id,options.toBundle());
        return tx;
    }

    private static void setLiveDisplayFullscreen(int display,int id) throws Exception {
        if(!liveDisplay(display,id))throw new SecurityException("Not a launcher-owned display");
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
        Object wm=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        Class<?> type=Class.forName("android.view.IWindowManager");
        try{
            int mode=(Integer)type.getMethod("getWindowingMode",int.class).invoke(wm,display);
            if(mode!=1)type.getMethod("setWindowingMode",int.class,int.class).invoke(wm,1,display);
        }catch(ReflectiveOperationException unsupported){
            android.util.Log.w("SmartMini","Display mode policy unavailable; verifying exact task mode instead",unsupported);
        }
    }

    private static JSONObject awaitFullscreenTask(Class<?> atm,Object service,int id,String component,int user,int display,long timeout) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+timeout;
        Object di=displayInfo(display);
        int width=di.getClass().getField("logicalWidth").getInt(di),height=di.getClass().getField("logicalHeight").getInt(di);
        do{
            Object current=exactTask(atm,service,id,component,user);
            if(current!=null){
                JSONObject row=describe(current);
                if(row.getInt("display")==display&&row.getInt("mode")==1&&row.getBoolean("visible")
                        &&Math.abs(row.getInt("l"))<=8&&Math.abs(row.getInt("t"))<=8
                        &&Math.abs(row.getInt("r")-width)<=8&&Math.abs(row.getInt("b")-height)<=8)return row;
            }
            android.os.SystemClock.sleep(50);
        }while(android.os.SystemClock.elapsedRealtime()<end);
        return null;
    }

    private static List<?> tasks(Class<?> atm, Object service) throws Exception {
        return (List<?>) atm.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class)
                .invoke(service, 200, false, false, -1);
    }

    private static Object displayInfo(int display) throws Exception {
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"display");
        Object dm=Class.forName("android.hardware.display.IDisplayManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        return Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayInfo",int.class).invoke(dm,display);
    }

    static boolean liveDisplay(int display,int task) throws Exception {
        if (display <= 0) return false;
        Object info=displayInfo(display);
        if(info==null)return false;
        String owner=(String)info.getClass().getField("ownerPackageName").get(info);
        return ("com.tbzmike.smartslauncher".equals(owner)||"com.tbzmike.smartslauncher.debug".equals(owner))
                && ("Smart S live task "+task).equals(info.getClass().getField("name").get(info));
    }

    private static JSONObject describe(Object task) throws Exception {
        Class<?> cls = task.getClass();
        ComponentName component = (ComponentName) cls.getField("baseActivity").get(task);
        if (component == null) return null;
        int display = cls.getField("displayId").getInt(task);
        if (display != 0 && !liveDisplay(display,cls.getField("taskId").getInt(task))) return null;
        Configuration configuration = (Configuration) cls.getField("configuration").get(task);
        Object wc = Configuration.class.getField("windowConfiguration").get(configuration);
        Rect bounds = (Rect) wc.getClass().getMethod("getBounds").invoke(wc);
        int mode = (Integer) cls.getMethod("getWindowingMode").invoke(task);
        int type = (Integer) cls.getMethod("getActivityType").invoke(task);
        return new JSONObject().put("display",display).put("id", cls.getField("taskId").getInt(task))
                .put("user", cls.getField("userId").getInt(task))
                .put("component", component.flattenToString()).put("pkg", component.getPackageName())
                .put("mode", mode).put("type", type)
                .put("visible", cls.getField("isVisible").getBoolean(task))
                .put("focused", cls.getField("isFocused").getBoolean(task))
                .put("l", bounds.left).put("t", bounds.top).put("r", bounds.right).put("b", bounds.bottom);
    }

    private static JSONObject observe(Class<?> atm, Object service, int id, String component,
                                      int user, Rect expected, boolean live) throws Exception {
        JSONObject last = null;
        long deadline = android.os.SystemClock.elapsedRealtime() + 1400;
        for (int attempt = 0; attempt < 12 && android.os.SystemClock.elapsedRealtime() < deadline; attempt++) {
            if (attempt != 0) android.os.SystemClock.sleep(live ? 16 : 40);
            List<?> rows = tasks(atm, service);
            for (Object item : rows) {
                JSONObject row = describe(item);
                if (row == null || row.getInt("id") != id || row.getInt("user") != user
                        || !component.equals(row.getString("component"))) continue;
                if (row.getInt("display") == 0 && row.getBoolean("visible") && row.getInt("mode") == 5) {
                    List<?> windows = visibleWindows();
                    addFrame(row, item, rows, windows);
                    if (!row.has("vl")) addLayoutFrame(row, windowLayouts(), windows);
                }
                last = row;
                if (row.getInt("display") == 0 && row.getBoolean("visible")
                        && row.getInt("mode") == 5 && row.has("vl") && row.getInt("l") == expected.left
                        && row.getInt("t") == expected.top && row.getInt("r") == expected.right
                        && row.getInt("b") == expected.bottom
                        && Math.abs(row.getInt("vl") - expected.left) <= 12
                        && Math.abs(row.getInt("vt") - expected.top) <= 12
                        && Math.abs(row.getInt("vr") - expected.right) <= 12
                        && Math.abs(row.getInt("vb") - expected.bottom) <= 12) {
                    return new JSONObject().put("ok", true).put("task", row);
                }
            }
        }
        return new JSONObject().put("ok", last != null).put("task", last);
    }

    /** SurfaceFlinger input-window frames in logical display coordinates, independent of TaskInfo. */
    private static List<?> visibleWindows() {
        try {
            Class<?> type = Class.forName("android.window.WindowInfosListenerForTest");
            Object listener = type.getConstructor().newInstance();
            java.util.concurrent.atomic.AtomicReference<List<?>> rows = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.function.Consumer<List<?>> callback = rows::set;
            Method add = type.getMethod("addWindowInfosListener", java.util.function.Consumer.class);
            Method remove = type.getMethod("removeWindowInfosListener", java.util.function.Consumer.class);
            try {
                add.invoke(listener, callback);
                return rows.get();
            } finally { remove.invoke(listener, callback); }
        } catch (Throwable unavailable) { return null; }
    }

    /** Independent WindowManager fallback, using exact task/user identity and visible main windows.
     * No reliance on an optional test API or on the application title matching its component. */
    private static List<WindowFrameLookup.Window> windowLayouts() {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "-t", "1", "window", "-a", "windows")
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.InputStream input = process.getInputStream();
            byte[] buffer = new byte[4096];
            long deadline = android.os.SystemClock.elapsedRealtime() + 1200;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                while (input.available() > 0) {
                    int n = input.read(buffer);
                    if (n < 0) break;
                    bytes.write(buffer, 0, n);
                    if (bytes.size() > 2_000_000) return java.util.Collections.emptyList();
                }
                if (!process.isAlive()) {
                    while (input.available() > 0) {
                        int n = input.read(buffer);
                        if (n < 0) break;
                        bytes.write(buffer, 0, n);
                    }
                    if (process.exitValue() != 0) return java.util.Collections.emptyList();
                    return WindowFrameLookup.parse(bytes.toString("UTF-8"));
                }
                android.os.SystemClock.sleep(5);
            }
        } catch (Exception ignored) { }
        finally { if (process != null) process.destroy(); }
        return java.util.Collections.emptyList();
    }

    private static void addLayoutFrame(JSONObject info, List<WindowFrameLookup.Window> layout,
                                       List<?> compositor) throws Exception {
        WindowFrameLookup.Window window = WindowFrameLookup.find(layout, info.getInt("id"), info.getInt("user"));
        if (window == null) return;
        // When compositor data exists but the app chose a custom title, match the WindowState
        // identity hash from the dump rather than guessing a package or activity.
        if (compositor != null) for (Object row : compositor) {
            Class<?> type = row.getClass();
            String name = (String) type.getField("name").get(row);
            if (name == null || !name.startsWith(window.hash + " ")
                    || !type.getField("isVisible").getBoolean(row)
                    || type.getField("displayId").getInt(row) != 0) continue;
            Rect rect = (Rect) type.getField("bounds").get(row);
            if (rect != null && !rect.isEmpty()) {
                putFrame(info, rect, "compositor-identity");
                return;
            }
        }
        int[] f = window.frame;
        putFrame(info, new Rect(f[0], f[1], f[2], f[3]), "window-manager");
    }

    private static void putFrame(JSONObject info, Rect frame, String source) throws Exception {
        info.put("vl", frame.left).put("vt", frame.top).put("vr", frame.right).put("vb", frame.bottom)
                .put("frameSource", source);
    }

    private static JSONArray layoutDecorations(List<WindowFrameLookup.Window> windows) throws Exception {
        JSONArray result = new JSONArray();
        for (WindowFrameLookup.Window window : windows) {
            if (!window.valid() || !window.name.matches("Smart S freeform (title|corner|restore|bubble) [0-9]+")) continue;
            int[] f = window.frame;
            result.put(new JSONObject().put("name", window.name)
                    .put("l", f[0]).put("t", f[1]).put("r", f[2]).put("b", f[3]));
        }
        return result;
    }

    /** Only our explicitly named controls; used to check real overlay alignment in CI. */
    private static JSONArray decorationFrames(List<?> windows) throws Exception {
        JSONArray result = new JSONArray();
        if (windows == null) return result;
        for (Object window : windows) {
            Class<?> type = window.getClass();
            String name = (String) type.getField("name").get(window);
            if (name == null || !name.matches(".*Smart S freeform (title|corner|restore|bubble) [0-9]+$")
                    || type.getField("displayId").getInt(window) != 0
                    || !type.getField("isVisible").getBoolean(window)) continue;
            Rect frame = (Rect) type.getField("bounds").get(window);
            if (frame != null && !frame.isEmpty()) result.put(new JSONObject().put("name", name)
                    .put("l", frame.left).put("t", frame.top).put("r", frame.right).put("b", frame.bottom));
        }
        return result;
    }

    private static boolean matchesWindow(String name, ComponentName component) {
        if (component == null || name == null) return false;
        for (String candidate : new String[]{component.flattenToString(), component.flattenToShortString()}) {
            if (name.equals(candidate) || name.endsWith(" " + candidate)
                    || name.endsWith(":" + candidate)) return true;
        }
        return false;
    }

    private static void addFrame(JSONObject info, Object task, List<?> tasks, List<?> windows) throws Exception {
        if (windows == null || info.getInt("display") != 0 || !info.getBoolean("visible")) return;
        ComponentName top = (ComponentName) task.getClass().getField("topActivity").get(task);
        if (top == null) return;
        // A matching visible component must belong to only one task. Never attach a caption
        // to a guessed window when two tasks share an indistinguishable activity.
        int matches = 0;
        for (Object candidate : tasks) {
            if (candidate.getClass().getField("isVisible").getBoolean(candidate)
                    && top.equals(candidate.getClass().getField("topActivity").get(candidate))) matches++;
        }
        if (matches != 1) return;
        for (Object window : windows) {
            Class<?> type = window.getClass();
            if (type.getField("displayId").getInt(window) != 0
                    || !type.getField("isVisible").getBoolean(window)
                    || !matchesWindow((String) type.getField("name").get(window), top)) continue;
            Rect frame = (Rect) type.getField("bounds").get(window);
            if (frame != null && !frame.isEmpty()) {
                putFrame(info, frame, "compositor");
                return;
            }
        }
    }

    private static void emit(JSONObject result) {
        System.out.println(PREFIX + result);
        System.out.flush();
    }
}
