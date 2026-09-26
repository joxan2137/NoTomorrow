#!/usr/bin/env python3
"""Generates `muscle_model.json`, the front/back body map the exercise sheet and Progress draw.

The figure is original work for NoTomorrow, drawn here as data: every shape is a closed list of points on the
viewer's left half of a figure, smoothed into cubic Béziers (Catmull-Rom) and mirrored onto the right half.
Front figure is centred on x = 50, back figure on x = 150, in a 200 × 300 box (the apps draw it at 2:3).

Each entry is {"muscle", "view", "d"}: `muscle` is a free-exercise-db muscle name ("chest", "middle back"), or
"outline" for the body underneath, which is drawn first; `view` is "front" or "back"; `d` is SVG path data using only
absolute M, C and Z, which both apps parse.

    python3 scripts/anatomy/muscle_map.py            # writes both apps' muscle_model.json
    python3 scripts/anatomy/muscle_map.py --svg out.svg [--highlight chest,triceps]
"""
import argparse
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
TARGETS = [ROOT / "NoTomorrow/Resources/muscle_model.json", ROOT / "android/app/src/main/assets/muscle_model.json"]

# Points ending in "c" are corners: the curve passes through them without smoothing.
P = tuple

# ---------------------------------------------------------------- body underneath (shared by both views)

HEAD = [(50, 7), (43.2, 9.6), (40.6, 17), (41.2, 26), (43.6, 34), (47, 38.8), (50, 39.8)]

# Head is its own shape; the torso half runs from the neck's centre line round the outside of the leg and back up
# the inside to the crotch.
TORSO = [
    (50, 36), (44.2, 36.5), (43.4, 44), (38, 48.6), (31, 51), (27, 53.5), (25.4, 58), (26.4, 66),
    (29.2, 76), (31.4, 88), (32.6, 100), (32.4, 110), (31.2, 120), (29.6, 132), (28.8, 146), (28.8, 164),
    (30.4, 186), (32.6, 202), (32.4, 214), (31.6, 226), (32.8, 242), (35.6, 258), (37.8, 268),
    (37.2, 278), (35.4, 285, "c"), (36.8, 289), (44, 289.6), (46.8, 287, "c"), (46.4, 276), (45.6, 266),
    (47.4, 248), (47.6, 230), (46.2, 214), (46.2, 200), (47.8, 180), (49, 160), (50, 150),
]

ARM = [
    (30, 52), (24.4, 54.6), (21, 61), (19.6, 72), (19, 86), (19.2, 100), (18.4, 112), (16.6, 124),
    (15.8, 138), (16.6, 150), (14.6, 158), (13.6, 168), (16.2, 176), (19.6, 176.4), (22.6, 170),
    (22.6, 160), (22.6, 151), (24.2, 136), (25.2, 120), (26.2, 108), (27, 94), (28.4, 80), (29.4, 70),
]

# ---------------------------------------------------------------- front muscles

