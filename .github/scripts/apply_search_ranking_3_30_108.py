from pathlib import Path
import re

query_searcher = Path('app/src/main/java/fr/neamar/kiss/searcher/QuerySearcher.java')
text = query_searcher.read_text()

# These exact result classes are the reachability-first types requested for bottom placement.
# Disabled records are deliberately excluded so a disabled target does not receive an artificial boost.
text = text.replace(
    'import fr.neamar.kiss.pojo.AppPojo;\n',
    'import fr.neamar.kiss.pojo.AppPojo;\nimport fr.neamar.kiss.pojo.SettingPojo;\nimport fr.neamar.kiss.pojo.ShortcutPojo;\n',
    1,
)

text = text.replace(
    'public class QuerySearcher extends Searcher {\n',
    '''public class QuerySearcher extends Searcher {\n    /**\n     * Exact app/shortcut/setting matches are intentionally the strongest local results.\n     * Searcher/RecordAdapter already place stronger relevance later in the result list, which\n     * keeps these exact launch targets at the bottom for one-handed reachability.\n     */\n    private static final int EXACT_REACHABILITY_RELEVANCE = 1_000_000;\n''',
    1,
)

old = '''            int originalRelevance = pojo.relevance;\n            Integer historyValue = knownIds == null ? null : knownIds.get(pojo.id);\n'''
new = '''            int originalRelevance = pojo.relevance;\n            if (isExactReachabilityMatch(pojo)) {\n                // Exact launch-target matches must stay below weaker messages/emails/etc.\n                // History and semantic reranking must not pull them away from the bottom.\n                pojo.relevance = EXACT_REACHABILITY_RELEVANCE;\n                continue;\n            }\n\n            Integer historyValue = knownIds == null ? null : knownIds.get(pojo.id);\n'''
if old not in text:
    raise SystemExit('Expected QuerySearcher relevance block not found; refusing to guess.')
text = text.replace(old, new, 1)

marker = '''    private float lexicalQuality(String rawQuery, Pojo pojo, boolean providerMatched) {\n'''
method = '''    private boolean isExactReachabilityMatch(Pojo pojo) {\n        if (pojo == null || pojo.isDisabled()) return false;\n        if (!(pojo instanceof AppPojo)\n                && !(pojo instanceof ShortcutPojo)\n                && !(pojo instanceof SettingPojo)) {\n            return false;\n        }\n\n        String normalizedQuery = normalize(query);\n        String normalizedName = normalize(pojo.getName());\n        return !normalizedQuery.isEmpty() && normalizedName.equals(normalizedQuery);\n    }\n\n'''
if marker not in text:
    raise SystemExit('Expected lexicalQuality marker not found; refusing to guess.')
text = text.replace(marker, method + marker, 1)
query_searcher.write_text(text)

gradle = Path('app/build.gradle')
g = gradle.read_text()
vm = re.search(r'versionName\\s+["\\']([^"\\']+)["\\']', g)
cm = re.search(r'versionCode\\s+(\\d+)', g)
if not vm or not cm or vm.group(1) != '3.30.107' or cm.group(1) != '535':
    raise SystemExit('Expected verified 3.30.107/535 baseline after slider preparation; refusing to guess.')
g = g[:vm.start(1)] + '3.30.108' + g[vm.end(1):]
g = g[:cm.start(1)] + '536' + g[cm.end(1):]
g = g.replace(
    'Smart S Launcher 3.30.107 - independent history icon sizing',
    'Smart S Launcher 3.30.108 - exact launch target search ranking',
    1,
)
gradle.write_text(g)

print('Applied exact launch-target bottom ranking and bumped Smart S Launcher to 3.30.108/536.')
