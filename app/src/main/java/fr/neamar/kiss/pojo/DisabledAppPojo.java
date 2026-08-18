package fr.neamar.kiss.pojo;

public final class DisabledAppPojo extends SettingPojo {
    public final String targetPackage;
    public final String activityName;

    public DisabledAppPojo(String id, String targetPackage, String activityName) {
        super(id, "", -1);
        this.targetPackage = targetPackage;
        this.activityName = activityName;
    }

    @Override
    public boolean isDisabled() {
        return true;
    }
}
