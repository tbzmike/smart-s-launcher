package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchRecallPolicyTest {
    @Test void normalizesCaseAccentsAndPunctuationConsistently() {
        assertEquals("whatsapp voice", SearchRecallPolicy.normalize("  WhatsApp—Voice! "));
        assertEquals("école", SearchRecallPolicy.normalize("ÉCOLE"));
        assertEquals("", SearchRecallPolicy.normalize(null));
    }

    @Test void limitsRecallToTheConfiguredHistoryWindowNotTotalHistoryRows() {
        assertEquals(257174, SearchRecallPolicy.firstAllowedIndex(307177, 50003));
        assertEquals(0, SearchRecallPolicy.firstAllowedIndex(180, 50003));
        assertEquals(200, SearchRecallPolicy.firstAllowedIndex(200, 0));
    }

    @Test void remembersExactAndPrefixQueriesButNotUnrelatedText() {
        assertTrue(SearchRecallPolicy.matches("whatsapp voice", "whatsapp voice"));
        assertTrue(SearchRecallPolicy.matches("whatsapp voice", "whats"));
        assertFalse(SearchRecallPolicy.matches("whatsapp voice", "telegram"));
        assertFalse(SearchRecallPolicy.matches("whatsapp voice", ""));
    }
}
