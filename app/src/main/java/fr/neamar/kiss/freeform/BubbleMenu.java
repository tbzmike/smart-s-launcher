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
    private static final java.util.WeakHashMap<View,View> menus = new java.util.WeakHashMap<>();
    static boolean showingFor(View panel) {
        return panel != null && menus.containsKey(panel.getRootView());
    }
    private BubbleMenu() {}
    static void install(View anchor, int type, Runnable open, View.OnTouchListener drag) {
        final Runnable[] dismiss = {null};
        anchor.setOnLongClickListener(view -> {
            if (dismiss[0] != null) dismiss[0].run();
            show(view, type, open, dismiss);
            return true;
        });
        anchor.setOnTouchListener(new View.OnTouchListener() {
            boolean tracking, moved, held;
            float startX, startY;
            final int slop = ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop();
            void cancelDrag() {
                long now = android.os.SystemClock.uptimeMillis();
                MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                try { drag.onTouch(anchor, cancel); } finally { cancel.recycle(); }
                anchor.setPressed(false);
            }
            final Runnable hold = () -> {
                if (!tracking || moved || !anchor.isAttachedToWindow()) return;
                held = true;
                cancelDrag();
                anchor.performLongClick();
            };
            public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    view.removeCallbacks(hold);
                    if (dismiss[0] != null) dismiss[0].run();
                    tracking = true;
                    moved = held = false;
                    startX = event.getRawX();
                    startY = event.getRawY();
                    drag.onTouch(view, event);
                    view.setPressed(true);
                    view.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    return true;
                }
                if (!tracking) return true;
                if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                    view.removeCallbacks(hold);
                    tracking = false;
                    if (!held) cancelDrag();
                    return true;
                }
                if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_MOVE) return true;
                if (Math.hypot(event.getRawX() - startX, event.getRawY() - startY) > slop) {
                    moved = true;
                    view.removeCallbacks(hold);
                }
                if (action == MotionEvent.ACTION_UP) {
                    view.removeCallbacks(hold);
                    tracking = false;
                    view.setPressed(false);
                    if (!held && !moved) {
                        cancelDrag();
                        view.performClick();
                    } else if (!held) drag.onTouch(view, event);
                } else if (moved && !held) drag.onTouch(view, event);
                return true;
            }
        });
    }

    private static void show(View anchor,int type,Runnable action,Runnable[] activeDismiss) {
        WindowManager wm=anchor.getContext().getSystemService(WindowManager.class);
        if(wm==null || !anchor.isAttachedToWindow()) return;
        float d=anchor.getResources().getDisplayMetrics().density;
        Rect area=wm.getCurrentWindowMetrics().getBounds();
        int w=Math.min(area.width(),Math.round(210*d)), h=Math.round(50*d);
        int[] at=new int[2]; anchor.getLocationOnScreen(at);
        View owner = anchor.getRootView();
        int[] rootAt = new int[2];
        owner.getLocationOnScreen(rootAt);
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
        // Place the action outside the expanded grid so its other members stay tappable.
        if (owner != anchor) {
            int below = rootAt[1] + owner.getHeight();
            if (below + h <= area.bottom) lp.y = below;
            else if (rootAt[1] - h >= area.top) lp.y = rootAt[1] - h;
        }
        lp.setTitle("Smart S bubble menu");
        View.OnAttachStateChangeListener lifetime=new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) {
                if (menus.get(owner) == button) menus.remove(owner); activeDismiss[0] = null;
                anchor.removeOnAttachStateChangeListener(this);
                if (button.getParent() != null) wm.removeViewImmediate(button);
            }
        };
        Runnable dismiss=()->{if (menus.get(owner) == button) menus.remove(owner); activeDismiss[0] = null;anchor.removeOnAttachStateChangeListener(lifetime);
            if (button.getParent() != null) wm.removeViewImmediate(button);};
        button.setOnClickListener(v->{dismiss.run();action.run();});
        button.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){dismiss.run();return true;}
            return false;
        });
        try { wm.addView(button,lp);menus.put(owner,button);activeDismiss[0] = dismiss;anchor.addOnAttachStateChangeListener(lifetime); }
        catch(WindowManager.BadTokenException ignored) { }
    }
}