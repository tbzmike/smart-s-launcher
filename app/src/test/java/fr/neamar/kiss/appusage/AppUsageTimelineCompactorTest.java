package fr.neamar.kiss.appusage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

class AppUsageTimelineCompactorTest {
    @Test
    void internalActivitiesBecomeOneContinuousAppSession() {
        List<AppUsageStore.TimelineEntry> result = AppUsageTimelineCompactor.compact(Arrays.asList(
                usage("settings", 5_000L, 8_000L, "smart.s", "SettingsActivity"),
                usage("usage", 9_000L, 12_000L, "smart.s", "AppUsageActivity"),
                usage("home", 1_000L, 4_000L, "smart.s", "MainActivity")));

        assertThat(result.size(), is(1));
        assertThat(result.get(0).startMs, is(1_000L));
        assertThat(result.get(0).endMs, is(12_000L));
        assertThat(result.get(0).durationMs, is(11_000L));
        assertThat(result.get(0).detail, is((String) null));
    }

    @Test
    void anotherForegroundAppAlwaysStartsAnotherVisibleSession() {
        List<AppUsageStore.TimelineEntry> result = AppUsageTimelineCompactor.compact(Arrays.asList(
                usage("new-smart", 9_000L, 12_000L, "smart.s", "MainActivity"),
                usage("other", 5_000L, 8_000L, "other.app", "OtherActivity"),
                usage("old-smart", 1_000L, 4_000L, "smart.s", "MainActivity")));

        assertThat(result.size(), is(3));
        assertThat(result.get(0).packageName, is("smart.s"));
        assertThat(result.get(1).packageName, is("other.app"));
        assertThat(result.get(2).packageName, is("smart.s"));
    }

    @Test
    void sameAppAfterARealPauseRemainsASeparateSession() {
        List<AppUsageStore.TimelineEntry> result = AppUsageTimelineCompactor.compact(Arrays.asList(
                usage("new", 20_000L, 24_000L, "smart.s", "MainActivity"),
                usage("old", 1_000L, 5_000L, "smart.s", "MainActivity")));

        assertThat(result.size(), is(2));
    }

    @Test
    void screenOrPhoneEventsBreakAnAppSession() {
        AppUsageStore.TimelineEntry screenOff = new AppUsageStore.TimelineEntry(
                "screen", 8_500L, 8_500L, AppUsageStore.KIND_SCREEN_OFF,
                null, null, 0L, false, "Screen off", null, null);

        List<AppUsageStore.TimelineEntry> result = AppUsageTimelineCompactor.compact(Arrays.asList(
                usage("new", 9_000L, 12_000L, "smart.s", "MainActivity"),
                screenOff,
                usage("old", 5_000L, 8_000L, "smart.s", "MainActivity")));

        assertThat(result.size(), is(3));
        assertThat(result.get(1).kind, is(AppUsageStore.KIND_SCREEN_OFF));
    }

    private static AppUsageStore.TimelineEntry usage(String key, long start, long end,
                                                      String packageName, String activity) {
        return new AppUsageStore.TimelineEntry(
                key, start, end, AppUsageStore.KIND_APP_USAGE,
                packageName, "Smart S", end - start, false,
                "Foreground app session · " + activity, null, null);
    }
}
