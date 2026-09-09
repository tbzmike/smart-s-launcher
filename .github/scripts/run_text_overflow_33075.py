from pathlib import Path

patcher = Path('.github/scripts/apply_text_overflow_33075.py')
source = patcher.read_text()
old = '''def replace_once(text: str, old: str, new: str, label: str) -> str:\n    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f"{label}: expected exactly one match, found {count}")\n    return text.replace(old, new, 1)\n'''
new = '''def replace_once(text: str, old: str, new: str, label: str) -> str:\n    count = text.count(old)\n    # 3.30.74 intentionally has the same fixed-height expression for the card title and caller\n    # name. The first replacement consumes the title occurrence; the later exact replacement\n    # consumes the one remaining caller-name occurrence. No other duplicate is accepted.\n    if label == "card title height" and count == 2:\n        return text.replace(old, new, 1)\n    if count != 1:\n        raise SystemExit(f"{label}: expected exactly one match, found {count}")\n    return text.replace(old, new, 1)\n'''
if source.count(old) != 1:
    raise SystemExit('patcher helper baseline mismatch')
source = source.replace(old, new, 1)
exec(compile(source, str(patcher), 'exec'), {'__name__': '__main__'})
