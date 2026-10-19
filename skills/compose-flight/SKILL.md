---
name: compose-flight
description: How to use the Flight library (io.github.angelomarzocchi.flight) for interruptible shared-element transitions in Jetpack Compose with Navigation 3. Load it before adding an element that flies between screens, before replacing SharedTransitionLayout / sharedElement / NavDisplay with Flight, before writing a FlightPaint, PageMotion or anything that reads LocalPagePresence, and when a flight jumps, blinks, lands in the wrong place or does not fly at all.
---

# Flight

Flight flies an element between two screens as a spring-driven object whose target is read from the
back stack. It replaces `SharedTransitionLayout` + `Modifier.sharedElement` + `NavDisplay`. Do not mix
the two systems on the same screens.

Package: `io.github.angelomarzocchi.flight`.
Gradle: `implementation("com.github.angelomarzocchi:compose-flight:<version>")` from
`https://jitpack.io`. minSdk 24, compileSdk 37.

## The three pieces — all three are required

1. **`FlightHost`** round the navigation. It runs the frame loop and draws elements in the air.
2. **`FlightNavDisplay`** instead of `NavDisplay`. Same parameters (`backStack`, `onBack`,
   `entryDecorators`, `sceneStrategies`, `entryProvider`) plus `pageMotion` / `gestureMotion`.
   It is what tells the host which page is on top and how far a back gesture has gone.
3. **An anchor on every screen** where the element appears, all with the **same key**.

```kotlin
FlightHost(Modifier.fillMaxSize()) {
    FlightNavDisplay(backStack = backStack, entryProvider = entryProvider {
        entry<ListKey> { ListScreen() }
        entry<DetailKey> { DetailScreen(it.id) }
    })
}
```

Without a `FlightHost` up the tree, `rememberFlightAnchor` returns null and every modifier that takes
the anchor is a no-op: the screen draws the element in place and nothing flies. That is the intended
behaviour in previews and screenshot tests, and the first thing to check when "nothing flies".

## Making an element fly

Choose one of the two ways to draw it in the air.

**A. Painted** — when you can draw the element with a few `DrawScope` calls (a circle, an avatar, a
shape). Sharp at every size.

```kotlin
val Dot = FlightPaint { size, from, to, morph ->
    val color = lerp(from as Color, to as Color, morph)
    drawCircle(color, radius = size.minDimension / 2f)
}

@Composable
fun Dot(id: String, color: Color, modifier: Modifier) {
    val anchor = rememberFlightAnchor(key = DotKey(id), paint = Dot)
    anchor?.look = color                 // what this end looks like; becomes `from`/`to`
    Canvas(modifier.flightAnchor(anchor).flightHole(anchor)) { drawCircle(color) }
}
```

- `flightAnchor(anchor)` marks where the element rests on this screen.
- `flightHole(anchor)` hides the screen's own drawing while the element is away. **Forget it and the
  element is drawn twice** during a flight.
- `look` can be anything (a `Color`, a data class); the paint casts it back. Set it every composition.
- `morph` goes 0→1 from `from`'s look to `to`'s; it turns back with the flight.

**B. Recorded** — when the element is the screen's own content at two sizes (a title, a row of
avatars, a chip). No paint code.

```kotlin
val anchor = rememberFlightAnchor(TitleKey(id), remember { RecordedPaint(single = false) })
Text(title, style = style, modifier = Modifier.recordedFlightAnchor(anchor))
```

`recordedFlightAnchor` does `flightAnchor` + `flightHole` + recording. `RecordedPaint(single = false)`
cross-fades the two ends' recordings as the size changes; `single = true` scales only the larger one.

## Rules that keep a flight correct

- **Keys**: the same key on both screens, and unique per element (`PlanetKey(id)`, not `"planet"`).
  Use data classes or strings; never a lambda or an object created in composition.
