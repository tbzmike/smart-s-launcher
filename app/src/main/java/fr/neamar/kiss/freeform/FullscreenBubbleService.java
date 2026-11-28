package fr.neamar.kiss.freeform;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.RequiresApi;
import fr.neamar.kiss.KissApplication;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Event-driven fullscreen task switcher. It never changes an app's windowing mode. */
@RequiresApi(35)
public final class FullscreenBubbleService extends AccessibilityService {
    private static java.lang.ref.WeakReference<FullscreenBubbleService> connected = new java.lang.ref.WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, View> views = new LinkedHashMap<>();
    private final Map<Integer, NativeTaskClient.Task> live = new HashMap<>();
    private BubbleFolder folder;
    private WindowManager wm;
    private SharedPreferences saved;
    private String lastPackage = "";
    private boolean reading, dirty, stopped;
    private final Set<String> attempted = new HashSet<>();
    private TextView target;
    private final Runnable refresh = this::readTasks;

    public static boolean enabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list != null) for (String name : list.split(":"))
            if (new ComponentName(c, FullscreenBubbleService.class).equals(ComponentName.unflattenFromString(name))) return true;
        return false;
    }

    public static void toggle(Activity activity) {
        FullscreenBubbleService instance = connected.get();
        if (instance != null) {
            instance.saved.edit().clear().apply();
            instance.shutdown();
            instance.disableSelf();
            return;
        }
        if (!KissApplication.getApplication(activity).getRootHandler().isRootActivated()) {
            Toast.makeText(activity, "Enable Smart S root mode to use fullscreen app bubbles.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Select Fullscreen app bubbles in Accessibility to switch it on or off.", Toast.LENGTH_LONG).show();
        activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    @Override protected void onServiceConnected() {
        connected = new java.lang.ref.WeakReference<>(this);
        saved = getSharedPreferences("fullscreen-switcher-tasks", MODE_PRIVATE);
        wm = getSystemService(WindowManager.class);
        folder = new BubbleFolder(this,wm,false,new BubbleFolder.Actions() {
            public void open(NativeTaskClient.Task t) { act(t,false); }
            public void floating(NativeTaskClient.Task t) { floatingWindow(t); }
            public void close(List<NativeTaskClient.Task> selected) {
                for (NativeTaskClient.Task t : selected) act(t,true);
            }
        }, this::schedule);
        schedule();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (saved == null || stopped || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (pkg.equals(lastPackage)) return;
        lastPackage = pkg;
        schedule();
    }

    private void schedule() {
        if (stopped) return;
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 60);
    }

    private void readTasks() {
        if (reading) { dirty = true; return; }
        reading = true;
        worker.execute(() -> {
            List<NativeTaskClient.Task> tasks = new NativeTaskClient(this).list(false);
            Set<Integer> managed = FreeformSession.managedIds(this);
            main.post(() -> {
                reading = false;
                if (stopped) return;
                if (tasks != null && managed != null) render(tasks, managed);
                if (dirty) { dirty = false; schedule(); }
            });
        });
    }

    private void render(List<NativeTaskClient.Task> tasks, Set<Integer> managed) {
        live.clear();
        Set<String> tracked = new HashSet<>(saved.getStringSet("tracked", Collections.emptySet()));
        Set<String> valid = new HashSet<>();
        Set<Integer> wanted = new LinkedHashSet<>();
        boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
        for (NativeTaskClient.Task t : tasks) {
            if ((t.display==0 && t.mode != 1) || t.type != 1 || managed.contains(t.id) || t.pkg.equals(getPackageName())
                    || t.pkg.equals("com.android.systemui") || t.user != NativeTaskClient.USER) continue;
            String key = t.id + "|" + t.component;
            if (t.display==0 && t.focused && t.visible && !locked) {tracked.add(key);attempted.remove(key);}
            if (!tracked.contains(key)) continue;
            valid.add(key);
            live.put(t.id, t);
            if (!locked && (t.display!=0 || !(t.focused && t.visible))) {
                wanted.add(t.id);
                if(t.display==0 && attempted.add(key))worker.execute(()->{
                    new NativeTaskClient(this).park(t,"fullscreen");
                    main.post(this::schedule);
                });
            }
        }
        attempted.retainAll(valid);
        if (!valid.equals(saved.getStringSet("tracked", Collections.emptySet())))
            saved.edit().putStringSet("tracked", valid).apply();
        List<NativeTaskClient.Task> members = new ArrayList<>();
        for (int id : wanted) members.add(live.get(id));
        if (folder.show(members,area())) wanted.clear();
        for (int id : new ArrayList<>(views.keySet())) if (!wanted.contains(id)) remove(views.remove(id));
        for (int id : wanted) if (!views.containsKey(id)) addBubble(live.get(id));
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private Rect area() {
        android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
        Rect bounds = new Rect(metrics.getBounds());
        android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        bounds.inset(insets.left, insets.top, insets.right, insets.bottom);
        return bounds;
    }
    private WindowManager.LayoutParams params(int size, String title) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.setTitle(title);
        return p;
    }
    private GradientDrawable shape(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(2), 0xff81f4db);
        return g;
    }
    private static int clamp(int x, int min, int max) { return Math.max(min, Math.min(x, max)); }

    private void addBubble(NativeTaskClient.Task task) {
        Rect a = area();
        int size = dp(50);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(shape(0xff094944));
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getActivityIcon(ComponentName.unflattenFromString(task.component)));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
        }
        icon.setPadding(dp(7),dp(7),dp(7),dp(7));
        tile.addView(icon,new FrameLayout.LayoutParams(-1,-1));
        TextView badge = new TextView(this);
        badge.setText("⇄"); badge.setTextColor(Color.WHITE); badge.setTextSize(12); badge.setGravity(Gravity.CENTER);
        badge.setBackgroundColor(0xff00584f);
        tile.addView(badge,new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.RIGHT | Gravity.BOTTOM));
        tile.setContentDescription("Switch fullscreen: " + task.pkg);
        WindowManager.LayoutParams lp = params(size, "Smart S fullscreen switch " + task.id);
        String key = "position:" + task.component;
        int rows = Math.max(1, (a.height()-dp(60))/(size+dp(4)));
        int slot = 0;
        for (; slot <= views.size(); slot++) {
            lp.x = clamp(a.left + dp(6) + (slot / rows)*(size+dp(4)),a.left,a.right-size);
            lp.y = clamp(a.top + dp(60) + (slot % rows)*(size+dp(4)),a.top,a.bottom-size);
            boolean occupied = false;
            for (View existing : views.values()) {
                WindowManager.LayoutParams other = (WindowManager.LayoutParams) existing.getLayoutParams();
                if (Math.abs(other.x-lp.x)<size && Math.abs(other.y-lp.y)<size) occupied = true;
            }
            if (!occupied) break;
        }
        lp.x = clamp(saved.getInt(key+"x",lp.x),a.left,a.right-size);
        lp.y = clamp(saved.getInt(key+"y",lp.y),a.top,a.bottom-size);
        tile.setOnClickListener(v -> act(task,false));
        BubbleMenu.install(tile,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,()->floatingWindow(task),new View.OnTouchListener() {
            float x,y; int left,top; boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent event) {
                float dx=event.getRawX()-x, dy=event.getRawY()-y;
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x=event.getRawX(); y=event.getRawY(); left=lp.x; top=lp.y; dragging=false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && Math.hypot(dx,dy)>ViewConfiguration.get(v.getContext()).getScaledTouchSlop()) {
                            dragging=true; showTarget();
                        }
                        if (dragging) {
                            Rect now=area();
                            lp.x=clamp(left+Math.round(dx),now.left,now.right-size);
                            lp.y=clamp(top+Math.round(dy),now.top,now.bottom-size);
                            wm.updateViewLayout(v,lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean close=false;
                        if (dragging && target!=null) {
                            int[] loc=new int[2]; target.getLocationOnScreen(loc);
                            close=new Rect(loc[0],loc[1],loc[0]+target.getWidth(),loc[1]+target.getHeight())
                                    .contains((int)event.getRawX(),(int)event.getRawY());
                        }
                        hideTarget();
                        if (close) act(task,true);
                        else if (!dragging) v.performClick();
                        else saved.edit().putInt(key+"x",lp.x).putInt(key+"y",lp.y).apply();
                        return true;
                    case MotionEvent.ACTION_CANCEL: hideTarget(); return true;
                    default: return false;
                }
            }
        });
        wm.addView(tile,lp); views.put(task.id,tile);
    }

    private void showTarget() {
        if (target!=null) return;
        target=new TextView(this); target.setGravity(Gravity.CENTER); target.setText("×");
        target.setTextSize(30); target.setTextColor(Color.WHITE); target.setBackground(shape(0xff80242a));
        target.setContentDescription("Close fullscreen app");
        int size=dp(72); Rect a=area();
        WindowManager.LayoutParams p=params(size,"Smart S fullscreen close target");
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        p.x=a.centerX()-size/2; p.y=a.bottom-size-dp(8); wm.addView(target,p);
    }
    private void floatingWindow(NativeTaskClient.Task task) {
        worker.execute(()->{
            boolean ok=FreeformOverlayService.floatExact(this,task);
            main.post(()->{
                if(stopped)return;
                if(!ok)Toast.makeText(this,"Could not open this task in a floating window",Toast.LENGTH_SHORT).show();
                schedule();
            });
        });
    }
    private void act(NativeTaskClient.Task t, boolean close) {
        worker.execute(() -> {
            NativeTaskClient client=new NativeTaskClient(this);
            Set<Integer> managed=FreeformSession.managedIds(this);
            if (!close && managed != null) {
                List<NativeTaskClient.Task> current = client.list(false);
                if (current != null) for (NativeTaskClient.Task foreground : current) {
                    if (foreground.display == 0 && foreground.focused && foreground.visible &&
                            foreground.mode == 1 && foreground.type == 1 && foreground.user == NativeTaskClient.USER &&
                            foreground.id != t.id && !managed.contains(foreground.id) &&
                            !foreground.pkg.equals(getPackageName()) && !foreground.pkg.equals("com.android.systemui")) {
                        String key = foreground.id + "|" + foreground.component;
                        main.post(() -> {
                            if (stopped) return;
                            Set<String> tracked = new HashSet<>(saved.getStringSet("tracked", Collections.emptySet()));
                            tracked.add(key);
                            saved.edit().putStringSet("tracked", tracked).apply();
                        });
                    }
                }
            }
            boolean ok=managed!=null && !managed.contains(t.id) && client.action(close?"closeFullscreen":"switchFullscreen",t,null);
            main.post(() -> {
                if (stopped) return;
                if (!ok) Toast.makeText(this,"Fullscreen task is no longer available",Toast.LENGTH_SHORT).show();
                schedule();
            });
        });
    }
    private void remove(View v) { if (v!=null && v.isAttachedToWindow()) wm.removeViewImmediate(v); }
    private void hideTarget() { remove(target); target=null; }
    private void shutdown() {
        if (folder != null) folder.destroy();
        LiveBubbleDisplays.releaseFamily("fullscreen");
        stopped=true; main.removeCallbacks(refresh); hideTarget();
        for (View v:views.values()) remove(v);
        views.clear(); worker.shutdownNow();
    }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { shutdown(); if (connected.get()==this) connected.clear(); super.onDestroy(); }
}