package fr.neamar.kiss.backup;

/** Accept only the exact Smart S production/debug pair, never arbitrary sibling packages. */
public final class BackupPackagePolicy {
    private static final String PRODUCTION = "com.tbzmike.smartslauncher";
    private static final String DEBUG = "com.tbzmike.smartslauncher.debug";

    private BackupPackagePolicy() { }

    public static boolean compatible(String installedPackage, String archivePackage) {
        if (installedPackage == null || archivePackage == null) return false;
        if (installedPackage.equals(archivePackage)) return true;
        return (PRODUCTION.equals(installedPackage) && DEBUG.equals(archivePackage))
                || (DEBUG.equals(installedPackage) && PRODUCTION.equals(archivePackage));
    }
}
