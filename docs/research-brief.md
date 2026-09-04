# No Tomorrow — research brief (2026-09-04)

Condensed from five fact-checked research passes. Corrections from the verification pass are already folded in. Full transcripts live in the session scratchpad; this file is what the build phase should read.

## 1. Platform decisions

- **Native SwiftUI, iOS 17+.** SwiftData for local storage, Swift Charts for progress, ActivityKit Live Activity for the rest timer (iOS 16.1+, Dynamic Island), VisionKit `DataScannerViewController` for barcodes (iOS 16+, A12+), String Catalogs (`.xcstrings`) for EN/PL plurals.
- **Do not put workout/nutrition data in iCloud/CloudKit.** App Store Review Guideline 5.1.3(ii): apps "may not store personal health information in iCloud". Keep logs local (SwiftData) with CSV export; ship export from day one (users feel locked in otherwise).
- **Gym-bro sync needs its own backend.** CloudKit `CKShare` would be iOS-only, has no invite codes natively, no server code for the Claude key, and unspecified push latency. Use a small backend (Supabase: Postgres + Realtime + Edge Functions + APNs via its push, Pro tier $25/mo when projects must not pause; or a Cloudflare Worker + D1). Only schedule, attendance, heads-up messages and the pairing code go there. The same backend proxies the Claude API key (never ship the key in the binary; gate the proxy with App Attest + per-user rate limits).
- **Rest timer must survive backgrounding and termination:** persist an absolute end timestamp, schedule a local notification, run a Live Activity; use a mixable audio session so the chime never pauses the user's music. Do not start the timer before a drop set.
- **Dark-first, but HIG says avoid an in-app appearance toggle.** Ship dark-only in v1 (allowed "in rare cases") or follow the system. No appearance row in Settings.

## 2. App Store guidelines that bite this app

| Rule | Consequence |
|---|---|
| 1.2 UGC | Free-text messages between users make it a UGC app: need filtering, report, block, published contact. Cheapest: "can't make it" = canned reasons + optional short note, plus block/report on the pairing. |
| 1.4.1 | AI calorie numbers are an accuracy claim. Label as estimates, disclose method (vision model + named food database), remind users to consult a professional. |
| 2.1 | Give App Review a demo account; a pre-paired second account makes the bro feature testable. |
| 2.3.7 | App name ≤30 chars; no unverifiable accuracy claims in subtitle. |
| 2.5.1 / HealthKit | If HealthKit capability is enabled, actually read/write and say so in the description. `NSHealthShareUsageDescription` (read) + `NSHealthUpdateUsageDescription` (write) both required. Remove `healthkit` from `UIRequiredDeviceCapabilities` if optional. |
| 4.8 | If any third-party login is offered, also offer Sign in with Apple. Own-account-only avoids it. |
| 5.1.1(v) | Solo logging must work without an account; account deletion must be in-app. |
| 5.1.2(i) | Photos sent to a hosted AI model = sharing with third-party AI: name it, get explicit consent, list it in the privacy label and policy. |
| 5.1.3(ii) | No health info in iCloud; do not auto-write unconfirmed AI estimates to HealthKit. |

Privacy nutrition label: uploading a meal photo for AI analysis counts as collection unless discarded after the response. Polish-language privacy policy URL is expected on the PL listing; Polish description and keywords do not inherit from English and must be written.

## 3. HIG numbers used in the prototypes

- iPhone 15 Pro / 16: 393×852 pt; 17 Pro / 16 Pro: 402×874 pt. Safe-area top ≈59 pt (62 on 402-wide), bottom 34 pt, tab bar 49 + 34 = 83 pt (all unofficial; read `safeAreaInsets` at runtime). iOS 26 tab bar floats over content (Liquid Glass) — let scroll content run under it.
- Type ramp (Large default): Large Title 34/41, Title 1 28/34, Title 2 22/28, Title 3 20/25, Headline 17/22 semibold, Body 17/22, Callout 16/21, Subhead 15/20, Footnote 13/18, Caption 1 12/16, Caption 2 11/13. Minimum 11 pt. Use `.monospacedDigit()` for timers, set tables, weights, kcal. SF Rounded is a good fit for the countdown numerals.
- Hit target 44×44 pt default, 28×28 minimum. Contrast ≥4.5:1 for text ≤17 pt (3:1 for ≥18 pt or bold). All current dark-mode system accents pass 4.5:1 on `#000`; the tight case is on `#1C1C1E` cards.
- Dark mode: prefer system background colors (base `#000` → elevated `#1C1C1E` for sheets); label hierarchy via secondary/tertiary label alphas (0.6 / 0.3); darken white-background photos (product packaging, plates) so they don't glow.
- Avoid full-width edge-to-edge buttons; inset from margins (16 pt compact).