FRONT = {
    "neck": [[(44.6, 38.6), (45.8, 38), (48.8, 47.6), (48.6, 50.2), (47.4, 50)]],
    "traps": [[(43.6, 43.6), (44.6, 46.6), (41.6, 49.4), (34.6, 51.6), (32.4, 51.2), (38, 48.4)]],
    "shoulders": [[(32.6, 52.8), (27.6, 54), (23.4, 58), (21.2, 65), (21, 72), (22.2, 77, "c"), (25.2, 72),
                   (28.2, 64), (32.2, 57.6), (35.4, 54.6)]],
    "chest": [[(49, 53.6), (43, 53), (36.6, 54.8), (32, 58.4), (28.8, 64.4), (28, 71), (31, 77.4),
               (36.8, 81.4), (43, 82.4), (47.4, 81), (49, 78.6)]],
    "biceps": [[(26.8, 76), (23.8, 78), (22, 86), (21.8, 96), (22.8, 104), (24.4, 108.4), (26, 104),
                (26.8, 94), (27.6, 84)]],
    # The lateral head of the triceps, seen along the outside of the upper arm.
    "triceps": [[(21, 80), (19.8, 88), (19.8, 98), (20.6, 104), (21.2, 96), (21.4, 86)]],
    "forearms": [[(19.4, 113), (17.8, 121), (17.4, 132), (17.8, 142), (19, 149.4), (21.4, 149.6), (23, 138),
                  (24.4, 124), (24.8, 114), (22.4, 110.4)]],
    "abdominals": [
        # Rectus abdominis, four rows each side of the linea alba.
        [(43.4, 84.8), (49, 84), (49, 95.4), (43.6, 96.6)],
        [(43.8, 98.6), (49, 97.8), (49, 108.6), (44, 109.4)],
        [(44, 111.4), (49, 110.8), (49, 122), (44.4, 122.4)],
        [(44.4, 124.6), (49, 124.4), (49, 142), (47.6, 142.4), (45.4, 134)],
        # External oblique.
        [(41.4, 86), (36, 85.4), (33.8, 90), (33.8, 100), (34.4, 110), (37.2, 119), (41.4, 126), (42.8, 122),
         (42.2, 108), (42, 96)],
    ],
    "abductors": [[(31.4, 121.6), (29.8, 130), (30, 142), (31.8, 145.6), (34, 136), (36, 126), (34.6, 121)]],
    "quadriceps": [
        # Rectus femoris with vastus lateralis beside it.
        [(36.4, 131), (33, 140), (30.6, 152), (30.2, 168), (31.6, 186), (34.6, 199.4), (38.4, 202.6),
         (41, 196), (41.6, 180), (41.4, 160), (40.4, 144)],
        # Vastus medialis, the teardrop above the knee.
        [(44.2, 172), (42.4, 182), (42, 194), (43.6, 202.8), (46.2, 200.4), (47, 190), (46.6, 179)],
    ],
    "adductors": [[(41.4, 136), (43.6, 140), (48.4, 149.6), (48, 160), (46, 170), (43.4, 168), (42.6, 154)]],
    "calves": [[(45.6, 216), (47, 226), (47.2, 238), (46.4, 250), (44.8, 250), (44.4, 236), (44.4, 222)]],
    "tibialis anterior": [[(36.4, 216.4), (34.6, 226), (35, 240), (37.8, 256), (40.2, 258.6), (41, 244),
                           (41.4, 228), (40.4, 216)]],
}

# ---------------------------------------------------------------- back muscles (drawn on the front's coordinates,
# then moved 100 to the right)

BACK = {
    "traps": [[(50, 37.6), (46, 39.2), (44, 45), (38, 49), (30.6, 52.2), (28, 54.6), (34, 56.8), (40, 61),
               (44.6, 70), (47.6, 82), (50, 94)]],
    "shoulders": [[(28.4, 56), (24.4, 57.4), (21.6, 62), (20.8, 70), (21.6, 76.6, "c"), (25, 72), (29, 64),
                   (32.6, 59.6)]],
    "middle back": [[(35, 60.4), (30.4, 64.6), (29.4, 71), (31.4, 77), (37, 79.4), (42.4, 77.6), (43.4, 72),
                     (40.4, 64.6)]],
    "lats": [[(29.6, 77.2), (29.8, 86), (31.6, 97), (34.6, 108), (39.4, 117), (42.6, 120), (43.4, 113),
              (42.8, 100), (42.2, 88), (42.6, 80.6), (37.4, 81.8), (32.6, 80)]],
    "lower back": [[(46.2, 84), (44.4, 96), (44.2, 110), (43.6, 121), (45.6, 126.4), (49, 126.8),
                    (49, 90)]],
    "triceps": [[(26.4, 77.6), (22.8, 79.4), (20.6, 86), (20, 96), (20.8, 104), (23.4, 109), (25.8, 104.4),
                 (26.8, 94), (27.8, 84)]],
    "forearms": FRONT["forearms"],
    "abductors": [[(33, 118.6), (31, 124), (31.2, 130.6), (35, 126), (40.4, 121.8), (43.4, 120.4), (38, 118)]],
    "glutes": [[(49, 123.4), (43.6, 123), (37.6, 125.4), (33, 130), (31, 138), (32.4, 146), (37.6, 151),
                (44, 151.6), (49, 149.6)]],
    "hamstrings": [
        # Biceps femoris, the outer head.
        [(33.6, 153.6), (31.4, 164), (31.2, 178), (33, 192), (36.4, 201.4), (39.2, 198), (40, 184),
         (40.2, 166), (38.6, 154.4)],
        # Semitendinosus and semimembranosus.
        [(41.2, 154.6), (42.4, 168), (41.8, 186), (42.2, 198), (45, 200.6), (46.6, 190), (47.4, 172),
         (47, 157.4)],
    ],
    "adductors": [[(48.8, 151.4), (47.6, 153.6), (48.2, 164), (48.8, 170)]],
    "calves": [
        [(34, 210.4), (32.4, 220), (32.8, 232), (35.2, 244), (38.8, 246.6), (40.2, 236), (40, 222),
         (38.4, 210.6)],
        [(41.6, 210.4), (41, 224), (41.4, 238), (43.2, 247.6), (46.2, 244), (47, 230), (46.6, 218),
         (45, 210.6)],
    ],
}


