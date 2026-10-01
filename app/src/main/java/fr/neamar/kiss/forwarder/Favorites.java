package fr.neamar.kiss.forwarder;

import static androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_IDLE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.AppUsageTodayStore;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.LaunchStatsProvider;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.LaunchMorphTransition;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.AppIdentityResolver;
import fr.neamar.kiss.utils.LauncherScrollWorkGate;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.NotificationHistoryResolver;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.UserHandle;

public class Favorites extends Forwarder {
    private static final String TAG = Favorites.class.getSimpleName();
    private static final String DEFAULT_RESOLVER = "com.android.internal.app.ResolverActivity";

    static final String PREF_BAR_MODE = "favorites-bar-mode";
    static final String MODE_STANDARD = "standard";
    static final String MODE_PIXEL = "pixel";
    static final String PREF_PIXEL_MAX_APPS = "pixel-favorites-max-apps";

    private FavoriteAdapter favoriteAdapter;
    @Nullable private ItemTouchHelper favoriteTouchHelper;
    private String lastRenderedMode;
    private int lastPixelLimit = -1;

    // Pixel prediction must never block HOME rendering. The previous synchronous launch-stats
    // query ran from onResume/onFavoriteChange and could hold the main thread for several seconds.
    private final Handler pixelHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pixelExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-s-pixel-predict");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    @Nullable private Future<?> pixelFuture;
    @Nullable private CancellationSignal pixelCancellation;
    private int pixelGeneration;