## 4. Data sources

- **Open Food Facts** (primary). Barcode: `GET https://world.openfoodfacts.org/api/v2/product/{barcode}.json?fields=...&lc=pl`. Filtered search: `/api/v2/search?countries_tags_en=poland&...`. Full-text: Search-a-licious `https://search.openfoodfacts.org/search?q=...&langs=pl` (weak relevance; typeahead only). User-Agent `NoTomorrow/1.0 (contact email)` required. Rate limits 15 req/min product, 10 req/min search, per end-user IP — search-as-you-type from the device gets blocked; mirror the Polish subset (~37,200 products, filtered locally from the full JSONL dump; no country-scoped dump exists) into the backend and fall through to live OFF on a miss. License ODbL + DbCL, images CC BY-SA; attribution required, commercial use OK. Fields: `product_name_pl`, `brands`, `quantity`, `serving_size`, `nutriments["energy-kcal_100g"]`, `proteins_100g`, `carbohydrates_100g`, `fat_100g`, `fiber_100g`, `image_front_small_url`.
- **USDA FoodData Central** (fallback for generic foods: chicken breast, oats). Free key, 1,000 req/h.
- Skip Edamam/Spoonacular (caching bans break offline), Nutritionix (no self-serve). FatSecret Premier is the only paid vendor with a real Polish dataset if OFF misses too much.
- **UPC-A barcodes arrive as EAN-13 with a leading 0 on Apple platforms** — query OFF with and without the zero. EAN-8 stays 8 digits.
- **Exercise DB: free-exercise-db (yuhonas)** — 876 exercises, 1,746 JPGs, The Unlicense (no attribution). English only; budget one LLM pass for Polish names + instructions, human-reviewed. Ship images in-bundle or on own CDN. Avoid wger (mixed CC-BY-SA share-alike) and ExerciseDB (AGPL).
- **HealthKit types:** `bodyMass` (read/write), `dietaryEnergyConsumed`, `dietaryProtein`, `dietaryCarbohydrates`, `dietaryFatTotal` (write), `HKWorkout` with `.traditionalStrengthTraining` + `activeEnergyBurned`. The app cannot distinguish denied read from empty data — always keep manual weight entry.

## 5. AI calorie estimation

