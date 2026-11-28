package fr.neamar.kiss.freeform;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.RequiresApi;
import java.util.*;

/** A real display per bubbled task permits Android multi-resume without taking phone input focus.
 * The surface consumer discards buffers; it never maps, copies, records or exports pixels. */
@RequiresApi(35)
final class LiveBubbleDisplays {
    private static final Map<Integer, Lease> active=new HashMap<>();
    private static HandlerThread buffers;
    private static int retiring;
    private static final class Lease {
        final VirtualDisplay display;
        final ImageReader surface;
        final int width, height;
        android.view.Surface preview;
        String family;
        final String component;
        boolean parked = true;
        int missingReads;
        Lease(VirtualDisplay d,ImageReader s,String f,String c,int w,int h){display=d;surface=s;family=f;component=c;width=w;height=h;}
        void release(){try { display.release(); } finally {surface.close();}}
    }
    static synchronized boolean park(Context app,NativeTaskClient client,NativeTaskClient.Task t,String family) {
        Lease existing = active.get(t.id);
        if (existing != null) {
            if (!existing.component.equals(t.component)) {
                release(t.id);
            } else {
                int destination = existing.display.getDisplay().getDisplayId();
                if (t.display == destination) { existing.family=family; return true; }
                if (t.display != 0) return false;
                // Keep the same display container for this task's lifetime.
                // Destroying its old display after reparent can detach the task
                // leash on Android 16, even while WindowManager reports HAS_DRAWN.
                if (!client.moveToBubble(t,destination,family)) return false;
                existing.family=family;
                existing.parked=true;
                existing.missingReads=0;
                BubbleProcessPriority.hold(app,t);
                return true;
            }
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        android.view.Display phone = app.getSystemService(DisplayManager.class).getDisplay(0);
        if (phone == null) return false;
        // Match the phone's pixel size and density. Half-resolution displays
        // force the app through a second configuration and surface crop change.
        phone.getRealMetrics(metrics);
        int w = metrics.widthPixels, h = metrics.heightPixels;
        if (w <= 0 || h <= 0) return false;
        ImageReader surface=null; VirtualDisplay display=null;
        try {
            if(buffers==null){ buffers=new HandlerThread("Bubble surfaces");buffers.start(); }
            surface=ImageReader.newInstance(w,h,ImageFormat.PRIVATE,2,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            surface.setOnImageAvailableListener(r->{
                try(Image frame=r.acquireLatestImage()){ /* Release to keep the app's surface flowing. */ }
                catch(IllegalStateException closed){ /* Display was released. */ }
            },new Handler(buffers.getLooper()));
            VirtualDisplayConfig config=new VirtualDisplayConfig.Builder("Smart S live task "+t.id,w,h,metrics.densityDpi)
                    .setRequestedRefreshRate("live".equals(family) ? 60f : 15f).setSurface(surface.getSurface())
                    .setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                    .build();
            display=app.getSystemService(DisplayManager.class).createVirtualDisplay(config);
            if(display==null || !client.moveToBubble(t,display.getDisplay().getDisplayId(),family)) return false;
            active.put(t.id,new Lease(display,surface,family,t.component,w,h)); display=null;surface=null;
            BubbleProcessPriority.hold(app, t);
            return true;
        } catch(RuntimeException unavailable){return false;}
        finally {
            if(display!=null)display.release();
            if(surface!=null)surface.close();
            stopIdleThread();
        }
    }
    // Render only the selected task's own virtual display, never the phone's screen.
    static synchronized android.util.Size previewSize(NativeTaskClient.Task task) {
        Lease lease=active.get(task.id);
        if (lease==null || !lease.component.equals(task.component) || !lease.parked
                || task.display!=lease.display.getDisplay().getDisplayId()) return null;
        return new android.util.Size(lease.width,lease.height);
    }
    static synchronized boolean attachPreview(NativeTaskClient.Task task, android.view.Surface surface) {
        Lease lease=active.get(task.id);
        if (previewSize(task)==null || lease==null || !surface.isValid()) return false;
        try {
            lease.display.setSurface(surface);
            lease.preview=surface;
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }
    static synchronized void detachPreview(int id, android.view.Surface surface) {
        Lease lease=active.get(id);
        if (lease==null || lease.preview!=surface) return;
        try { lease.display.setSurface(lease.surface.getSurface()); }
        catch (RuntimeException unavailable) { android.util.Log.w("SmartMini", "Display output unavailable", unavailable); }
        finally { lease.preview=null; }
    }
    static synchronized Map<Integer,Integer> beforeRead(){
        Map<Integer,Integer> ids=new HashMap<>();
        for(Map.Entry<Integer,Lease> e:active.entrySet())ids.put(e.getKey(),e.getValue().display.getDisplay().getDisplayId());
        return ids;
    }
    static synchronized void reconcile(Map<Integer,Integer> before, List<NativeTaskClient.Task> tasks, boolean withFrames) {
        // Identity snapshots can identify a closed task, but cannot confirm restoration.
        // Keep the display container while the task exists; no destruction based on
        // logical frame visibility alone (it did not prove the surface was attached).
        Set<Integer> seen = new HashSet<>();
        for (NativeTaskClient.Task t : tasks) {
            if (!before.containsKey(t.id)) continue;
            seen.add(t.id);
            Lease lease = active.get(t.id);
            if (lease == null || lease.display.getDisplay().getDisplayId() != before.get(t.id)) continue;
            lease.missingReads = 0;
            if (withFrames && t.display == 0 && t.visible && !t.frame.isEmpty() && lease.parked) {
                lease.parked=false;
                BubbleProcessPriority.drop(t.id);
            }
        }
        // Retire only after complete frame reads no longer contain the task.
        if (!withFrames) return;
        for (Map.Entry<Integer,Integer> e : before.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            Lease lease = active.get(e.getKey());
            if (lease != null && lease.display.getDisplay().getDisplayId() == e.getValue()
                    && ++lease.missingReads >= 3) release(e.getKey());
        }
    }
    static synchronized void release(int id) {
        Lease lease = active.remove(id);
        if (lease != null) {
            BubbleProcessPriority.drop(id);
            // Shell may still animate the reparented surface on its former display.
            retiring++;
            new Handler(buffers.getLooper()).postDelayed(() -> {
                try { lease.release(); }
                finally {
                    synchronized (LiveBubbleDisplays.class) {
                        retiring--;
                        stopIdleThread();
                    }
                }
            }, 1800);
        }
        stopIdleThread();
    }
    static synchronized void releaseFamily(String family){
        for(int id:new ArrayList<>(active.keySet())) if(family.equals(active.get(id).family))release(id);
    }
    private static void stopIdleThread(){if(active.isEmpty() && retiring == 0 && buffers!=null){buffers.quitSafely();buffers=null;}}
    private LiveBubbleDisplays(){}
}