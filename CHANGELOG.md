# Changelog

## 0.2.0

- **Fix**: a tap on the page coming back is no longer taken by the page leaving. A page leaving the
  top but drawn over it — a pop, or a push sent back halfway — stayed on top for touches too until it
  was gone, so tapping the list again right after going back did nothing. Such a page is now laid out
  under every page and drawn in its place by a stand-in nothing can touch: it looks the same, and
  touches reach the page on top of the stack. Compose's `NavDisplay` still loses such a tap for up to a
  quarter of a second (`TapDuringPopTest`, in the README); Flight takes it from the first frame the
  list is on screen.
- **Fix**: a `FlightPaint` draws in the element's own bounds. Its `DrawScope` used to be the whole
  overlay, only moved to the element's corner, so `size` and `center` were the screen's: a
  `drawCircle(color)` with no centre — the README's own example — flew half a screen off the element
  and jumped onto it on landing. Paints that drew from the `size` parameter and the origin are
  unaffected.
- Side by side with Compose's shared elements: `CompareActivity` in the demo plays the same script on
  Flight and on `SharedTransitionLayout` + `NavDisplay`, recorded in the README.
- How light it is: `CostTest` counts what the script costs each engine's pages (compositions,
  measures, placements, draws) and runs in CI; `:benchmark` times it on a phone (stutters, frames,
  CPU per thread, memory), with a finger resting on the screen so that adaptive displays stay at their
  full rate. The script includes a quick swipe back with the element tapped again straight after.

## 0.1.1

- `FlightHostState.drawnBy(key)` is public, as `drawnBounds` already was: in a test, which page holds an
  element once a gesture is over, or `"air"` while it flies.

## 0.1.0

- First release: `FlightHost`, `FlightNavDisplay`, anchors, `FlightOverlay`, `PagePresence`.
