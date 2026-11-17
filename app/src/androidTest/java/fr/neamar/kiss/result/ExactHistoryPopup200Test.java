package fr.neamar.kiss.result;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.ui.AnimatedListView;

/** Real dialog verification plus recycled-row ordinary click routing. */
public final class ExactHistoryPopup200Test {
    private static final String OLD = "Popup200 original event";
    private static final String NEW = "Popup200 exact recycled event";

    private static final class FrozenList extends AnimatedListView {
        boolean frozen;
        FrozenList(Context context) { super(context); }
        @Override public boolean isScrollInProgress() { return frozen; }
    }

    private static final class TapResult extends SettingsResult {
        int taps;
        TapResult(NotificationPojo pojo) { super(pojo); }
        @Override public void doLaunch(Context context, View view) { taps++; }
    }

    private static final class TapAdapter extends RecordAdapter {
        String iconEvent;
        int iconTaps;
        int contextMenus;
        String menuEvent;
        TapAdapter(MainActivity activity, List<Result<?>> data) { super(activity, data); }
        @Override protected void openNotificationApp(Result<?> result, NotificationPojo notification,
                                                     View source) {
            iconEvent = notification.exactNotificationId;
            iconTaps++;
        }
        @Override public void onLongClick(int position, View source) {
            contextMenus++;
            menuEvent = ((NotificationPojo) getItem(position).getPojo()).exactNotificationId;
        }
    }

    @Test public void recycledDuringScrollShowsRealExactDialogAndKeepsSingleTaps() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        boolean oldLock = prefs.getBoolean(UiEditLock.PREF_KEY, false);
        boolean oldHistory = prefs.getBoolean("enable-notification-history", false);
        String oldLayout = prefs.getString("smart-history-layout", "vertical");
        long time = System.currentTimeMillis() - 60000L;
        String pkg = app.getPackageName();
        String first = "notification://popup200-old-" + time;
        String second = "notification://popup200-new-" + time;
        try {
            assertTrue(prefs.edit().putBoolean(UiEditLock.PREF_KEY, true)
                    .putBoolean("enable-notification-history", true)
                    .putString("smart-history-layout", "vertical").commit());
            assertTrue(SmartStateStore.saveNotification(app, first, pkg,
                    "Popup200", "", OLD, time, false, null, 0L, null, null, null));
            assertTrue(SmartStateStore.saveNotification(app, second, pkg,
                    "Popup200", "", NEW, time + 1L, false, null, 0L, null, null, null));
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                final View[] selected = new View[1];
                final TapAdapter[] adapter = new TapAdapter[1];
                final TapResult old = new TapResult(new NotificationPojo(first, pkg,
                        "Popup200", "popup200-group", first, 1, "", OLD, time));
                final TapResult current = new TapResult(new NotificationPojo(second, pkg,
                        "Popup200", "popup200-group", second, 1, "", NEW, time + 1L));
                scenario.onActivity(activity -> {
                    SearchHandler handler = SearchHandler.getInstance();
                    handler.cancelSearch();
                    try {
                        Field searchType = SearchHandler.class.getDeclaredField("lastSearchType");
                        searchType.setAccessible(true);
                        searchType.set(handler, Searcher.Type.HISTORY);
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError(error);
                    }
                    List<Result<?>> data = new ArrayList<>();
                    data.add(old);
                    data.add(current);
                    adapter[0] = new TapAdapter(activity, data);
                    FrozenList parent = new FrozenList(activity);
                    View original = adapter[0].getView(0, null, parent);
                    parent.frozen = true;
                    selected[0] = adapter[0].getView(1, original, parent);
                    assertSame("Must exercise the actual recycled notification view",
                            original, selected[0]);
                    View title = selected[0].findViewById(R.id.item_notification_title);
                    assertTrue("Locked long press must invoke the real smart popup",
                            title.performLongClick());
                });
                // This is the real rich dialog, not a stub that merely captures a Result.
                onView(withText(NEW)).inRoot(isDialog()).check(matches(isDisplayed()));
                pressBack();

                scenario.onActivity(activity -> {
                    // The ListView's positional callback may still refer to the old slot.
                    assertTrue(adapter[0].onListItemLongClick(0, selected[0]));
                });
                onView(withText(NEW)).inRoot(isDialog()).check(matches(isDisplayed()));
                pressBack();

                scenario.onActivity(activity -> {
                    View title = selected[0].findViewById(R.id.item_notification_title);
                    View icon = selected[0].findViewById(R.id.item_notification_icon);
                    assertTrue(title.performClick());
                    assertEquals(0, old.taps);
                    assertEquals(1, current.taps);
                    assertEquals(0, adapter[0].iconTaps);
                    assertTrue(icon.performClick());
                    assertEquals(1, current.taps);
                    assertEquals(1, adapter[0].iconTaps);
                    assertEquals(second, adapter[0].iconEvent);
                    assertEquals(0, adapter[0].contextMenus);

                    prefs.edit().putBoolean(UiEditLock.PREF_KEY, false).commit();
                    assertTrue(title.performLongClick());
                    assertEquals("Unlocked long press must still enter the context-menu path",
                            1, adapter[0].contextMenus);
                    assertEquals(second, adapter[0].menuEvent);
                    assertEquals("Long press must not perform an ordinary tap", 1, current.taps);
                });
            }
        } finally {
            prefs.edit().putBoolean(UiEditLock.PREF_KEY, oldLock)
                    .putBoolean("enable-notification-history", oldHistory)
                    .putString("smart-history-layout", oldLayout).commit();
        }
    }
}