def mirror(points, about=50.0):
    return [(2 * about - p[0], p[1], *p[2:]) for p in points]


def shift(points, dx):
    return [(p[0] + dx, p[1], *p[2:]) for p in points]


def smooth(points, tension=1 / 6):
    """Closed Catmull-Rom spline through `points` as absolute SVG path data; corner points stay sharp."""
    n = len(points)
    xy = [(p[0], p[1]) for p in points]
    corner = [len(p) > 2 and p[2] == "c" for p in points]

    def f(v):
        s = f"{v:.1f}".rstrip("0").rstrip(".")
        return "0" if s == "-0" else s

    d = [f"M{f(xy[0][0])} {f(xy[0][1])}"]
    for i in range(n):
        p0, p1, p2, p3 = xy[i - 1], xy[i], xy[(i + 1) % n], xy[(i + 2) % n]
        t1 = 0 if corner[i] else tension
        t2 = 0 if corner[(i + 1) % n] else tension
        c1 = (p1[0] + (p2[0] - p0[0]) * t1, p1[1] + (p2[1] - p0[1]) * t1)
        c2 = (p2[0] - (p3[0] - p1[0]) * t2, p2[1] - (p3[1] - p1[1]) * t2)
        d.append(f"C{f(c1[0])} {f(c1[1])} {f(c2[0])} {f(c2[1])} {f(p2[0])} {f(p2[1])}")
    d.append("Z")
    return "".join(d)


def whole(half):
    """A half shape that starts and ends on the centre line, joined with its mirror image into one outline."""
    return half + list(reversed(mirror(half)))[1:-1]


# The points above were drawn for a slim figure; widening everything but the head gives it a trained build.
WIDTH = 1.12


def widen(points):
    return [(50 + (p[0] - 50) * WIDTH, p[1], *p[2:]) for p in points]


def build():
    regions = []
    for view, dx in (("front", 0), ("back", 100)):
        regions.append({"muscle": "outline", "view": view, "d": smooth(shift(HEAD + list(reversed(mirror(HEAD)))[1:-1], dx))})
        regions.append({"muscle": "outline", "view": view, "d": smooth(shift(widen(whole(TORSO)), dx))})
        for arm in (ARM, mirror(ARM)):
            regions.append({"muscle": "outline", "view": view, "d": smooth(shift(widen(arm), dx))})
    for view, dx, muscles in (("front", 0, FRONT), ("back", 100, BACK)):
        for muscle, shapes in muscles.items():
            for shape in shapes:
                if shape[0][0] == 50 and shape[-1][0] == 50:
                    # A shape across the centre line (the traps' diamond) is one outline, not two halves.
                    regions.append({"muscle": muscle, "view": view, "d": smooth(shift(widen(whole(shape)), dx))})
                    continue
                for side in (shape, mirror(shape)):
                    regions.append({"muscle": muscle, "view": view, "d": smooth(shift(widen(side), dx))})
    return regions


def svg(regions, highlight, secondary):
    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="600" height="900" viewBox="0 0 200 300">',
           '<rect width="200" height="300" fill="#0A0A0B"/>']
    for r in regions:
        if r["muscle"] == "outline":
            fill = "#2A2A2E"
        elif r["muscle"] in highlight:
            fill = "#FF6A2B"
        elif r["muscle"] in secondary:
            fill = "#A8502E"
        else:
            fill = "#48484E"
        out.append(f'<path d="{r["d"]}" fill="{fill}" stroke="#0A0A0B" stroke-width="0.6" stroke-linejoin="round"/>')
    out.append("</svg>")
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--svg")
    parser.add_argument("--highlight", default="")
    parser.add_argument("--secondary", default="")
    args = parser.parse_args()
    regions = build()
    if args.svg:
        split = lambda s: {m.strip() for m in s.split(",") if m.strip()}
        pathlib.Path(args.svg).write_text(svg(regions, split(args.highlight), split(args.secondary)))
        return
    text = json.dumps(regions, separators=(",", ":")) + "\n"
    for target in TARGETS:
        target.write_text(text)
        print(f"wrote {target.relative_to(ROOT)} ({len(regions)} regions)")


if __name__ == "__main__":
    main()