- **Paint stability**: create a `FlightPaint` once (top-level `val` or `remember`). A new paint object
  every composition is harmless but wasteful.
- **Anchors live inside pages**: an anchor must be composed inside a `FlightNavDisplay` entry to know
  which page it is on. An anchor outside any page (e.g. in an app bar) is allowed; it is where the
  element goes when no page on top has it — pair it with `FlightLayer.Chrome` (below).
- **Do not read `LocalNavAnimatedContentScope`** inside `FlightNavDisplay`: it does not exist and
  throws. For content that should fade/slide *with* its page, read
  `LocalPagePresence.current?.rememberShown()` (a `State<Float>` 0→1) and apply it in `graphicsLayer`.
- **`isSettled`**: `LocalPagePresence.current?.isSettled` is true once the page is on top and still —
  use it to start work that should wait for the transition (e.g. requesting focus).
- **Lazy lists**: anchors inside `LazyColumn` items work, including while the list scrolls; the flight
  rides the scroll. Give the item a stable `key`.
- **One layer per element**: every anchor of one key must use the same `FlightLayer`.

## Placement

- Inside a `Scaffold`, put `FlightHost` in the content slot (padded by the Scaffold's padding): flights
  then pass *under* the top bar, like the pages.
- For an element that lives in a bar and flies into a page (an avatar → profile), give its anchors
  `layer = FlightLayer.Chrome`, hold the `FlightHostState` yourself, and draw
  `FlightOverlay(state, FlightLayer.Chrome, Modifier.matchParentSize())` as the last child of the box
  that holds both the Scaffold and the host.

## Page motion

`pageMotion: () -> PageMotion` is asked once per change of page; `gestureMotion` is used under a
predictive back gesture. A `PageMotion` is a pure function of the page's presence (0 gone → 1 on top):

```kotlin
val Fade = PageMotion { presence, arriving, back -> alpha = presence.coerceIn(0f, 1f) }
```

`arriving`/`back` say how the current trip began and stay fixed if it is turned round. Clamp alpha:
the presence can overshoot 0..1 slightly. Override `under`/`over` to paint a backdrop or a veil inside
the page's layer, and `covers = true` for a motion where the new page always grows over the old one.

## Springs

Defaults are Material 3 Expressive's (`FlightSprings.Default`). To follow a theme's motion scheme:
`FlightSprings(spatial = FlightSpring.of(scheme.defaultSpatialSpec<Float>(), FlightSpring.Spatial), …)`
passed to `FlightHost(springs = …)`. With system animations off (`animator duration scale 0`) flights
jump to their target.

## Testing a flight

`FlightHostState` exposes `isInAir(key)`, `isFlying` and `drawnBounds(key)` (in the host's pixels).
Drive the clock by hand (`mainClock.autoAdvance = false`, `advanceTimeByFrame()`), record
`drawnBounds(key)!!.center` every frame, and assert continuity: the largest frame-to-frame change of
velocity of an interrupted flight should stay within ~2× that of an uninterrupted one. A flight takes
off within ~3 frames of the back stack changing (composition, layout of the new anchor, first step).

## Troubleshooting

| Symptom | Cause |
|---|---|
| Nothing flies | No `FlightHost` above, `NavDisplay` used instead of `FlightNavDisplay`, keys differ, or the key is null. |
| Element drawn twice mid-flight | Missing `flightHole` (or use `recordedFlightAnchor`). |
| Element blinks at take-off or landing | The paint draws differently from the screen at rest; draw the same thing, at the given `size`. |
| Lands a few pixels off its place | The anchor reports where its modifier sits in the chain: put `flightAnchor` after the modifiers that move or pad the element (`offset`, `padding`), so it measures the bounds that are actually drawn. |
| Crash reading `LocalNavAnimatedContentScope` | Use `LocalPagePresence`. |
| Element flies under the top bar but should be over it | Use `FlightLayer.Chrome` and a `FlightOverlay` outside the Scaffold. |
