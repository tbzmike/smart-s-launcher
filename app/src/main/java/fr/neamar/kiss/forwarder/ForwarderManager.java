package fr.neamar.kiss.forwarder;

import android.content.Intent;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fr.neamar.kiss.AppUsageActivity;
import fr.neamar.kiss.BatteryMonitorActivity;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.NotificationHistoryActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.preference.UiEditLock;

public class ForwarderManager extends Forwarder {
    private final Widgets widgetsForwarder;
    private final LiveWallpaper liveWallpaperForwarder;
    private final InterfaceTweaks interfaceTweaks;
    private final ExperienceTweaks experienceTweaks;
    private final LockedHistoryGestureBridge lockedHistoryGestureBridge;
    private final Favorites favoritesForwarder;
    private final OreoShortcuts shortcutsForwarder;
    private final TagsMenu tagsMenu;
    private final Notification notificationForwarder;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final SmartCardListForwarder smartCardListForwarder;
    private final VerticalCardViewportController verticalCardViewportController;
    private final VerticalMapsCardForwarder verticalMapsCardForwarder;
    private final VerticalCardGroupResizeController verticalCardGroupResizeController;
    private final VerticalCardNotificationHistoryForwarder verticalCardNotificationHistoryForwarder;
    private final VerticalCardUsageForwarder verticalCardUsageForwarder;
    private final SquareUHostFullscreenController squareUHostFullscreenController;
    private final SquareUStabilityController squareUStabilityController;
    private final SquareUEdgeBoundsController squareUEdgeBoundsController;
    private final HistoryVisualEnhancer historyVisualEnhancer;
    private final UNotificationHistoryLongPressForwarder uNotificationHistoryLongPressForwarder;
    private final CommunicationHistoryForwarder communicationHistoryForwarder;
    private boolean initialResumeComplete;
    private boolean lastUiEditLocked;
    private String lastSearchQuery;

    public ForwarderManager(MainActivity mainActivity) {
        super(mainActivity);

        this.widgetsForwarder = new WorkspaceWidgets(mainActivity);
        this.interfaceTweaks = new InterfaceTweaks(mainActivity);
        this.liveWallpaperForwarder = new LiveWallpaper(mainActivity);
        this.experienceTweaks = new ExperienceTweaks(mainActivity);
        this.lockedHistoryGestureBridge = new LockedHistoryGestureBridge(mainActivity, experienceTweaks);
        this.favoritesForwarder = new Favorites(mainActivity);
        this.shortcutsForwarder = new OreoShortcuts(mainActivity);
        this.notificationForwarder = new Notification(mainActivity);
        this.tagsMenu = new TagsMenu(mainActivity);
        this.historyDisplayForwarder = new HistoryDisplayForwarder(mainActivity);
        this.smartCardListForwarder = new SmartCardListForwarder(mainActivity);
        this.verticalCardViewportController = new VerticalCardViewportController(mainActivity, smartCardListForwarder);
        this.verticalMapsCardForwarder = new VerticalMapsCardForwarder(mainActivity, smartCardListForwarder);
        this.verticalCardGroupResizeController = new VerticalCardGroupResizeController(
                mainActivity, smartCardListForwarder, verticalCardViewportController);
        this.verticalCardNotificationHistoryForwarder = new VerticalCardNotificationHistoryForwarder(mainActivity, smartCardListForwarder);
        this.verticalCardUsageForwarder = new VerticalCardUsageForwarder(
                mainActivity, smartCardListForwarder, verticalCardViewportController);
        this.squareUHostFullscreenController = new SquareUHostFullscreenController(mainActivity, historyDisplayForwarder);
        this.squareUStabilityController = new SquareUStabilityController(mainActivity, historyDisplayForwarder);
        this.squareUEdgeBoundsController = new SquareUEdgeBoundsController(mainActivity, historyDisplayForwarder);
        this.historyVisualEnhancer = new HistoryVisualEnhancer(mainActivity, historyDisplayForwarder);
        this.uNotificationHistoryLongPressForwarder = new UNotificationHistoryLongPressForwarder(mainActivity, historyDisplayForwarder);
        this.communicationHistoryForwarder = new CommunicationHistoryForwarder(mainActivity);
    }

