package io.github.angelomarzocchi.flight.demo

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import io.github.angelomarzocchi.flight.FlightHost
import io.github.angelomarzocchi.flight.FlightHostState
import io.github.angelomarzocchi.flight.FlightNavDisplay
import io.github.angelomarzocchi.flight.FlightPaint
import io.github.angelomarzocchi.flight.FlightSpring
import io.github.angelomarzocchi.flight.RecordedPaint
import io.github.angelomarzocchi.flight.flightAnchor
import io.github.angelomarzocchi.flight.flightHole
import io.github.angelomarzocchi.flight.recordedFlightAnchor
import io.github.angelomarzocchi.flight.rememberFlightAnchor
import kotlinx.coroutines.delay

/**
 * One scene, two engines: the same list of planets and the same page, flown by Flight or by Compose's
 * own `SharedTransitionLayout` + Navigation 3's `NavDisplay` — with everything that can be the same,
 * the same. The pages slide and fade on the same spring, the elements move on the same spring, the
 * title cross-fades as it grows on both. What differs is only the engine.
 *
 * [CompareActivity] shows the two side by side for the README; [BenchActivity] plays one of them alone,
 * for the benchmarks in `:benchmark`.
 */
internal enum class Engine(val title: String) {
    Flight("Flight"),
    Shared("SharedTransitionLayout"),
}

internal data class World(val name: String, val color: Color, val blurb: String)

internal val Worlds = listOf(
    World("Mercury", Color(0xFF9E9E9E), "The smallest, and the closest to the Sun."),
    World("Venus", Color(0xFFE8B04B), "Wrapped in clouds of sulphuric acid."),
    World("Earth", Color(0xFF3D7BD9), "The only planet known to hold life."),
    World("Mars", Color(0xFFD0533A), "Red with iron oxide, and two small moons."),
    World("Jupiter", Color(0xFFC99A6B), "A storm older than any map of it."),
    World("Saturn", Color(0xFFE3C778), "Rings of ice, wider than the planet."),
    World("Neptune", Color(0xFF3F5BD9), "The windiest place in the Solar System."),
)

/** The planet the script opens: low on the list, so the flight is a long one. */
private val Opened = Worlds[5]

private data object WorldList
private data class WorldPage(val world: World)

internal data class SphereKey(val name: String)
private data class NameKey(val name: String)

/**
 * One copy of the scene's navigation, driven by the script: its back stack, and a back gesture fed
 * to it directly, as a finger would.
 */
internal class Navigator {
    val backStack = mutableStateListOf<Any>(WorldList)
    val gesture = DirectNavigationEventInput()

