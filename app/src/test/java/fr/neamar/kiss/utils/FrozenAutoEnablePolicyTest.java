package fr.neamar.kiss.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;

class FrozenAutoEnablePolicyTest {
    @Test void selectedAutoEnableAndGrantedRootAllowTapToUnfreeze() {
        assertTrue(FrozenAutoEnablePolicy.canEnable(true, () -> true));
    }

    @Test void selectedAutoEnableStillRequiresRealRootGrant() {
        assertFalse(FrozenAutoEnablePolicy.canEnable(true, () -> false));
    }

    @Test void disablingAutoEnableDoesNotAttemptRootAtAll() {
        AtomicBoolean queried = new AtomicBoolean(false);
        assertFalse(FrozenAutoEnablePolicy.canEnable(false, () -> {
            queried.set(true);
            return true;
        }));
        assertFalse(queried.get());
    }
}