    public void onCreate() {
        UiEditLock.syncRuntimeState(mainActivity);
        lastUiEditLocked = UiEditLock.isLocked(mainActivity);
        favoritesForwarder.onCreate();
        widgetsForwarder.onCreate();
        interfaceTweaks.onCreate();
        experienceTweaks.onCreate();
        shortcutsForwarder.onCreate();
        tagsMenu.onCreate();
        historyDisplayForwarder.onCreate();
        squareUHostFullscreenController.onCreate();
        smartCardListForwarder.onCreate();
        verticalCardViewportController.onCreate();
        verticalMapsCardForwarder.onCreate();
        verticalCardGroupResizeController.onCreate();
        verticalCardNotificationHistoryForwarder.onCreate();
        verticalCardUsageForwarder.onCreate();
        squareUStabilityController.onCreate();
        squareUEdgeBoundsController.onCreate();
        historyVisualEnhancer.onCreate();
        uNotificationHistoryLongPressForwarder.onCreate();
        lockedHistoryGestureBridge.onCreate();
    }

    public void onStart() { widgetsForwarder.onStart(); }

    public void onResume() {
        UiEditLock.syncRuntimeState(mainActivity);
        boolean uiEditLocked = UiEditLock.isLocked(mainActivity);
        boolean uiEditLockChanged = uiEditLocked != lastUiEditLocked;
        lastUiEditLocked = uiEditLocked;

        // These two listeners are explicitly unregistered in onPause and therefore must be restored.
        experienceTweaks.onResume();
        notificationForwarder.onResume();

        if (initialResumeComplete) {
            // If the UI-lock preference changed while Settings was in front, only re-sync the
            // resize controller; this updates the edit handle without rebuilding cards/history.
            if (uiEditLockChanged) verticalCardGroupResizeController.onResume();

            // Usage time is external state that changed while another app was foreground, so
            // refresh only that metadata asynchronously without rebuilding cards/history. Its
            // content-height mutation is protected by VerticalCardViewportController.
            verticalCardUsageForwarder.onResume();
            return;
        }

        interfaceTweaks.onResume();
        lockedHistoryGestureBridge.onResume();
        tagsMenu.onResume();
        communicationHistoryForwarder.onResume();
        historyDisplayForwarder.onResume();
        squareUHostFullscreenController.onResume();
        verticalCardViewportController.beforeDataSetChanged();
        smartCardListForwarder.onResume();
        verticalMapsCardForwarder.onResume();
        verticalCardGroupResizeController.onResume();
        verticalCardNotificationHistoryForwarder.onResume();
        // The usage label must be applied after SmartCardListForwarder has rebuilt the initial
        // Vertical Cards and after notification/launch metadata has been attached.
        verticalCardUsageForwarder.onResume();
        squareUStabilityController.onResume();
        squareUEdgeBoundsController.onResume();
        historyVisualEnhancer.onResume();
        uNotificationHistoryLongPressForwarder.onResume();
        verticalCardViewportController.afterDataSetChanged();
        initialResumeComplete = true;
    }

    public void onPause() {
        experienceTweaks.onPause();
        notificationForwarder.onPause();
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) { widgetsForwarder.onActivityResult(requestCode, resultCode, data); }

    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        // Monitoring/history screens are read-only launcher tools. Keep them available even while
        // the home interface is locked, and do not route them through widget/edit gesture logic.
        if (itemId == R.id.app_usage) {
            mainActivity.startActivity(new Intent(mainActivity, AppUsageActivity.class));
            return true;
        }
        if (itemId == R.id.notification_history) {
            mainActivity.startActivity(new Intent(mainActivity, NotificationHistoryActivity.class));
            return true;
        }
        if (itemId == R.id.battery_monitor) {
            mainActivity.startActivity(new Intent(mainActivity, BatteryMonitorActivity.class));
            return true;
        }

