#!/usr/bin/env python3
"""Generate Android string resources from the iOS String Catalog.

Source of truth stays NoTomorrow/Resources/Localizable.xcstrings (en + pl).
This script is deterministic; the Android app never hand-edits the generated
files. Run it after every catalog change:

    python3 scripts/xcstrings_to_android.py

Outputs
  android/app/src/main/res/values/strings.xml       (en, the source language)
  android/app/src/main/res/values-pl/strings.xml    (pl)
  android/strings-map.json                          (iOS key -> Android resource name)

Naming rule (iOS key -> Android name)
  "dashboard.nextSession"          -> dashboard_nextSession
  "progress.prOn %@"               -> progress_prOn_s
  "in %lld h %lld min"             -> in_n_h_n_min
  every "%lld"/"%d" placeholder becomes "n", every "%@" becomes "s",
  any other non [A-Za-z0-9_] run becomes "_", leading digits get a "k_" prefix.

Format specifiers
  %lld / %ld / %d -> %d, %@ -> %s, %.1f kept, positional %1$lld -> %1$d.
  Strings with more than one non-positional specifier are auto-indexed
  (aapt2 rejects "multiple substitutions specified in non-positional format").

Plurals
  xcstrings "variations.plural" -> <plurals> with quantity one/few/many/other.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "NoTomorrow" / "Resources" / "Localizable.xcstrings"
ANDROID = ROOT / "android"
RES = ANDROID / "app" / "src" / "main" / "res"
MAP_FILE = ANDROID / "strings-map.json"

SPEC_RE = re.compile(r"%(\d+\$)?([-+ 0#]*)(\d*)(?:\.(\d+))?(lld|ld|lu|llu|d|u|f|@|s|%)")


def android_name(key: str) -> str:
    n = key
    n = re.sub(r"%\d*\$?(?:lld|ld|llu|lu|d|u)", "n", n)
    n = re.sub(r"%\d*\$?@", "s", n)
    n = re.sub(r"%\d*\$?\.?\d*f", "f", n)
    n = re.sub(r"[^A-Za-z0-9_]+", "_", n).strip("_")
    if not n or n[0].isdigit():
        n = "k_" + n
    return n


def convert_format(value: str) -> str:
    """Convert Apple format specifiers to Java ones and auto-index when needed."""
    specs = list(SPEC_RE.finditer(value))
    real = [m for m in specs if m.group(5) != "%"]
    unindexed = [m for m in real if not m.group(1)]
    auto_index = len(unindexed) > 1 and len(unindexed) == len(real)

    out = []
    last = 0
    counter = 0
    for m in specs:
        out.append(value[last:m.start()])
        last = m.end()
        pos, flags, width, prec, conv = m.groups()
        if conv == "%":
            out.append("%%")
            continue
        if conv in ("lld", "ld", "d", "llu", "lu", "u"):
            jconv = "d"
        elif conv == "@" or conv == "s":
            jconv = "s"
        else:
            jconv = "f"
        if auto_index and not pos:
            counter += 1
            pos = f"{counter}$"
        piece = "%" + (pos or "") + (flags or "") + (width or "")
        if prec is not None:
            piece += "." + prec
        piece += jconv
        out.append(piece)
    out.append(value[last:])
    result = "".join(out)
    # A lone '%' (not part of a specifier) breaks String.format when the
    # string carries arguments; escape it. Strings without arguments keep it.
    if real:
        result = re.sub(r"%(?![%\d$]|[-+ 0#]*\d*\.?\d*[dsf])", "%%", result)
    return result


def escape_android(value: str) -> str:
    v = value.replace("\\", "\\\\")
    v = xml_escape(v)  # & < >
    v = v.replace('"', '\\"').replace("'", "\\'")
    v = v.replace("\n", "\\n").replace("\t", "\\t")
    if v.startswith("@") or v.startswith("?"):
        v = "\\" + v
    return v


def unit_value(unit: dict | None) -> str | None:
    if not unit:
        return None
    su = unit.get("stringUnit")
    if su is None:
        return None
    return su.get("value")


def collect(catalog: dict) -> tuple[dict, dict]:
    """Return (singles, plurals): {lang: [(name, value, key)]}, {lang: [(name, {qty: value}, key)]}."""
    source = catalog.get("sourceLanguage", "en")
    singles: dict[str, list] = {}
    plurals: dict[str, list] = {}
    names: dict[str, str] = {}
    for key, entry in catalog["strings"].items():
        name = android_name(key)
        if name in names and names[name] != key:
            sys.exit(f"resource name collision: {name!r} for {names[name]!r} and {key!r}")
        names[name] = key
        locs = entry.get("localizations", {})
        langs = set(locs.keys()) | {source}
        for lang in langs:
            loc = locs.get(lang, {})
            if "variations" in loc and "plural" in loc["variations"]:
                qtys = {}
                for qty, unit in loc["variations"]["plural"].items():
                    val = unit_value(unit)
                    if val is not None:
                        qtys[qty] = val
                plurals.setdefault(lang, []).append((name, qtys, key))
            else:
                val = unit_value(loc)
                if val is None and lang == source:
                    val = key  # Apple semantics: missing source value == key text
                if val is None:
                    continue
                singles.setdefault(lang, []).append((name, val, key))
    return singles, plurals


def render(singles: list, plurals: list) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- GENERATED by scripts/xcstrings_to_android.py from Localizable.xcstrings. Do not edit. -->",
             "<resources>"]
    for name, val, key in sorted(singles, key=lambda t: t[0]):
        text = escape_android(convert_format(val))
        lines.append(f'    <string name="{name}">{text}</string>')
    for name, qtys, key in sorted(plurals, key=lambda t: t[0]):
        lines.append(f'    <plurals name="{name}">')
        for qty in ("zero", "one", "two", "few", "many", "other"):
            if qty in qtys:
                text = escape_android(convert_format(qtys[qty]))
                lines.append(f'        <item quantity="{qty}">{text}</item>')
        lines.append("    </plurals>")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def main() -> None:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    source = catalog.get("sourceLanguage", "en")
    singles, plurals = collect(catalog)
    written = []
    for lang in sorted(set(singles) | set(plurals)):
        folder = RES / ("values" if lang == source else f"values-{lang}")
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / "strings.xml"
        path.write_text(render(singles.get(lang, []), plurals.get(lang, [])), encoding="utf-8")
        written.append((path, len(singles.get(lang, [])), len(plurals.get(lang, []))))
    mapping = {key: android_name(key) for key in catalog["strings"]}
    kinds = {android_name(k): ("plurals" if any(
        "variations" in l and "plural" in l["variations"] for l in e.get("localizations", {}).values())
        else "string") for k, e in catalog["strings"].items()}
    MAP_FILE.parent.mkdir(parents=True, exist_ok=True)
    MAP_FILE.write_text(json.dumps({"naming": "see scripts/xcstrings_to_android.py", "keys": mapping,
                                    "kinds": kinds}, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                        encoding="utf-8")
    for path, n, p in written:
        print(f"{path.relative_to(ROOT)}: {n} strings, {p} plurals")
    print(f"{MAP_FILE.relative_to(ROOT)}: {len(mapping)} keys")


if __name__ == "__main__":
    main()
