package fr.neamar.kiss.freeform;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreeformTaskPolicyTest {
    private static FreeformTaskPolicy.Candidate task(int id, String component) {
        return new FreeformTaskPolicy.Candidate(id, "example.app", component);
    }
    @Test void preferExactExistingTaskAndNeverLaunchDuplicates() {
        assertEquals(7, FreeformTaskPolicy.select(Arrays.asList(task(7, "example.app/Main"),
                task(9, "example.app/Other")), "example.app", "example.app/Main"));
        assertEquals(7, FreeformTaskPolicy.select(Collections.singletonList(task(7, "example.app/Alias")),
                "example.app", "example.app/Main"));
        assertEquals(FreeformTaskPolicy.NONE, FreeformTaskPolicy.select(Collections.emptyList(),
                "example.app", "example.app/Main"));
    }
    @Test void ambiguousTasksFailClosed() {
        assertEquals(FreeformTaskPolicy.AMBIGUOUS, FreeformTaskPolicy.select(Arrays.asList(
                task(7, "example.app/Alias"), task(9, "example.app/Other")), "example.app", "example.app/Main"));
        assertEquals(FreeformTaskPolicy.AMBIGUOUS, FreeformTaskPolicy.select(Arrays.asList(
                task(7, "example.app/Main"), task(9, "example.app/Main")), "example.app", "example.app/Main"));
    }
    @Test void staleTaskCannotActOnReusedIdOrDifferentComponent() {
        assertTrue(FreeformTaskPolicy.same(7, "example.app/Main", 7, "example.app/Main"));
        assertFalse(FreeformTaskPolicy.same(7, "example.app/Main", 7, "other.app/Main"));
        assertFalse(FreeformTaskPolicy.same(7, "example.app/Main", 9, "example.app/Main"));
    }
    @Test void bubbleCoordinatesStayInsideDisplay() {
        assertEquals(10, FreeformTaskPolicy.clamp(-100, 10, 200));
        assertEquals(200, FreeformTaskPolicy.clamp(500, 10, 200));
        assertEquals(10, FreeformTaskPolicy.clamp(50, 10, 5));
    }
}
