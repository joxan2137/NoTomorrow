#!/usr/bin/env python3
"""Fit Roboto Flex's `wdth` axis per NT text style to SF Pro's advance widths.

The Android port stands in for SF Pro with Roboto Flex (`android-research.md`
§6.10 — SF Pro cannot ship).  At its default `wdth 100` Roboto Flex sets 3-9 %
NARROWER than SF Pro at text sizes and ~5 % WIDER at the 34 pt display size, so
every line of copy lands on a different measure than iOS and multi-line blocks
wrap in different places.

This script fits, per NT text style, the `wdth` (and optionally a letter-spacing
delta) that reproduces SF Pro's rendered widths.  It is fully self-contained:

  1.  MEASURE — threshold-scan the parity captures
      (`design/parity/ios/*.png` and `design/parity/android-r1/*.png`, both
      1206 x 2622 px = 402 x 874 dp at 3x) for the ink extent of ~60 reference
      strings whose NT style is known from the Compose source.  Bright text on
      a dark ground is scanned with `L > 70`; text inside a white pill is
      scanned with `L < 120` over the rows that are actually pill interior.
  2.  MODEL — instantiate the variable font with `fontTools.varLib.instancer`
      at candidate (wdth, XTRA, opsz, wght) locations and measure each string's
      ink advance with `PIL.ImageFont` at the exact pixel size (3 px per pt),
      plus `letterSpacing x (len - 1)`.
  3.  CALIBRATE — the model is only used as a RATIO.  For sample *i* the
      predicted new Android width is

          pred_i(wdth) = measured_android_i x  W_i(wdth, opsz)
                                              -------------------
                                              W_i(wdth_now, opsz_now)

      so PIL's hinting, the missing HarfBuzz kerning and the capture threshold
      all cancel out.  (Raw model bias against the round-1 captures is
      +0.2…+3.0 %; the ratio form removes it.)
  4.  FIT — least squares on the relative error against the iOS widths, over a
      quadratic in `wdth` sampled at four locations, then re-verified by
      actually instantiating the winner.

Usage:
    python3 scripts/fit_font_width.py            # fit + report
    python3 scripts/fit_font_width.py --validate # only check the current NT.kt

Requires `fonttools` and `pillow` (`pip install fonttools pillow`).
"""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass, field

try:
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
except ImportError:  # pragma: no cover
    sys.exit("fontTools missing — pip install fonttools")