    private static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(@NonNull View itemView) { super(itemView); }
        public void inflateFavorite(@NonNull Result<?> item) {
            item.inflateFavorite(itemView.getContext(), itemView);
        }
    }

    private interface OnItemClickListener {
        void onClick(View v, Result<?> result);
    }

    private interface OnItemLongClickListener {
        boolean onLongClick(View v, Result<?> result);
    }

    private class FavoriteAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<Result<?>> results = new ArrayList<>();
        private OnItemClickListener mOnItemClickListener;
        private OnItemLongClickListener mOnItemLongClickListener;

        public FavoriteAdapter() {
            super();
            setHasStableIds(true);
        }

        public boolean moveItem(int fromPosition, int toPosition) {
            if (fromPosition == toPosition) return false;
            Result<?> result = results.remove(fromPosition);
            results.add(toPosition, result);
            notifyItemMoved(fromPosition, toPosition);
            return true;
        }

        public void setFavorites(List<Result<?>> incoming) {
            Map<String, Result<?>> warmById = new HashMap<>(Math.max(4, results.size() * 2));
            for (Result<?> result : results) warmById.put(result.getFavoriteId(), result);

            List<Result<?>> merged = new ArrayList<>(incoming.size());
            for (Result<?> candidate : incoming) {
                Result<?> warm = warmById.get(candidate.getFavoriteId());
                if (warm != null && warm.getClass() == candidate.getClass()
                        && warm.getPojo() == candidate.getPojo()) {
                    merged.add(warm);
                } else {
                    merged.add(candidate);
                }
            }

            if (sameOrder(merged)) return;
            results.clear();
            results.addAll(merged);
            notifyDataSetChanged();
        }

        private boolean sameOrder(List<Result<?>> incoming) {
            if (incoming.size() != results.size()) return false;
            for (int i = 0; i < results.size(); i++) {
                if (incoming.get(i) != results.get(i)) return false;
            }
            return true;
        }

        public void updateFavoritePositions(Context context) {
            List<Pair<String, Integer>> positions = new ArrayList<>();
            for (int i = 0; i < getItemCount(); i++) {
                positions.add(new Pair<>(getItem(i).getFavoriteId(), i));
            }
            KissApplication.getApplication(context).getDataHandler().setFavoritePositions(positions);
        }

        public void setOnItemClickListener(OnItemClickListener listener) {
            mOnItemClickListener = listener;
        }

        public void setOnItemLongClickListener(OnItemLongClickListener listener) {
            mOnItemLongClickListener = listener;
        }

        @Override public int getItemViewType(int position) {
            return Result.getItemViewType(getItem(position));
        }

        private Result<?> getItem(int position) {
            return results.get(position);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = R.layout.favorite_item;
            if (viewType == 6) {
                SharedPreferences sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(parent.getContext());
                if (!sharedPreferences.getBoolean("pref-fav-tags-drawable", false)) {
                    layout = R.layout.favorite_tag;
                }
            }
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(layout, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Result<?> result = getItem(position);
            holder.inflateFavorite(result);

            // Keep click + long-click on the same physical touch target. In 3.30.142 the icon
            // received a direct long-click listener while its click listener remained only on the
            // parent FrameLayout. Android then let the long-clickable ImageView consume taps, so
            // neither Pixel nor Standard icons reached the parent's launch callback.
            View.OnClickListener launchClick = v -> {
                if (mOnItemClickListener != null) mOnItemClickListener.onClick(v, result);
            };
            View.OnLongClickListener contextLongPress = v ->
                    mOnItemLongClickListener == null
                            || mOnItemLongClickListener.onLongClick(v, result);

            holder.itemView.setOnClickListener(launchClick);
            holder.itemView.setOnLongClickListener(contextLongPress);

            View favoriteIcon = holder.itemView.findViewById(R.id.favorite);
            if (favoriteIcon != null) {
                favoriteIcon.setClickable(true);
                favoriteIcon.setLongClickable(true);
                favoriteIcon.setOnClickListener(launchClick);
                favoriteIcon.setOnLongClickListener(contextLongPress);
            }

            // ItemTouchHelper's built-in long-press drag used to win the gesture before the normal
            // Smart S popup could remain visible. Keep a stationary long press for the standard
            // result menu, while preserving Standard-mode reordering as hold-then-drag.
            final int touchSlop = ViewConfiguration.get(holder.itemView.getContext())
                    .getScaledTouchSlop();
            holder.itemView.setOnTouchListener(new View.OnTouchListener() {
                float downX;
                float downY;
                long downTime;
                boolean dragStarted;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getX();
                            downY = event.getY();
                            downTime = SystemClock.uptimeMillis();
                            dragStarted = false;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            if (!dragStarted
                                    && !isPixelMode()
                                    && !UiEditLock.isLocked(mainActivity)
                                    && favoriteTouchHelper != null
                                    && holder.getAbsoluteAdapterPosition()
                                    != RecyclerView.NO_POSITION
                                    && SystemClock.uptimeMillis() - downTime
                                    >= ViewConfiguration.getLongPressTimeout()) {
                                float dx = event.getX() - downX;
                                float dy = event.getY() - downY;
                                if (dx * dx + dy * dy >= (float) touchSlop * touchSlop) {
                                    dragStarted = true;
                                    mainActivity.dismissPopup();
                                    favoriteTouchHelper.startDrag(holder);
                                }
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            dragStarted = false;
                            break;
                        default:
                            break;
                    }
                    return false;
                }
            });
        }

        @Override public int getItemCount() {
            return results.size();
        }

        @Override public long getItemId(int position) {
            return getItem(position).getFavoriteId().hashCode();
        }
    }

    private class ItemMoveCallback extends ItemTouchHelper.Callback {
        private final FavoriteAdapter mAdapter;
        private boolean moved;

        private ItemMoveCallback(@NonNull FavoriteAdapter adapter) {
            mAdapter = adapter;
        }

        @Override public boolean isItemViewSwipeEnabled() {
            return false;
        }

        @Override public boolean isLongPressDragEnabled() {
            // Long press belongs to the normal Smart S item menu. Standard-mode reordering is
            // explicitly started only after the user holds and then moves the favorite.
            return false;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder) {
            // Pixel mode contains predicted items that are not persisted favorites. Keep its row
            // stable and manage pinned favorites through the normal Add/Remove favorite actions.
            // Standard mode retains the original unlimited drag/reorder behavior unchanged.
            if (isPixelMode()) return makeMovementFlags(0, 0);
            return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            if (isPixelMode()) return false;
            Favorites.this.mainActivity.dismissPopup();
            Favorites.this.mainActivity.closeContextMenu();
            return mAdapter.moveItem(
                    viewHolder.getAbsoluteAdapterPosition(), target.getAbsoluteAdapterPosition());
        }

        @Override
        public void onMoved(@NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder, int fromPos,
                            @NonNull RecyclerView.ViewHolder target, int toPos, int x, int y) {
            super.onMoved(recyclerView, viewHolder, fromPos, target, toPos, x, y);
            moved = true;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) { }

        @Override
        public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (actionState == ACTION_STATE_IDLE && moved) {
                if (!isPixelMode()) mAdapter.updateFavoritePositions(mainActivity);
                moved = false;
            }
        }
    }

    Favorites(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        if (isExternalFavoriteBarEnabled()) {
            mainActivity.favoritesBar = mainActivity.findViewById(R.id.externalFavoriteBar);
            mainActivity.findViewById(R.id.embeddedFavoritesBar).setVisibility(View.INVISIBLE);
        } else {
            mainActivity.favoritesBar = mainActivity.findViewById(R.id.embeddedFavoritesBar);
            mainActivity.findViewById(R.id.externalFavoriteBar).setVisibility(View.GONE);
        }

        favoriteAdapter = new FavoriteAdapter();
        favoriteAdapter.setOnItemClickListener(this::onClick);
        favoriteAdapter.setOnItemLongClickListener(this::onLongClick);
        favoriteTouchHelper = new ItemTouchHelper(new ItemMoveCallback(favoriteAdapter));
        favoriteTouchHelper.attachToRecyclerView(mainActivity.favoritesBar);
        mainActivity.favoritesBar.setAdapter(favoriteAdapter);

        if (prefs.getBoolean("first-run-favorites", true)) {
            if (DBHelper.getHistoryLength(mainActivity) == 0) addDefaultAppsToFavs();
            prefs.edit().putBoolean("first-run-favorites", false).apply();
        }

        onFavoriteChange();
    }

    /**
     * Pixel predictions are refreshed only when Pixel mode is selected and the launcher resumes.
     * Standard mode does not execute the prediction query or any Pixel-mode worker.
     */
    void onResume() {
        String mode = currentMode();
        int pixelLimit = getPixelLimit();

        if (MODE_PIXEL.equals(mode)) {
            onFavoriteChange();
        } else if (!MODE_STANDARD.equals(lastRenderedMode) || lastPixelLimit != pixelLimit) {
            // Handles switching Pixel -> Standard while Settings was in the foreground. The
            // standard path below is exactly the legacy favorites path and starts no predictor.
            onFavoriteChange();
        }
    }

    public void onFavoriteChange() {
        DataHandler dataHandler = KissApplication.getApplication(mainActivity).getDataHandler();
        String mode = currentMode();
        int pixelLimit = getPixelLimit();

        if (MODE_PIXEL.equals(mode)) {
            // Draw something immediately from already-loaded in-memory state. No SQLite, usage
            // query or package scan is allowed to delay HOME.
            List<Pojo> pinned = dataHandler.getFavoritesIncludingDisabled();
            List<Pojo> immediate = buildPixelFavorites(
                    dataHandler, pinned, pixelLimit, Collections.emptyMap(), null);
            applyFavorites(immediate, mode, pixelLimit);

            // Prediction runs only for Pixel mode, at background priority, after HOME gets a chance
            // to draw. Standard mode never starts this worker.
            schedulePixelPrediction(dataHandler, pinned, pixelLimit);
            return;
        }

        cancelPixelPrediction();
        applyFavorites(dataHandler.getFavorites(), mode, pixelLimit);
    }

    private void applyFavorites(List<Pojo> displayed, String mode, int pixelLimit) {
        List<Result<?>> results = new ArrayList<>(displayed.size());
        for (Pojo pojo : displayed) {
            results.add(Result.fromPojo(mainActivity, pojo));
        }

        RecyclerView favoritesBar = mainActivity.favoritesBar;
        if (favoritesBar.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) favoritesBar.getLayoutManager())
                    .setSpanCount(Math.max(displayed.size(), 1));
        }
        favoriteAdapter.setFavorites(results);
        lastRenderedMode = mode;
        lastPixelLimit = pixelLimit;
    }

    private void schedulePixelPrediction(DataHandler dataHandler,
                                         List<Pojo> pinnedFavorites,
                                         int pixelLimit) {
        cancelPixelPrediction();
        final int generation = ++pixelGeneration;
        final List<Pojo> pinnedSnapshot = new ArrayList<>(pinnedFavorites);

        pixelHandler.postDelayed(() -> {
            if (generation != pixelGeneration || !MODE_PIXEL.equals(currentMode())
                    || LauncherScrollWorkGate.isScrolling()) {
                return;
            }

            final CancellationSignal signal = new CancellationSignal();
            pixelCancellation = signal;
            pixelFuture = pixelExecutor.submit(() -> {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    if (signal.isCanceled() || generation != pixelGeneration
                            || LauncherScrollWorkGate.isScrolling()) return;

                    Map<String, LaunchStatsProvider.LaunchStats> launchStats =
                            LaunchStatsProvider.loadAll(
                                    mainActivity.getApplicationContext(), signal);
                    if (signal.isCanceled() || generation != pixelGeneration
                            || LauncherScrollWorkGate.isScrolling()) return;

                    AppUsageTodayStore.Snapshot usage =
                            AppUsageTodayStore.getToday(mainActivity.getApplicationContext());
                    if (signal.isCanceled() || generation != pixelGeneration
                            || LauncherScrollWorkGate.isScrolling()) return;

                    List<Pojo> predicted = buildPixelFavorites(
                            dataHandler, pinnedSnapshot, pixelLimit, launchStats, usage);
                    pixelHandler.post(() -> {
                        if (signal.isCanceled() || generation != pixelGeneration
                                || !MODE_PIXEL.equals(currentMode())
                                || LauncherScrollWorkGate.isScrolling()
                                || mainActivity.isFinishing()) {
                            return;
                        }
                        applyFavorites(predicted, MODE_PIXEL, pixelLimit);
                    });
                } catch (RuntimeException ignored) {
                    // Cancellation/provider reload races are expected; keep the immediate bar.
                }
            });
        }, 250L);
    }

    private void cancelPixelPrediction() {
        pixelGeneration++;
        pixelHandler.removeCallbacksAndMessages(null);
        CancellationSignal signal = pixelCancellation;
        if (signal != null) signal.cancel();
        pixelCancellation = null;
        Future<?> future = pixelFuture;
        if (future != null) future.cancel(true);
        pixelFuture = null;
    }

    void onDestroy() {
        cancelPixelPrediction();
        if (favoriteTouchHelper != null) {
            favoriteTouchHelper.attachToRecyclerView(null);
            favoriteTouchHelper = null;
        }
        pixelExecutor.shutdownNow();
    }

    private List<Pojo> buildPixelFavorites(DataHandler dataHandler,
                                           List<Pojo> pinnedFavorites,
                                           int requestedLimit,
                                           Map<String, LaunchStatsProvider.LaunchStats> launchStats,
                                           @Nullable AppUsageTodayStore.Snapshot usageSnapshot) {
        int max = PixelFavoritesPolicy.clampMaxApps(requestedLimit);

        List<String> pinnedKeys = new ArrayList<>(pinnedFavorites.size());
        Map<String, Pojo> pojoByKey = new HashMap<>();
        for (Pojo favorite : pinnedFavorites) {
            if (favorite == null) continue;
            String key = AppIdentityResolver.canonicalSelectionKey(
                    mainActivity, dataHandler, favorite);
            if (key == null || key.isEmpty() || pojoByKey.containsKey(key)) continue;

            Pojo representative = launchablePixelRepresentative(favorite);
            pinnedKeys.add(key);
            pojoByKey.put(key, representative);
            if (pinnedKeys.size() >= max) break;
        }

        List<String> rankedKeys = new ArrayList<>();
        boolean hasLaunchSignals = launchStats != null && !launchStats.isEmpty();
        boolean hasUsageSignals = usageSnapshot != null && usageSnapshot.available
                && !usageSnapshot.foregroundMsByPackage.isEmpty();
        if (pinnedKeys.size() < max && (hasLaunchSignals || hasUsageSignals)) {
            List<AppPojo> availableApps = dataHandler.getApplicationsWithoutExcluded();
            if (availableApps != null && !availableApps.isEmpty()) {
                List<AppPojo> rankedApps = new ArrayList<>(availableApps);
                rankedApps.sort((left, right) -> {
                    LaunchStatsProvider.LaunchStats leftStats = launchStats == null
                            ? null : launchStats.get(left.getHistoryId());
                    LaunchStatsProvider.LaunchStats rightStats = launchStats == null
                            ? null : launchStats.get(right.getHistoryId());

                    long leftForeground = usageForPackage(usageSnapshot, left.packageName);
                    long rightForeground = usageForPackage(usageSnapshot, right.packageName);
                    if (ShortcutUtil.isIceBoxPublisher(mainActivity, left.packageName)
                            && (leftStats == null || leftStats.totalLaunches <= 0)) {
                        leftForeground = 0L;
                    }
                    if (ShortcutUtil.isIceBoxPublisher(mainActivity, right.packageName)
                            && (rightStats == null || rightStats.totalLaunches <= 0)) {
                        rightForeground = 0L;
                    }
                    PixelFavoritesPolicy.PredictionSignal leftSignal =
                            predictionSignal(leftStats, leftForeground);
                    PixelFavoritesPolicy.PredictionSignal rightSignal =
                            predictionSignal(rightStats, rightForeground);
                    int prediction = PixelFavoritesPolicy.comparePrediction(
                            leftSignal, rightSignal);
                    if (prediction != 0) return prediction;
                    return String.CASE_INSENSITIVE_ORDER.compare(
                            left.getName(), right.getName());
                });

                for (AppPojo app : rankedApps) {
                    if (app == null || TextUtils.equals(
                            app.packageName, mainActivity.getPackageName())) continue;
                    LaunchStatsProvider.LaunchStats stats = launchStats == null
                            ? null : launchStats.get(app.getHistoryId());
                    long foregroundToday = usageForPackage(usageSnapshot, app.packageName);
                    if (ShortcutUtil.isIceBoxPublisher(mainActivity, app.packageName)
                            && (stats == null || stats.totalLaunches <= 0)) {
                        // Android can briefly foreground IceBox while it routes another app. That
                        // must not make IceBox itself a predicted app; only an explicit IceBox app
                        // launch gives it launch history of its own.
                        foregroundToday = 0L;
                    }
                    if ((stats == null || stats.totalLaunches <= 0) && foregroundToday <= 0L) {
                        continue;
                    }

                    String key = AppIdentityResolver.canonicalSelectionKey(
                            mainActivity, dataHandler, app);
                    if (key == null || key.isEmpty() || pojoByKey.containsKey(key)) continue;

                    Pojo representative = launchablePixelRepresentative(app);
                    rankedKeys.add(key);
                    pojoByKey.put(key, representative);
                }
            }
        }

        List<String> selectedKeys = PixelFavoritesPolicy.select(
                pinnedKeys, rankedKeys, max);
        List<Pojo> selected = new ArrayList<>(selectedKeys.size());
        for (String key : selectedKeys) {
            Pojo pojo = pojoByKey.get(key);
            if (pojo != null) selected.add(pojo);
        }
        return selected;
    }

    private PixelFavoritesPolicy.PredictionSignal predictionSignal(
            @Nullable LaunchStatsProvider.LaunchStats stats, long foregroundMsToday) {
        if (stats == null) {
            return new PixelFavoritesPolicy.PredictionSignal(
                    0, 0, 0, foregroundMsToday, 0L, 0);
        }
        return new PixelFavoritesPolicy.PredictionSignal(
                stats.launchesToday,
                stats.launchesLast24Hours,
                stats.launchesLast7Days,
                foregroundMsToday,
                stats.lastLaunchTime,
                stats.totalLaunches);
    }

    private long usageForPackage(@Nullable AppUsageTodayStore.Snapshot snapshot,
                                 @Nullable String packageName) {
        if (snapshot == null || !snapshot.available || packageName == null) return 0L;
        Long value = snapshot.foregroundMsByPackage.get(packageName);
        return value == null ? 0L : Math.max(0L, value);
    }

    private Pojo launchablePixelRepresentative(Pojo logicalItem) {
        // Pixel app slots represent the app itself, even while frozen. AppResult performs a live
        // package-state check and, when "Auto-enable frozen apps when opened" is enabled, restores
        // the package before launching it. Replacing a frozen app with an IceBox wrapper here made
        // the bottom bar bypass Smart S's own enable/open path. Explicit IceBox shortcuts remain
        // ShortcutPojo items and continue to follow IceBox's route exactly as before.
        return logicalItem;
    }

    private String currentMode() {
        String mode = prefs.getString(PREF_BAR_MODE, MODE_STANDARD);
        return MODE_PIXEL.equals(mode) ? MODE_PIXEL : MODE_STANDARD;
    }

    private int getPixelLimit() {
        String value = prefs.getString(PREF_PIXEL_MAX_APPS,
                Integer.toString(PixelFavoritesPolicy.DEFAULT_MAX_APPS));
        try {
            return PixelFavoritesPolicy.clampMaxApps(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return PixelFavoritesPolicy.DEFAULT_MAX_APPS;
        }
    }

    private boolean isPixelMode() {
        return MODE_PIXEL.equals(currentMode());
    }

    private void onClick(View v, Result<?> result) {
        Pojo pojo = result.getPojo();
        RecentLaunchTracker.remember(pojo);
        boolean morphLaunch = pojo instanceof AppPojo
                || pojo instanceof ShortcutPojo
                || pojo instanceof DisabledAppPojo;
        if (morphLaunch) {
            Runnable launch = () -> result.launch(mainActivity, v, mainActivity);
            if (!LaunchMorphTransition.start(mainActivity, v, launch)) launch.run();
        } else {
            result.fastLaunch(mainActivity, v);
        }
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private boolean onLongClick(View v, Result<?> result) {
        if (UiEditLock.isLocked(mainActivity)) {
            // Locked UI keeps the previous read-only notification-history behavior. Editing menus
            // are deliberately available only after the launcher UI is unlocked.
            if (NotificationHistoryResolver.showForPojo(mainActivity, result.getPojo())) return true;
            UiEditLock.allowEdit(mainActivity);
            return true;
        }

        // Standard and Pixel bars use the same Result popup as History/Search: tags, add/remove
        // favorite, custom icon and every result-specific action are therefore identical.
        ListPopup popup = result.getPopupMenu(mainActivity, mainActivity.adapter, v);
        if (popup.getAdapter() != null && popup.getAdapter().getCount() > 0) {
            mainActivity.registerPopup(popup);
            popup.show(v);
        }
        return true;
    }

    private boolean isExternalFavoriteBarEnabled() {
        return prefs.getBoolean("enable-favorites-bar", true);
    }

    private void addDefaultAppsToFavs() {
        Intent phoneIntent = new Intent(Intent.ACTION_DIAL);
        phoneIntent.setData(Uri.parse("tel:0000"));
        ComponentName componentName = getLaunchingComponent(phoneIntent);
        if (componentName != null) {
            Log.i(TAG, "Dialer resolves to: " + componentName);
            KissApplication.getApplication(mainActivity).getDataHandler().addToFavorites(
                    "app://" + componentName.getPackageName() + "/" + componentName.getClassName());
        }

        Intent contactsIntent =
                new Intent(Intent.ACTION_DEFAULT, ContactsContract.Contacts.CONTENT_URI);
        componentName = getLaunchingComponent(contactsIntent);
        if (componentName != null) {
            Log.i(TAG, "Contacts resolves to: " + componentName);
            KissApplication.getApplication(mainActivity).getDataHandler().addToFavorites(
                    "app://" + componentName.getPackageName() + "/" + componentName.getClassName());
        }

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://"));
        componentName = getLaunchingComponent(browserIntent);
        if (componentName != null) {
            Log.i(TAG, "Browser resolves to: " + componentName);
            KissApplication.getApplication(mainActivity).getDataHandler().addToFavorites(
                    "app://" + componentName.getPackageName() + "/" + componentName.getClassName());
        }
    }

    @Nullable
    private ComponentName getLaunchingComponent(Intent intent) {
        ComponentName componentName = PackageManagerUtils.getComponentName(mainActivity, intent);
        ComponentName launchingComponent =
                PackageManagerUtils.getLaunchingComponent(mainActivity, componentName, UserHandle.OWNER);
        if (launchingComponent != null
                && !launchingComponent.getClassName().equals(DEFAULT_RESOLVER)) {
            return launchingComponent;
        }
        return null;
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        onFavoriteChange();
    }
}