        if (UiEditLock.isLocked(mainActivity)) {
            if (itemId == R.id.preferences || itemId == R.id.settings) return false;
            if (itemId == R.id.add_widget || itemId == R.id.wallpaper) { UiEditLock.allowEdit(mainActivity); return true; }
            return widgetsForwarder.onOptionsItemSelected(item);
        }
        return widgetsForwarder.onOptionsItemSelected(item);
    }

    public void onCreateContextMenu(ContextMenu menu) { if (!UiEditLock.isLocked(mainActivity)) widgetsForwarder.onCreateContextMenu(menu); }

    public boolean onTouch(View view, MotionEvent event) { experienceTweaks.onTouch(event); return liveWallpaperForwarder.onTouch(view, event); }

    public void onDataSetChanged() {
        widgetsForwarder.onDataSetChanged();
        squareUHostFullscreenController.onDataSetChanged();
        historyDisplayForwarder.onDataSetChanged();

        // SmartCardListForwarder historically full-scrolled every rebuild. Capture the viewport
        // immediately before that rebuild and restore it only after all card decorators are queued.
        verticalCardViewportController.beforeDataSetChanged();
        smartCardListForwarder.onDataSetChanged();
        verticalMapsCardForwarder.onDataSetChanged();
        verticalCardGroupResizeController.onDataSetChanged();
        verticalCardNotificationHistoryForwarder.onDataSetChanged();
        verticalCardUsageForwarder.onDataSetChanged();
        squareUStabilityController.onDataSetChanged();
        squareUEdgeBoundsController.onDataSetChanged();
        historyVisualEnhancer.onDataSetChanged();
        uNotificationHistoryLongPressForwarder.onDataSetChanged();
        lockedHistoryGestureBridge.onDataSetChanged();
        verticalCardViewportController.afterDataSetChanged();
    }

    public void updateSearchRecords(String query) {
        String normalized = query == null ? "" : query;
        boolean sameQuery = TextUtils.equals(lastSearchQuery, normalized);
        verticalCardViewportController.onSearchQueryChanged(
                !TextUtils.isEmpty(normalized), !sameQuery);
        if (initialResumeComplete && !mainActivity.hasWindowFocus() && sameQuery) {
            // MainActivity calls updateSearchRecords() from onResume. On a normal back/unlock
            // return the query has not changed, so keep the current adapter and scroll position.
            return;
        }

        lastSearchQuery = normalized;
        experienceTweaks.updateSearchRecords(query);
    }

    /** Route the exact Android HOME intent directly to the viewport owner. */
    public void onNewIntent(@NonNull Intent intent) {
        if (isHomeIntent(intent)) verticalCardViewportController.onHomeIntent();
    }

    public void onFavoriteChange() { favoritesForwarder.onFavoriteChange(); experienceTweaks.onFavoriteChange(); }
    public void onDisplayKissBar(boolean display) { experienceTweaks.onDisplayKissBar(display); }
    public boolean onMenuButtonClicked(View menuButton) { return tagsMenu.onMenuButtonClicked(menuButton); }

    public void onDestroy() {
        verticalCardViewportController.onDestroy();
        verticalCardUsageForwarder.onDestroy();
        uNotificationHistoryLongPressForwarder.onDestroy();
        lockedHistoryGestureBridge.onDestroy();
        verticalCardNotificationHistoryForwarder.onDestroy();
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
        verticalCardNotificationHistoryForwarder.onConfigurationChanged();
        verticalCardViewportController.onConfigurationChanged();
        verticalCardUsageForwarder.onConfigurationChanged();
        squareUStabilityController.onConfigurationChanged();
        squareUEdgeBoundsController.onConfigurationChanged();
        uNotificationHistoryLongPressForwarder.onConfigurationChanged();
        lockedHistoryGestureBridge.onResume();
    }

    private static boolean isHomeIntent(@Nullable Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
    }
}
