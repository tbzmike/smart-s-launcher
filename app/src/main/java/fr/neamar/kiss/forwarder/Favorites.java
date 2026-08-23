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
import java.util.List;
import java.util.stream.Collectors;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.NotificationHistoryResolver;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.UserHandle;

public class Favorites extends Forwarder {
    private static final String TAG = Favorites.class.getSimpleName();
    private static final String DEFAULT_RESOLVER = "com.android.internal.app.ResolverActivity";
    private FavoriteAdapter favoriteAdapter;

    private static class ViewHolder extends RecyclerView.ViewHolder {
        private ViewHolder(@NonNull View itemView) { super(itemView); }
        public void inflateFavorite(@NonNull Result<?> item) { item.inflateFavorite(itemView.getContext(), itemView); }
    }

    private static class FavoriteAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<Result<?>> results = new ArrayList<>();
        private OnItemClickListener mOnItemClickListener;
        private OnItemLongClickListener mOnItemLongClickListener;

        public FavoriteAdapter() { super(); setHasStableIds(true); }
        public boolean moveItem(int fromPosition, int toPosition) {
            if (fromPosition == toPosition) return false;
            Result<?> result = results.remove(fromPosition); results.add(toPosition, result); notifyItemMoved(fromPosition, toPosition); return true;
        }
        public void setFavorites(List<Result<?>> results) { this.results.clear(); this.results.addAll(results); notifyDataSetChanged(); }
        public void updateFavoritePositions(Context context) {
            List<Pair<String, Integer>> positions = new ArrayList<>();
            for (int i = 0; i < getItemCount(); i++) positions.add(new Pair<>(getItem(i).getFavoriteId(), i));
            KissApplication.getApplication(context).getDataHandler().setFavoritePositions(positions);
        }
        public interface OnItemClickListener { void onClick(View v, Result<?> result); }
        public interface OnItemLongClickListener { boolean onLongClick(View v, Result<?> result); }
        public void setOnItemClickListener(OnItemClickListener listener) { mOnItemClickListener = listener; }
        public void setOnItemLongClickListener(OnItemLongClickListener listener) { mOnItemLongClickListener = listener; }
        @Override public int getItemViewType(int position) { return Result.getItemViewType(getItem(position)); }
        private Result<?> getItem(int position) { return results.get(position); }

        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = R.layout.favorite_item;
            if (viewType == 6) {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(parent.getContext());
                if (!sharedPreferences.getBoolean("pref-fav-tags-drawable", false)) layout = R.layout.favorite_tag;
            }
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Result<?> result = getItem(position); holder.inflateFavorite(result);
            holder.itemView.setOnClickListener(v -> { if (mOnItemClickListener != null) mOnItemClickListener.onClick(v, result); });
            holder.itemView.setOnLongClickListener(v -> mOnItemLongClickListener == null || mOnItemLongClickListener.onLongClick(v, result));
        }
        @Override public int getItemCount() { return results.size(); }
        @Override public long getItemId(int position) { return getItem(position).getFavoriteId().hashCode(); }
    }

    private class ItemMoveCallback extends ItemTouchHelper.Callback {
        private final FavoriteAdapter mAdapter;
        private boolean moved;
        private ItemMoveCallback(@NonNull FavoriteAdapter adapter) { mAdapter = adapter; }
        @Override public boolean isItemViewSwipeEnabled() { return false; }
        @Override public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) { return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0); }
        @Override public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            Favorites.this.mainActivity.dismissPopup(); Favorites.this.mainActivity.closeContextMenu();
            return mAdapter.moveItem(viewHolder.getAbsoluteAdapterPosition(), target.getAbsoluteAdapterPosition());
        }
        @Override public void onMoved(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, int fromPos, @NonNull RecyclerView.ViewHolder target, int toPos, int x, int y) { super.onMoved(recyclerView, viewHolder, fromPos, target, toPos, x, y); moved = true; }
        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) { }
        @Override public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (actionState == ACTION_STATE_DRAG) {
                if (viewHolder != null && viewHolder.itemView.isLongClickable()) viewHolder.itemView.performLongClick();
            } else if (actionState == ACTION_STATE_IDLE && moved) { mAdapter.updateFavoritePositions(mainActivity); moved = false; }
        }
    }

    Favorites(MainActivity mainActivity) { super(mainActivity); }

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

    public void onFavoriteChange() {
        List<Pojo> favoritesPojo = KissApplication.getApplication(mainActivity).getDataHandler().getFavorites();
        int favSize = favoritesPojo.size();
        RecyclerView favoritesBar = mainActivity.favoritesBar;
        if (favoritesBar.getLayoutManager() instanceof GridLayoutManager) ((GridLayoutManager) favoritesBar.getLayoutManager()).setSpanCount(Math.max(favSize, 1));
        favoriteAdapter.setFavorites(favoritesPojo.stream().map(pojo -> Result.fromPojo(mainActivity, pojo)).collect(Collectors.toList()));
    }

    private void onClick(View v, Result<?> result) { result.fastLaunch(mainActivity, v); v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }

    private boolean onLongClick(View v, Result<?> result) {
        if (NotificationHistoryResolver.showForPojo(mainActivity, result.getPojo())) return true;
        ListPopup popup = result.getPopupMenu(mainActivity, mainActivity.adapter, v);
        mainActivity.registerPopup(popup); popup.show(v); return true;
    }

    private boolean isExternalFavoriteBarEnabled() { return prefs.getBoolean("enable-favorites-bar", true); }

    private void addDefaultAppsToFavs() {
        Intent phoneIntent = new Intent(Intent.ACTION_DIAL); phoneIntent.setData(Uri.parse("tel:0000"));
        ComponentName componentName = getLaunchingComponent(phoneIntent);
        if (componentName != null) { Log.i(TAG, "Dialer resolves to: " + componentName); KissApplication.getApplication(mainActivity).getDataHandler().addToFavorites("app://" + componentName.getPackageName() + "/" + componentName.getClassName()); }

        Intent contactsIntent = new Intent(Intent.ACTION_DEFAULT, ContactsContract.Contacts.CONTENT_URI);
        componentName = getLaunchingComponent(contactsIntent);
        if (componentName != null) { Log.i(TAG, "Contacts resolves to: " + componentName); KissApplication.getApplication(mainActivity).getDataHandler().addToFavorites("app://" + componentName.getPackageName() + "/" + componentName.getClassName()); }

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://"));
        componentName = getLaunchingComponent(browserIntent);
        if (componentName != null) { Log.i(TAG, "Browser resolves to: " + componentName); KissApplication.getApplication(mainActivity).getDataHandler().addToFavorites("app://" + componentName.getPackageName() + "/" + componentName.getClassName()); }
    }

    @Nullable private ComponentName getLaunchingComponent(Intent intent) {
        ComponentName componentName = PackageManagerUtils.getComponentName(mainActivity, intent);
        ComponentName launchingComponent = PackageManagerUtils.getLaunchingComponent(mainActivity, componentName, UserHandle.OWNER);
        if (launchingComponent != null && !launchingComponent.getClassName().equals(DEFAULT_RESOLVER)) return launchingComponent;
        return null;
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) { onFavoriteChange(); }
}
