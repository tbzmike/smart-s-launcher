package fr.neamar.kiss.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class WorkspacePaneAssignmentsTest {
    @Test
    void twoPaneHistoryPositionSelectsTheAuthoritativePane() {
        assertThat(WorkspacePaneAssignments.resolve(2, 2, 1), arrayContaining(
                WorkspacePaneAssignments.Content.WIDGETS,
                WorkspacePaneAssignments.Content.APPS_AND_HISTORY));
    }

    @Test
    void fourPaneAssignmentsLeaveUnassignedPanesEmpty() {
        assertThat(WorkspacePaneAssignments.resolve(4, 4, 3), arrayContaining(
                WorkspacePaneAssignments.Content.EMPTY,
                WorkspacePaneAssignments.Content.EMPTY,
                WorkspacePaneAssignments.Content.WIDGETS,
                WorkspacePaneAssignments.Content.APPS_AND_HISTORY));
    }

    @Test
    void duplicatePositionsNeverCloneAnAuthoritativeContainer() {
        WorkspacePaneAssignments.Content[] assignments =
                WorkspacePaneAssignments.resolve(4, 2, 2);

        assertThat(count(assignments, WorkspacePaneAssignments.Content.APPS_AND_HISTORY), is(1));
        assertThat(count(assignments, WorkspacePaneAssignments.Content.WIDGETS), is(1));
        assertThat(assignments[1], is(WorkspacePaneAssignments.Content.APPS_AND_HISTORY));
    }

    @Test
    void malformedPositionsFallBackInsideTheSelectedGeometry() {
        assertThat(WorkspacePaneAssignments.resolve(2, 99, -4), arrayContaining(
                WorkspacePaneAssignments.Content.APPS_AND_HISTORY,
                WorkspacePaneAssignments.Content.WIDGETS));
    }

    private static int count(WorkspacePaneAssignments.Content[] assignments,
                             WorkspacePaneAssignments.Content content) {
        int count = 0;
        for (WorkspacePaneAssignments.Content assignment : assignments) {
            if (assignment == content) count++;
        }
        return count;
    }
}
