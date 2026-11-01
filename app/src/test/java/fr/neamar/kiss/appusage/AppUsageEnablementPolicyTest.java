package fr.neamar.kiss.appusage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class AppUsageEnablementPolicyTest {
    @Test
    void initializesOnlyAnAbsentDefaultAndPreservesExplicitOff() {
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(false, false, false), is(true));
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(false, false, true), is(false));
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(false, true, true), is(false));
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(true, false, false), is(false));
        assertThat(AppUsageEnablementPolicy.shouldRestoreDefault(true, true, true), is(false));
    }
}
