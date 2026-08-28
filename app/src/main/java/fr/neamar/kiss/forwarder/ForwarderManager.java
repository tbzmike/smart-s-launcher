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
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

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
    private final WidgetPeelController widgetPeelController;
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
        this.widgetPeelController = new WidgetPeelController(mainActivity);
    }

    public void onCreate() {
        UiEditLock.syncRuntimeState(mainActivity);
        lastUiEditLocked = UiEditLock.isLocked(mainActivity);
        favoritesForwarder.onCreate();
        widgetsForwarder.onCreate();
        widgetPeelController.onCreate();
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
        uNotificationHistoryLongPressForwarder.onCreate();
        lockedHistoryGestureBridge.onCreate();
    }

    public void onStart() { widgetsForwarder.onStart(); }

    public void onResume() {
        UiEditLock.syncRuntimeState(mainActivity);
        boolean uiEditLocked = UiEditLock.isLocked(mainActivity);
        boolean uiEditLockChanged = uiEditLocked != lastUiEditLocked;
        lastUiEditLocked = uiEditLocked;
        boolean verticalCards = isVerticalCardsMode();
        boolean square = isSquareMode();

        if (verticalCards) {
            // Only the selected Vertical Cards renderer owns this persisted viewport state.
            verticalCardViewportController.onLauncherResumed();
        }

        // These two listeners are explicitly unregistered in onPause and therefore must be restored.
        experienceTweaks.onResume();
        notificationForwarder.onResume();

        if (initialResumeComplete) {
            if (verticalCards && uiEditLockChanged) verticalCardGroupResizeController.onResume();
            if (verticalCards) verticalCardUsageForwarder.onResume();
            return;
        }

        interfaceTweaks.onResume();
        lockedHistoryGestureBridge.onResume();
        tagsMenu.onResume();
        communicationHistoryForwarder.onResume();
        historyDisplayForwarder.onResume();

        if (verticalCards) {
            verticalCardViewportController.beforeDataSetChanged();
            smartCardListForwarder.onResume();
            verticalMapsCardForwarder.onResume();
            verticalCardGroupResizeController.onResume();
            verticalCardNotificationHistoryForwarder.onResume();
            verticalCardUsageForwarder.onResume();
            verticalCardViewportController.afterDataSetChanged();
        } else if (square) {
            squareUHostFullscreenController.onResume();
            squareUStabilityController.onResume();
            squareUEdgeBoundsController.onResume();
            uNotificationHistoryLongPressForwarder.onResume();
        }

        if (isHistorySearch()) historyVisualEnhancer.onResume();
        initialResumeComplete = true;
    }

    public void onPause() {
        if (isVerticalCardsMode()) {
            // Capture only when Vertical Cards actually owns the visible history viewport.
            verticalCardViewportController.onLauncherPaused();
        }
        experienceTweaks.onPause();
        notificationForwarder.onPause();
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) { widgetsForwarder.onActivityResult(requestCode, resultCode, data); }

    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
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
        if (itemId == R.id.wallpaper) {
            mainActivity.hideKeyboard();
            WallpaperChooser.show(mainActivity);
            return true;
        }
        return widgetsForwarder.onOptionsItemSelected(item);
    }

    public void onCreateContextMenu(ContextMenu menu) { if (!UiEditLock.isLocked(mainActivity)) widgetsForwarder.onCreateContextMenu(menu); }

    public boolean onTouch(View view, MotionEvent event) { experienceTweaks.onTouch(event); return liveWallpaperForwarder.onTouch(view, event); }

    public void onDataSetChanged() {
        widgetsForwarder.onDataSetChanged();
        widgetPeelController.onDataSetChanged();
        historyDisplayForwarder.onDataSetChanged();

        if (isVerticalCardsMode()) {
            verticalCardViewportController.beforeDataSetChanged();
            smartCardListForwarder.onDataSetChanged();
            verticalMapsCardForwarder.onDataSetChanged();
            verticalCardGroupResizeController.onDataSetChanged();
            verticalCardNotificationHistoryForwarder.onDataSetChanged();
            verticalCardUsageForwarder.onDataSetChanged();
            verticalCardViewportController.afterDataSetChanged();
        } else if (isSquareMode()) {
            squareUHostFullscreenController.onDataSetChanged();
            squareUStabilityController.onDataSetChanged();
            squareUEdgeBoundsController.onDataSetChanged();
            uNotificationHistoryLongPressForwarder.onDataSetChanged();
        }

        // Launch-stat/live-card enrichment is history decoration. Never run its database/live-data
        // pipeline for ordinary query results, where it only competes with search and scrolling.
        if (isHistorySearch()) historyVisualEnhancer.onDataSetChanged();
        lockedHistoryGestureBridge.onDataSetChanged();
    }

    public void updateSearchRecords(String query) {
        String normalized = query == null ? "" : query;
        boolean sameQuery = TextUtils.equals(lastSearchQuery, normalized);
        if (isVerticalCardsMode()) {
            verticalCardViewportController.onSearchQueryChanged(
                    !TextUtils.isEmpty(normalized), !sameQuery);
        }
        if (HistoryRefreshPolicy.shouldSkip(initialResumeComplete, sameQuery, isHistorySearch())) {
            return;
        }

        lastSearchQuery = normalized;
        experienceTweaks.updateSearchRecords(query);
    }

    /** Route the exact Android HOME intent with verified foreground/background lifecycle state. */
    public void onNewIntent(@NonNull Intent intent, boolean launcherWasForeground) {
        if (isVerticalCardsMode() && isHomeIntent(intent)) {
            verticalCardViewportController.onHomeIntent(launcherWasForeground);
        }
    }

    public void onFavoriteChange() { favoritesForwarder.onFavoriteChange(); experienceTweaks.onFavoriteChange(); }
    public void onDisplayKissBar(boolean display) { experienceTweaks.onDisplayKissBar(display); }
    public boolean onMenuButtonClicked(View menuButton) { return tagsMenu.onMenuButtonClicked(menuButton); }

    public void onDestroy() {
        liveWallpaperForwarder.onDestroy();
        widgetPeelController.onDestroy();
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
        smartCardListForwarder.onDestroy();
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        widgetPeelController.onConfigurationChanged();
        interfaceTweaks.onConfigurationChanged(newConfig);
        favoritesForwarder.onConfigurationChanged(newConfig);
        if (isVerticalCardsMode()) {
            verticalCardGroupResizeController.onConfigurationChanged();
            verticalCardNotificationHistoryForwarder.onConfigurationChanged();
            verticalCardViewportController.onConfigurationChanged();
            verticalCardUsageForwarder.onConfigurationChanged();
        } else if (isSquareMode()) {
            squareUHostFullscreenController.onConfigurationChanged();
            squareUStabilityController.onConfigurationChanged();
            squareUEdgeBoundsController.onConfigurationChanged();
            uNotificationHistoryLongPressForwarder.onConfigurationChanged();
        }
        lockedHistoryGestureBridge.onResume();
    }

    private String activeHistoryLayout() {
        String layout = prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT,
                HistoryDisplayForwarder.VERTICAL);
        return layout == null ? HistoryDisplayForwarder.VERTICAL : layout;
    }

    private boolean isVerticalCardsMode() {
        return HistoryDisplayForwarder.VERTICAL_CARDS.equals(activeHistoryLayout());
    }

    private boolean isSquareMode() {
        return HistoryDisplayForwarder.SQUARE_U.equals(activeHistoryLayout());
    }

    private boolean isHistorySearch() {
        return SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY;
    }

    private static boolean isHomeIntent(@Nullable Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
    }
}
