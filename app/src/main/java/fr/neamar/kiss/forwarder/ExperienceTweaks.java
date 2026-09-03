package fr.neamar.kiss.forwarder;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.NotificationHistoryActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.ui.KeyboardManager;
import fr.neamar.kiss.utils.LockAccessibilityService;
import fr.neamar.kiss.utils.Log;

public class ExperienceTweaks extends Forwarder {
    private final static int INPUT_TYPE_STANDARD = InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    private final static int INPUT_TYPE_WORKAROUND = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
    private static final String TAG = ExperienceTweaks.class.getSimpleName();

    private View mainEmptyView;
    private final GestureDetector gd;
    private final KeyboardManager keyboardManager;

    ExperienceTweaks(final MainActivity mainActivity) {
        super(mainActivity);
        setRequestedOrientation(mainActivity, prefs);

        gd = new GestureDetector(mainActivity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (!prefs.getBoolean("double-tap", false)) {
                    if (prefs.getBoolean("history-onclick", false)) {
                        doAction("single-tap", "display-history");
                    } else if (isMinimalisticModeEnabledForFavorites()) {
                        doAction("single-tap", "display-favorites");
                    }
                }
                return super.onSingleTapUp(e);
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (prefs.getBoolean("double-tap", false)) {
                    if (prefs.getBoolean("history-onclick", false)) {
                        doAction("single-tap", "display-history");
                    } else if (isMinimalisticModeEnabledForFavorites()) {
                        doAction("single-tap", "display-favorites");
                    }
                }
                return super.onSingleTapConfirmed(e);
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                doAction("gesture-long-press", prefs.getString("gesture-long-press", "do-nothing"));
                super.onLongPress(e);
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return super.onDoubleTap(e);
                if (!prefs.getBoolean("double-tap", false)) return super.onDoubleTap(e);

                if (isAccessibilityServiceEnabled(mainActivity)) {
                    Intent intent = new Intent(LockAccessibilityService.ACTION_LOCK, null, mainActivity, LockAccessibilityService.class);
                    mainActivity.startService(intent);
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(mainActivity);
                    builder.setMessage(R.string.enable_double_tap_to_lock);
                    builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mainActivity.startActivity(intent);
                    });
                    builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());
                    builder.create().show();
                }
                return super.onDoubleTap(e);
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null) return false;
                float directionY = e2.getY() - e1.getY();
                float directionX = e2.getX() - e1.getX();
                if (Math.abs(directionX) > Math.abs(directionY)) {
                    if (directionX > 0) doAction("gesture-right", prefs.getString("gesture-right", "display-apps"));
                    else doAction("gesture-left", prefs.getString("gesture-left", "display-apps"));
                } else {
                    if (directionY > 0) doAction("gesture-down", prefs.getString("gesture-down", "display-notifications"));
                    else doAction("gesture-up", prefs.getString("gesture-up", "display-keyboard"));
                }
                return true;
            }

            private void doAction(String source, String action) {
                switch (action) {
                    case "display-notifications":
                        displayNotificationDrawer();
                        break;
                    case "display-notification-history":
                        mainActivity.startActivity(new Intent(mainActivity, NotificationHistoryActivity.class));
                        break;
                    case "display-quicksettings":
                        displayQuickSettings();
                        break;
                    case "display-keyboard":
                        mainActivity.showKeyboard();
                        break;
                    case "hide-keyboard":
                        mainActivity.hideKeyboard();
                        break;
                    case "display-apps":
                        if (mainActivity.isViewingSearchResults()) mainActivity.displayKissBar(true);
                        break;
                    case "display-history":
                        if (isMinimalisticModeEnabled()) {
                            if (mainActivity.isViewingSearchResults() && TextUtils.isEmpty(mainActivity.searchEditText.getText())) {
                                if (mainActivity.adapter == null || mainActivity.adapter.isEmpty()) mainActivity.showHistory();
                            }
                        }
                        if (isMinimalisticModeEnabledForFavorites()) mainActivity.setFavoritesBarVisible(true);
                        break;
                    case "display-favorites":
                        mainActivity.setFavoritesBarVisible(true);
                        break;
                    case "display-menu":
                        mainActivity.openContextMenu(mainActivity.menuButton);
                        break;
                    case "go-to-homescreen":
                        mainActivity.displayKissBar(false);
                        if (!shouldShowKeyboard()) mainActivity.hideKeyboard();
                        break;
                    case "launch-pojo": {
                        String launchId = prefs.getString(source + "-launch-id", "");
                        Pojo item = KissApplication.getApplication(mainActivity).getDataHandler().getItemById(launchId);
                        if (item != null) {
                            Result<?> result = Result.fromPojo(mainActivity, item);
                            result.fastLaunch(mainActivity, ExperienceTweaks.this.mainEmptyView);
                        }
                        break;
                    }
                }
            }

            private boolean isAccessibilityServiceEnabled(Context context) {
                AccessibilityManager am = ContextCompat.getSystemService(context, AccessibilityManager.class);
                if (am == null) return false;
                List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                for (AccessibilityServiceInfo enabledService : enabledServices) {
                    ServiceInfo enabledServiceInfo = enabledService.getResolveInfo().serviceInfo;
                    if (enabledServiceInfo.packageName.equals(context.getPackageName())
                            && enabledServiceInfo.name.equals(LockAccessibilityService.class.getName())) return true;
                }
                return false;
            }
        });

        keyboardManager = new KeyboardManager();
    }

    void onCreate() {
        mainEmptyView = mainActivity.findViewById(R.id.main_empty);
    }

    void onResume() {
        keyboardManager.registerKeyboardListener(
                mainActivity.findViewById(android.R.id.content),
                shouldShowKeyboard(),
                this::onKeyboardVisibilityChanged);
        adjustInputType();
        if (shouldShowKeyboard()) {
            mainActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            mainActivity.showKeyboard();
        } else {
            mainActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            mainActivity.hideKeyboard();
        }

        if (isMinimalisticModeEnabled()) {
            mainEmptyView.setVisibility(View.GONE);
            mainActivity.list.setVerticalScrollBarEnabled(false);
            mainActivity.searchEditText.setHint("");
        }
        if (prefs.getBoolean("pref-hide-circle", false)) {
            ((ImageView) mainActivity.launcherButton).setImageBitmap(null);
            mainActivity.menuButton.setImageBitmap(null);
        }
    }

    /** Keep the newest/highest-priority history result anchored above each real IME resize. */
    private void onKeyboardVisibilityChanged(boolean keyboardIsVisible) {
        mainActivity.onKeyboardVisibilityChanged(keyboardIsVisible);
        VerticalCardKeyboardAnchor.onKeyboardVisibilityChanged(mainActivity, keyboardIsVisible);
        if (!keyboardIsVisible) return;

        // Vertical Cards own IME geometry through VerticalCardViewportController. Running the
        // normal hidden ListView resize/scroll path at the same time creates competing layout
        // mutations during typing and can destabilize IME focus.
        if (HistoryDisplayForwarder.VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL))) {
            return;
        }

        // Normal KISS list path.
        if (mainActivity.hider != null) mainActivity.hider.fixScroll();
        mainActivity.list.post(this::scrollToLatestResult);
        mainActivity.list.postDelayed(this::scrollToLatestResult, 220L);
    }

    private void scrollToLatestResult() {
        if (mainActivity.isFinishing() || mainActivity.adapter == null || mainActivity.adapter.isEmpty()) return;
        mainActivity.list.setTranscriptMode(android.widget.AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        mainActivity.list.setSelection(mainActivity.adapter.getCount() - 1);
    }

    void onTouch(MotionEvent event) {
        gd.onTouchEvent(event);
    }

    void onDisplayKissBar(boolean display) {
        setFavoritesBarVisible(mainActivity.searchEditText.getText());
    }

    private boolean isExternalFavoriteBarEnabled() {
        return prefs.getBoolean("enable-favorites-bar", true);
    }

    void updateSearchRecords(String query) {
        if (mainActivity.isViewingAllApps()) {
            mainActivity.search(Searcher.Type.APPLICATION, query, false);
        } else if (TextUtils.isEmpty(query)) {
            if (isMinimalisticModeEnabled()) {
                mainActivity.search(Searcher.Type.NULL, query, false);
                mainEmptyView.setVisibility(View.GONE);
            } else {
                mainActivity.showHistory();
            }
        }
        setFavoritesBarVisible(query);
    }

    private void setFavoritesBarVisible(CharSequence query) {
        if (isExternalFavoriteBarEnabled()) {
            mainActivity.setFavoritesBarVisible(!mainActivity.isViewingAllApps() && TextUtils.isEmpty(query) && !isMinimalisticModeEnabledForFavorites());
        } else {
            mainActivity.setFavoritesBarVisible(mainActivity.isViewingAllApps());
        }
    }

    private void adjustInputType() {
        int currentInputType = mainActivity.searchEditText.getInputType();
        int requiredInputType;
        if (isSuggestionsEnabled()) requiredInputType = InputType.TYPE_CLASS_TEXT;
        else requiredInputType = isNonCompliantKeyboard() ? INPUT_TYPE_WORKAROUND : INPUT_TYPE_STANDARD;
        if (currentInputType != requiredInputType) mainActivity.searchEditText.setInputType(requiredInputType);
    }

    protected void displayNotificationDrawer() {
        try {
            @SuppressLint("WrongConstant")
            Object sbservice = mainActivity.getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbservice);
        } catch (Exception e) {
            Log.e(TAG, "Unable to display notification drawer", e);
        }
    }

    protected void displayQuickSettings() {
        try {
            @SuppressLint("WrongConstant")
            Object sbservice = mainActivity.getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(sbservice);
        } catch (Exception e) {
            Log.e(TAG, "Unable to display quick settings", e);
        }
    }

    protected boolean isMinimalisticModeEnabled() {
        return prefs.getBoolean("history-hide", false);
    }

    protected boolean isMinimalisticModeEnabledForFavorites() {
        return isMinimalisticModeEnabled() && prefs.getBoolean("favorites-hide", false) && prefs.getBoolean("enable-favorites-bar", true);
    }

    private boolean isNonCompliantKeyboard() {
        String currentKeyboard = Settings.Secure.getString(mainActivity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD).toLowerCase(Locale.ROOT);
        return currentKeyboard.contains("swiftkey") || currentKeyboard.contains("flesky");
    }

    private boolean isKeyboardOnStartEnabled() {
        return prefs.getBoolean("display-keyboard", false);
    }

    protected boolean shouldShowKeyboard() {
        boolean isAssistant = "android.intent.action.ASSIST".equalsIgnoreCase(mainActivity.getIntent().getAction());
        return isAssistant || isKeyboardOnStartEnabled();
    }

    private boolean isSuggestionsEnabled() {
        return prefs.getBoolean("enable-suggestions-keyboard", false);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public static void setRequestedOrientation(Activity activity, SharedPreferences prefs) {
        if (prefs.getBoolean("force-portrait", true)) activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        else activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
    }

    public void onPause() {
        keyboardManager.unregisterKeyboardListener();
    }

    public void onFavoriteChange() {
        setFavoritesBarVisible(mainActivity.searchEditText.getText());
    }
}
