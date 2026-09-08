from pathlib import Path

p = Path("app/src/test/java/fr/neamar/kiss/forwarder/VerticalCardWidthPolicyTest.java")
text = p.read_text()
old = '''import static org.junit.Assert.assertEquals;\n\nimport org.junit.Test;'''
new = '''import static org.hamcrest.MatcherAssert.assertThat;\nimport static org.hamcrest.Matchers.is;\n\nimport org.junit.jupiter.api.Test;'''
if text.count(old) != 1:
    raise SystemExit("VerticalCardWidthPolicyTest: unexpected test imports")
text = text.replace(old, new)
replacements = {
    '        assertEquals(480, VerticalCardWidthPolicy.targetWidth(1000, 48));':
        '        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 48), is(480));',
    '        assertEquals(8, VerticalCardWidthPolicy.insetForPercent(8, 48));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 48), is(8));',
    '        assertEquals(4, VerticalCardWidthPolicy.insetForPercent(4, 48));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 48), is(4));',
    '        assertEquals(1000, VerticalCardWidthPolicy.targetWidth(1000, 100));':
        '        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 100), is(1000));',
    '        assertEquals(8, VerticalCardWidthPolicy.insetForPercent(8, 100));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 100), is(8));',
    '        assertEquals(4, VerticalCardWidthPolicy.insetForPercent(4, 100));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 100), is(4));',
    '        assertEquals(1000, VerticalCardWidthPolicy.targetWidth(1000, 150));':
        '        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 150), is(1000));',
    '        assertEquals(4, VerticalCardWidthPolicy.insetForPercent(8, 150));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 150), is(4));',
    '        assertEquals(2, VerticalCardWidthPolicy.insetForPercent(4, 150));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 150), is(2));',
    '        assertEquals(1000, VerticalCardWidthPolicy.targetWidth(1000, 200));':
        '        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 200), is(1000));',
    '        assertEquals(0, VerticalCardWidthPolicy.insetForPercent(8, 200));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 200), is(0));',
    '        assertEquals(0, VerticalCardWidthPolicy.insetForPercent(4, 200));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(4, 200), is(0));',
    '        assertEquals(1000, VerticalCardWidthPolicy.targetWidth(1000, 500));':
        '        assertThat(VerticalCardWidthPolicy.targetWidth(1000, 500), is(1000));',
    '        assertEquals(0, VerticalCardWidthPolicy.insetForPercent(8, 500));':
        '        assertThat(VerticalCardWidthPolicy.insetForPercent(8, 500), is(0));',
}
for old_line, new_line in replacements.items():
    if text.count(old_line) != 1:
        raise SystemExit(f"VerticalCardWidthPolicyTest: missing exact assertion: {old_line}")
    text = text.replace(old_line, new_line)
p.write_text(text)
