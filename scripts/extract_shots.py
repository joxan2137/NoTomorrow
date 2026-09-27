#!/usr/bin/env python3
"""Pull the gym-screen images out of a GitHub Actions job log.

The iOS snapshot run (`Tests` workflow, run by hand with `snapshots` ticked;
`NoTomorrowTests/GymSnapshotTests.swift`) prints every image as

    NTSHOT-BEGIN train.png
    NTSHOT:iVBORw0KGgoAAAANSUhEUgAA...      (base64, 76 characters a line)
    NTSHOT-END train.png

GitHub puts a timestamp in front of every line ("2026-09-26T12:30:15.2934190Z ");
anything before the marker is ignored, so raw xcodebuild output works too.

    python3 scripts/extract_shots.py job.log shots/

Writes each block to <outdir>/<name> (a later block with the same name wins)
and lists what it wrote; a damaged block is reported and skipped. Exit status
1 when no image could be written.
"""

import base64
import binascii
import os
import re
import sys

BEGIN = re.compile(r"NTSHOT-BEGIN (\S+)")
END = re.compile(r"NTSHOT-END (\S+)")
DATA = re.compile(r"NTSHOT:([A-Za-z0-9+/=]*)")
NOTE = re.compile(r"NTSHOT-(?:SKIP|DONE) .*")

TIMESTAMP = re.compile(r"^﻿?\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z ")


def safe_name(name: str) -> str:
    """The block's name as a plain file name (no directories)."""
    base = os.path.basename(name.replace("\\", "/"))
    return re.sub(r"[^A-Za-z0-9._-]", "_", base) or "shot"


def extract(lines):
    """Yields (name, bytes-or-None, error) per block, in log order."""
    name = None
    chunks = []
    for raw in lines:
        line = TIMESTAMP.sub("", raw.rstrip("\r\n"))
        begin = BEGIN.search(line)
        if begin:
            if name is not None:
                yield name, None, "no NTSHOT-END before the next block"
            name, chunks = begin.group(1), []
            continue
        end = END.search(line)
        if end:
            if name is None:
                continue
            if end.group(1) != name:
                yield name, None, f"ended by NTSHOT-END {end.group(1)}"
            else:
                try:
                    yield name, base64.b64decode("".join(chunks), validate=True), None
                except (binascii.Error, ValueError) as error:
                    yield name, None, f"bad base64 ({error})"
            name, chunks = None, []
            continue
        if NOTE.search(line):
            continue
        data = DATA.search(line)
        if data and name is not None:
            chunks.append(data.group(1))
    if name is not None:
        yield name, None, "log ended inside the block"


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    log_path, out_dir = argv[1], argv[2]
    os.makedirs(out_dir, exist_ok=True)
    with open(log_path, encoding="utf-8", errors="replace") as log:
        lines = log.readlines()
    for line in lines:
        note = NOTE.search(line)
        if note:
            print(note.group(0))
    written, failed = {}, 0
    for name, data, error in extract(lines):
        if error:
            print(f"skipped {name}: {error}", file=sys.stderr)
            failed += 1
            continue
        path = os.path.join(out_dir, safe_name(name))
        with open(path, "wb") as out:
            out.write(data)
        written[path] = len(data)
    for path, size in sorted(written.items()):
        print(f"{path}  {size / 1024:.0f} KB")
    print(f"{len(written)} image(s) written to {out_dir}")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
