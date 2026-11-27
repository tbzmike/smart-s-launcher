package fr.neamar.kiss.freeform;

import java.util.List;

/** No duplicate or wrong-task fallback when an application's existing task identity is ambiguous. */
public final class FreeformTaskPolicy {
    public static final int NONE = -1;
    public static final int AMBIGUOUS = -2;
    private FreeformTaskPolicy() {}

    public static final class Candidate {
        public final int id;
        public final String pkg, component;
        public Candidate(int id, String pkg, String component) {
            this.id = id; this.pkg = pkg; this.component = component;
        }
    }

    public static int select(List<Candidate> tasks, String pkg, String component) {
        int packageCount = 0, exactCount = 0, packageId = NONE, exactId = NONE;
        for (Candidate task : tasks) {
            if (!pkg.equals(task.pkg)) continue;
            packageCount++; packageId = task.id;
            if (component.equals(task.component)) { exactCount++; exactId = task.id; }
        }
        if (exactCount == 1) return exactId;
        if (exactCount > 1 || packageCount > 1) return AMBIGUOUS;
        return packageCount == 1 ? packageId : NONE;
    }

    public static boolean same(int id, String component, int actualId, String actualComponent) {
        return id == actualId && component != null && component.equals(actualComponent);
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
