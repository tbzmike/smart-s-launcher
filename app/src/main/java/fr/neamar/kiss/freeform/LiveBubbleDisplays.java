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
    private static final class Lease {
        final VirtualDisplay display;
        final ImageReader surface;
        final String family;
        Lease(VirtualDisplay d,ImageReader s,String f){display=d;surface=s;family=f;}
        void release(){try { display.release(); } finally {surface.close();}}
    }
    static synchronized boolean park(Context app,NativeTaskClient client,NativeTaskClient.Task t,String family) {
        if(active.containsKey(t.id)) return t.display!=0;
        android.util.DisplayMetrics metrics=app.getResources().getDisplayMetrics();
        int w=Math.max(160,metrics.widthPixels/2),h=Math.max(240,metrics.heightPixels/2);
        ImageReader surface=null; VirtualDisplay display=null;
        try {
            if(buffers==null){ buffers=new HandlerThread("Bubble surfaces");buffers.start(); }
            surface=ImageReader.newInstance(w,h,ImageFormat.PRIVATE,2,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            surface.setOnImageAvailableListener(r->{
                try(Image frame=r.acquireLatestImage()){ /* Release to keep the app's surface flowing. */ }
                catch(IllegalStateException closed){ /* Display was released. */ }
            },new Handler(buffers.getLooper()));
            VirtualDisplayConfig config=new VirtualDisplayConfig.Builder("Smart S live task "+t.id,w,h,Math.max(72,metrics.densityDpi/2))
                    .setRequestedRefreshRate(15f).setSurface(surface.getSurface())
                    .setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                    .build();
            display=app.getSystemService(DisplayManager.class).createVirtualDisplay(config);
            if(display==null || !client.moveToBubble(t,display.getDisplay().getDisplayId(),family)) return false;
            active.put(t.id,new Lease(display,surface,family)); display=null;surface=null;
            return true;
        } catch(RuntimeException unavailable){return false;}
        finally {
            if(display!=null)display.release();
            if(surface!=null)surface.close();
            stopIdleThread();
        }
    }
    static synchronized Map<Integer,Integer> beforeRead(){
        Map<Integer,Integer> ids=new HashMap<>();
        for(Map.Entry<Integer,Lease> e:active.entrySet())ids.put(e.getKey(),e.getValue().display.getDisplay().getDisplayId());
        return ids;
    }
    static synchronized void reconcile(Map<Integer,Integer> before,List<NativeTaskClient.Task> tasks){
        for(NativeTaskClient.Task t:tasks) if(t.display!=0 && before.getOrDefault(t.id,-1)==t.display)before.remove(t.id);
        for(Map.Entry<Integer,Integer> e:before.entrySet()){
            Lease l=active.get(e.getKey());
            if(l!=null && l.display.getDisplay().getDisplayId()==e.getValue())release(e.getKey());
        }
    }
    static synchronized void release(int id){Lease l=active.remove(id);if(l!=null)l.release();stopIdleThread();}
    static synchronized void releaseFamily(String family){
        for(int id:new ArrayList<>(active.keySet())) if(family.equals(active.get(id).family))release(id);
    }
    private static void stopIdleThread(){if(active.isEmpty() && buffers!=null){buffers.quitSafely();buffers=null;}}
    private LiveBubbleDisplays(){}
}