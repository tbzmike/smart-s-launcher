package fr.neamar.kiss.freeform;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.*;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One independently positioned folder, owned by the service that owns its tasks. */
@RequiresApi(35)
public final class BubbleFolder {
    private static final String PREFS = "switch-folder-layouts";
    interface Actions {
        void open(NativeTaskClient.Task task);
        void floating(NativeTaskClient.Task task);
        void close(List<NativeTaskClient.Task> tasks);
    }

    public static void choose(Activity a) {
        String[] labels = {"Fullscreen switcher ⇄", "Floating windows ▣"};
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        new AlertDialog.Builder(a).setTitle("Use expandable folders")
            .setMultiChoiceItems(labels, new boolean[]{p.getBoolean("fullscreen", true), p.getBoolean("windows", true)},
                    (dialog, which, checked) -> p.edit().putBoolean(which == 0 ? "fullscreen" : "windows", checked).apply())
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private final Context context;
    private final WindowManager wm;
    private final int type;
    private final String key;
    private final Actions actions;
    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final Rect area = new Rect();
    private final List<NativeTaskClient.Task> members = new ArrayList<>();
    private TextView handle, target;
    private ScrollView panel;
    private WindowManager.LayoutParams anchor;

    BubbleFolder(Context c, WindowManager wm, boolean windows, Actions actions, Runnable changed) {
        this.context = c;
        this.wm = wm;
        this.actions = actions;
        key = windows ? "windows" : "fullscreen";
        type = windows ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        listener = (p, k) -> { if (key.equals(k)) changed.run(); };
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private WindowManager.LayoutParams params(int w, int h, String name) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w,h,type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.setFitInsetsTypes(0);
        p.setTitle("Smart S " + key + " " + name);
        return p;
    }
    private GradientDrawable background(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), key.equals("windows") ? 0xffaccaff : 0xff81f4db);
        return bg;
    }
    private TextView label() {
        TextView v = new TextView(context);
        v.setGravity(Gravity.CENTER); v.setTextColor(Color.WHITE); return v;
    }
    boolean show(List<NativeTaskClient.Task> tasks, Rect bounds) {
        if (!prefs.getBoolean(key, true) || tasks.size() < 2) { clear(); return false; }
        boolean different = members.size() != tasks.size() || !area.equals(bounds);
        if (!different) for (int i=0; i<tasks.size(); i++) {
            NativeTaskClient.Task a=members.get(i), b=tasks.get(i);
            if (a.id != b.id || !a.component.equals(b.component)) { different=true; break; }
        }
        members.clear(); members.addAll(tasks); area.set(bounds);
        int size=dp(56);
        if (handle == null) {
            handle=label(); handle.setTextSize(17);
            handle.setBackground(background(key.equals("windows") ? 0xff29416b : 0xff094944));
            anchor=params(size,size,"folder");
            anchor.x=prefs.getInt(key+"X", key.equals("windows") ? area.right-size : area.left);
            anchor.y=prefs.getInt(key+"Y", area.top+dp(key.equals("windows") ? 140 : 64));
            anchor.x=clamp(anchor.x,area.left,area.right-size);
            anchor.y=clamp(anchor.y,area.top,area.bottom-size);
            handle.setOnClickListener(v -> { if(panel==null) expand(); else collapse(); });
            drag(handle,null);
            wm.addView(handle,anchor);
        } else if (different) {
            anchor.x=clamp(anchor.x,area.left,area.right-size);
            anchor.y=clamp(anchor.y,area.top,area.bottom-size);
            wm.updateViewLayout(handle,anchor);
        }
        handle.setText((key.equals("windows") ? "▣ " : "⇄ ") + tasks.size());
        handle.setContentDescription((key.equals("windows") ? "Floating windows" : "Fullscreen switcher") + ", " + tasks.size());
        if (different && panel!=null) { collapse(); expand(); }
        return true;
    }
    private void expand() {
        int cols=Math.min(members.size(),Math.max(1,Math.min(3,area.width()/dp(76))));
        int width=Math.min(area.width(),cols*dp(76)+dp(8));
        int height=Math.min(Math.max(dp(78),area.height()-dp(64)),dp(82)*((members.size()+cols-1)/cols)+dp(8));
        panel=new ScrollView(context);
        panel.setBackground(background(0xff202833));
        GridLayout grid=new GridLayout(context);
        grid.setPadding(dp(4),dp(4),dp(4),dp(4)); grid.setColumnCount(cols);
        panel.addView(grid);
        WindowManager.LayoutParams p=params(width,height,"folder contents");
        boolean right=anchor.x+anchor.width/2>area.centerX();
        p.x=clamp(right ? anchor.x-width : anchor.x+anchor.width,area.left,area.right-width);
        p.y=clamp(anchor.y,area.top,area.bottom-height);
        p.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        panel.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE) {
                if(!inside(handle,e)) collapse();
                return true;
            }
            return false;
        });
        for (NativeTaskClient.Task task:members) {
            LinearLayout item=new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            ImageView icon=new ImageView(context); String name=task.pkg;
            try {
                android.content.pm.ActivityInfo info=context.getPackageManager().getActivityInfo(ComponentName.unflattenFromString(task.component),0);
                icon.setImageDrawable(info.loadIcon(context.getPackageManager()));
                name=info.loadLabel(context.getPackageManager()).toString();
            } catch(android.content.pm.PackageManager.NameNotFoundException ignored) { icon.setImageDrawable(context.getPackageManager().getDefaultActivityIcon()); }
            item.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));
            TextView title=label(); title.setSingleLine(true); title.setTextSize(12); title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(name); item.addView(title,new LinearLayout.LayoutParams(-1,-2));
            item.setContentDescription(name);
            item.setOnClickListener(v->{ collapse(); actions.open(task); });
            drag(item,task); grid.addView(item,(width-dp(8))/cols,dp(82));
        }
        wm.addView(panel,p);
        panel.setPivotX(right ? width : 0); panel.setPivotY(clamp(anchor.y-p.y+anchor.height/2,0,height));
        panel.setScaleX(.82f); panel.setScaleY(.82f); panel.setAlpha(0f);
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(context.getResources().getInteger(android.R.integer.config_shortAnimTime)).start();
    }
    private boolean inside(View v,MotionEvent e) {
        if(v==null || !v.isAttachedToWindow()) return false;
        int[] pos=new int[2]; v.getLocationOnScreen(pos);
        return new Rect(pos[0],pos[1],pos[0]+v.getWidth(),pos[1]+v.getHeight()).contains((int)e.getRawX(),(int)e.getRawY());
    }
    private void drag(View v,NativeTaskClient.Task task) {
        v.setOnLongClickListener(item->{ item.getParent().requestDisallowInterceptTouchEvent(true); return false; });
        View.OnTouchListener touch = new View.OnTouchListener(){
            float x,y; int left,top; boolean moved;
            List<NativeTaskClient.Task> selected=Collections.emptyList();
            public boolean onTouch(View view,MotionEvent e){
                float dx=e.getRawX()-x,dy=e.getRawY()-y;
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        x=e.getRawX(); y=e.getRawY(); left=anchor.x; top=anchor.y; moved=false;
                        selected=task==null ? new ArrayList<>(members) : Collections.singletonList(task);
                        if (panel!=null && !panel.canScrollVertically(1) && !panel.canScrollVertically(-1))
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.hypot(dx,dy)>ViewConfiguration.get(context).getScaledTouchSlop()){
                            moved=true; view.cancelLongPress(); if(task==null) collapse(); showTarget();
                        }
                        if(moved && task==null){
                            anchor.x=clamp(left+Math.round(dx),area.left,area.right-anchor.width);
                            anchor.y=clamp(top+Math.round(dy),area.top,area.bottom-anchor.height);
                            wm.updateViewLayout(handle,anchor);
                        }
                        if(moved && task!=null){view.setTranslationX(dx); view.setTranslationY(dy);}
                        return moved;
                    case MotionEvent.ACTION_UP:
                        if(!moved) return false;
                        boolean close=inside(target,e);
                        view.setPressed(false); view.setTranslationX(0f); view.setTranslationY(0f); hideTarget();
                        if(close){
                            if(task==null){anchor.x=left;anchor.y=top;wm.updateViewLayout(handle,anchor);}
                            collapse(); actions.close(selected);
                        } else if(task==null) prefs.edit().putInt(key+"X",anchor.x).putInt(key+"Y",anchor.y).apply();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        hideTarget(); view.setTranslationX(0);view.setTranslationY(0);return false;
                    default:return false;
                }
            }
        };
        if(task==null)v.setOnTouchListener(touch);
        else BubbleMenu.install(v,type,()->{collapse();actions.floating(task);},touch);
    }
    private void showTarget(){
        if(target!=null)return;
        target=label();target.setText("×");target.setTextSize(32);target.setBackground(background(0xff842930));
        int size=dp(72);WindowManager.LayoutParams p=params(size,size,"folder close target");
        p.x=area.centerX()-size/2;p.y=area.bottom-size-dp(8);p.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.addView(target,p);
    }
    private void remove(View v){ if(v!=null && v.isAttachedToWindow())wm.removeViewImmediate(v); }
    private void hideTarget(){ remove(target);target=null; }
    private void collapse(){ remove(panel);panel=null; hideTarget(); }
    void clear(){ collapse();remove(handle);handle=null;members.clear(); }
    void destroy(){ clear();prefs.unregisterOnSharedPreferenceChangeListener(listener); }
}