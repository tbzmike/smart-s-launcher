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
    private final SquareUInteractionController squareUInteractionController;
    private final HistoryVisualEnhancer historyVisualEnhancer;
    private final SmartUFoundationForwarder smartUFoundationForwarder;
    private final SmartUIntelligenceForwarder smartUIntelligenceForwarder;
    private final SmartUFinalPolishForwarder smartUFinalPolishForwarder;

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
        this.squareUInteractionController = new SquareUInteractionController(
                mainActivity, historyDisplayForwarder);
        this.historyVisualEnhancer = new HistoryVisualEnhancer(
                mainActivity, historyDisplayForwarder);
        this.smartUFoundationForwarder = new SmartUFoundationForwarder(
                mainActivity, historyDisplayForwarder);
        this.smartUIntelligenceForwarder = new SmartUIntelligenceForwarder(
                mainActivity, historyDisplayForwarder);
        this.smartUFinalPolishForwarder = new SmartUFinalPolishForwarder(
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
        smartCardListForwarder.onCreate();
        squareUInteractionController.onCreate();
        historyVisualEnhancer.onCreate();
        smartUFoundationForwarder.onCreate();
        smartUIntelligenceForwarder.onCreate();
        smartUFinalPolishForwarder.onCreate();
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
        smartCardListForwarder.onResume();
        squareUInteractionController.onResume();
        historyVisualEnhancer.onResume();
        smartUFoundationForwarder.onResume();
        smartUIntelligenceForwarder.onResume();
        smartUFinalPolishForwarder.onResume();
    }

    public void onPause() {
        smartUFinalPolishForwarder.onPause();
        smartUFoundationForwarder.onPause();
        squareUInteractionController.onPause();
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
        historyDisplayForwarder.onDataSetChanged();
        smartCardListForwarder.onDataSetChanged();
        squareUInteractionController.onDataSetChanged();
        historyVisualEnhancer.onDataSetChanged();
        smartUFoundationForwarder.onDataSetChanged();
        smartUIntelligenceForwarder.onDataSetChanged();
        smartUFinalPolishForwarder.onDataSetChanged();
    }

    public void updateSearchRecords(String query) {
        experienceTweaks.updateSearchRecords(query);
    }

    public void onFavoriteChange() {
        favoritesForwarder.onFavoriteChange();
        experienceTweaks.onFavoriteChange();
        smartUIntelligenceForwarder.onFavoriteChange();
    }

    public void onDisplayKissBar(boolean display) {
        experienceTweaks.onDisplayKissBar(display);
    }

    public boolean onMenuButtonClicked(View menuButton) {
        return tagsMenu.onMenuButtonClicked(menuButton);
    }

    public void onDestroy() {
        smartUFinalPolishForwarder.onDestroy();
        smartUIntelligenceForwarder.onDestroy();
        smartUFoundationForwarder.onDestroy();
        squareUInteractionController.onDestroy();
        widgetsForwarder.onDestroy();
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        interfaceTweaks.onConfigurationChanged(newConfig);
        favoritesForwarder.onConfigurationChanged(newConfig);
        smartUIntelligenceForwarder.onConfigurationChanged();
        smartUFinalPolishForwarder.onConfigurationChanged();
    }
}
