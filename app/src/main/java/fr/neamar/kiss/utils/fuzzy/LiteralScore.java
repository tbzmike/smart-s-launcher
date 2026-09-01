package fr.neamar.kiss.utils.fuzzy;

/**
 * Cheap contiguous literal matcher used when fuzzy search is disabled.
 * It deliberately does not perform subsequence/fuzzy matching: exact, prefix and contains are the
 * only accepted forms. This keeps ordinary search useful while honoring the fuzzy-search OFF state.
 */
final class LiteralScore implements FuzzyScore {
    private final int[] pattern;
    private final boolean detailedMatchIndices;

    LiteralScore(int[] pattern, boolean detailedMatchIndices) {
        this.pattern = pattern == null ? new int[0] : pattern.clone();
        this.detailedMatchIndices = detailedMatchIndices;
    }

    @Override public FuzzyScore setFullWordBonus(int value) { return this; }
    @Override public FuzzyScore setAdjacencyBonus(int value) { return this; }
    @Override public FuzzyScore setSeparatorBonus(int value) { return this; }
    @Override public FuzzyScore setCamelBonus(int value) { return this; }
    @Override public FuzzyScore setLeadingLetterPenalty(int value) { return this; }
    @Override public FuzzyScore setMaxLeadingLetterPenalty(int value) { return this; }
    @Override public FuzzyScore setUnmatchedLetterPenalty(int value) { return this; }
    @Override public FuzzyScore setFirstLetterBonus(int value) { return this; }

    @Override
    public MatchInfo match(CharSequence text) {
        if (text == null) return MatchInfo.UNMATCHED;
        int count = Character.codePointCount(text, 0, text.length());
        int[] codePoints = new int[count];
        int offset = 0;
        for (int i = 0; i < count; i++) {
            int cp = Character.codePointAt(text, offset);
            codePoints[i] = cp;
            offset += Character.charCount(cp);
        }
        return match(codePoints);
    }

    @Override
    public MatchInfo match(int[] text) {
        if (pattern.length == 0 || text == null || text.length < pattern.length) {
            return MatchInfo.UNMATCHED;
        }

        int matchStart = -1;
        outer:
        for (int start = 0; start <= text.length - pattern.length; start++) {
            for (int i = 0; i < pattern.length; i++) {
                if (Character.toLowerCase(text[start + i]) != Character.toLowerCase(pattern[i])) {
                    continue outer;
                }
            }
            matchStart = start;
            break;
        }
        if (matchStart < 0) return MatchInfo.UNMATCHED;

        int score;
        if (matchStart == 0 && text.length == pattern.length) score = 360;
        else if (matchStart == 0) score = 320;
        else if (isWordBoundary(text, matchStart)) score = 290 - Math.min(40, matchStart);
        else score = 250 - Math.min(80, matchStart);

        if (!detailedMatchIndices) return new MatchInfo(true, score);
        MatchInfo result = new MatchInfo(pattern.length);
        result.match = true;
        result.score = score;
        for (int i = 0; i < pattern.length; i++) result.matchedIndices.add(matchStart + i);
        return result;
    }

    private boolean isWordBoundary(int[] text, int index) {
        if (index <= 0) return true;
        int previous = text[index - 1];
        return !Character.isLetterOrDigit(previous);
    }
}
