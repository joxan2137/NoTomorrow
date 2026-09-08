# Exercise and nutrition upgrade

Both iOS and Android now bundle 900 exercises (24 additions), including Polish names. Import is additive on existing installations and preserves custom exercises and workout history. The Details control opens an offline front/back muscle model with separate primary/secondary highlights, instructions, and the original public-domain demonstration photos when available. The new exercises have muscle diagrams and instructions; they do not have fabricated demonstration photos. Photos need a network connection. The diagram illustrates anatomical regions, not measured muscle activation or a 3D simulation.

Barcode lookup uses the worldwide Open Food Facts catalog with Polish names, accepts EAN-8, UPC-A, EAN-13 and padded GTIN-14 forms, and converts kJ-only labels to kcal. It does not require a Polish barcode prefix: Polish retailers sell imported products too. Mass parsing handles `2 x 15 g`, `1 porcja (30 g)` and `0,5 kg`; it no longer treats millilitres as weighed grams. Network errors are distinct from absent products. Manual code entry is available while the camera works. Unknown items can be saved from the printed per-100 g label; subsequent exact-code scans check the local database first. This is a local correction, not a contribution to OFF. Retailer-internal weight codes and missing catalog entries cannot be guaranteed globally.

The backend defaults to `gemini-3.8-flash`. Configure `GEMINI_API_KEY` on the server, and remove/update any existing `GEMINI_MODEL` override. No key belongs in either mobile app. Fallback models are opt-in through `GEMINI_FALLBACK_MODELS`; the requested model is not silently downgraded by default. Existing authentication, provider consent, image size limits, quota reservation and quota release on failure remain in place. This change does not deploy the backend or change production secrets.

`POST /ai/estimate` accepts an optional multipart `notes` field (up to 1,500 characters). The two apps send weighed portions, cooking details and eaten quantities from this field, and allow recalculation after reading returned assumptions and questions. Recalculation consumes another daily estimate. Responses retain the legacy foods and macro aliases and add optional assumptions, questions and scale-reference metadata.

Accuracy safeguards:

- Structured per-100 g nutrients are scaled to the estimated portion by server arithmetic.
- Invalid mass, non-finite values, impossible nutrient densities and malformed food arrays fail instead of being logged.
- The prompt distinguishes cooked/raw food, per-serving/per-100 g labels, edible mass, kcal/kJ and added fat, and avoids deliberately rounding portions upward.
- Exact readable, checksum-valid product barcodes can ground nutrients in OFF; missing/incomplete lookup results retain the photo estimate. Portion mass remains an estimate even when nutrient density is grounded. Retailer-internal variable-weight codes are excluded from this grounding.
- Reasoning text is excluded from JSON parsing. Gemini requests have a timeout and interaction storage is disabled.

There is no measured accuracy claim. A single photo cannot reveal hidden ingredients or reliably establish portion mass. Before release, evaluate consented photos against weighed recipes and current labels, stratified by packaged Polish products, mixed dishes, low light and oily food. Track median/mean absolute kcal error, portion-mass error, signed bias, and the failure rate. Compare photo-only and weighed-notes runs on held-out meals; never treat model confidence as a calibrated probability. Actual Biedronka/Lidl/Auchan camera scans and a live Gemini call still require device/API validation.

Sources: [Gemini 3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash), [structured output](https://ai.google.dev/gemini-api/docs/structured-output), [Open Food Facts barcode guide](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/tutorials/scanning-barcodes/), [exercise dataset and photo license](https://github.com/yuhonas/free-exercise-db).