- Vision LLMs land at ~35% mean absolute error on energy and **systematically underestimate, worse for large and high-fat portions** (Fridolfsson et al., Curr Dev Nutr 2025; NIH/ASN 2026: commercial apps low by 250–345 kcal/meal). Show that honestly.
- Prompt: image first, then text. Use structured outputs (`output_config.format`, `type: json_schema`): `foods[] {name_en, name_pl, estimated_grams, kcal, protein_g, carbs_g, fat_g, portion_reasoning, confidence}`, plus `scale_reference_used`, `overall_confidence`. Force a scale-reference step (plate ≈26–28 cm, fork ≈19 cm, fist ≈1 cup ≈150 g cooked rice, palm ≈100–120 g meat, thumb ≈1 tbsp fat). Inject context (meal slot, recent foods, locale PL).
- **Two-stage:** LLM identifies foods + grams; kcal/macros come from OFF/USDA rows, not model memory. Pre-populate a cooking-fat line (the #1 user complaint is unaccounted oil/butter). Every AI row is editable; mark AI entries visually distinct from scanned ones.
- Downsample to ~1024 px long edge before upload (1,296 image tokens). Cost per photo ≈ $0.003 (Haiku 4.5) / $0.006 (Sonnet 5). Sonnet 5 default; Haiku for a free tier. Sonnet 5 cache write is $2.50/MTok (5 min).
- Disclaimer (EN): "AI estimates are approximate. Photo-based calorie estimates typically differ from actual values by roughly a third and tend to underestimate large or high-fat portions. Always check and adjust the portion. No Tomorrow is not a medical device and does not provide medical, dietary, or nutritional advice; consult a qualified professional before making health decisions."
- Disclaimer (PL): "Szacunki AI są przybliżone. Szacunki kaloryczności ze zdjęcia zwykle odbiegają od wartości rzeczywistych o około jedną trzecią i zaniżają wartości dla dużych lub tłustych porcji. Zawsze sprawdź i skoryguj wielkość porcji. No Tomorrow nie jest wyrobem medycznym i nie udziela porad medycznych ani dietetycznych. Przed podjęciem decyzji dotyczących zdrowia skonsultuj się z lekarzem lub dietetykiem."

## 6. UX patterns worth copying (patterns, not visuals)

- Set row: `SET | PREVIOUS | KG | REPS | ✓`; tap the set number for W/D/F type; previous-value source is a setting (same exercise vs same exercise within this routine); swipe left to delete; plate calculator on the kg field; failure sets log the last completed rep, never 0.
- Rest timer auto-starts on ✓ (on by default, visibly dismissible), −15/+15, per-exercise duration with off, skipped before drop sets.
- Records in two tiers: frequent **set records** (heaviest for N reps) and rare **PRs** (heaviest, best e1RM, best set volume, best session volume). Celebrate the moment the set is ticked; keep a medal on the saved workout.
- Progress: metric chips (Heaviest, 1RM, Best set volume, Session volume, Total reps), ranges 3M/1Y/All, volume per muscle group, body-weight trend = interpolated raw line + smoothed bold trend, change rate over 20 days.
- e1RM: Epley `w × (1 + r/30)`; Brzycki `w × 36 / (37 − r)`. Show "true" 1RM when a single was actually lifted.
- Nutrition: one plate/cart where scan, search, quick-add, AI photo interleave; recents scoped by hour of day; favorites pinned at a serving size; copy day/meal; consumed↔remaining toggle; no red shaming numbers; barcode miss → label OCR fallback; always rear camera; every scanned value editable; autosave drafts.
- Onboarding: ask only days + time (+ optional pairing); everything else in Settings; never questionnaire→paywall. No interstitial ads in the log loop (the dominant Polish-market complaint about Fitatu).
- Partner: nobody major has "can't make it". Ship: shared week goal, one-tap "I'm in", shared streak, heads-up with reason + make-up day that writes a missed entry both see.

## 7. Polish localization rules

- Address the user per "ty", sentence case everywhere, imperative on buttons (Zacznij, Dodaj, Zeskanuj, Zapisz). Weekdays and months lowercase. **Avoid gendered past tense** in system messages: "Trening zapisany." not "Zapisałeś trening."
- Vocabulary: trening (never "sesja"), seria/serie/serii = set (never for streak — streak = passa), powtórzenie (header "powt."), ciężar = load (waga = body weight), przerwa, timer przerwy, rekord życiowy / życiówka, objętość, szacowany 1RM, kalorie, białko, węglowodany (chip "węgle"), tłuszcze, błonnik, śniadanie / obiad (= lunch) / kolacja (= dinner) / przekąska, zeskanuj kod kreskowy, szukaj produktu, oszacuj z AI, popraw, grafik (schedule), dni treningowe, "Dziś nie dam rady", "Spóźnię się", "Lecimy!", zaliczony / opuszczony. Keep Push / Pull / Core / 1RM / PR in English.
- Gym bro: "ziomek z siłki" (slang, masculine), "partner treningowy" (neutral). Tab label options: Ziomek / Ekipa / Kumpel.
- Plurals (CLDR one/few/many/other): 1 seria, 2 serie, 5 serii, 1,5 serii; 1 powtórzenie, 2 powtórzenia, 5 powtórzeń; 1 dzień, 2 dni, 5 dni; 1 tydzień, 2 tygodnie, 5 tygodni. `other` fires for decimals; do not skip it.
- Formatting: 24 h clock, decimal comma (82,5 kg), space thousands separator with non-breaking spaces (12 500 kg), space before units (80 kg, 15 s). Dates: `d MMMM` → "4 września" (genitive month), `E, d MMM` → "śr., 8 wrz"; standalone month label is nominative ("wrzesień"). "w środę o 18:00", irregular "we wtorek". Week starts Monday.
- Weekday abbreviations: CLDR `pon. wt. śr. czw. pt. sob. niedz.`; 2-char set for calendar strips per PWN: `PN WT ŚR CZ PT SB ND`. Never CLDR narrow (`p w ś c p s n` has two "p").
- Taglines: "Nie ma jutra." / "Nie ma jutra. Jest dzisiaj." / "Jutro nie zrobi tego za Ciebie." Motivational lines that land: "Jedna seria więcej niż wczoraj.", "Sztanga sama się nie podniesie.", "Ziomek już czeka pod klatką." Avoid "mistrzu", "bestia", "wojownik".
- HealthKit purpose string (PL): "No Tomorrow odczytuje Twoją wagę, aby pokazać postępy, i przenosi zapisane posiłki i treningi do aplikacji Zdrowie."
