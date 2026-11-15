package fr.neamar.kiss.androidTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.utils.UserHandle;

/** UI-locked History inspects the exact rendered row; UI-unlocked opens a context menu. */
public final class LockedHistoryPopup196IntegrationTest {
    static final class CaptureAdapter extends RecordAdapter {
        int popups;
        int menus;
        String target;

        CaptureAdapter(MainActivity activity, List<Result<?>> items) {
            super(activity, items);
        }

        @Override public boolean onBoundResultLongClick(Result<?> bound, View anchor) {
            if (UiEditLock.isLocked(anchor.getContext())) {
                target = bound.getPojoId();
                popups++;
                return true;
            }
            return super.onBoundResultLongClick(bound, anchor);
        }

        @Override public void onLongClick(int position, View source) {
            target = getItem(position).getPojoId();
            menus++;
        }
    }

    @Test public void lockedUsesBoundIdentityAndUnlockedKeepsContextMenu() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String oldLayout = prefs.getString("smart-history-layout", "vertical");
        boolean oldLock = prefs.getBoolean(UiEditLock.PREF_KEY, false);
        try {
            assertTrue(prefs.edit().putBoolean(UiEditLock.PREF_KEY, true)
                    .putString("smart-history-layout", "vertical").commit());
            try (ActivityScenario<MainActivity> scenario =
                         ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        SearchHandler.getInstance().cancelSearch();
                        List<Result<?>> items = new ArrayList<>();
                        UserHandle user = new UserHandle(activity,
                                android.os.Process.myUserHandle());
                        for (int i = 0; i < 3; i++) {
                            AppPojo app = new AppPojo("app://locked196-" + i,
                                    activity.getPackageName(), MainActivity.class.getName(),
                                    user, false, false, false, false);
                            app.setName("Exact target " + i);
                            items.add(Result.fromPojo(activity, app));
                        }
                        CaptureAdapter adapter = new CaptureAdapter(activity, items);
                        activity.adapter = adapter;
                        activity.list.setAdapter(adapter);
                        String expected = items.get(0).getPojoId();

                        // The original row is still physically present when another adapter slot
                        // is recycled/reordered. Locked inspection must never follow that slot.
                        View firstRow = adapter.getView(0, null, activity.list);
                        items.set(0, items.get(2));
                        assertTrue(firstRow.performLongClick());
                        assertEquals(expected, adapter.target);
                        assertEquals(1, adapter.popups);
                        assertEquals(0, adapter.menus);
                        items.set(0, Result.fromPojo(activity, newApp(activity, user, 0)));

                        Object manager = field(activity, "forwarderManager");
                        Object wheel = field(manager, "historyDisplayForwarder");
                        prefs.edit().putString("smart-history-layout", "wheel_3d").commit();
                        invoke(wheel, "onResume");
                        invoke(wheel, "rebuildWheelNow");
                        LinearLayout wheelColumn = (LinearLayout) field(wheel, "wheelColumn");
                        assertTrue(wheelColumn.getChildCount() >= 3);
                        FrameLayout shell = (FrameLayout) wheelColumn.getChildAt(0);
                        assertTrue(shell.performLongClick());
                        assertEquals(expected, adapter.target);
                        assertEquals(2, adapter.popups);

                        prefs.edit().putString("smart-history-layout", "vertical_cards").commit();
                        invoke(wheel, "onResume");
                        Object cards = field(manager, "smartCardListForwarder");
                        invoke(cards, "onResume");
                        LinearLayout cardColumn = (LinearLayout) field(cards, "column");
                        assertTrue(cardColumn.getChildCount() >= 3);
                        View card = cardColumn.getChildAt(0);
                        assertTrue(card.performLongClick() || ((android.view.ViewGroup) card)
                                .getChildAt(0).performLongClick());
                        assertEquals(expected, adapter.target);
                        assertEquals(3, adapter.popups);
                        assertEquals(0, adapter.menus);

                        // Unlocked must use the OLD editable context-menu action, not popup.
                        prefs.edit().putBoolean(UiEditLock.PREF_KEY, false).commit();
                        View unlockedRow = adapter.getView(0, null, activity.list);
                        assertTrue(unlockedRow.performLongClick());
                        assertEquals(3, adapter.popups);
                        assertEquals(1, adapter.menus);
                    } catch (Exception error) {
                        throw new AssertionError("Wrong History popup or context menu route", error);
                    }
                });
            }
        } finally {
            prefs.edit().putBoolean(UiEditLock.PREF_KEY, oldLock)
                    .putString("smart-history-layout", oldLayout).commit();
        }
    }

    private static AppPojo newApp(MainActivity activity, UserHandle user, int i) {
        AppPojo app = new AppPojo("app://locked196-" + i,
                activity.getPackageName(), MainActivity.class.getName(),
                user, false, false, false, false);
        app.setName("Exact target " + i);
        return app;
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void invoke(Object owner, String method) throws Exception {
        Method action = owner.getClass().getDeclaredMethod(method);
        action.setAccessible(true);
        action.invoke(owner);
    }
}
