package com.tbzmike.shortcutfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Persistent, resizable test window. Existing shortcut fixtures intentionally finish immediately. */
public final class FixtureFreeformActivity extends Activity {
    private android.media.MediaPlayer player;
    private boolean resumed;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sample=new Runnable(){
        public void run(){
            if(!resumed)return;
            if(player!=null && player.isPlaying())android.util.Log.i("SmartBubbleMedia","tick "+getTaskId()+" "+getDisplay().getDisplayId()+" "+player.getCurrentPosition());
            handler.postDelayed(this,250);
        }
    };
    @Override protected void onResume(){super.onResume();resumed=true;if(player!=null)player.start();handler.post(sample);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(sample);if(player!=null && player.isPlaying())player.pause();super.onPause();}
    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            android.util.Log.i("FreeformFixture", "Back received in task " + getTaskId());
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setTitle("Freeform custom title " + getPackageName());
        TextView label = new TextView(this);
        label.setText("Native freeform fixture: " + getPackageName());
        label.setTextSize(20);
        label.setBackgroundColor(getPackageName().endsWith(".target") ? 0xffbd24d6 : 0xff11b8d5);
        label.setTextColor(android.graphics.Color.WHITE);
        label.setOnClickListener(v -> android.util.Log.i("SmartMiniFixture","tap "+getTaskId()+" "+getDisplay().getDisplayId()));
        android.widget.FrameLayout layout=new android.widget.FrameLayout(this);
        layout.addView(label,new android.widget.FrameLayout.LayoutParams(-1,-1));
        setContentView(layout);
        int clip=getResources().getIdentifier("bubble_playback","raw",getPackageName());
        if(clip!=0){
            android.view.SurfaceView video=new android.view.SurfaceView(this);
            layout.addView(video,new android.widget.FrameLayout.LayoutParams(8,8));
            video.getHolder().addCallback(new android.view.SurfaceHolder.Callback(){
                public void surfaceCreated(android.view.SurfaceHolder holder){
                    player=android.media.MediaPlayer.create(FixtureFreeformActivity.this,clip);
                    if(player!=null){player.setDisplay(holder);player.setLooping(true);player.setVolume(0.01f,0.01f);
                        if(resumed)player.start();}
                }
                public void surfaceChanged(android.view.SurfaceHolder holder,int format,int w,int h){}
                public void surfaceDestroyed(android.view.SurfaceHolder holder){if(player!=null){player.release();player=null;}}
            });
        }
    }
}
