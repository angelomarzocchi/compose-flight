# Flight

**Interruptible shared-element flights for Jetpack Compose and Navigation 3.**

Elements fly between screens as physical objects — a centre, a size and a look, each on a spring —
instead of as segments of a transition. The target is read from the back stack, which changes the
instant the user taps, so going back halfway through a flight is *the same flight with a new target*:
it curves round from where it is, at the speed it has. Nothing is rewound, nothing jumps, and nothing
waits for the page underneath to finish moving.

<p align="center">
  <img src="docs/media/flight.gif" width="320" alt="A ball flies between target A and target B on a drawn spring, turns round mid-flight and hangs between the two pages under a predictive back gesture">
</p>
<p align="center"><sub>
  The two anchors of one element (dashed), the spring pulling it towards the one the back stack points at,
  and the trail of its centre. Push, pop, a flight interrupted both ways, a predictive back gesture held,
  cancelled and completed. Slow motion ×2 (the system animator scale, which Flight honours) ·
  <a href="docs/media/flight.mp4">mp4</a>
</sub></p>

- **Interruptible everywhere.** Push, pop halfway, push again: the element never jumps or loses its speed.
  Pages are springs too, so the pages turn round with it.
- **Predictive back.** Under the back gesture the element hangs between the two pages, following the
  finger; let go and it carries on at the speed the finger gave it.
- **Drawn, not recorded — or recorded, when you want.** An element in the air is drawn by a
  `FlightPaint` you write (sharp at every size, no layout), or flown as the screen's own drawing with
  `recordedFlightAnchor` (a title at two type scales, cross-faded as it grows).
- **Cheap.** One frame loop, running only while something flies. A frame of flight redraws one overlay:
  no recomposition, no layout, not even the pages' own drawing.
- **Lands exactly.** It rides lists that scroll under it, and lands on its anchor only once the anchor's
  page has stopped, so the hand-over from the air to the page is invisible.

