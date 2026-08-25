package fr.neamar.kiss.result;

/**
 * Chooses the only valid mark-read target for a notification timeline row.
 *
 * Persisted history rows are still useful after Android removes a notification, but they must not
 * expose a live action. In particular, an old individual row must never fall through to a newer
 * notification from the same application group.
 */
final class NotificationActionPolicy {
    enum Target {
        NONE,
        INDIVIDUAL,
        GROUP
    }

    private NotificationActionPolicy() {
    }

    static Target resolve(boolean individualRecord,
                          boolean groupRecord,
                          boolean individualIsActive,
                          int activeGroupCount) {
        if (individualRecord) {
            return individualIsActive ? Target.INDIVIDUAL : Target.NONE;
        }
        if (groupRecord && activeGroupCount > 0) return Target.GROUP;
        return Target.NONE;
    }
}
