package fr.neamar.kiss.androidTest;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import fr.neamar.kiss.MainActivity;

/** Observe the actual system-created HOME task; ActivityScenario starts a normal test task. */
final class SystemHome192TestSession implements AutoCloseable {
    private final Context app;
    private SystemHome192TestSession(Context app) { this.app = app; }

    static SystemHome192TestSession open(Context app) throws Exception {
        PowerTestSupport187.shell("cmd package set-home-activity --user 0 "
                + app.getPackageName() + "/fr.neamar.kiss.MainActivity");
        PowerTestSupport187.shell("input keyevent KEYCODE_HOME");
        PowerTestSupport187.await("The system Home activity is resumed", () -> resumedHome() != null);
        return new SystemHome192TestSession(app);
    }

    static MainActivity resumedHome() {
        AtomicReference<MainActivity> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(resumedOnMain()));
        return result.get();
    }

    private static MainActivity resumedOnMain() {
        MainActivity result = null;
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof MainActivity) {
                assertNull("Only one Home activity is resumed", result);
                result = (MainActivity) activity;
            }
        }
        return result;
    }

    void onActivity(Consumer<MainActivity> action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MainActivity current = resumedOnMain();
            assertNotNull("System Home remains resumed", current);
            action.accept(current);
        });
    }

    boolean isReady(Predicate<MainActivity> condition) {
        AtomicBoolean ready = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MainActivity current = resumedOnMain();
            if (current != null) ready.set(condition.test(current));
        });
        return ready.get();
    }

    @Override public void close() throws Exception { finishHome(app); }

    static void finishHome(Context app) throws Exception {
        String homes = PowerTestSupport187.shell("cmd package query-activities --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        String stock = null;
        for (String line : homes.split("\\n")) {
            String component = line.trim();
            if (component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
                    && !component.startsWith(app.getPackageName() + "/")
                    && !component.contains("FallbackHome")) { stock = component; break; }
        }
        assertNotNull("A stock Home is available for test cleanup", stock);
        try {
            PowerTestSupport187.shell("cmd package set-home-activity --user 0 " + stock);
            PowerTestSupport187.shell("input keyevent KEYCODE_HOME");
            PowerTestSupport187.await("Home has stopped before cleanup", () -> resumedHome() == null);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Stage stage : new Stage[]{Stage.CREATED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED}) {
                    for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(stage))) {
                        if (activity instanceof MainActivity) activity.finish();
                    }
                }
            });
        } finally {
            PowerTestSupport187.shell("cmd package set-home-activity --user 0 "
                    + app.getPackageName() + "/fr.neamar.kiss.MainActivity");
        }
    }
}