try:
    from PIL import Image, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("Pillow missing — pip install pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "android/app/src/main/res/font/robotoflex_variable.ttf")
IOS_DIR = os.path.join(ROOT, "design/parity/ios")
AND_DIR = os.path.join(ROOT, "design/parity/android-r1")
CACHE = os.path.join(ROOT, "build/fontfit")

SCALE = 3.0            # capture density: 1 dp = 3 px
LIGHT_THRESHOLD = 70   # ink on the dark ground
DARK_THRESHOLD = 120   # ink inside a white pill
PILL_ROW_MEAN = 150    # a row that is mostly pill interior
DISPLAY_HINGE_PT = 20  # SF Pro's Text -> Display optical-size boundary


# ── NT text styles ──────────────────────────────────────────────────────────
# (point size, weight, letterSpacing in em, wdth as shipped in round 1).
# `opsz_now` is 14 everywhere: round 1 never set the optical-size axis, so the
# font stayed at its own default.
@dataclass
class Style:
    size: int
    weight: int
    tracking_em: float
    wdth_now: float
    opsz_now: float = 14.0
    opsz_new: float | None = None   # None -> track the point size

    @property
    def px(self) -> int:
        return int(round(self.size * SCALE))

    @property
    def tracking_px(self) -> float:
        return self.tracking_em * self.size * SCALE

    def opsz(self) -> float:
        # SF Pro ships TWO optical designs: `Text` for every size below 20 pt and
        # `Display` from 20 pt up.  Roboto Flex's continuous `opsz` axis is asked
        # for the same thing — its own text default (14) below the 20 pt hinge,
        # the point size itself above it.  Driving `opsz` continuously through
        # the small sizes instead is a trap: the axis is strongly non-linear
        # under 14, so 11/12/13 pt then want wildly different `wdth` for the same
        # 3-4 % correction (105 at 12 pt vs 117 at 11 and 13).
        if self.opsz_new is not None:
            return self.opsz_new
        return float(self.size) if self.size >= DISPLAY_HINGE_PT else 14.0


# What `designsystem/NT.kt` ships after this fit: (wdth, opsz).  The four display
# rows carry a further correction — re-measuring 42 of the reference strings on
# emulator-5554 showed the model tracks the device to −0.1 % at `opsz 14`
# (n = 19 body rows) but underestimates by a consistent +1.2 % at `opsz 34`, so
# the rows above the 20 pt hinge are pulled back by that bias pro-rated over
# 14…34.  Device RMS across those 42 strings: 4.12 % (round 1) → 1.84 %.
SHIPPED: dict[str, tuple[float, float]] = {
    "largeTitle": (96.2, 34.0),   # fit 99.2, −3.0 display bias
    "title1": (99.4, 28.0),       # interpolated 22↔34 pt, −3.1 display bias
    "title2": (104.2, 22.0),      # fit 106.0, −1.8 display bias
    "title3": (105.7, 20.0),      # extrapolated, −1.3 display bias
    "headline": (119.2, 14.0),
    "body": (120.8, 14.0),
    "callout": (118.5, 14.0),     # interpolated 15↔17 pt
    "subheadline": (116.2, 14.0),
    "subheadlineBold": (124.8, 14.0),
    "footnote": (124.0, 14.0),
    "footnoteBold": (124.0, 14.0),
    "caption": (117.0, 14.0),
    "eyebrow": (125.2, 14.0),
    "wheelDigit": (100.0, 14.0),  # measured directly off the wheel, not fitted
}

STYLES: dict[str, Style] = {
    "largeTitle":      Style(34, 700, 0.0118, 97.0),
    "title2":          Style(22, 700, -0.0118, 100.0),
    "headline":        Style(17, 600, -0.0253, 106.0),
    "body":            Style(17, 400, -0.0253, 106.0),
    "subheadline":     Style(15, 400, -0.0153, 106.0),
    "subheadlineBold": Style(15, 600, -0.0153, 106.0),
    "footnote":        Style(13, 400, -0.0062, 106.0),
    "caption":         Style(12, 500, 0.0, 106.0),
    "eyebrow":         Style(11, 600, 0.0818, 106.0),
}


# ── Reference strings ───────────────────────────────────────────────────────
# rect = (x0, x1, y0, y1) in capture pixels.  `pill` marks dark-on-white text.
@dataclass
class Sample:
    style: str
    text: str
    screen: str
    ios: tuple[int, int, int, int]
    android: tuple[int, int, int, int]
    pill: bool = False
    ios_px: float = field(default=0.0, init=False)
    and_px: float = field(default=0.0, init=False)


S = Sample
SAMPLES: list[Sample] = [
    # ── eyebrow — 11 pt SemiBold, +0.9 pt tracking ──────────────────────────
    S("eyebrow", "SATURDAY, 5 SEPTEMBER", "06-dashboard", (40, 700, 217, 247), (40, 700, 167, 196)),
    S("eyebrow", "NEXT SESSION", "06-dashboard", (100, 700, 785, 812), (100, 700, 735, 763)),
    S("eyebrow", "WORKOUT IN PROGRESS", "07-train", (150, 700, 499, 524), (150, 700, 448, 473)),
    S("eyebrow", "NO PR YET", "09-progress", (40, 600, 193, 218), (40, 600, 143, 168)),
    S("eyebrow", "BODY WEIGHT · TREND", "09-progress", (100, 700, 463, 488), (100, 700, 412, 437)),
    S("eyebrow", "BRO", "10-bro", (40, 600, 217, 242), (40, 600, 167, 192)),
    S("eyebrow", "YOU", "11-settings", (60, 600, 595, 620), (60, 600, 588, 613)),
    S("eyebrow", "TRAINING", "11-settings", (60, 600, 1097, 1122), (60, 600, 1090, 1115)),
    S("eyebrow", "APP", "11-settings", (60, 600, 1599, 1623), (60, 600, 1592, 1616)),
    S("eyebrow", "GYM BRO", "11-settings", (60, 600, 2371, 2396), (60, 600, 2364, 2389)),
    S("eyebrow", "WHY", "19-cant-make-it", (60, 600, 887, 909), (60, 600, 918, 942)),
    S("eyebrow", "PROPOSE A MAKE-UP", "19-cant-make-it", (60, 700, 1552, 1576), (60, 700, 1467, 1492)),

    # ── largeTitle — 34 pt Bold ─────────────────────────────────────────────
    S("largeTitle", "Today", "06-dashboard", (40, 700, 281, 371), (40, 700, 224, 323)),
    S("largeTitle", "Train", "07-train", (40, 700, 283, 360), (40, 700, 232, 308)),
    S("largeTitle", "Progress", "09-progress", (40, 700, 257, 348), (40, 700, 204, 299)),
    S("largeTitle", "Training solo", "10-bro", (40, 750, 277, 372), (40, 750, 224, 323)),
    S("largeTitle", "Settings", "11-settings", (40, 700, 428, 523), (40, 700, 421, 516)),

    # ── title2 — 22 pt Bold ─────────────────────────────────────────────────
    S("title2", "Push A", "14-active-workout", (40, 700, 225, 293), (40, 700, 175, 242)),
    S("title2", "Add to Breakfast", "18-portion-sheet", (40, 700, 700, 769), (40, 700, 700, 769)),
    S("title2", "Can't make it today", "19-cant-make-it", (60, 750, 700, 760), (60, 750, 723, 787)),

    # ── headline — 17 pt SemiBold ───────────────────────────────────────────
    S("headline", "Fuel", "06-dashboard", (40, 400, 1629, 1672), (40, 400, 1554, 1597)),
    S("headline", "Last session", "06-dashboard", (40, 600, 2115, 2162), (40, 600, 2043, 2082)),
    S("headline", "Resume", "07-train", (60, 600, 563, 604), (60, 600, 509, 552)),
    S("headline", "Routines", "07-train", (40, 600, 806, 846), (40, 600, 755, 794)),
    S("headline", "Legs", "07-train", (40, 600, 1426, 1472), (40, 600, 1376, 1424)),
    S("headline", "History", "07-train", (40, 600, 1878, 1927), (40, 600, 1831, 1880)),
    S("headline", "Estimated 1RM", "09-progress", (40, 700, 835, 875), (40, 700, 783, 823)),
    S("headline", "Barbell Bench Press - Medium Grip", "14-active-workout",
      (40, 950, 397, 450), (40, 950, 349, 399)),
    S("headline", "Sign in to pair", "10-bro", (114, 1093, 857, 1013), (114, 1093, 777, 933), pill=True),
    S("headline", "Add to Breakfast", "18-portion-sheet",
      (81, 1126, 2149, 2311), (81, 1126, 2232, 2400), pill=True),
    S("headline", "Log as missed", "19-cant-make-it",
      (81, 1126, 1952, 2114), (81, 1126, 1883, 2051), pill=True),

    # ── body — 17 pt Regular (the Settings rows) ────────────────────────────
    S("body", "Name", "11-settings", (100, 600, 701, 746), (100, 600, 694, 739)),
    S("body", "Body weight", "11-settings", (100, 600, 834, 882), (100, 600, 827, 875)),
    S("body", "Daily target", "11-settings", (100, 460, 968, 1016), (100, 460, 961, 1009)),
    S("body", "Gym days", "11-settings", (100, 420, 1201, 1249), (100, 420, 1194, 1242)),
    S("body", "Rest timer", "11-settings", (100, 600, 1336, 1375), (100, 600, 1329, 1368)),
    S("body", "1:30 · auto-start", "11-settings", (620, 1060, 1336, 1375), (620, 1060, 1329, 1368)),
    S("body", "Units", "11-settings", (100, 600, 1471, 1519), (100, 600, 1464, 1512)),
    S("body", "kg", "11-settings", (900, 1060, 1471, 1519), (900, 1060, 1464, 1512)),
    S("body", "Language", "11-settings", (100, 600, 1703, 1751), (100, 600, 1696, 1744)),
    S("body", "Notifications", "11-settings", (100, 600, 1838, 1877), (100, 600, 1831, 1870)),
    S("body", "2 on", "11-settings", (900, 1060, 1838, 1877), (900, 1060, 1831, 1870)),
    S("body", "Health app", "11-settings", (100, 600, 1973, 2020), (100, 600, 1966, 2013)),
    S("body", "Not connected", "11-settings", (620, 1060, 1973, 2020), (620, 1060, 1966, 2013)),
    S("body", "Export my data", "11-settings", (100, 600, 2108, 2156), (100, 600, 2101, 2149)),
    S("body", "CSV", "11-settings", (900, 1060, 2108, 2156), (900, 1060, 2101, 2149)),
    S("body", "AI estimates", "11-settings", (100, 600, 2243, 2282), (100, 600, 2236, 2275)),
    S("body", "Standard", "11-settings", (750, 1060, 2243, 2282), (750, 1060, 2236, 2275)),
    S("body", "No bro yet", "11-settings", (100, 600, 2475, 2523), (100, 600, 2468, 2516)),
    S("body", "Pair in Bro", "11-settings", (700, 1110, 2475, 2523), (700, 1110, 2468, 2516)),
    S("body", "Add a note (optional)", "19-cant-make-it", (100, 700, 1413, 1457), (100, 700, 1319, 1367)),

    # ── subheadline — 15 pt Regular ─────────────────────────────────────────
    S("subheadline", "Body", "09-progress", (940, 1120, 257, 348), (940, 1120, 204, 299)),
    S("subheadline", "3 months", "09-progress", (900, 1160, 835, 875), (900, 1160, 783, 823)),
    S("subheadline", "Pairing needs an account so your bro's phone can", "10-bro",
      (40, 1150, 412, 453), (40, 1150, 359, 403)),
    S("subheadline", "reach yours.", "10-bro", (40, 600, 466, 507), (40, 600, 413, 457)),
    S("subheadline", "Sun 6", "19-cant-make-it", (100, 300, 1653, 1694), (100, 300, 1570, 1614)),
    S("subheadline", "Tue 8", "19-cant-make-it", (330, 500, 1653, 1694), (330, 500, 1570, 1614)),
    S("subheadline", "Skip this one", "19-cant-make-it", (560, 900, 1653, 1694), (560, 900, 1570, 1614)),
    S("subheadline", "Never mind, I'm going", "19-cant-make-it",
      (350, 900, 2195, 2235), (350, 900, 2134, 2178)),

    # ── subheadlineBold — 15 pt SemiBold ────────────────────────────────────
    S("subheadlineBold", "Start workout", "07-train",
      (745, 1150, 922, 1072), (745, 1150, 870, 1023), pill=True),
    S("subheadlineBold", "Start empty workout", "07-train", (300, 900, 1718, 1761), (300, 900, 1670, 1714)),
    S("subheadlineBold", "Lifts", "09-progress", (740, 900, 257, 348), (740, 900, 204, 299)),
    S("subheadlineBold", "Finish", "14-active-workout", (930, 1140, 225, 293), (930, 1140, 175, 242)),

    # ── footnote — 13 pt Regular ────────────────────────────────────────────
    S("footnote", "5 exercises · Barbell Squat,", "07-train",
      (40, 700, 1491, 1527), (40, 700, 1443, 1480)),
    S("footnote", "Romanian Deadlift, Leg Press", "07-train", (40, 700, 1538, 1574), (40, 700, 1489, 1527)),
    S("footnote", "over 4 weeks · logged 1 of 28 days", "09-progress",
      (100, 800, 672, 708), (100, 800, 620, 657)),
    S("footnote", "Default for new exercises. Each exercise can have its", "12-settings-resttimer",
      (100, 1100, 851, 880), (100, 1100, 849, 880)),
    S("footnote", "own rest.", "12-settings-resttimer", (100, 400, 901, 927), (100, 400, 901, 927)),

    # ── caption — 12 pt Medium ──────────────────────────────────────────────
    S("caption", "Push A", "06-dashboard", (900, 1100, 785, 812), (900, 1100, 735, 763)),
    S("caption", "Set", "14-active-workout", (60, 200, 559, 593), (60, 200, 509, 544)),
    S("caption", "Previous", "14-active-workout", (250, 520, 559, 593), (250, 520, 509, 544)),
    S("caption", "kg", "14-active-workout", (620, 760, 559, 593), (620, 760, 509, 544)),
    S("caption", "Reps", "14-active-workout", (800, 980, 559, 593), (800, 980, 509, 544)),
]


# ── 1. capture measurement ──────────────────────────────────────────────────
_gray_cache: dict[str, list[list[int]]] = {}


def _gray(path: str):
    im = _gray_cache.get(path)
    if im is None:
        im = Image.open(path).convert("L")
        _gray_cache[path] = im
    return im


def _ink_light(im, rect) -> float:
    """Left-to-right ink extent of bright text over the dark ground."""
    x0, x1, y0, y1 = rect
    crop = im.crop((x0, y0, x1, y1))
    w, h = crop.size
    px = crop.load()
    on = [x for x in range(w) if max(px[x, y] for y in range(h)) > LIGHT_THRESHOLD]
    if not on:
        raise ValueError(f"no ink in {rect}")
    return float(on[-1] - on[0] + 1)


def _ink_pill(im, rect) -> float:
    """Ink extent of dark text inside a white capsule."""
    x0, x1, y0, y1 = rect
    crop = im.crop((x0, y0, x1, y1))
    w, h = crop.size
    px = crop.load()
    rows = [y for y in range(h) if sum(px[x, y] for x in range(w)) / w > PILL_ROW_MEAN]
    if not rows:
        raise ValueError(f"no pill in {rect}")
    top, bot = rows[0], rows[-1]
    # Inset off the rounded caps so the ground outside them is not sampled.
    inset = int((bot - top + 1) * 0.28)
    top, bot = top + inset, bot - inset
    on = [x for x in range(w) if min(px[x, y] for y in range(top, bot + 1)) < DARK_THRESHOLD]
    if not on:
        raise ValueError(f"no dark ink in {rect}")
    # `on` also holds the ground just outside the capsule's left and right ends;
    # drop every dark run that touches a window edge, keep the label.
    runs: list[list[int]] = []
    for x in on:
        if runs and x - runs[-1][1] <= 1:
            runs[-1][1] = x
        else:
            runs.append([x, x])
    inner = [r for r in runs if r[0] != 0 and r[1] != w - 1]
    if not inner:
        raise ValueError(f"only capsule edges in {rect}")
    return float(inner[-1][1] - inner[0][0] + 1)


def measure_captures() -> None:
    for s in SAMPLES:
        for side, folder, rect in (("ios_px", IOS_DIR, s.ios), ("and_px", AND_DIR, s.android)):
            im = _gray(os.path.join(folder, f"{s.screen}.png"))
            value = _ink_pill(im, rect) if s.pill else _ink_light(im, rect)
            setattr(s, side, value)


# ── 2. font model ───────────────────────────────────────────────────────────
_font_cache: dict[tuple, "ImageFont.FreeTypeFont"] = {}


def instance_path(wdth: float, wght: int, opsz: float, xtra: float | None) -> str:
    os.makedirs(CACHE, exist_ok=True)
    key = f"w{wdth:g}_g{wght}_o{opsz:g}" + (f"_x{xtra:g}" if xtra is not None else "")
    path = os.path.join(CACHE, f"rf_{key}.ttf")
    if not os.path.exists(path):
        font = TTFont(FONT)
        loc = {"wdth": wdth, "wght": float(wght), "opsz": opsz}
        if xtra is not None:
            loc["XTRA"] = xtra
        instancer.instantiateVariableFont(font, loc, inplace=True, updateFontNames=False)
        font.save(path)
        font.close()
    return path


def pil_font(px: int, wdth: float, wght: int, opsz: float, xtra: float | None):
    key = (px, wdth, wght, opsz, xtra)
    f = _font_cache.get(key)
    if f is None:
        f = ImageFont.truetype(instance_path(wdth, wght, opsz, xtra), px,
                               layout_engine=ImageFont.Layout.BASIC)
        _font_cache[key] = f
    return f


def model_width(text: str, st: Style, wdth: float, opsz: float, xtra: float | None = None) -> float:
    """Ink advance of `text` in px, including the style's letter spacing."""
    f = pil_font(st.px, wdth, st.weight, opsz, xtra)
    box = f.getbbox(text)
    return (box[2] - box[0]) + st.tracking_px * (len(text) - 1)


# ── 3/4. fit ────────────────────────────────────────────────────────────────
PROBES = (88.0, 100.0, 112.0, 124.0)


def _quad(xs, ys):
    """Least-squares quadratic through >=3 (x, y) points."""
    n = len(xs)
    sums = [sum(x ** k for x in xs) for k in range(5)]
    m = [[sums[i + j] for j in range(3)] for i in range(3)]
    m[0][0] = float(n)
    rhs = [sum(y * x ** k for x, y in zip(xs, ys)) for k in range(3)]
    # Gaussian elimination on the 3x3 normal equations.
    a = [row[:] + [r] for row, r in zip(m, rhs)]
    for i in range(3):
        p = max(range(i, 3), key=lambda r: abs(a[r][i]))
        a[i], a[p] = a[p], a[i]
        for r in range(3):
            if r == i:
                continue
            fct = a[r][i] / a[i][i]
            for c in range(i, 4):
                a[r][c] -= fct * a[i][c]
    c = [a[i][3] / a[i][i] for i in range(3)]
    return lambda x: c[0] + c[1] * x + c[2] * x * x


def fit_style(name: str, st: Style, samples: list[Sample], fit_tracking: bool):
    opsz_new = st.opsz()
    base = [model_width(s.text, st, st.wdth_now, st.opsz_now) for s in samples]
    curves = []
    for s, b in zip(samples, base):
        ys = [model_width(s.text, st, w, opsz_new) / b for w in PROBES]
        curves.append(_quad(PROBES, ys))

    def cost(wdth: float, dtrack: float) -> float:
        total = 0.0
        for s, curve in zip(samples, curves):
            pred = s.and_px * curve(wdth) + dtrack * (len(s.text) - 1)
            total += ((pred - s.ios_px) / s.ios_px) ** 2
        return total

    best = None
    tracks = [0.0] if not fit_tracking else [t / 20.0 for t in range(-20, 21)]
    w = 60.0
    while w <= 151.0:
        for dt in tracks:
            c = cost(w, dt)
            if best is None or c < best[0]:
                best = (c, w, dt)
        w += 0.25
    _, wdth, dtrack = best

    # Re-verify on a real instance rather than the quadratic surrogate.
    rows = []
    for s in samples:
        b = model_width(s.text, st, st.wdth_now, st.opsz_now)
        exact = model_width(s.text, st, wdth, opsz_new)
        pred = s.and_px * exact / b + dtrack * (len(s.text) - 1)
        rows.append((s, pred))
    return wdth, dtrack, opsz_new, rows


def rms(rows) -> float:
    return (sum(((p - s.ios_px) / s.ios_px) ** 2 for s, p in rows) / len(rows)) ** 0.5 * 100


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--validate", action="store_true",
                    help="only report the round-1 (current NT.kt) deviation")
    ap.add_argument("--tracking", action="store_true",
                    help="also fit a letterSpacing delta per style")
    ap.add_argument("--opsz", default="auto",
                    help="'auto' (14 below 20 pt, point size above), 'size', 'keep', or a number")
    args = ap.parse_args()

    if args.opsz != "auto":
        for st in STYLES.values():
            if args.opsz == "size":
                st.opsz_new = float(st.size)
            elif args.opsz == "keep":
                st.opsz_new = st.opsz_now
            else:
                st.opsz_new = float(args.opsz)

    measure_captures()

    by_style: dict[str, list[Sample]] = {}
    for s in SAMPLES:
        by_style.setdefault(s.style, []).append(s)

    print(f"{'style':16} {'n':>2}  {'now':>6} {'iOS/And':>8}   ->  "
          f"{'wdth':>6} {'opsz':>5} {'dTrack em':>10}  {'RMS now':>8} {'RMS fit':>8}")
    print("-" * 96)
    results = {}
    for name, st in STYLES.items():
        samples = by_style.get(name, [])
        if not samples:
            continue
        ratio = sum(s.ios_px / s.and_px for s in samples) / len(samples)
        now_rows = [(s, s.and_px) for s in samples]
        if args.validate:
            print(f"{name:16} {len(samples):2}  {st.wdth_now:6.1f} {ratio:8.4f}"
                  f"{'':>28}  {rms(now_rows):7.2f}%")
            continue
        wdth, dtrack, opsz, rows = fit_style(name, st, samples, args.tracking)
        dt_em = dtrack / (st.size * SCALE)
        results[name] = (wdth, opsz, st.tracking_em + dt_em)
        print(f"{name:16} {len(samples):2}  {st.wdth_now:6.1f} {ratio:8.4f}   ->  "
              f"{wdth:6.1f} {opsz:5.0f} {st.tracking_em + dt_em:+10.4f}  "
              f"{rms(now_rows):7.2f}% {rms(rows):7.2f}%")

    if args.validate:
        return

    print("\nPer-sample residual after the fit (px, capture scale 3x):")
    for name, st in STYLES.items():
        samples = by_style.get(name, [])
        if not samples:
            continue
        wdth, opsz, track = results[name]
        dtrack = (track - st.tracking_em) * st.size * SCALE
        print(f"\n  {name}  wdth={wdth:.1f} opsz={opsz:.0f}")
        for s in samples:
            b = model_width(s.text, st, st.wdth_now, st.opsz_now)
            pred = s.and_px * model_width(s.text, st, wdth, opsz) / b + dtrack * (len(s.text) - 1)
            print(f"    {s.text[:44]:46} iOS {s.ios_px:6.0f}  r1 {s.and_px:6.0f}"
                  f" ({100 * (s.and_px - s.ios_px) / s.ios_px:+5.1f}%)"
                  f"  fit {pred:6.1f} ({100 * (pred - s.ios_px) / s.ios_px:+5.1f}%)")

    print("\nKotlin (designsystem/NT.kt):")
    for name, (wdth, opsz, track) in results.items():
        print(f"    {name:16} wdth={round(wdth * 2) / 2:g}f  opsz={opsz:g}f  letterSpacing={track:+.4f}f.em")


if __name__ == "__main__":
    main()
