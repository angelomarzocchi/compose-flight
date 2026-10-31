# Changelog

## Unreleased

- **New**: `FlightAnchor.canLand`. An end that is itself fading can keep the element in the air, drawn
  whole, until it can show it: landed there, the element would fade with it, or vanish.
- **Fix**: an end going away while its element flies no longer doubles the element's light. The cross-fade
  was left halfway between two looks that were now the same one, so `RecordedPaint` added a recording to
  itself until the element landed.
- **New**: `FlightAnchor.hasOtherEnd()`, to keep an element that will fly out of its screen's fade.
- **New**: `recordedFlightAnchor(anchor, spill)`. With `RecordedPaint(overflow = true)`, what an end draws
  outside its box is no longer cut while the ends cross-fade (a border centred on the edge lost half its
  width, a glow disappeared): a recording drawn see-through passes through a layer of its own size, now
  `spill` larger. The cross-fade's own layer goes as far as that, instead of three times the element's size.
- **New**: `FlightAnchor.held`. Under a back gesture a held element stays with its screen, wherever the
  screen moves it, instead of hanging between the two pages; it takes off when the page changes, from
  where it is and at the speed it had. For a screen that drives the back gesture itself, with the
  element under the finger (drag a photo down to go back to the grid).
- **Fix**: an element going home to a page that is leaving fades with that page. Sent back to a list
  and tapped again straight away, an element on its way to the list was left in the air with nowhere
  on the new page to go, and stayed drawn over that page, opaque, for up to half a second until it
  landed on a page nobody could see any more.
- **Fix**: an element in the air with no place left to go rests on the end there is, rather than being
  drawn where it last was.

## 1.0.0

- First stable release. The API is the one of 0.2.0; from here on it follows semantic versioning.

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
