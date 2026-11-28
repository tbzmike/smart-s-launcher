package fr.neamar.kiss.freeform;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.RequiresApi;

/** One recognizer for standalone and folder-member bubbles. */
@RequiresApi(35)
final class BubbleMenu {
    private BubbleMenu() {}
    static void install(View anchor, int type, Runnable open, View.OnTouchListener ordinary) {
        anchor.setOnLongClickListener(v -> { show(v,type,open); return true; });
        anchor.setOnTouchListener(new View.OnTouchListener() {
            boolean held, tracking;
            float x,y;
            final int slop=ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop();
            final Runnable hold=()->{
                if (!tracking || !anchor.isAttachedToWindow()) return;
                held=true;
                long now=android.os.SystemClock.uptimeMillis();
                MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);
                ordinary.onTouch(anchor,cancel);
                anchor.onTouchEvent(cancel);
                cancel.recycle();
                anchor.performLongClick();
            };
            public boolean onTouch(View v, MotionEvent e) {
                int a=e.getActionMasked();
                if(a==MotionEvent.ACTION_DOWN) {
                    held=false; tracking=true; x=e.getRawX(); y=e.getRawY();
                    v.postDelayed(hold,ViewConfiguration.getLongPressTimeout());
                } else if(a==MotionEvent.ACTION_UP || a==MotionEvent.ACTION_CANCEL ||
                        Math.hypot(e.getRawX()-x,e.getRawY()-y)>slop) {
                    tracking=false; v.removeCallbacks(hold);
                }
                if(held) return true;
                boolean consumed=ordinary.onTouch(v,e);
                if(!consumed) v.onTouchEvent(e);
                // The timer above owns long presses; suppress View's duplicate timeout.
                if(a==MotionEvent.ACTION_DOWN) v.cancelLongPress();
                return true;
            }
        });
    }

    private static void show(View anchor,int type,Runnable action) {
        WindowManager wm=anchor.getContext().getSystemService(WindowManager.class);
        if(wm==null || !anchor.isAttachedToWindow()) return;
        float d=anchor.getResources().getDisplayMetrics().density;
        Rect area=wm.getCurrentWindowMetrics().getBounds();
        int w=Math.min(area.width(),Math.round(210*d)), h=Math.round(50*d);
        int[] at=new int[2]; anchor.getLocationOnScreen(at);
        TextView button=new TextView(anchor.getContext());
        button.setText("Open in floating window"); button.setTextSize(15);
        button.setGravity(Gravity.CENTER); button.setTextColor(0xffffffff);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(0xff253240); bg.setCornerRadius(12*d);
        button.setBackground(bg);
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(w,h,type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(0);
        lp.gravity=Gravity.TOP|Gravity.LEFT;
        lp.x=Math.max(area.left,Math.min(at[0],area.right-w));
        lp.y=at[1]+anchor.getHeight()+h<=area.bottom ? at[1]+anchor.getHeight() : Math.max(area.top,at[1]-h);
        lp.setTitle("Smart S bubble menu");
        View.OnAttachStateChangeListener lifetime=new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) {
                anchor.removeOnAttachStateChangeListener(this);
                if(button.isAttachedToWindow()) wm.removeViewImmediate(button);
            }
        };
        Runnable dismiss=()->{anchor.removeOnAttachStateChangeListener(lifetime);
            if(button.isAttachedToWindow()) wm.removeViewImmediate(button);};
        button.setOnClickListener(v->{dismiss.run();action.run();});
        button.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){dismiss.run();return true;}
            return false;
        });
        try { wm.addView(button,lp);anchor.addOnAttachStateChangeListener(lifetime); }
        catch(WindowManager.BadTokenException ignored) { }
    }
}