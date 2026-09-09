package fr.neamar.kiss.utils.fuzzy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

class MatchInfoTest {
    @Test
    void emptyMatchedIndexListIsNormalizedToNoHighlight() {
        MatchInfo info = new MatchInfo(0);
        info.match = true;

        assertThat(info.getMatchedIndices(), nullValue());
    }
}