    fun open() {
        backStack += WorldPage(Opened)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}

/**
 * The script both engines play, at the same instants: a push and a pop, a flight turned round
 * halfway, one turned round twice, a quick swipe back with the planet tapped again straight after,
 * a back gesture completed. [pace] slows it
 * down with the system animator scale, which both engines honour too.
 */
internal suspend fun playScript(navigators: List<Navigator>, pace: Float, caption: (String) -> Unit) {
    suspend fun wait(millis: Long) = delay((millis * pace).toLong())
    fun each(action: Navigator.() -> Unit) = navigators.forEach(action)
    // One step a frame, as a finger on the screen moves: at 120 Hz a step every 16 ms would hold
    // every other frame still, and an engine that draws the gesture as it comes would stutter.
    suspend fun drag(from: Float, to: Float, millis: Long) {
        val start = withFrameMillis { it }
        do {
            val done = withFrameMillis { now -> ((now - start) / (millis * pace)).coerceAtMost(1f) }
            val event = gestureAt(from + (to - from) * done)
            each { gesture.backProgressed(event) }
        } while (done < 1f)
    }

    caption("Open, and back")
    each { open() }
    wait(1500)
    each { back() }
    wait(1500)

    caption("Back halfway through the flight")
    each { open() }
    wait(220)
    each { back() }
    wait(1500)

    caption("Back, and forward again")
    each { open() }
    wait(220)
    each { back() }
    wait(260)
    each { open() }
    wait(1700)

    // What users do all the time: a quick swipe back, and the planet tapped again before the way
    // back is over. Twice, at two instants, so it is not one lucky timing.
    caption("Swipe back, and tap again")
    for (tapAfter in listOf(150L, 350L)) {
        each { gesture.backStarted(gestureAt(0f)) }
        drag(from = 0f, to = 0.3f, millis = 180)
        each { gesture.backCompleted() }
        wait(tapAfter)
        each { open() }
        wait(1700)
    }

    caption("Predictive back: completed")
    each { gesture.backStarted(gestureAt(0f)) }
    drag(from = 0f, to = 0.35f, millis = 500)
    each { gesture.backCompleted() }
    wait(1700)
}

private fun gestureAt(progress: Float) = NavigationEvent(
    progress = progress,
    touchX = progress * 1000f,
    touchY = 1200f,
    swipeEdge = NavigationEvent.EDGE_LEFT,
)

/**
 * The scene on [engine], driven by [navigator]. It has a back dispatcher of its own, so that two of
 * them side by side each get the script's gesture, as each would get a finger's on a screen of its own.
 */
@Composable
internal fun Versus(
    engine: Engine,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    flightState: FlightHostState = remember { FlightHostState() },
) {
    val owner = rememberNavigationEventDispatcherOwner(parent = null)
    DisposableEffect(owner) {
        val dispatcher = owner.navigationEventDispatcher
        dispatcher.addInput(navigator.gesture)
        onDispose { dispatcher.removeInput(navigator.gesture) }
    }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
        Scene(engine, navigator, modifier, flightState)
    }
}

/** The scene on [engine], on whatever back dispatcher is around it: the activity's, for a finger. */
@Composable
internal fun Scene(
    engine: Engine,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    flightState: FlightHostState = remember { FlightHostState() },
) {
    when (engine) {
        Engine.Flight -> FlightScene(navigator, flightState, modifier)
        Engine.Shared -> SharedScene(navigator, modifier)
    }
}

/** What each engine puts in the pages: the planet and its name, as that engine flies them. */
private interface Parts {
    @Composable
    fun Sphere(world: World, modifier: Modifier)

    @Composable
    fun Name(world: World, style: TextStyle)
}

@Composable
private fun FlightScene(navigator: Navigator, state: FlightHostState, modifier: Modifier) {
    FlightHost(modifier, state = state) {
        FlightNavDisplay(
            backStack = navigator.backStack,
            onBack = navigator::back,
            entryProvider = entryProvider {
                entry<WorldList> { ListPage(FlightParts, onOpen = { navigator.open() }) }
                entry<WorldPage> { DetailPage(it.world, FlightParts) }
            },
        )
    }
}

private val SpherePaint = FlightPaint { size, from, to, morph ->
    val a = from as? Color ?: return@FlightPaint
    val b = to as? Color ?: return@FlightPaint
    drawSphere(lerp(a, b, morph), size.minDimension / 2f)
}

/** The demo's planet: a sphere lit from the top left. */
private fun DrawScope.drawSphere(color: Color, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to lerp(color, Color.White, 0.35f),
            1f to lerp(color, Color.Black, 0.35f),
            center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f),
            radius = radius * 1.6f,
        ),
        radius = radius,
    )
}

private object FlightParts : Parts {
    @Composable
    override fun Sphere(world: World, modifier: Modifier) {
        val anchor = rememberFlightAnchor(SphereKey(world.name), SpherePaint)
        anchor?.look = world.color
        Canvas(modifier.flightAnchor(anchor).flightHole(anchor).metered()) { drawSphere(world.color, size.minDimension / 2f) }
    }

    @Composable
    override fun Name(world: World, style: TextStyle) {
        val anchor = rememberFlightAnchor(NameKey(world.name), remember { RecordedPaint(single = false) })
        Text(world.name, style = style, modifier = Modifier.recordedFlightAnchor(anchor).metered())
    }
}

