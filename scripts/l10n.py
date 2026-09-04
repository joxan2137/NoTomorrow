#!/usr/bin/env python3
"""Merge localized strings into NoTomorrow/Resources/Localizable.xcstrings under a file lock.
Usage: scripts/l10n.py add path/to/new-strings.json
       scripts/l10n.py add - < strings.json
Input JSON: {"key": {"en": "English", "pl": "Polski"}, ...}
Existing keys are left untouched unless --force is given.
"""
import json, sys, fcntl, os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "NoTomorrow/Resources/Localizable.xcstrings")

def main():
    if len(sys.argv) < 3 or sys.argv[1] != "add":
        print(__doc__); sys.exit(2)
    force = "--force" in sys.argv
    src = sys.argv[2]
    new = json.load(sys.stdin if src == "-" else open(src, encoding="utf-8"))
    with open(CATALOG, "r+", encoding="utf-8") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        cat = json.load(f)
        strings = cat.setdefault("strings", {})
        added, skipped = 0, 0
        for key, langs in new.items():
            if key in strings and not force:
                skipped += 1; continue
            if not isinstance(langs, dict) or "en" not in langs or "pl" not in langs:
                print(f"SKIP {key!r}: need both en and pl"); skipped += 1; continue
            strings[key] = {"localizations": {
                lang: {"stringUnit": {"state": "translated", "value": langs[lang]}} for lang in ("en", "pl")}}
            added += 1
        f.seek(0); f.truncate()
        json.dump(cat, f, ensure_ascii=False, indent=2); f.write("\n")
        fcntl.flock(f, fcntl.LOCK_UN)
    print(f"added {added}, skipped {skipped}, total {len(strings)}")

if __name__ == "__main__":
    main()
