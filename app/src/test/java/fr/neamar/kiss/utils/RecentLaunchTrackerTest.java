package fr.neamar.kiss.utils;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import fr.neamar.kiss.pojo.Pojo;

class RecentLaunchTrackerTest {

    @Test
    void olderSelectionRemainsResolvableAfterNewerSelection() {
        Pojo first = new Pojo("shortcut://first") { };
        first.setName("First shortcut");
        Pojo second = new Pojo("app://second") { };
        second.setName("Second app");

        RecentLaunchTracker.remember(first);
        RecentLaunchTracker.remember(second);

        assertSame(first, RecentLaunchTracker.resolve(first.getHistoryId()));
        assertSame(second, RecentLaunchTracker.getMostRecent());
    }
}
