# Machine scan

Scan a gym machine's name plate with the camera and add the matching exercise to the workout.
iOS: `Features/Workout/MachineScanView.swift`, `Services/MachineLabelMatcher.swift`.
Android: `feature/workout/MachineScanScreen.kt`, `service/MachineLabelMatcher.kt`.

## Flow

1. The exercise picker's header has a camera button ("Scan a machine").
2. The scanner reads text live from the rear camera, on device: VisionKit `DataScannerViewController`
   on iOS, CameraX + ML Kit text recognition (bundled Latin model) on Android. Nothing is uploaded.
3. Every ~300 ms the recognised lines go through the matcher; up to three matches show in the bottom
   panel. Live matches stay on screen while the camera briefly loses the text.
4. **Add** on a match selects it (together with anything already ticked in the picker) and adds it
   to the workout, exactly like the picker's "Add n" button.
5. **Photo** reads a picked photo instead (the path on the simulator, devices without live text,
   or with the camera denied). **Search** closes the scanner and puts the most prominent text read
   into the picker's search field.

Exercises already in the workout are never offered.

## Matching

- Text is folded (case, diacritics, `ł`), split on anything but a–z/0–9, and "pull down",
  "push down", "pec deck" are joined. Stop words, numbers and single letters are dropped; plurals
  are stemmed (`-sses`→`-ss`, `-ies`→`-y`, trailing `-s` unless `-ss`/`-us`), then a few canonical
  forms (`flye`→`fly`, `abduction`→`abductor`, `ab`/`abs`→`abdominal`, `delt`→`deltoid`, …).
  On the label side `pec` and `pectoral` also count as `butterfly`.
- Each line weighs its text height over the tallest line's, so the machine name outweighs the fine
  print. A label word keeps the weight of its most prominent line. A word the library never uses is
  ignored, except that a 6+ letter word one edit away from a library word (an OCR misread) counts
  as that word at 0.7.
- A word's rarity is `ln(1 + N / df)` over the English and Polish names of all N exercises.
- For each exercise name (English and Polish, the better one wins):
  `recall = 0.5 · Σ rarity·w / Σ rarity + 0.5 · Σ w / words`, `precision = Σ rarity·w / label mass`,
  score = F1. Free-weight equipment (dumbbell, barbell, bands, …) is scaled by 0.85, since placards
  sit on machines and cable stacks.
- Matches under 0.4 are dropped; best three are shown, ties go to the shorter name.

Both platforms run the same test cases against the bundled library
(`MachineLabelMatcherTests.swift`, `MachineLabelMatcherTest.kt`).
