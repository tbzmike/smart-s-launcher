package fr.neamar.kiss.freeform;

import android.content.Context;
import android.graphics.Matrix;
import android.os.Parcel;
import android.util.Base64;
import android.util.Size;
import android.view.MotionEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** One root stream per touch gesture, with coalesced move events and no idle polling. */
final class LiveWindowInput implements AutoCloseable {
    private final NativeTaskClient client;
    private final NativeTaskClient.Task task;
    private final Size size;
    private final ExecutorService worker=new ThreadPoolExecutor(0,1,5,TimeUnit.SECONDS,new LinkedBlockingQueue<>());
    private final ArrayDeque<Packet> queue=new ArrayDeque<>();
    private NativeTaskClient.Pipe pipe;
    private boolean scheduled, closed, touching;
    private static final class Packet{
        final int action;final String data;
        Packet(int action,String data){this.action=action;this.data=data;}
    }
    LiveWindowInput(Context c,NativeTaskClient.Task t,Size s){client=new NativeTaskClient(c);task=t;size=s;}
    synchronized void offer(MotionEvent source,int w,int h){
        if(closed||w<=0||h<=0)return;
        int action=source.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN)touching=true;
        if(!touching)return;
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)touching=false;
        MotionEvent copy=MotionEvent.obtainNoHistory(source);
        Parcel p=Parcel.obtain();
        try{
            Matrix m=new Matrix();m.setScale((float)size.getWidth()/w,(float)size.getHeight()/h);
            copy.transform(m);copy.writeToParcel(p,0);
            Packet packet=new Packet(action,Base64.encodeToString(p.marshall(),Base64.NO_WRAP));
            if(action==MotionEvent.ACTION_MOVE && !queue.isEmpty()&&queue.peekLast().action==MotionEvent.ACTION_MOVE)queue.removeLast();
            if(queue.size()>=64){queue.clear();queue.add(new Packet(-1,""));touching=false;enqueue();return;}
            queue.add(packet);enqueue();
        }finally{p.recycle();copy.recycle();}
    }
    synchronized void cancel(){
        if(closed)return;
        touching=false;queue.clear();queue.add(new Packet(-1,""));enqueue();
    }
    private synchronized void enqueue(){
        if(scheduled)return;scheduled=true;worker.execute(this::drain);
    }
    private void drain(){
        while(true){
            Packet p;
            synchronized(this){p=queue.poll();if(p==null){scheduled=false;return;}}
            try{
                if(p.action==MotionEvent.ACTION_DOWN){end();pipe=client.liveInputPipe(task);}
                if(p.action<0){end();continue;}
                if(pipe==null)continue;
                pipe.input().write((p.data+"\n").getBytes(StandardCharsets.UTF_8));pipe.input().flush();
                if(p.action==MotionEvent.ACTION_UP||p.action==MotionEvent.ACTION_CANCEL)end();
            }catch(Exception unavailable){end();}
        }
    }
    private void end(){
        if(pipe==null)return;
        try{pipe.input().write("end\n".getBytes(StandardCharsets.UTF_8));pipe.input().flush();pipe.input().close();}
        catch(Exception ignored){}
        // Closing stdin lets the helper consume the final UP/CANCEL. Do not destroy it
        // before it consumes the event. The process exits on EOF.
        try{pipe.output().close();}catch(Exception ignored){}
        pipe=null;
    }
    @Override public synchronized void close(){
        if(closed)return;cancel();closed=true;worker.shutdown();
    }
}
