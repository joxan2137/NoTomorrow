# Selection pill in motion (iOS 26.1, Simulator "Slow Animations" 10x, burst screenshots)

`burst-01..30.png` are consecutive full-frame captures (~150 ms apart at 10x slow-mo) while the tab
selection moved across the bar; `motion-strip.png` stacks the tab-bar crops (y 2340-2570 px) of the
moving frames; `key-*.png` are the clearest mid-travel frames.

What the pill does while it moves (see motion-strip.png rows 4-5, 8-9, 15-16):
- it is a Liquid Glass LENS (`_UILiquidLensView`): it grows from the 77x54 rest capsule into a larger,
  rounder blob and stretches along the direction of travel;
- it magnifies and displaces the icons/labels beneath it, with visible chromatic fringes (dispersion)
  at the edges of bright glyphs, and carries a thin bright specular rim;
- it settles into the flat additive +31/255 capsule at rest (no rim, no lens) — the state measured in
  `docs/android-glass.md` §1.2, which is why the first Android build only reproduced the rest state.
