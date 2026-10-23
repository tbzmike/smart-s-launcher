package fr.neamar.kiss.backup;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BackupPackagePolicyTest {
    @Test void supportsUpgradeBetweenOfficialSmartSVariants() {
        assertTrue(BackupPackagePolicy.compatible("com.tbzmike.smartslauncher",
                "com.tbzmike.smartslauncher.debug"));
        assertTrue(BackupPackagePolicy.compatible("com.tbzmike.smartslauncher.debug",
                "com.tbzmike.smartslauncher"));
        assertTrue(BackupPackagePolicy.compatible("com.tbzmike.smartslauncher",
                "com.tbzmike.smartslauncher"));
    }
    @Test void rejectsUnrelatedPackages() {
        assertFalse(BackupPackagePolicy.compatible("com.tbzmike.smartslauncher",
                "com.tbzmike.smartslauncher.other"));
        assertFalse(BackupPackagePolicy.compatible("com.tbzmike.smartslauncher",
                "com.catchingnow.icebox"));
        assertFalse(BackupPackagePolicy.compatible(null, "com.tbzmike.smartslauncher"));
    }
}