Flight comes from [Big Goal](https://github.com/angelomarzocchi), where it replaced the
`SharedTransitionLayout` + `NavDisplay` pair because their transitions could not be interrupted.

## Install

Flight is published on [JitPack](https://jitpack.io/#angelomarzocchi/compose-flight).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts of your app
dependencies {
    implementation("com.github.angelomarzocchi:compose-flight:0.1.1")
}
```

Requires minSdk 24, compileSdk 37, Compose BOM `2026.09.00` or later and Navigation 3 `1.2.0`
(both come with the library as `api` dependencies).

## Quick start

Three pieces: a **host** round the navigation, the navigation itself (**`FlightNavDisplay`**, a
drop-in for `NavDisplay`), and an **anchor** on each screen where the element sits, with the same key.

```kotlin
@Composable
fun App() {
    val backStack = remember { mutableStateListOf<Any>(Home) }
    FlightHost(Modifier.fillMaxSize()) {
        FlightNavDisplay(
            backStack = backStack,
            entryProvider = entryProvider {
                entry<Home> { PlanetList(onOpen = { backStack += Detail(it) }) }
                entry<Detail> { PlanetPage(it.planet) }
            },
        )
    }
}

// One look for the element in the air: a disc of the colour each end gave its anchor.
val Disc = FlightPaint { size, from, to, morph ->
    drawCircle(lerp(from as Color, to as Color, morph), radius = size.minDimension / 2f)
}

@Composable
fun PlanetDisc(planet: Planet, modifier: Modifier) {
    // Same key on every screen = one element.
    val anchor = rememberFlightAnchor(key = "planet-${planet.id}", paint = Disc)
    anchor?.look = planet.color
    Canvas(
        modifier
            .flightAnchor(anchor) // where it rests on this screen
            .flightHole(anchor),  // empty while it is anywhere else
    ) { drawCircle(planet.color) }
}
```

Put `PlanetDisc` in the list row at 48dp and on the detail page at 220dp: tapping a row flies the disc
into the page, and back flies it home.

### Flying the screen's own drawing

For something that is the same thing drawn twice — a title at two type scales — record it instead of
painting it:

```kotlin
@Composable
fun PlanetName(planet: Planet, style: TextStyle) {
    val anchor = rememberFlightAnchor("name-${planet.id}", remember { RecordedPaint(single = false) })
    Text(planet.name, style = style, modifier = Modifier.recordedFlightAnchor(anchor))
}
```

`RecordedPaint(single = false)` cross-fades the two recordings as the bounds grow from one to the
other; `single = true` flies only the larger one, scaled.

### Moving with the page

What is on a page and should come and go *with* it — rather than fly — reads the page's presence:

```kotlin
val shown by LocalPagePresence.current?.rememberShown() ?: remember { mutableFloatStateOf(1f) }
Text(blurb, Modifier.graphicsLayer { alpha = shown })
```

There is no `LocalNavAnimatedContentScope` inside `FlightNavDisplay`; use `LocalPagePresence`.

## API at a glance

| | |
|---|---|
| `FlightHost(state, springs) { … }` | The frame loop and the overlay flights are drawn in. Put it round the navigation. |
| `FlightNavDisplay(backStack, …, pageMotion, gestureMotion, entryProvider)` | `NavDisplay` with pages on springs, telling the host what is on screen and the back gesture. |
| `rememberFlightAnchor(key, paint, layer)` | This screen's copy of an element. Null outside a host or with a null key. |
| `Modifier.flightAnchor(anchor)` / `Modifier.flightHole(anchor)` | Where the element rests here / leave its place empty while it is away. |
| `Modifier.recordedFlightAnchor(anchor)` + `RecordedPaint` | Fly the screen's own drawing. |
| `FlightAnchor.look` | What this end looks like, handed to your `FlightPaint` as `from`/`to`. |
| `FlightPaint { size, from, to, morph -> }` | Draws the element in the air, `morph` 0→1 from one end's look to the other's. |
| `progressBetween(from, to, value, fallback)` | How far a growing element has grown: drive the rest of a morph off its size. |
| `FlightLayer.Chrome` + `FlightOverlay(state, layer)` | For an element that flies out of the app bars, over them. |
| `PageMotion`, `DefaultPageMotion` | How a page moves as its presence goes 0→1: slide, fade, scale, a veil (`under`/`over`), `covers`. |
| `LocalPagePresence` | A page's presence, for what moves with it. |
| `FlightSprings`, `FlightSpring` | The springs: spatial (with a small bounce), effects, gesture. |
| `FlightHostState.isInAir(key)`, `drawnBounds(key)` | For tests: whether an element flies, where it is drawn. |

### Your theme's springs

The defaults are Material 3 Expressive's. To fly on your own motion scheme:

```kotlin
val scheme = MaterialTheme.motionScheme
FlightHost(
    springs = FlightSprings(
        spatial = FlightSpring.of(scheme.defaultSpatialSpec<Float>(), FlightSpring.Spatial),
        effects = FlightSpring.of(scheme.defaultEffectsSpec<Float>(), FlightSpring.Effects),
    ),
) { … }
```

### Flying over the bars

A `FlightHost` inside a `Scaffold` draws its flights under the top bar, as the pages are. For an element
that lives in a bar (an avatar opening a profile), give its anchors `layer = FlightLayer.Chrome` and put
an overlay over the whole chrome:

```kotlin
val host = remember { FlightHostState() }
Box {
    Scaffold(topBar = { MyBar() }) { padding ->
        FlightHost(Modifier.padding(padding), state = host) { FlightNavDisplay(…) }
    }
    FlightOverlay(host, FlightLayer.Chrome, Modifier.matchParentSize())
}
```

The bar's own copy of the element sits outside any page; that is fine — an anchor on no page is where
the element goes home to when no page on top has it.

## How it works

Each element is a `FlightBody`: centre, size and morph, each with a velocity, stepped on
animation-core's analytic spring every frame. A frame works out an *aim* from the navigation: the
anchor on the page on top; under a back gesture, a point between the top page's anchor and the one
behind it, as far as the gesture has gone; or, when no page on top has the element, home to where it
was going. Changing the aim is all an interruption does, so nothing can jump.

It takes off from where its anchor is drawn that very frame, page transition and all, inheriting the
page's speed. It lands — hands itself back to the anchor — only once it is on its target and still,
and the target's page has stopped moving: so the anchor draws it exactly where it already was.

The pages are presences on springs (`PresenceDisplay`): a change of page changes targets, never a
presence, so the same rule holds for them.

## Demo

`demo/` is a list of planets that fly into their pages. `ExplainActivity` is the diagram above, which
plays by itself: `adb shell am start -n io.github.angelomarzocchi.flight.demo/.ExplainActivity` (set
the animator duration scale to 2 in developer options for the slow-motion version). Open the project in Android Studio and run
`demo`, or `./gradlew :demo:installDebug`. Try going back halfway through a flight, and holding a
predictive back gesture.

## Tests

```
./gradlew :flight:testDebugUnitTest
```

Unit tests for the spring body (frame-rate independence, turning round without a jerk, landing) and
Robolectric tests that drive a real `FlightHost` + `FlightNavDisplay` frame by frame: a push flies and
lands, popping halfway turns the same flight round with no jump, and a predictive back gesture holds
the element between the two pages, cancelling and completing.

## Using it with an AI coding assistant

[`skills/compose-flight/SKILL.md`](skills/compose-flight/SKILL.md) teaches an LLM how to use Flight
correctly: the three pieces, the rules that keep a flight smooth, and the mistakes to avoid. With
Claude Code, copy the folder into your project's `.claude/skills/`; other assistants can be pointed at
the file.

## License

Apache 2.0 — see [LICENSE](LICENSE).
