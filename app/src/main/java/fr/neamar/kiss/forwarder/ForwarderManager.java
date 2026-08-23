package fr.neamar.kiss.forwarder;

import android.content.Intent;
import android.content.res.Configuration;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.preference.UiEditLock;

public class ForwarderManager extends Forwarder {
    private final Widgets widgetsForwarder;
    private final LiveWallpaper liveWallpaperForwarder;
    private final InterfaceTweaks interfaceTweaks;
    private final ExperienceTweaks experienceTweaks;
    private final Favorites favoritesForwarder;
    private final OreoShortcuts shortcutsForwarder;
    private final TagsMenu tagsMenu;
    private final Notification notificationForwarder;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SmartCardListForwarder smartCardListForwarder;
    private final VerticalCardGroupResizeController verticalCardGroupResizeController;
    private final SquareUHostFullscreenController squareUHostFullscreenController;
    private final SquareUStabilityController squareUStabilityController;
    private final SquareUEdgeBoundsController squareUEdgeBoundsController;
    private final HistoryVisualEnhancer historyVisualEnhancer;

    public ForwarderManager(MainActivity mainActivity) {
        super(mainActivity);

        this.widgetsForwarder = new WorkspaceWidgets(mainActivity);
        this.interfaceTweaks = new InterfaceTweaks(mainActivity);
        this.liveWallpaperForwarder = new LiveWallpaper(mainActivity);
        this.experienceTweaks = new ExperienceTweaks(mainActivity);
        this.favoritesForwarder = new Favorites(mainActivity);
        this.shortcutsForwarder = new OreoShortcuts(mainActivity);
        this.notificationForwarder = new Notification(mainActivity);
        this.tagsMenu = new TagsMenu(mainActivity);
        this.historyDisplayForwarder = new HistoryDisplayForwarder(mainActivity);
        this.smartCardListForwarder = new SmartCardListForwarder(mainActivity);
        this.verticalCardGroupResizeController = new VerticalCardGroupResizeController(
                mainActivity, smartCardListForwarder);
        this.squareUHostFullscreenController = new SquareUHostFullscreenController(
                mainActivity, historyDisplayForwarder);
        this.squareUStabilityController = new SquareUStabilityController(
                mainActivity, historyDisplayForwarder);
        this.squareUEdgeBoundsController = new SquareUEdgeBoundsController(
                mainActivity, historyDisplayForwarder);
        this.historyVisualEnhancer = new HistoryVisualEnhancer(
                mainActivity, historyDisplayForwarder);
    }

    public void onCreate() {
        UiEditLock.syncRuntimeState(mainActivity);
        favoritesForwarder.onCreate();
        widgetsForwarder.onCreate();
        interfaceTweaks.onCreate();
        experienceTweaks.onCreate();
        shortcutsForwarder.onCreate();
        tagsMenu.onCreate();
        historyDisplayForwarder.onCreate();
        squareUHostFullscreenController.onCreate();
        smartCardListForwarder.onCreate();
        verticalCardGroupResizeController.onCreate();
        squareUStabilityController.onCreate();
        squareUEdgeBoundsController.onCreate();
        historyVisualEnhancer.onCreate();
    }

    public void onStart() {
        widgetsForwarder.onStart();
    }

    public void onResume() {
        UiEditLock.syncRuntimeState(mainActivity);
        interfaceTweaks.onResume();
        experienceTweaks.onResume();
        notificationForwarder.onResume();
        tagsMenu.onResume();
        historyDisplayForwarder.onResume();
        squareUHostFullscreenController.onResume();
        smartCardListForwarder.onResume();
        verticalCardGroupResizeController.onResume();
        squareUStabilityController.onResume();
        squareUEdgeBoundsController.onResume();
        historyVisualEnhancer.onResume();
    }

    public void onPause() {
        verticalCardGroupResizeController.onPause();
        squareUEdgeBoundsController.onPause();
        squareUStabilityController.onPause();
        squareUHostFullscreenController.onPause();
        experienceTweaks.onPause();
        notificationForwarder.onPause();
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        widgetsForwarder.onActivityResult(requestCode, resultCode, data);
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (UiEditLock.isLocked(mainActivity)) {
            int itemId = item.getItemId();
            if (itemId == R.id.preferences || itemId == R.id.settings) {
                return false;
            }
            if (itemId == R.id.add_widget || itemId == R.id.wallpaper) {
                UiEditLock.allowEdit(mainActivity);
                return true;
            }
            return widgetsForwarder.onOptionsItemSelected(item);
        }
        return widgetsForwarder.onOptionsItemSelected(item);
    }

    public void onCreateContextMenu(ContextMenu menu) {
        if (UiEditLock.isLocked(mainActivity)) return;
        widgetsForwarder.onCreateContextMenu(menu);
    }

    public boolean onTouch(View view, MotionEvent event) {
        experienceTweaks.onTouch(event);
        return liveWallpaperForwarder.onTouch(view, event);
    }

    public void onDataSetChanged() {
        widgetsForwarder.onDataSetChanged();
        squareUHostFullscreenController.onDataSetChanged();
        historyDisplayForwarder.onDataSetChanged();
        smartCardListForwarder.onDataSetChanged();
        verticalCardGroupResizeController.onDataSetChanged();
        squareUStabilityController.onDataSetChanged();
        squareUEdgeBoundsController.onDataSetChanged();
        historyVisualEnhancer.onDataSetChanged();
    }

    public void updateSearchRecords(String query) {
        experienceTweaks.updateSearchRecords(query);
    }

    public void onFavoriteChange() {
        favoritesForwarder.onFavoriteChange();
        experienceTweaks.onFavoriteChange();
    }

    public void onDisplayKissBar(boolean display) {
        experienceTweaks.onDisplayKissBar(display);
    }

    public boolean onMenuButtonClicked(View menuButton) {
        return tagsMenu.onMenuButtonClicked(menuButton);
    }

    public void onDestroy() {
        verticalCardGroupResizeController.onDestroy();
        squareUEdgeBoundsController.onDestroy();
        squareUStabilityController.onDestroy();
        squareUHostFullscreenController.onDestroy();
        widgetsForwarder.onDestroy();
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        interfaceTweaks.onConfigurationChanged(newConfig);
        favoritesForwarder.onConfigurationChanged(newConfig);
        squareUHostFullscreenController.onConfigurationChanged();
        verticalCardGroupResizeController.onConfigurationChanged();
        squareUStabilityController.onConfigurationChanged();
        squareUEdgeBoundsController.onConfigurationChanged();
    }
}
