# Vendored Swift packages

UI effects from [libraries.dev](https://libraries.dev/) by Jakub Antalik, copied from
[Jakubantalik/Libraries.dev](https://github.com/Jakubantalik/Libraries.dev) at `f201163` and wired
into the app target as local packages in `project.yml`. Each keeps its upstream `LICENSE` (MIT);
MetalFxKit also carries a `NOTICE` for Paper Shaders' `liquidMetal` material (Apache-2.0).

| Package | Upstream path | Used for |
|---|---|---|
| `ThinkingOrbsKit` | `packages/thinking-orbs/ports/ios/ThinkingOrbsKit` | Loading orbs: AI photo estimate (`searching`, 64 pt), nutrition-label read (`searching`, 20 pt), barcode lookup pill (`connecting`, 20 pt) |
| `BorderBeamKit` | `packages/border-beam/ports/ios/BorderBeamKit` | The sunset beam round the photo while the AI estimate runs |
| `MetalFxKit` | `packages/metal-fx/ports/ios/MetalFxKit` | Exercise picker: the search field's chromatic edge and the silver edge of selected result cards |

The only local change is that the test targets were dropped from the manifests (their golden
files live elsewhere in the upstream repo). The READMEs are upstream's and still mention those
tests and scripts.

`BorderBeamKit` and `MetalFxKit` compile `.metal` shaders, which needs Xcode's build system
(`xcodebuild`), not `swift build`.

## Android

The Android app has no equivalent packages; `android/app/src/main/java/app/notomorrow/designsystem/effects/`
ports the same pieces:

- `OrbEngine.kt` / `ThinkingOrb.kt` transcribe the `searching` and `connecting` modes, checked
  against upstream's golden vectors (`OrbEngineTest`, `src/test/resources/orbs-golden-subset.json`).
- `BorderBeam.kt` and `LiquidMetal.kt` run upstream's React Native SkSL shaders as AGSL (API 33+)
  with the same numbers; below API 33 the beam is left out and the metal edge is a still gradient.
