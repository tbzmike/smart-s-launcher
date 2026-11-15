package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class HistoryStartupPreview198Test {
    @Test void coldStartPreviewBoundedButRealHistoryLimitUnchanged() {
        assertEquals(200, HistoryFirstSearchPolicy.startupPreviewLimit(50_003));
        assertEquals(200, HistoryFirstSearchPolicy.startupPreviewLimit(307_177));
        assertEquals(25, HistoryFirstSearchPolicy.startupPreviewLimit(25));
        assertEquals(0, HistoryFirstSearchPolicy.startupPreviewLimit(0));
        assertEquals(50_003, HistoryFirstSearchPolicy.seedLimit(50_003));
    }
}
