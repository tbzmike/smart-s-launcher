package fr.neamar.kiss.searcher;

import java.util.Comparator;

import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.RelevanceComparator;

/**
 * Query-only ordering for Smart S Launcher search results.
 *
 * The adapter is bottom-anchored: ascending comparator order is rendered from weaker/top to
 * stronger/bottom. Therefore generic matches use group 0, frozen apps group 1 and active apps
 * group 2. Relevance remains the secondary key inside each group.
 */
final class QueryResultComparator implements Comparator<Pojo> {
    private final RelevanceComparator relevanceComparator = new RelevanceComparator();

    static int priorityGroup(Pojo pojo) {
        if (!(pojo instanceof AppPojo)) return 0;
        return pojo.isDisabled() ? 1 : 2;
    }

    @Override
    public int compare(Pojo lhs, Pojo rhs) {
        int group = Integer.compare(priorityGroup(lhs), priorityGroup(rhs));
        if (group != 0) return group;
        return relevanceComparator.compare(lhs, rhs);
    }
}
