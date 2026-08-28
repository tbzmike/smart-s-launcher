package fr.neamar.kiss.appusage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class AppUsageEnablementPolicyTest {
    @Test
    void restoresDisabledDefaultOnlyBeforeRepairMarker() {
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(false, false), is(true));
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(false, true), is(false));
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(true, false), is(false));
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(true, true), is(false));
    }
}
