package fr.neamar.kiss.result;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ListAdapter;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.UserHandle;

public final class FreeformLockedAction206Test {
    @Test public void lockedAppActionsKeepRealNotificationPopupAndUnlockedMenu() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean oldLock = prefs.getBoolean(UiEditLock.PREF_KEY, false);
        boolean oldHistory = prefs.getBoolean("enable-notification-history", false);
        String pkg = "com.tbzmike.freeform.fixture.history";
        String message = "Freeform206 retained notification text";
        long time = System.currentTimeMillis() - 500L;
        try {
            assertTrue(prefs.edit().putBoolean(UiEditLock.PREF_KEY, true)
                    .putBoolean("enable-notification-history", true).commit());
            assertTrue(SmartStateStore.saveNotification(context, "notification://freeform206-" + time,
                    pkg, "Freeform test", "", message, time, false, null, 0L, null, null, null));
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    AppResult app = app(pkg);
                    List<Result<?>> data = new ArrayList<>();
                    data.add(app);
                    RecordAdapter adapter = new RecordAdapter(activity, data);
                    assertTrue(adapter.onBoundResultLongClick(app, new View(activity)));
                });
                onView(withText(message)).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(R.string.menu_app_freeform)).inRoot(isDialog()).check(matches(isDisplayed()));
                pressBack();

                scenario.onActivity(activity -> {
                    AppResult app = app("com.tbzmike.freeform.fixture.nohistory");
                    List<Result<?>> data = new ArrayList<>();
                    data.add(app);
                    RecordAdapter adapter = new RecordAdapter(activity, data);
                    assertTrue(adapter.onBoundResultLongClick(app, new View(activity)));
                });
                onView(withText(R.string.menu_app_freeform)).inRoot(isDialog()).check(matches(isDisplayed()));
                pressBack();

                scenario.onActivity(activity -> {
                    DisabledAppPojo pojo = new DisabledAppPojo("disabled://freeform206",
                            "com.tbzmike.freeform.fixture.frozen", "com.tbzmike.freeform.fixture.frozen.Main");
                    pojo.setName("Frozen app");
                    Result<?> frozen = Result.fromPojo(activity, pojo);
                    List<Result<?>> data = new ArrayList<>();
                    data.add(frozen);
                    RecordAdapter adapter = new RecordAdapter(activity, data);
                    assertTrue(adapter.onBoundResultLongClick(frozen, new View(activity)));
                });
                onView(withText(R.string.menu_app_freeform)).inRoot(isDialog()).check(matches(isDisplayed()));
                pressBack();

                scenario.onActivity(activity -> {
                    prefs.edit().putBoolean(UiEditLock.PREF_KEY, false).commit();
                    AppResult app = app("com.android.settings");
                    List<Result<?>> data = new ArrayList<>();
                    data.add(app);
                    RecordAdapter adapter = new RecordAdapter(activity, data);
                    ListAdapter menu = app.getPopupMenu(activity, adapter, new View(activity)).getAdapter();
                    boolean found = false;
                    for (int i = 0; i < menu.getCount(); i++) {
                        Object item = menu.getItem(i);
                        if (item instanceof ListPopup.Item
                                && ((ListPopup.Item) item).stringId == R.string.menu_app_freeform) found = true;
                    }
                    assertTrue("Existing unlocked app menu retains floating launch", found);
                });
            }
        } finally {
            prefs.edit().putBoolean(UiEditLock.PREF_KEY, oldLock)
                    .putBoolean("enable-notification-history", oldHistory).commit();
        }
    }

    private static AppResult app(String pkg) {
        AppPojo pojo = new AppPojo("app://" + pkg + "/Main", pkg, pkg + ".Main",
                UserHandle.OWNER, false, false, false, false);
        pojo.setName("Freeform fixture");
        return new AppResult(pojo);
    }
}
