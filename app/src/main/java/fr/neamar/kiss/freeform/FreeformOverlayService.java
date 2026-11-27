package fr.neamar.kiss.freeform;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;

/**
 * Explicitly started only for managed freeform sessions. Controls are small, separate overlays;
 * the application content remains the real Android task. No screen capture or accessibility
 * service. Reconciliation is paused when the screen is off/locked and ends when no tasks remain.
 */
@RequiresApi(35)
public final class FreeformOverlayService extends Service {
    public static final int OPEN_FAILED = 0, OPEN_VERIFIED = 1, OPEN_UNVERIFIED = 2, OPEN_AMBIGUOUS = -2;
    private static final String CHANNEL = "smart-native-freeform";
    private static final int NOTIFICATION_ID = 20615;
    private static final String STOP = "fr.neamar.kiss.freeform.RESTORE_AND_STOP";
    private static final AtomicInteger PENDING_OPEN = new AtomicInteger();
    private static volatile java.lang.ref.WeakReference<FreeformOverlayService> active =
            new java.lang.ref.WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> overlays = new ArrayList<>();
    private final Runnable poll = this::refresh;
    private Context windowContext;
    private WindowManager wm;
    private boolean destroyed, busy, stopping, receiverRegistered, screenReady;
    private int dragging;
    private long lastOpen;
    private String signature = "";
    private int queryFailures;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            screenReady = isScreenReady();
            if (!screenReady) { main.removeCallbacks(poll); removeOverlays(); }
            else requestRefresh();
        }
    };

    /** This is an ordinary user-granted Android overlay permission, not silently granted by root. */
    public static void askPermission(Context context, Runnable basicLaunch) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.freeform_overlay_title)
                .setMessage(R.string.freeform_overlay_explanation)
                .setPositiveButton(R.string.freeform_allow_controls, (dialog, which) -> {
                    try {
                        context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.getPackageName())));
                        Toast.makeText(context, R.string.freeform_permission_retry, Toast.LENGTH_LONG).show();
                    } catch (RuntimeException unavailable) {
                        Toast.makeText(context, R.string.freeform_control_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(R.string.freeform_basic_launch, (dialog, which) -> basicLaunch.run())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Called on the visible launcher before handing control to another app. */
    public static boolean prepare(Context context) {
        if (!Settings.canDrawOverlays(context)) return false;
        PENDING_OPEN.incrementAndGet();
        try {
            ContextCompat.startForegroundService(context, new Intent(context, FreeformOverlayService.class));
            return true;
        } catch (RuntimeException failure) {
            PENDING_OPEN.decrementAndGet();
            return false;
        }
    }

    /** Called by the existing app-launch worker; no repeated start on verification failure. */
    public static int openManaged(Context context, ComponentName component) {
        try {
            FreeformSession.OpenResult result = FreeformSession.open(context, component, displayArea(context), true);
            switch (result) {
                case VERIFIED: return OPEN_VERIFIED;
                case REQUESTED_UNVERIFIED: return OPEN_UNVERIFIED;
                case AMBIGUOUS: return OPEN_AMBIGUOUS;
                default: return OPEN_FAILED;
            }
        } catch (RuntimeException failure) {
            return OPEN_FAILED;
        } finally {
            PENDING_OPEN.decrementAndGet();
            FreeformOverlayService service = active.get();
            if (service != null) service.main.post(() -> {
                service.lastOpen = SystemClock.elapsedRealtime();
                service.requestRefresh();
            });
        }
    }

    /** Permission-denied basic mode still deduplicates via the same task-ID bridge. */
    public static int openBasic(Context context, ComponentName component) {
        try {
            FreeformSession.OpenResult result = FreeformSession.open(context, component, displayArea(context), false);
            switch (result) {
                case VERIFIED: return OPEN_UNVERIFIED; // No overlay controls were requested.
                case REQUESTED_UNVERIFIED: return OPEN_UNVERIFIED;
                case AMBIGUOUS: return OPEN_AMBIGUOUS;
                default: return OPEN_FAILED;
            }
        } catch (RuntimeException failure) { return OPEN_FAILED; }
    }

    /** Recover a same-boot journal after process death. Nothing starts on a clean install/boot. */
    public static void resumeIfNeeded(Context context) {
        FreeformOverlayService service = active.get();
        if (service != null) {
            service.queryFailures = 0;
            service.requestRefresh();
            return;
        }
        if (!FreeformSession.hasJournal(context)
                || !KissApplication.getApplication(context).getRootHandler().isRootActivated()) return;
        try { ContextCompat.startForegroundService(context, new Intent(context, FreeformOverlayService.class)); }
        catch (RuntimeException ignored) { /* The journal is retained for the next explicit launch. */ }
    }

    private static Context overlayContext(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("No default display");
        return context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
    }

    private static Rect displayArea(Context context) {
        WindowManager manager = overlayContext(context).getSystemService(WindowManager.class);
        return displayAreaFor(manager);
    }

    private static Rect displayAreaFor(@Nullable WindowManager manager) {
        if (manager == null) return new Rect();
        WindowMetrics metrics = manager.getMaximumWindowMetrics();
        Rect area = new Rect(metrics.getBounds());
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        area.inset(insets.left, insets.top, insets.right, insets.bottom);
        return area;
    }

    @Override public void onCreate() {
        super.onCreate();
        active = new java.lang.ref.WeakReference<>(this);
        lastOpen = SystemClock.elapsedRealtime();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.freeform_overlay_title), NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, FreeformOverlayService.class).setAction(STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2061, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2062, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(getString(R.string.freeform_overlay_title))
                .setContentText(getString(R.string.freeform_overlay_notification))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.freeform_restore_all), stopIntent).build())
                .build();
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        try {
            windowContext = overlayContext(this);
            wm = windowContext.getSystemService(WindowManager.class);
        } catch (RuntimeException unavailable) {
            // Keep recovery available through the notification even if overlay setup fails.
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        screenReady = isScreenReady();
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        lastOpen = SystemClock.elapsedRealtime();
        if (intent != null && STOP.equals(intent.getAction())) stopAndRestore();
        else requestRefresh();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        signature = "";
        if (dragging == 0) requestRefresh();
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        removeOverlays();
        if (receiverRegistered) unregisterReceiver(screen);
        worker.shutdown();
        if (active.get() == this) active.clear();
        super.onDestroy();
    }

    private boolean isScreenReady() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private void requestRefresh() {
        main.removeCallbacks(poll);
        if (!destroyed && !stopping) main.post(poll);
    }

    private void later(long millis) {
        main.removeCallbacks(poll);
        if (!destroyed && !stopping && screenReady) main.postDelayed(poll, millis);
    }

    private void refresh() {
        if (destroyed || stopping) return;
        screenReady = isScreenReady();
        if (!screenReady) { removeOverlays(); return; }
        if (PENDING_OPEN.get() > 0 || busy || dragging > 0) { later(650); return; }
        if (!KissApplication.getApplication(this).getRootHandler().isRootActivated()) {
            removeOverlays();
            return;
        }
        if (!Settings.canDrawOverlays(this) || wm == null) {
            stopAndRestore();
            return;
        }
        if (!FreeformSession.hasJournal(this)) {
            if (SystemClock.elapsedRealtime() - lastOpen > 6000) { stopSelf(); return; }
            later(1000); return;
        }
        busy = true;
        worker.execute(() -> {
            FreeformSession.Snapshot state = FreeformSession.snapshot(this);
            main.post(() -> {
                if (destroyed) return;
                busy = false;
                if (state == null) {
                    // No stale control may operate after a failed identity query.
                    removeOverlays();
                    if (++queryFailures < 2) later(6000);
                    return;
                }
                queryFailures = 0;
                if (state.entries.isEmpty()) { removeOverlays(); stopSelf(); return; }
                if (screenReady && dragging == 0) render(state);
                boolean visible = false;
                for (NativeTaskClient.Task task : state.tasks) {
                    FreeformSession.Entry entry = state.entries.get(task.id);
                    if (entry != null && !entry.minimized && task.visible) { visible = true; break; }
                }
                later(visible ? 3000 : 6000);
            });
        });
    }

    private void stopAndRestore() {
        if (destroyed || stopping) return;
        if (busy || PENDING_OPEN.get() > 0 || dragging > 0) {
            main.postDelayed(this::stopAndRestore, 500);
            return;
        }
        stopping = true;
        main.removeCallbacks(poll);
        removeOverlays();
        worker.execute(() -> {
            boolean restored;
            try { restored = !FreeformSession.hasJournal(this)
                    || FreeformSession.restoreAllAndForget(this, wm == null ? displayArea(this) : displayAreaFor(wm)); }
            catch (RuntimeException failure) { restored = false; }
            final boolean ok = restored;
            main.post(() -> {
                if (destroyed) return;
                if (ok) { stopSelf(); return; }
                stopping = false;
                Toast.makeText(this, R.string.freeform_recovery_pending, Toast.LENGTH_LONG).show();
                // Recovery journal stays intact. Don't poll or repeatedly ask for root if the
                // permission was revoked; the notification still offers a manual retry.
                if (Settings.canDrawOverlays(this)) later(6000);
            });
        });
    }

    private int dp(float value) {
        Context context = windowContext == null ? this : windowContext;
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void removeOverlays() {
        for (View view : overlays) {
            try { if (wm != null) wm.removeViewImmediate(view); }
            catch (RuntimeException ignored) { }
        }
        overlays.clear();
        signature = "";
        dragging = 0;
    }

    @SuppressLint("RtlHardcoded") // Native bounds are absolute screen coordinates, not RTL offsets.
    private WindowManager.LayoutParams params(int width, int height, int x, int y) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x; lp.y = y;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("Smart S native freeform control");
        return lp;
    }

    private boolean add(View view, WindowManager.LayoutParams lp) {
        try { wm.addView(view, lp); overlays.add(view); return true; }
        catch (RuntimeException failure) { return false; }
    }

    private GradientDrawable background(boolean round) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xff20252e);
        drawable.setStroke(dp(1), 0xff8ca9df);
        if (round) drawable.setShape(GradientDrawable.OVAL);
        else drawable.setCornerRadius(dp(9));
        return drawable;
    }

    private Drawable nativeIcon(NativeTaskClient.Task task) {
        PackageManager pm = getPackageManager();
        ComponentName component = ComponentName.unflattenFromString(task.component);
        if (component != null) {
            try { return pm.getActivityIcon(component); }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        try { return pm.getApplicationIcon(task.pkg); }
        catch (PackageManager.NameNotFoundException ignored) { return pm.getDefaultActivityIcon(); }
    }

    private CharSequence nativeLabel(NativeTaskClient.Task task) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(task.pkg, 0)); }
        catch (PackageManager.NameNotFoundException ignored) { return task.pkg; }
    }

    private TextView control(String glyph, int label, Runnable action) {
        TextView button = new TextView(windowContext);
        button.setText(glyph);
        button.setTextColor(Color.WHITE);
        button.setTextSize(20f);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(getString(label));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private boolean coveredByHigherTask(List<NativeTaskClient.Task> tasks, int index, Rect target) {
        for (int i = 0; i < index; i++) {
            NativeTaskClient.Task other = tasks.get(i);
            if (other.visible && other.type != 2 && Rect.intersects(other.bounds, target)) return true;
        }
        return false;
    }

    private void render(FreeformSession.Snapshot state) {
        if (destroyed || stopping || !screenReady || wm == null || dragging > 0) return;
        Rect area;
        try { area = displayAreaFor(wm); }
        catch (RuntimeException unavailable) { return; }
        if (area.isEmpty()) return;
        StringBuilder key = new StringBuilder(area.toString());
        for (NativeTaskClient.Task task : state.tasks) {
            key.append('|').append(task.id).append(':').append(task.mode).append(':')
                    .append(task.visible).append(':').append(task.focused).append(':').append(task.bounds);
        }
        for (FreeformSession.Entry e : state.entries.values()) {
            key.append('|').append(e.id).append(':').append(e.component).append(':')
                    .append(e.minimized).append(':').append(e.bubbleX).append(':').append(e.bubbleY);
        }
        String next = key.toString();
        if (next.equals(signature)) return;
        removeOverlays();
        signature = next;
        boolean ok = true;
        int bubbleIndex = 0;
        // Back-to-front order for independent title bars. Suppress controls over occluded apps.
        for (int i = state.tasks.size() - 1; i >= 0; i--) {
            NativeTaskClient.Task task = state.tasks.get(i);
            FreeformSession.Entry entry = state.entries.get(task.id);
            if (entry == null || !entry.component.equals(task.component) || entry.minimized
                    || !task.visible || (task.mode != 5 && task.mode != 1)) continue;
            int height = dp(40);
            int width = Math.min(area.width(), task.mode == 1 ? dp(250) : Math.max(dp(170), task.bounds.width()));
            int x = task.mode == 1 ? area.right - width
                    : FreeformTaskPolicy.clamp(task.bounds.left, area.left, area.right - width);
            int y = FreeformTaskPolicy.clamp(task.bounds.top - height, area.top, area.bottom - height);
            Rect title = new Rect(x, y, x + width, y + height);
            if (!coveredByHigherTask(state.tasks, i, title)) ok &= addTitle(task, title, area);
            if (task.mode == 5) {
                int corner = dp(30);
                int rx = FreeformTaskPolicy.clamp(task.bounds.right - corner, area.left, area.right - corner);
                int ry = FreeformTaskPolicy.clamp(task.bounds.bottom - corner, area.top, area.bottom - corner);
                Rect handle = new Rect(rx, ry, rx + corner, ry + corner);
                if (!coveredByHigherTask(state.tasks, i, handle)) ok &= addResize(task, handle, area);
            }
        }
        // Bubbles retain their own native identity, independent position and task number.
        for (NativeTaskClient.Task task : state.tasks) {
            FreeformSession.Entry entry = state.entries.get(task.id);
            if (entry == null || !entry.component.equals(task.component) || !entry.minimized) continue;
            int sameApp = 0;
            for (NativeTaskClient.Task other : state.tasks) {
                if (task.pkg.equals(other.pkg) && state.entries.containsKey(other.id)) sameApp++;
            }
            ok &= addBubble(task, entry, area, bubbleIndex++, sameApp > 1);
        }
        if (!ok) {
            removeOverlays();
            Toast.makeText(this, R.string.freeform_control_failed, Toast.LENGTH_LONG).show();
            stopAndRestore();
        }
    }

    private boolean addTitle(NativeTaskClient.Task task, Rect title, Rect area) {
        LinearLayout bar = new LinearLayout(windowContext);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(background(false));
        ImageView icon = new ImageView(windowContext);
        icon.setImageDrawable(nativeIcon(task));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.addView(icon, new LinearLayout.LayoutParams(dp(30), title.height()));
        TextView label = new TextView(windowContext);
        label.setText(nativeLabel(task));
        label.setTextColor(Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(label, new LinearLayout.LayoutParams(0, title.height(), 1f));
        WindowManager.LayoutParams lp = params(title.width(), title.height(), title.left, title.top);
        bar.addView(control("−", R.string.freeform_minimize,
                () -> operate(task, "minimize", null)), new LinearLayout.LayoutParams(dp(40), title.height()));
        if (task.mode == 5) {
            label.setOnClickListener(v -> operate(task, "focus", null));
            drag(label, bar, lp, task, area, 0);
            icon.setOnClickListener(v -> operate(task, "focus", null));
            drag(icon, bar, lp, task, area, 0);
        }
        boolean full = task.mode == 1;
        bar.addView(control(full ? "▣" : "□", full ? R.string.freeform_restore : R.string.freeform_maximize,
                () -> operate(task, full ? "restore" : "maximize", null)),
                new LinearLayout.LayoutParams(dp(40), title.height()));
        bar.addView(control("×", R.string.freeform_close, () -> operate(task, "close", null)),
                new LinearLayout.LayoutParams(dp(40), title.height()));
        return add(bar, lp);
    }

    private boolean addResize(NativeTaskClient.Task task, Rect handle, Rect area) {
        TextView view = control("◢", R.string.freeform_resize, () -> {});
        view.setTextSize(16f);
        view.setBackground(background(false));
        WindowManager.LayoutParams lp = params(handle.width(), handle.height(), handle.left, handle.top);
        drag(view, view, lp, task, area, 1);
        return add(view, lp);
    }

    private boolean addBubble(NativeTaskClient.Task task, FreeformSession.Entry entry,
                              Rect area, int index, boolean numbered) {
        int size = dp(54);
        int x = entry.bubbleX < 0 ? area.right - size - dp(6) : entry.bubbleX;
        int y = entry.bubbleY < 0 ? area.top + dp(64 + index * 62) : entry.bubbleY;
        x = FreeformTaskPolicy.clamp(x, area.left, area.right - size);
        y = FreeformTaskPolicy.clamp(y, area.top, area.bottom - size);
        FrameLayout bubble = new FrameLayout(windowContext);
        bubble.setBackground(background(true));
        ImageView image = new ImageView(windowContext);
        image.setImageDrawable(nativeIcon(task)); // Exact activity icon, then package icon; no icon pack.
        image.setPadding(dp(7), dp(7), dp(7), dp(7));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bubble.addView(image, new FrameLayout.LayoutParams(size, size));
        if (numbered) {
            TextView badge = new TextView(windowContext);
            badge.setText(String.valueOf(task.id));
            badge.setTextSize(9f); badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER); badge.setBackground(background(false));
            bubble.addView(badge, new FrameLayout.LayoutParams(dp(25), dp(16), Gravity.BOTTOM | Gravity.END));
        }
        bubble.setContentDescription(getString(R.string.freeform_restore_app, nativeLabel(task), task.id));
        bubble.setOnClickListener(v -> operate(task, "restore", null));
        WindowManager.LayoutParams lp = params(size, size, x, y);
        drag(bubble, bubble, lp, task, area, 2);
        return add(bubble, lp);
    }

    /**
     * During a drag only the small control is moved. Commit exactly one real task transaction
     * on release instead of starting a root process on every MotionEvent.
     * kind: 0 move title, 1 resize corner, 2 move native app bubble.
     */
    private void drag(View target, View window, WindowManager.LayoutParams lp,
                      NativeTaskClient.Task task, Rect area, int kind) {
        target.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int initialX, initialY, dx, dy;
            boolean moved, down;
            final int slop = ViewConfiguration.get(windowContext).getScaledTouchSlop();
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (busy || stopping || destroyed) return false;
                        down = true; moved = false; dx = 0; dy = 0;
                        downX = event.getRawX(); downY = event.getRawY();
                        initialX = lp.x; initialY = lp.y;
                        dragging++;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!down) return false;
                        dx = Math.round(event.getRawX() - downX);
                        dy = Math.round(event.getRawY() - downY);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        if (moved) {
                            lp.x = FreeformTaskPolicy.clamp(initialX + dx, area.left, area.right - lp.width);
                            lp.y = FreeformTaskPolicy.clamp(initialY + dy, area.top, area.bottom - lp.height);
                            try { wm.updateViewLayout(window, lp); } catch (RuntimeException ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        if (!down) return false;
                        down = false; dragging = Math.max(0, dragging - 1);
                        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            signature = ""; requestRefresh(); return true;
                        }
                        if (!moved) { view.performClick(); return true; }
                        int mx = lp.x - initialX, my = lp.y - initialY;
                        if (kind == 2) { moveBubble(task, lp.x, lp.y); return true; }
                        Rect next = new Rect(task.bounds);
                        if (kind == 0) next.offset(mx, my);
                        else {
                            next.right = Math.max(next.left + dp(150), next.right + mx);
                            next.bottom = Math.max(next.top + dp(180), next.bottom + my);
                        }
                        operate(task, "resize", FreeformSession.fit(next, area, dp(150)));
                        return true;
                    default: return true;
                }
            }
        });
    }

    private void moveBubble(NativeTaskClient.Task task, int x, int y) {
        if (busy || stopping || destroyed) return;
        busy = true;
        worker.execute(() -> {
            boolean ok = FreeformSession.moveBubble(this, task.id, task.component, x, y);
            main.post(() -> complete(ok));
        });
    }

    private void operate(NativeTaskClient.Task task, String action, @Nullable Rect bounds) {
        if (busy || stopping || destroyed) return;
        Rect area;
        try { area = displayAreaFor(wm); }
        catch (RuntimeException unavailable) { return; }
        if (area.isEmpty()) return;
        busy = true;
        worker.execute(() -> {
            boolean ok;
            try { ok = FreeformSession.operate(this, task.id, task.component, action, bounds, area); }
            catch (RuntimeException failure) { ok = false; }
            final boolean result = ok;
            main.post(() -> complete(result));
        });
    }

    private void complete(boolean ok) {
        if (destroyed) return;
        busy = false;
        if (!ok) Toast.makeText(this, R.string.freeform_operation_unverified, Toast.LENGTH_LONG).show();
        signature = "";
        requestRefresh();
    }
}
