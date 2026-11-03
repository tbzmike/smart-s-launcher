package fr.neamar.kiss.androidTest;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import fr.neamar.kiss.notification.NotificationListener;
import static org.junit.Assert.*;

final class PowerTestSupport187 {
    static final String FIXTURE = "com.tbzmike.shortcutfixture.target";
    static String shell(String command) {
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) { throw new AssertionError(command, e); }
    }
    static void await(String message, BooleanSupplier ready) throws Exception {
        for (int i = 0; i < 160; i++) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(50L);
        }
        fail(message);
    }
    static void connect(Context context) throws Exception {
        android.content.ComponentName component = new android.content.ComponentName(
                context, NotificationListener.class);
        await("Listener component is enabled before Android access is granted", () ->
                context.getPackageManager().getComponentEnabledSetting(component)
                        != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        shell("cmd notification allow_listener " + context.getPackageName()
                + "/fr.neamar.kiss.notification.NotificationListener");
        shell("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
        NotificationListener.requestListenerReconnect(context);
        await("Real notification listener connected", NotificationListener::isReadyForExactNotificationLookup);
    }
    static void control(String mode) throws Exception {
        String last = "";
        for (int i = 0; i < 8; i++) {
            last = shell("am broadcast -f 0x10000020 -n " + FIXTURE
                    + "/com.tbzmike.shortcutfixture.FixtureControlReceiver --es mode " + mode);
            if (last.contains("result=1")) return;
            Thread.sleep(150L);
        }
        fail("Fixture did not run: " + last);
    }
    static int files(File root) {
        File[] children = root.listFiles(); if (children == null) return 0;
        int n = 0; for (File f : children) n += f.isDirectory() ? files(f) : 1;
        return n;
    }
}
