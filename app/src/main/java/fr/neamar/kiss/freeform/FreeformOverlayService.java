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
    private final java.util.Map<Integer, Decoration> decorations = new java.util.HashMap<>();
    private final java.util.Map<View, android.graphics.Point> overlayOrigins = new java.util.IdentityHashMap<>();
    @Nullable private GestureRun activeGesture;
    private boolean normalizeOnRefresh = true;
    private final Runnable poll = this::refresh;
    private Context windowContext;
    private BubbleFolder folder;
    private WindowManager wm;
    private boolean destroyed, busy, refreshing, stopping, receiverRegistered, screenReady;
    private int dragging;
    private long lastOpen;
    private String signature = "";
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
    static boolean floatExact(Context c,NativeTaskClient.Task t){
        if(!prepare(c))return false;
        try { return FreeformSession.floatExact(c,t,displayArea(c)); }
        finally {
            PENDING_OPEN.decrementAndGet();
            FreeformOverlayService service=active.get();
            if(service!=null)service.main.post(service::requestRefresh);
        }
    }

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
            folder = new BubbleFolder(windowContext, wm, true, new BubbleFolder.Actions() {
                public void open(NativeTaskClient.Task t) { operate(t,"restore",null); }
                public void floating(NativeTaskClient.Task t) { operate(t,"restore",null); }
                public void close(java.util.List<NativeTaskClient.Task> selected) {
                    if (busy || stopping || destroyed) return;
                    Rect area = displayAreaFor(wm);
                    busy = true;
                    worker.execute(() -> {
                        boolean all = true;
                        try {
                            for (NativeTaskClient.Task t : selected)
                                all &= FreeformSession.closeMinimized(FreeformOverlayService.this,t,area);
                        } finally {
                            final boolean result = all;
                            main.post(() -> complete(result));
                        }
                    });
                }
            }, () -> { signature = ""; requestRefresh(); });
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
        normalizeOnRefresh = true;
        if (activeGesture != null) activeGesture.cancel();
        if (dragging == 0) requestRefresh();
    }

    @Override public void onDestroy() {
        destroyed = true;
        LiveBubbleDisplays.releaseFamily("windows");
        if (folder != null) folder.destroy();
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
        if (PENDING_OPEN.get() > 0 || busy || refreshing || dragging > 0) { later(650); return; }
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
        Rect area = displayAreaFor(wm);
        boolean normalize = normalizeOnRefresh;
        normalizeOnRefresh = false;
        refreshing = true;
        worker.execute(() -> {
            FreeformSession.Snapshot snapshot;
            try { snapshot = FreeformSession.snapshot(this, area, normalize); }
            catch (RuntimeException failure) { snapshot = null; }
            final FreeformSession.Snapshot state = snapshot;
            main.post(() -> {
                if (destroyed) return;
                refreshing = false;
                // A touch or command can start during a background read. Its worker is queued
                // after this read; never replace controls or clear command ownership mid-gesture.
                if (busy || dragging > 0 || stopping || PENDING_OPEN.get() > 0) {
                    if (normalize) normalizeOnRefresh = true;
                    later(650);
                    return;
                }
                if (state == null) {
                    // No stale control may operate after a failed identity query.
                    removeOverlays();
                    later(6000);
                    return;
                }
                if (state.entries.isEmpty()) { removeOverlays(); stopSelf(); return; }
                if (screenReady && dragging == 0) render(state);
                boolean visible = false;
                for (NativeTaskClient.Task task : state.tasks) {
                    FreeformSession.Entry entry = state.entries.get(task.id);
                    if (entry != null && !entry.minimized && task.visible) { visible = true; break; }
                }
                later(visible ? 1200 : 6000);
            });
        });
    }

    private void stopAndRestore() {
        if (destroyed || stopping) return;
        if (busy || refreshing || PENDING_OPEN.get() > 0 || dragging > 0) {
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
        if (folder != null) folder.clear();
        removeWindowControls();
    }

    private void removeWindowControls() {
        for (View view : overlays) {
            try { if (wm != null) wm.removeViewImmediate(view); }
            catch (RuntimeException ignored) { }
        }
        overlays.clear();
        decorations.clear();
        overlayOrigins.clear();
        if (activeGesture != null) activeGesture.cancel();
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
        android.util.TypedValue feedback = new android.util.TypedValue();
        if (windowContext.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, feedback, true)
                && feedback.resourceId != 0) button.setBackgroundResource(feedback.resourceId);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private boolean coveredByHigherTask(List<NativeTaskClient.Task> tasks, int index, Rect target) {
        for (int i = 0; i < index; i++) {
            NativeTaskClient.Task other = tasks.get(i);
            Rect frame = other.frame.isEmpty() ? other.bounds : other.frame;
            // A visible always-on-top popup is above normal fullscreen apps even
            // when getTasks() lists their more recently focused task first.
            if (other.display == 0 && other.visible && other.mode != 1 && other.type != 2 && Rect.intersects(frame, target)) return true;
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
        int freeformCount = 0;
        for (NativeTaskClient.Task task : state.tasks) if (task.display == 0 && task.mode == 5 && task.visible) freeformCount++;
        for (NativeTaskClient.Task task : state.tasks) {
            if (task.mode == 1 && !state.entries.containsKey(task.id)) continue;
            key.append('|').append(task.id).append(':').append(task.mode).append(':')
                    .append(task.visible).append(':').append(freeformCount > 1 && task.focused).append(':').append(task.bounds)
                    .append(':').append(task.frame);
        }
        for (FreeformSession.Entry e : state.entries.values()) {
            key.append('|').append(e.id).append(':').append(e.component).append(':')
                    .append(e.minimized).append(':').append(e.bubbleX).append(':').append(e.bubbleY);
        }
        String next = key.toString();
        if (next.equals(signature)) return;
        removeWindowControls();
        signature = next;
        boolean ok = true;
        int bubbleIndex = 0;
        // Back-to-front order for independent title bars. Suppress controls over occluded apps.
        for (int i = state.tasks.size() - 1; i >= 0; i--) {
            NativeTaskClient.Task task = state.tasks.get(i);
            FreeformSession.Entry entry = state.entries.get(task.id);
            if (entry == null || !entry.component.equals(task.component) || entry.minimized
                    || task.display != 0 || !task.visible || (task.mode != 5 && task.mode != 1)) continue;
            // Never put a caption at a guessed task/configuration rectangle. Use the actual
            // visible application frame, including compositor position, in display coordinates.
            Rect frame = new Rect(task.frame);
            if (frame.isEmpty() || !frame.intersect(area)) continue;
            if (task.mode == 1) {
                if (FreeformGeometry.fullscreen(task.frame, area, dp(8))) ok &= addFullscreenRestore(task, area);
                continue;
            }
            Rect title = FreeformGeometry.caption(frame, area, dp(40));
            Rect handle = FreeformGeometry.corner(frame, dp(30));
            Decoration decor = new Decoration();
            decorations.put(task.id, decor);
            decor.frame.set(frame);
            decor.focused = task.focused;
            decor.order = i;
            // Keep the controls as a pair; never leave an orphan title or resize handle.
            // The focused task is in front even when a ROM reports stale task ordering.
            boolean covered = !task.focused && (coveredByHigherTask(state.tasks, i, title)
                    || coveredByHigherTask(state.tasks, i, handle));
            if (!covered) {
                ok &= addTitle(task, title, area, decor);
                ok &= addResize(task, handle, area, decor);
            }
        }
        ok &= updateBorders();
        java.util.List<NativeTaskClient.Task> minimized = new java.util.ArrayList<>();
        for (NativeTaskClient.Task task : state.tasks) {
            FreeformSession.Entry e = state.entries.get(task.id);
            if (e != null && e.minimized && e.component.equals(task.component)) minimized.add(task);
        }
        boolean grouped = folder != null && folder.show(minimized,area);
        // Individual controls keep their existing behavior in separate mode.
        for (NativeTaskClient.Task task : state.tasks) {
            FreeformSession.Entry entry = state.entries.get(task.id);
            if (grouped || entry == null || !entry.component.equals(task.component) || !entry.minimized) continue;
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

    private static final class Decoration {
        final Rect frame = new Rect();
        final java.util.List<View> borders = new java.util.ArrayList<>();
        int order;
        boolean focused;
        View title, corner;
        WindowManager.LayoutParams titleParams, cornerParams;
    }

    // Draw only exposed edges. Separate thin, non-touchable strips keep the app interior clear.
    private boolean updateBorders() {
        for (java.util.Map.Entry<Integer, Decoration> item : decorations.entrySet()) {
            Decoration current = item.getValue();
            Rect frame = current.frame;
            int width = Math.min(dp(3), Math.min(frame.width(), frame.height()) / 2);
            Rect[] edges = {
                    new Rect(frame.left, frame.top, frame.right, frame.top + width),
                    new Rect(frame.left, frame.bottom - width, frame.right, frame.bottom),
                    new Rect(frame.left, frame.top + width, frame.left + width, frame.bottom - width),
                    new Rect(frame.right - width, frame.top + width, frame.right, frame.bottom - width)
            };
            int used = 0;
            for (int edge = 0; edge < edges.length; edge++) {
                android.graphics.Region visible = new android.graphics.Region(edges[edge]);
                for (Decoration other : decorations.values()) {
                    if (other != current && !current.focused && (other.focused || other.order < current.order))
                        visible.op(other.frame, android.graphics.Region.Op.DIFFERENCE);
                }
                android.graphics.RegionIterator pieces = new android.graphics.RegionIterator(visible);
                Rect part = new Rect();
                while (pieces.next(part)) {
                    View strip;
                    if (used < current.borders.size()) {
                        strip = current.borders.get(used);
                        align(strip, (WindowManager.LayoutParams) strip.getLayoutParams(), part);
                    } else {
                        strip = new View(windowContext);
                        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                        WindowManager.LayoutParams lp = params(part.width(), part.height(), part.left, part.top);
                        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        lp.alpha = Math.min(0.8f, getSystemService(android.hardware.input.InputManager.class)
                                .getMaximumObscuringOpacityForTouch());
                        lp.setTitle("Smart S freeform border " + item.getKey());
                        if (!add(strip, lp)) return false;
                        current.borders.add(strip);
                        alignAfterLayout(strip, lp, new Rect(part));
                    }
                    if (!Integer.valueOf(edge).equals(strip.getTag())) {
                        GradientDrawable.Orientation[] directions = {GradientDrawable.Orientation.TOP_BOTTOM,
                                GradientDrawable.Orientation.BOTTOM_TOP, GradientDrawable.Orientation.LEFT_RIGHT,
                                GradientDrawable.Orientation.RIGHT_LEFT};
                        strip.setBackground(new GradientDrawable(directions[edge], new int[]{
                                0xff101824, 0xff101824, current.focused ? 0xff59e8ff : 0xffedf3ff, 0xffffffff}));
                        strip.setTag(edge);
                    }
                    used++;
                }
            }
            while (current.borders.size() > used) {
                View extra = current.borders.remove(current.borders.size() - 1);
                overlays.remove(extra);
                overlayOrigins.remove(extra);
                wm.removeViewImmediate(extra);
            }
        }
        return true;
    }

    private void align(View view, WindowManager.LayoutParams lp, Rect target) {
        if (view == null || lp == null || !view.isAttachedToWindow()) return;
        // Android may translate overlay gravity into its own containing-frame origin. Measure
        // that offset from the actual attached view instead of assuming an origin of (0,0).
        android.graphics.Point origin = overlayOrigins.get(view);
        if (origin == null) {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
            int[] actual = new int[2];
            view.getLocationOnScreen(actual);
            origin = new android.graphics.Point(actual[0] - lp.x, actual[1] - lp.y);
            overlayOrigins.put(view, origin);
        }
        // Use one measured origin per attached view. Never accumulate corrections from a
        // pending layout while processing rapid drag events.
        int nextX = target.left - origin.x;
        int nextY = target.top - origin.y;
        if (nextX == lp.x && nextY == lp.y && lp.width == target.width()
                && lp.height == target.height()) return;
        lp.x = nextX; lp.y = nextY; lp.width = target.width(); lp.height = target.height();
        try { wm.updateViewLayout(view, lp); } catch (RuntimeException ignored) { }
    }

    private void alignAfterLayout(View view, WindowManager.LayoutParams lp, Rect target) {
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
                v.removeOnLayoutChangeListener(this);
                v.post(() -> { if (!destroyed && overlays.contains(v)) align(v, lp, target); });
            }
        });
        view.post(() -> { if (!destroyed && overlays.contains(view)) align(view, lp, target); });
    }

    private void alignDecoration(int id, Rect frame, Rect area) {
        Decoration decor = decorations.get(id);
        if (decor == null || frame.isEmpty()) return;
        Rect shown = new Rect(frame);
        if (!shown.intersect(area)) return;
        decor.frame.set(shown);
        updateBorders();
        align(decor.title, decor.titleParams, FreeformGeometry.caption(shown, area, dp(40)));
        align(decor.corner, decor.cornerParams, FreeformGeometry.corner(shown, dp(30)));
    }

    private boolean addFullscreenRestore(NativeTaskClient.Task task, Rect area) {
        // Fullscreen content must not retain the detached title bar or resize handle.
        TextView restore = control("▣", R.string.freeform_restore, () -> operate(task, "restore", null));
        restore.setTextSize(18f);
        restore.setBackground(background(true));
        int size = dp(38);
        Rect target = new Rect(area.right - size - dp(4), area.top + dp(4),
                area.right - dp(4), area.top + dp(4) + size);
        WindowManager.LayoutParams lp = params(size, size, target.left, target.top);
        lp.setTitle("Smart S freeform restore " + task.id);
        boolean added = add(restore, lp);
        if (added) alignAfterLayout(restore, lp, target);
        return added;
    }

    private boolean addTitle(NativeTaskClient.Task task, Rect title, Rect area, Decoration decor) {
        LinearLayout bar = new LinearLayout(windowContext);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(background(false));
        ImageView icon = new ImageView(windowContext);
        icon.setImageDrawable(nativeIcon(task));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        int buttonWidth = Math.min(dp(40), Math.max(1, title.width() / 6));
        bar.addView(icon, new LinearLayout.LayoutParams(Math.min(dp(30), buttonWidth), title.height()));
        TextView label = new TextView(windowContext);
        label.setText(nativeLabel(task));
        label.setTextColor(Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(label, new LinearLayout.LayoutParams(0, title.height(), 1f));
        WindowManager.LayoutParams lp = params(title.width(), title.height(), title.left, title.top);
        lp.setTitle("Smart S freeform title " + task.id);
        bar.addView(control("‹", R.string.freeform_back, () -> operate(task, "back", null)),
                new LinearLayout.LayoutParams(buttonWidth, title.height()));
        bar.addView(control("−", R.string.freeform_minimize,
                () -> operate(task, "minimize", null)), new LinearLayout.LayoutParams(buttonWidth, title.height()));
        if (task.mode == 5) {
            label.setOnClickListener(v -> operate(task, "focus", null));
            drag(label, bar, lp, task, area, 0);
            icon.setOnClickListener(v -> operate(task, "focus", null));
            drag(icon, bar, lp, task, area, 0);
        }
        bar.addView(control("□", R.string.freeform_maximize,
                () -> operate(task, "maximize", null)),
                new LinearLayout.LayoutParams(buttonWidth, title.height()));
        bar.addView(control("×", R.string.freeform_close, () -> operate(task, "close", null)),
                new LinearLayout.LayoutParams(buttonWidth, title.height()));
        decor.title = bar; decor.titleParams = lp;
        boolean added = add(bar, lp);
        if (added) alignAfterLayout(bar, lp, title);
        return added;
    }

    private boolean addResize(NativeTaskClient.Task task, Rect handle, Rect area, Decoration decor) {
        TextView view = control("◢", R.string.freeform_resize, () -> {});
        view.setTextSize(16f);
        view.setBackground(background(false));
        WindowManager.LayoutParams lp = params(handle.width(), handle.height(), handle.left, handle.top);
        lp.setTitle("Smart S freeform corner " + task.id);
        drag(view, view, lp, task, area, 1);
        decor.corner = view; decor.cornerParams = lp;
        boolean added = add(view, lp);
        if (added) alignAfterLayout(view, lp, handle);
        return added;
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
        lp.setTitle("Smart S freeform bubble " + task.id);
        drag(bubble, bubble, lp, task, area, 2);
        boolean added = add(bubble, lp);
        if (added) alignAfterLayout(bubble, lp, new Rect(x, y, x + size, y + size));
        return added;
    }

    /**
     * A gesture owns one task and one bounded root process. Motion events coalesce into the
     * latest desired rectangle; the UI moves BOTH controls only on observed surface geometry.
     * No speculative title/corner movement or unbounded command queue.
     */
    private final class GestureRun implements Runnable {
        final NativeTaskClient.Task task;
        final Rect area;
        private Rect wanted;
        private boolean finished, cancelled;
        GestureRun(NativeTaskClient.Task task, Rect area) {
            this.task = task; this.area = new Rect(area);
        }
        synchronized void offer(Rect bounds, boolean end) {
            if (cancelled) return;
            wanted = new Rect(bounds); finished = end; notifyAll();
        }
        synchronized void cancel() { cancelled = true; notifyAll(); }
        @Override public void run() {
            boolean success = false;
            try (NativeTaskClient.Gesture pipe = new NativeTaskClient(FreeformOverlayService.this).new Gesture(task)) {
                while (true) {
                    Rect next; boolean end;
                    synchronized (this) {
                        // Prevent an orphan process/worker if Android never sends ACTION_UP.
                        long deadline = SystemClock.elapsedRealtime() + 15000;
                        while (wanted == null && !cancelled) {
                            long left = deadline - SystemClock.elapsedRealtime();
                            if (left <= 0) { cancelled = true; break; }
                            wait(left);
                        }
                        if (cancelled) break;
                        next = wanted; wanted = null; end = finished;
                    }
                    NativeTaskClient.Task observed = pipe.send(next, end);
                    if (observed == null || observed.id != task.id || !task.component.equals(observed.component)) break;
                    Rect frame = new Rect(observed.frame);
                    main.post(() -> {
                        if (!destroyed && screenReady && activeGesture == this)
                            alignDecoration(task.id, frame, area);
                    });
                    if (end) {
                        success = FreeformSession.finishGesture(FreeformOverlayService.this, observed, next);
                        break;
                    }
                }
            } catch (Exception failure) { /* Report only after reconciling actual task state. */ }
            final boolean ok = success;
            main.post(() -> {
                if (destroyed || activeGesture != this) return;
                activeGesture = null;
                dragging = 0;
                complete(ok);
            });
        }
    }

    private void drag(View target, View window, WindowManager.LayoutParams lp,
                      NativeTaskClient.Task task, Rect area, int kind) {
        View.OnTouchListener touch = new View.OnTouchListener() {
            float downX, downY;
            int initialX, initialY, dx, dy;
            boolean moved, down;
            GestureRun gesture;
            final int slop = ViewConfiguration.get(windowContext).getScaledTouchSlop();
            Rect desired() {
                Rect next = new Rect(task.bounds);
                Rect safe = FreeformGeometry.contentArea(area, dp(40));
                if (kind == 0) next.offset(dx, dy);
                else {
                    next.right = Math.max(next.left + dp(150), next.right + dx);
                    next.bottom = Math.max(next.top + dp(180), next.bottom + dy);
                    next.right = Math.min(next.right, safe.right);
                    next.bottom = Math.min(next.bottom, safe.bottom);
                }
                return FreeformGeometry.fit(next, safe, dp(150));
            }
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (busy || stopping || destroyed || dragging > 0) return false;
                        down = true; moved = false; dx = 0; dy = 0; gesture = null;
                        downX = event.getRawX(); downY = event.getRawY();
                        int[] actual = new int[2]; window.getLocationOnScreen(actual);
                        initialX = actual[0]; initialY = actual[1];
                        dragging++;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_UP:
                        if (!down) return false;
                        dx = Math.round(event.getRawX() - downX);
                        dy = Math.round(event.getRawY() - downY);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        boolean end = event.getActionMasked() == MotionEvent.ACTION_UP;
                        if (!moved) {
                            if (end) { down = false; dragging = 0; view.performClick(); }
                            return true;
                        }
                        if (kind == 2) {
                            int x = FreeformTaskPolicy.clamp(initialX + dx, area.left, area.right - lp.width);
                            int y = FreeformTaskPolicy.clamp(initialY + dy, area.top, area.bottom - lp.height);
                            align(window, lp, new Rect(x, y, x + lp.width, y + lp.height));
                            if (end) { down = false; dragging = 0; moveBubble(task, x, y); }
                            return true;
                        }
                        if (gesture == null) {
                            gesture = new GestureRun(task, area);
                            activeGesture = gesture; busy = true;
                            worker.execute(gesture);
                        }
                        gesture.offer(desired(), end);
                        if (end) down = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (!down) return false;
                        down = false;
                        if (gesture != null) gesture.cancel();
                        else { dragging = 0; if(kind!=2)signature = ""; requestRefresh(); }
                        return true;
                    default: return true;
                }
            }
        };
        if(kind==2)BubbleMenu.install(target,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                ()->operate(task,"restore",null),touch);
        else target.setOnTouchListener(touch);
    }

    private void moveBubble(NativeTaskClient.Task task, int x, int y) {
        if (busy || stopping || destroyed) return;
        busy = true;
        worker.execute(() -> {
            boolean ok = FreeformSession.moveBubble(this, task.id, task.component, x, y);
            main.post(() -> complete(ok));
        });
    }

    private NativeTaskClient.Task pendingClose;

    private void operate(NativeTaskClient.Task task, String action, @Nullable Rect bounds) {
        if (stopping || destroyed) return;
        if (busy) {
            if ("close".equals(action)) pendingClose = task;
            return;
        }
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
            main.post(() -> complete(result, !"back".equals(action)));
        });
    }

    private void complete(boolean ok) { complete(ok, true); }

    private void complete(boolean ok, boolean rebuild) {
        if (destroyed) return;
        busy = false;
        if (pendingClose != null) {
            NativeTaskClient.Task next = pendingClose;
            pendingClose = null;
            operate(next, "close", null);
            return;
        }
        if (!ok) Toast.makeText(this, R.string.freeform_operation_unverified, Toast.LENGTH_LONG).show();
        if (rebuild) signature = "";
        requestRefresh();
    }
}
