# No Tomorrow v2 — UI redesign proposal

A proposal to evolve direction A (ember on near-black, iOS 26 glass) rather than replace it, plus
a working prototype of every tab and the live workout.

| Path | What it is |
|---|---|
| `index.html` | The proposal: diagnosis of the current UI, the seven design moves, before/after, token changes, rollout plan. Embeds the prototype. |
| `app/` | Interactive prototype (plain HTML/CSS/JS, no build step). Opens framed on desktop and full screen on a phone. |
| `app/muscles.js` | Generated from `NoTomorrow/Resources/muscle_model.json` for the muscle map. |
| `img/` | `before-*` are downscaled copies of `design/parity/ios`; `after-*` are renders of the prototype. |

Run it with any static server from this folder, e.g. `npx http-server design/v2`, then open
`/` for the proposal or `/app/` for the prototype. Deep links such as `/app/#workout`, `#fuel`,
`#ai`, `#summary`, `#body` or `#cant` open a screen in a given state.

All sample data in the prototype is invented.