/**
 * Compose's shared elements as the Navigation 3 sample has them, given Flight's springs: the
 * pages' slide and fade on `spring(MediumLow)`, the element's bounds on Flight's spatial spring.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedScene(navigator: Navigator, modifier: Modifier) {
    SharedTransitionLayout(modifier) {
        val parts = remember(this) { SharedParts(this) }
        NavDisplay(
            backStack = navigator.backStack,
            onBack = navigator::back,
            transitionSpec = { PushTransform },
            popTransitionSpec = { PopTransform },
            predictivePopTransitionSpec = { PopTransform },
            entryProvider = entryProvider {
                entry<WorldList> { ListPage(parts, onOpen = { navigator.open() }) }
                entry<WorldPage> { DetailPage(it.world, parts) }
            },
        )
    }
}

// Flight's page spring, for the slide and for the fade.
private val PageSlide = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold)
private val PageFade = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

/** Flight's `DefaultPageMotion` as a `ContentTransform`: in from half a width, out a whole one. */
private val PushTransform = (slideInHorizontally(PageSlide) { -it / 2 } + fadeIn(PageFade)) togetherWith
    (slideOutHorizontally(PageSlide) { -it } + fadeOut(PageFade))

private val PopTransform = (slideInHorizontally(PageSlide) { -it / 2 } + fadeIn(PageFade)) togetherWith
    (slideOutHorizontally(PageSlide) { it } + fadeOut(PageFade))

private val ElementSpring = FlightSpring.Spatial

@OptIn(ExperimentalSharedTransitionApi::class)
private class SharedParts(private val scope: SharedTransitionScope) : Parts {
    private val bounds = { _: Rect, _: Rect ->
        spring(ElementSpring.dampingRatio, ElementSpring.stiffness, Rect.VisibilityThreshold)
    }

    @Composable
    override fun Sphere(world: World, modifier: Modifier) = with(scope) {
        Canvas(
            modifier.sharedElement(
                rememberSharedContentState(SphereKey(world.name)),
                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                boundsTransform = bounds,
            ).metered(),
        ) { drawSphere(world.color, size.minDimension / 2f) }
    }

    @Composable
    override fun Name(world: World, style: TextStyle) = with(scope) {
        Text(
            world.name,
            style = style,
            modifier = Modifier.sharedBounds(
                rememberSharedContentState(NameKey(world.name)),
                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                boundsTransform = bounds,
            ).metered(),
        )
    }
}

@Composable
private fun ListPage(parts: Parts, onOpen: (World) -> Unit) {
    Surface(Modifier.fillMaxSize().metered()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Planets",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).metered(),
            )
            Worlds.forEach { world ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(world) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .metered(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    parts.Sphere(world, Modifier.size(40.dp))
                    parts.Name(world, MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun DetailPage(world: World, parts: Parts) {
    Surface(Modifier.fillMaxSize().metered()) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            parts.Sphere(world, Modifier.size(150.dp))
            Spacer(Modifier.height(24.dp))
            parts.Name(world, MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                world.blurb,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.metered(),
            )
        }
    }
}

/**
 * What the pages cost: how many times their pieces were composed, measured, placed and drawn. Off
 * unless a test provides one, so the recording and the benchmarks pay nothing for it.
 */
internal class Meter {
    var compositions = 0
    var measures = 0
    var placements = 0
    var draws = 0

    override fun toString() =
        "compositions=$compositions measures=$measures placements=$placements draws=$draws"
}

internal val LocalMeter = staticCompositionLocalOf<Meter?> { null }

/** Counts this piece's work in the [LocalMeter], if there is one. */
@Composable
private fun Modifier.metered(): Modifier {
    val meter = LocalMeter.current ?: return this
    SideEffect { meter.compositions++ }
    return this
        .layout { measurable, constraints ->
            meter.measures++
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                meter.placements++
                placeable.place(0, 0)
            }
        }
        .drawBehind { meter.draws++ }
}
