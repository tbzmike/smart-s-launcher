package fr.neamar.kiss.forwarder;

import static androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG;
import static androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_IDLE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
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

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ValuedHistoryRecord;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.LaunchMorphTransition;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.NotificationHistoryResolver;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.UserHandle;

public class Favorites extends Forwarder {
    private static final String TAG = Favorites.class.getSimpleName();
    private static final String DEFAULT_RESOLVER = "com.android.internal.app.ResolverActivity";

    static final String PREF_BAR_MODE = "favorites-bar-mode";
    static final String MODE_STANDARD = "standard";
    static final String MODE_PIXEL = "pixel";
    static final String PREF_PIXEL_MAX_APPS = "pixel-favorites-max-apps";

    private FavoriteAdapter favoriteAdapter;
    private String lastRenderedMode;
    private int lastPixelLimit = -1;

    private static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(@NonNull View itemView) { super(itemView); }
        public void inflateFavorite(@NonNull Result<?> item) {
            item.inflateFavorite(itemView.getContext(), itemView);
        }
    }

    private static class FavoriteAdapter extends RecyclerView.Adapter<ViewHolder> {
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

        public interface OnItemClickListener {
            void onClick(View v, Result<?> result);
        }

        public interface OnItemLongClickListener {
            boolean onLongClick(View v, Result<?> result);
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
            holder.itemView.setOnClickListener(v -> {
                if (mOnItemClickListener != null) mOnItemClickListener.onClick(v, result);
            });
            holder.itemView.setOnLongClickListener(v ->
                    mOnItemLongClickListener == null || mOnItemLongClickListener.onLongClick(v, result));
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
            if (actionState == ACTION_STATE_DRAG) {
                if (viewHolder != null && viewHolder.itemView.isLongClickable()) {
                    viewHolder.itemView.performLongClick();
                }
            } else if (actionState == ACTION_STATE_IDLE && moved) {
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
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemMoveCallback(favoriteAdapter));
        touchHelper.attachToRecyclerView(mainActivity.favoritesBar);
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
        List<Pojo> pinnedFavorites = dataHandler.getFavorites();
        String mode = currentMode();

        List<Pojo> displayed;
        if (MODE_PIXEL.equals(mode)) {
            displayed = buildPixelFavorites(dataHandler, pinnedFavorites, getPixelLimit());
        } else {
            // Legacy behavior: no cap, no usage query, no predictor.
            displayed = pinnedFavorites;
        }

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
        lastPixelLimit = getPixelLimit();
    }

    private List<Pojo> buildPixelFavorites(DataHandler dataHandler,
                                           List<Pojo> pinnedFavorites,
                                           int requestedLimit) {
        int max = PixelFavoritesPolicy.clampMaxApps(requestedLimit);

        List<String> pinnedIds = new ArrayList<>(pinnedFavorites.size());
        Map<String, Pojo> pojoById = new HashMap<>();
        for (Pojo favorite : pinnedFavorites) {
            if (favorite == null) continue;
            String id = favorite.getFavoriteId();
            if (id == null || id.isEmpty()) continue;
            pinnedIds.add(id);
            pojoById.put(id, favorite);
        }

        // If pinned favorites already fill every Pixel slot, there is nothing to predict and no
        // history query is executed at all.
        List<String> rankedIds = new ArrayList<>();
        if (pinnedIds.size() < max) {
            List<AppPojo> availableApps = dataHandler.getApplicationsWithoutExcluded();
            Map<String, AppPojo> appsById = new HashMap<>();
            if (availableApps != null) {
                for (AppPojo app : availableApps) {
                    if (app == null || app.isDisabled()) continue;
                    appsById.put(app.getFavoriteId(), app);
                }
            }

            int scanLimit = Math.min(80, Math.max(24, max * 6));
            List<ValuedHistoryRecord> mostUsed =
                    DBHelper.getMostUsedAppHistory(mainActivity, scanLimit);
            for (ValuedHistoryRecord record : mostUsed) {
                if (record == null || record.record == null) continue;
                AppPojo app = appsById.get(record.record);
                if (app == null) continue;
                rankedIds.add(app.getFavoriteId());
                pojoById.put(app.getFavoriteId(), app);
            }
        }

        List<String> selectedIds = PixelFavoritesPolicy.select(pinnedIds, rankedIds, max);
        List<Pojo> selected = new ArrayList<>(selectedIds.size());
        for (String id : selectedIds) {
            Pojo pojo = pojoById.get(id);
            if (pojo != null) selected.add(pojo);
        }
        return selected;
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
        if (NotificationHistoryResolver.showForPojo(mainActivity, result.getPojo())) return true;
        ListPopup popup = result.getPopupMenu(mainActivity, mainActivity.adapter, v);
        mainActivity.registerPopup(popup);
        popup.show(v);
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
