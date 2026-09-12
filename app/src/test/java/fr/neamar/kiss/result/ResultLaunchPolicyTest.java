package fr.neamar.kiss.result;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.pojo.SettingPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;

class ResultLaunchPolicyTest {

    @Test
    void installedFeatureUsesExternalLaunchReturnPath() {
        SettingPojo feature = new SettingPojo(
                "feature://com.example/.FeatureActivity",
                "com.example.FeatureActivity",
                "com.example",
                -1);

        assertTrue(Result.isExternalLaunchTarget(feature));
    }

    @Test
    void appShortcutUsesExternalLaunchReturnPath() {
        ShortcutRecord record = new ShortcutRecord();
        record.name = "Conversation";
        record.packageName = "com.example";
        record.intentUri = ShortcutPojo.OREO_PREFIX + "conversation";

        ShortcutPojo shortcut = new ShortcutPojo(
                null, record, null, false, true, false);

        assertTrue(Result.isExternalLaunchTarget(shortcut));
    }
}
