package io.github.angelomarzocchi.flight

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs

/**
 * Flight: elements that fly between screens as physical objects, not as segments of a transition.
 *
 * Each element is a flight: a centre, a size and a look, each on a spring, pulled towards one of the
 * places it can be — its [FlightAnchor]s. Which place is read from the back stack, which changes the
 * instant the user taps, so going back halfway through a flight is the same flight with a new target:
 * it curves round from where it is, at the speed it has. Nothing is rewound, and nothing waits for the
 * page under it to finish moving.
 *
 * The host is the one frame loop that moves them, running only while something flies, and the place
 * that draws them in the air — after its content, so over the pages. An element that has to fly over
 * the app's bars as well is drawn a layer further out, over the whole chrome ([FlightLayer],
 * [FlightOverlay]). A frame of flight invalidates those overlays' draw and nothing else: no
 * composition, no layout, not even the pages' own drawing.
 */
@Stable
public class FlightHostState {

    /** The host's own: flights are drawn, and anchors measured, in them. */
    internal var coordinates: LayoutCoordinates? = null

    /** Where the pages are at rest: the navigation's, which every page fills. */
    internal var restingPages: LayoutCoordinates? = null

    internal val navigation = FlightNavigation()

    internal var springs = FlightSprings.Default

    /** A target moving more than this in a frame, beyond how it was already moving, has jumped. */
    internal var jumpPx = 32f

    private val flights = mutableStateMapOf<Any, Flight>()

    /** Ticks once a frame while something flies: read in draw, it is what redraws the flights. */
    internal var frame by mutableIntStateOf(0)
        private set

    private var nextOrder = 0L

    /** Whether [key]'s element is in the air: neither resting on a screen nor gone. */
    public fun isInAir(key: Any): Boolean = flights[key]?.isInAir == true

    /** Whether anything at all is in the air. */
    public val isFlying: Boolean
        get() = flights.values.any { it.isInAir }

    internal fun register(key: Any, anchor: FlightAnchor) {
        anchor.order = nextOrder++
        anchor.host = this
        val flight = flights[key]
        if (flight == null) {
            flights[key] = Flight(anchor)
        } else {
            flight.add(anchor)
        }
    }

    internal fun unregister(key: Any, anchor: FlightAnchor) {
        anchor.host = null
        val flight = flights[key] ?: return
        flight.remove(anchor)
        if (flight.anchors.isEmpty()) flights.remove(key)
        wake()
    }

    /** Something must move: a flight in the air, or one whose target is no longer where it rests. */
    internal fun isRestless(): Boolean = flights.values.any { it.isRestless(navigation) }

    /**
     * A nudge for the frame loop, from what changes during a frame's layout — an anchor laid out for
     * the first time, the navigation moving on. Waiting for the snapshot to say so instead costs a
     * frame: its writes from layout are only announced at the next one.
     */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    internal fun wake() {
        wakeups.trySend(Unit)
    }

    /** Until something has to move. */
    internal suspend fun awaitRestless() {
        while (!isRestless()) {
            merge(snapshotFlow { isRestless() }.filter { it }, wakeups.receiveAsFlow()).first()
        }
    }

    private fun pagesOrigin(host: LayoutCoordinates): Offset =
        restingPages?.takeIf { it.isAttached }?.let { host.localPositionOf(it, Offset.Zero) } ?: Offset.Zero

    internal fun step(frameNanos: Long, dtNanos: Long, durationScale: Float) {
        val host = coordinates?.takeIf { it.isAttached } ?: return
        val origin = pagesOrigin(host)
        flights.values.forEach { it.step(host, origin, navigation, frameNanos, dtNanos, durationScale, springs, jumpPx) }
        frame++
    }

    /**
     * The flights of [layer] in the air, drawn by an overlay laid out at [overlay]: in the host's
     * pixels, moved to the overlay's.
     */
    internal fun DrawScope.drawFlights(layer: FlightLayer, overlay: LayoutCoordinates?) {
        // Read to be redrawn every frame of flight.
        frame
        val host = coordinates?.takeIf { it.isAttached } ?: return
        val shift = overlay?.takeIf { it.isAttached }?.localPositionOf(host, Offset.Zero) ?: Offset.Zero
        val origin = pagesOrigin(host)
        translate(shift.x, shift.y) {
            flights.values.forEach { if (it.layer == layer) with(it) { draw(host, origin, jumpPx) } }
        }
    }

    /**
     * Where [key]'s element is drawn: its bounds in the air, its anchor's at rest, in the host's
     * pixels. Null when nothing with that key is registered. Useful to measure a flight frame by frame
     * in a test.
     */
    public fun drawnBounds(key: Any): Rect? {
        val host = coordinates?.takeIf { it.isAttached } ?: return null
        return flights[key]?.drawnBounds(host, pagesOrigin(host), jumpPx)
    }

    /** How opaque [key]'s element is drawn in the air; null when it is not in the air. */
    internal fun airAlpha(key: Any): Float? = flights[key]?.takeIf { it.isInAir }?.fade

    /**
     * Who draws [key]'s element: `"air"` while it flies, or the key of the page whose anchor it rests
     * on; null otherwise. For tests: which page holds it once a gesture is over.
     */
    public fun drawnBy(key: Any): Any? = flights[key]?.let { it.home?.page?.key ?: if (it.isInAir) "air" else null }
}

/**
 * The host the anchors below register with. Null outside a [FlightHost]: previews and screenshot
 * tests then draw every element where it is, and nothing flies.
 */
public val LocalFlightHost: ProvidableCompositionLocal<FlightHostState?> = compositionLocalOf { null }

/**
 * Where elements fly: put it round the navigation ([FlightNavDisplay]), so flights are drawn over the
 * pages and keep to the same area the pages do. Put it inside a `Scaffold`'s content and they fly
 * under its top bar, which the Scaffold draws after its content. What has to fly over the bars too
 * ([FlightLayer.Chrome]) is drawn by a [FlightOverlay] further out, on the same [state].
 *
 * @param springs how elements move: where and how big, how their look morphs, and under a back
 *   gesture. Pass `FlightSprings(spatial = FlightSpring.of(MaterialTheme.motionScheme.defaultSpatialSpec(), …))`
 *   to fly on your theme's motion scheme.
 */
@Composable
public fun FlightHost(
    modifier: Modifier = Modifier,
    state: FlightHostState = remember { FlightHostState() },
    springs: FlightSprings = FlightSprings.Default,
    content: @Composable () -> Unit,
) {
    val jumpPx = with(LocalDensity.current) { JumpDistance.toPx() }
    SideEffect {
        state.jumpPx = jumpPx
        state.springs = springs
    }
    LaunchedEffect(state) {
        // How long a frame lasts here, for the first frame after waking, which has no frame before it
        // to measure from: stepped by nothing, the element would stand still for a frame at take-off.
        var frameNanos = NominalFrameNanos
        while (true) {
            // Asleep until something has to move: a tap, a gesture, a page with an anchor arriving.
            state.awaitRestless()
            var last = -1L
            do {
                // The system's animator scale: 0 with animations off, where flights jump to their
                // target; 2 with "animator duration scale 2x", where they take twice as long.
                val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                withFrameNanos { now ->
                    if (last >= 0L) frameNanos = (now - last).coerceIn(MinFrameNanos, NominalFrameNanos * 4)
                    state.step(now, if (last < 0L) frameNanos else now - last, scale)
                    last = now
                }
            } while (state.isRestless())
        }
    }
    CompositionLocalProvider(LocalFlightHost provides state) {
        Box(modifier.onPlaced { state.coordinates = it }, propagateMinConstraints = true) {
            content()
            FlightOverlay(state, FlightLayer.Content, Modifier.matchParentSize())
        }
    }
}

/** Where a flight is drawn while it is in the air. */
public enum class FlightLayer {
    /** Over the pages, under the bars: everything that flies from one page to another. */
    Content,

    /**
     * Over the bars as well: what flies out of the app's chrome and into a page — an avatar in the
     * top bar opening a profile. Drawn by a [FlightOverlay] you place yourself, over the chrome.
     */
    Chrome,
}

/**
 * Draws [state]'s flights of [layer], over whatever was laid out before it. Its own node, sibling to
 * what it covers, so that a frame of flight redraws this alone: an overlay on the content's own node
 * would draw all of it again every frame. Touches pass through: it has nothing to touch.
 *
 * [FlightHost] places the [FlightLayer.Content] overlay itself. Place a [FlightLayer.Chrome] one as the
 * last child of whatever holds your bars and your [FlightHost].
 */
@Composable
public fun FlightOverlay(state: FlightHostState, layer: FlightLayer, modifier: Modifier = Modifier) {
    val overlay = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Spacer(
        modifier
            .onPlaced { overlay[0] = it }
            .drawBehind { with(state) { drawFlights(layer, overlay[0]) } },
    )
}

/**
 * How much a target's step may change from one frame to the next and still be motion rather than a
 * jump. A list being dragged changes it by a dp or two; a layout that re-lays itself out moves it tens
 * of dp at once. The first frame of a target's own spring can land either side, and either way it
 * reads as continuous: carried, or smoothed by the flight's spring.
 */
private val JumpDistance = 12.dp

private const val NominalFrameNanos = 16_666_667L

/** A 240 Hz frame, the shortest there is. */
private const val MinFrameNanos = 4_166_667L

/**
 * How an element draws itself in the air: at [size], [morph] of the way from [from]'s look to
 * [to]'s — the looks its two ends gave their [FlightAnchor]s. Drawn, not recorded: sharp at every
 * size, and nothing is laid out while it flies.
 *
 * The DrawScope is the element's own, as a `Canvas`'s is at rest: its origin is the element's top
 * left and its `size` and `center` are [size]'s, so the same drawing code serves both. It is not
 * clipped: a shadow or a glow may spill over.
 */
public fun interface FlightPaint {
    public fun DrawScope.paint(size: Size, from: Any, to: Any, morph: Float)
}

/**
 * One of the places an element can be: one screen's copy of it.
 *
 * At rest the element is drawn by the screen, as it always was — with whatever the screen draws round
 * it. While it is anywhere else ([isAway]) the screen leaves its place empty and the host draws it.
 */
@Stable
public class FlightAnchor internal constructor(internal val page: FlightPage?, internal val layer: FlightLayer) {
    internal var coordinates: LayoutCoordinates? = null

    /** Laid out at least once: until then there is nowhere to fly to, or from. */
    internal var placed by mutableStateOf(false)
        private set

    internal var flight: Flight? by mutableStateOf(null)

    /**
     * What this end looks like, for [FlightPaint]: set by the screen, opaque to the host. A colour, a
     * size, a data class of everything the paint needs — whatever your paint casts it back to.
     */
    public var look: Any = Unit

    internal var paint: FlightPaint? = null

    /** When it was registered: of two anchors on the page on top, the newer wins. */
    internal var order = 0L

    /**
     * The element is somewhere else — in the air, or resting at another end — so this end draws a hole
     * where it would be. Reads snapshot state: call it in draw.
     */
    public fun isAway(): Boolean {
        val flight = flight ?: return false
        return flight.home !== this
    }

    /** On one of [pages]: false for an anchor that is on no page at all. */
    internal fun isOn(pages: Set<Any>): Boolean = page?.let { it.key in pages } == true

    /** On a page that has been laid out before: one already on screen, not one arriving. */
    internal fun isOnShownPage(): Boolean = page?.coordinates != null

    /** The host it is registered with, to wake when it is laid out for the first time. */
    internal var host: FlightHostState? = null

    internal fun place(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        if (!placed) {
            placed = true
            host?.wake()
        }
    }

    /** How much of its page is left, 0 to 1; null for an anchor on no page, or a page without a presence. */
    internal fun pageShown(): Float? = page?.shown?.value

    /** How far from where it rests its page is drawn: zero at rest, or for an anchor on no page. */
    internal fun pageOffset(host: LayoutCoordinates, pagesOrigin: Offset): Offset {
        val pageCoordinates = page?.coordinates?.takeIf { it.isAttached } ?: return Offset.Zero
        return host.localPositionOf(pageCoordinates, Offset.Zero) - pagesOrigin
    }

    /** Where it is on screen, page transitions and all, in the host's pixels. */
    internal fun actualBounds(host: LayoutCoordinates): Rect? =
        coordinates?.takeIf { it.isAttached }?.let { host.localBoundingBoxOf(it, clipBounds = false) }

    /**
     * Where it is once its page is at rest: measured inside the page, which carries the page's slide,
     * fade and scale, and put where the page rests. The page sliding in does not move it: it is
     * already where the page is going.
     */
    internal fun restBounds(host: LayoutCoordinates, pagesOrigin: Offset): Rect? {
        val own = coordinates?.takeIf { it.isAttached } ?: return null
        val pageCoordinates = page?.coordinates?.takeIf { it.isAttached } ?: return actualBounds(host)
        return Rect(pagesOrigin + pageCoordinates.localPositionOf(own, Offset.Zero), own.size.toSize())
    }

    /** How far the lists it is in have scrolled it, in its page: what a flight follows one to one. */
    internal fun scrollOffset(): Offset {
        val own = coordinates?.takeIf { it.isAttached } ?: return Offset.Zero
        val pageCoordinates = page?.coordinates?.takeIf { it.isAttached } ?: return Offset.Zero
        return pageCoordinates.localPositionOf(own, Offset.Zero, includeMotionFrameOfReference = true) -
            pageCoordinates.localPositionOf(own, Offset.Zero, includeMotionFrameOfReference = false)
    }
}

/**
 * This screen's copy of [key]'s element, or null where nothing flies — no host, no [key] — and the
 * screen draws it where it is, as always.
 *
 * Every copy of one element shares its [key], and the copy on the page on top of the stack is where
 * the element goes. [paint] draws it in the air, on [layer]; the screen gives the anchor its
 * [FlightAnchor.look]. Every copy of one element must fly on the same layer.
 *
 * Put the anchor on the composable that is the element's place with [flightAnchor], and leave the
 * place empty while the element is away with [flightHole] — or use [recordedFlightAnchor], which does
 * both and flies the screen's own drawing.
 */
@Composable
public fun rememberFlightAnchor(key: Any?, paint: FlightPaint, layer: FlightLayer = FlightLayer.Content): FlightAnchor? {
    val host = LocalFlightHost.current ?: return null
    if (key == null) return null
    val page = LocalFlightPage.current
    val anchor = remember(host, key, page, layer) { FlightAnchor(page, layer) }
    SideEffect { anchor.paint = paint }
    DisposableEffect(anchor) {
        host.register(key, anchor)
        onDispose { host.unregister(key, anchor) }
    }
    return anchor
}

/** Where [anchor]'s element sits on this screen: on the composable that is its place. */
public fun Modifier.flightAnchor(anchor: FlightAnchor?): Modifier =
    if (anchor == null) this else onPlaced { anchor.place(it) }

/**
 * The place of an element drawn by the screen itself, left empty while the element is anywhere else:
 * in the air the host draws it, at the other end the other screen does.
 */
public fun Modifier.flightHole(anchor: FlightAnchor?): Modifier =
    if (anchor == null) this else drawWithContent { if (!anchor.isAway()) drawContent() }

/**
 * [anchor]'s place, with what is drawn in it recorded: drawn here at rest, and flown as the recording
 * in the air — the screen's own drawing, scaled, with nothing laid out again. For what is the same
 * thing at two sizes (a title at two type scales, a stack of avatars), with [RecordedPaint] as the
 * anchor's paint.
 *
 * Recorded whether or not it is shown, so the arriving end has a recording from its first frame.
 */
@Composable
public fun Modifier.recordedFlightAnchor(anchor: FlightAnchor?): Modifier {
    if (anchor == null) return this
    val layer = rememberGraphicsLayer()
    val look = remember(anchor, layer) { RecordedLook(layer) }
    SideEffect { anchor.look = look }
    return this
        .flightAnchor(anchor)
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            look.size = size
            if (!anchor.isAway()) {
                // The host may have drawn it see-through, cross-fading in the air.
                layer.alpha = 1f
                drawLayer(layer)
            }
        }
}

/** One end's recording, and how big it was: the look [recordedFlightAnchor] gives its anchor. */
public class RecordedLook internal constructor(internal val layer: GraphicsLayer) {
    internal var size: Size = Size.Zero
}

/**
 * Flies [recordedFlightAnchor]s: each end's recording scaled to the width of the bounds in the air,
 * hung from their top and cut to them — so a short block and a tall one both grow out of the box as
 * it opens, rather than one of them spilling over it.
 *
 * [single]: only the larger end's recording, shrunk to the smaller's — one picture at two sizes,
 * sharp all the way because a recording is drawing, not pixels. Otherwise the two cross-fade as the
 * bounds go from one end's size to the other's: the fade spread over the whole flight, and turned
 * back with it.
 */
public class RecordedPaint(private val single: Boolean) : FlightPaint {
    override fun DrawScope.paint(size: Size, from: Any, to: Any, morph: Float) {
        val a = from as? RecordedLook ?: return
        val b = to as? RecordedLook ?: return
        clipRect(0f, 0f, size.width, size.height) {
            if (single) {
                // The larger, once it has been recorded at all: the end just arrived may not have been.
                val recorded = listOf(a, b).filter { it.size.width > 0f }
                recorded.maxByOrNull { it.size.width }?.let { drawRecording(it, size, 1f) }
            } else {
                val toward = when {
                    b.size.width <= 0f -> 0f
                    a.size.width <= 0f -> 1f
                    else -> progressBetween(a.size, b.size, size, morph)
                }
                drawRecording(a, size, 1f - toward)
                drawRecording(b, size, toward)
            }
        }
    }

    private fun DrawScope.drawRecording(look: RecordedLook, size: Size, alpha: Float) {
        if (alpha <= 0f || look.size.width <= 0f || look.size.height <= 0f) return
        val scale = size.width / look.size.width
        look.layer.alpha = alpha.coerceAtMost(1f)
        scale(scale, scale, pivot = Offset.Zero) { drawLayer(look.layer) }
    }
}

/**
 * How far an element in the air has gone from one end's look to the other's, read off its own size:
 * [value] of the way from [from] to [to], 0 to 1. What a look that grows uses, so that everything in
 * it changes together with the size rather than on a clock of its own; [fallback] when the two ends
 * are the same size.
 */
public fun progressBetween(from: Float, to: Float, value: Float, fallback: Float): Float =
    if (abs(to - from) < 1f) fallback.coerceIn(0f, 1f) else ((value - from) / (to - from)).coerceIn(0f, 1f)

/** The same for a size: along whichever side changes more between the two ends. */
public fun progressBetween(from: Size, to: Size, value: Size, fallback: Float): Float =
    if (abs(to.width - from.width) >= abs(to.height - from.height)) {
        progressBetween(from.width, to.width, value.width, fallback)
    } else {
        progressBetween(from.height, to.height, value.height, fallback)
    }

/** What the navigation says, for the host to pick targets from. Written by [FlightNavDisplay]. */
@Stable
internal class FlightNavigation {
    /** The pages of the scene on screen, by their entries' content keys. */
    var current: Set<Any> by mutableStateOf(emptySet())

    /** The pages a back gesture would go back to: the previous scene's. */
    var back: Set<Any> by mutableStateOf(emptySet())

    /** How far a back gesture has gone, 0 to 1; null with no gesture. Reads snapshot state. */
    var gesture: () -> Float? = { null }
}

/** Where a flight is going this frame. */
private sealed interface Aim {
    /** Nowhere new: resting where it is. */
    data object Stay : Aim

    /** The anchor on the page on top, where it will rest once that page does. */
    class At(val anchor: FlightAnchor) : Aim

    /** Under a back gesture: [progress] of the way from the page on top to the one behind it. */
    class Between(val top: FlightAnchor, val back: FlightAnchor, val progress: Float) : Aim

    /** No page on top has it: home to the last place it was going, wherever that page is now. */
    class Follow(val anchor: FlightAnchor) : Aim
}

/** One element: its anchors, where it rests, and — in the air — its body. */
internal class Flight(first: FlightAnchor) {
    val anchors = mutableStateListOf(first)

    /** The layer it is drawn on in the air: its first anchor's, which every other shares. */
    val layer: FlightLayer = first.layer

    /** The anchor it rests on; null while it is in the air. */
    var home: FlightAnchor? by mutableStateOf(first)
        private set

    val isInAir: Boolean
        get() = home == null

    private val body = FlightBody()

    // The two looks it is between, [body.morph] of the way from the first to the second.
    private var lookFrom = first
    private var lookTo = first

    /** The last anchor it was going to: where it goes home to if no page on top has it any more. */
    private var aimedAt: FlightAnchor? = null

    /** On the gesture's spring: a gesture that found it at rest. */
    private var underFinger = false

    /** How opaque it is drawn in the air: below 1 only while it goes home to a page that is leaving. */
    var fade = 1f
        private set

    /** How much of that page there was when the element was left to it. */
    private var fadeFrom: Float? = null

    // The anchor whose motion the flight is carried by, and where it was a frame ago: its place, how
    // far it was scrolled, and how far it had moved on its own.
    private var scrolled: FlightAnchor? = null
    private var lastTarget = Offset.Zero
    private var lastScroll = Offset.Zero
    private var lastDrift = Offset.Zero

    /** How far from its rest the target's page was drawn a frame ago; null with no frame yet. */
    private var lastPageOffset: Offset? = null

    // Where it was resting a frame ago, and when: what a page moving it hands on at take-off.
    private var trail: Offset? = null
    private var trailNanos = 0L

    init {
        first.flight = this
    }

    fun add(anchor: FlightAnchor) {
        anchors += anchor
        anchor.flight = this
        // Both ends arrived in the same frame — a tap that keys its own end along with the page it
        // opens: the element is at the end whose page was already on screen, whichever of the two
        // happened to register first. Lazy lists compose their items while they are laid out, so which
        // one that is cannot be told from the order.
        val resting = home
        if (resting != null && !resting.placed && !resting.isOnShownPage() && anchor.isOnShownPage()) {
            home = anchor
            lookFrom = anchor
            lookTo = anchor
        }
    }

    fun remove(anchor: FlightAnchor) {
        anchors -= anchor
        anchor.flight = null
        if (home === anchor) {
            // Its page went with the element on it: the element goes with whichever end is left.
            home = anchors.maxByOrNull { it.order }
        }
        if (lookFrom === anchor) lookFrom = lookTo
        if (lookTo === anchor) lookTo = lookFrom
        if (aimedAt === anchor) aimedAt = null
        if (scrolled === anchor) scrolled = null
    }

    private fun aim(navigation: FlightNavigation): Aim {
        val current = navigation.current
        val top = anchors.filter { it.placed && it.isOn(current) }.maxByOrNull { it.order }
        if (top != null) {
            val progress = navigation.gesture()
            if (progress != null) {
                val backPages = navigation.back
                val back = anchors.filter { it.placed && it.isOn(backPages) }.maxByOrNull { it.order }
                if (back != null && back !== top) return Aim.Between(top, back, progress)
            }
            return Aim.At(top)
        }
        if (home != null) return Aim.Stay
        val last = aimedAt?.takeIf { it.placed && it in anchors } ?: anchors.filter { it.placed }.maxByOrNull { it.order }
        return last?.let { Aim.Follow(it) } ?: Aim.Stay
    }

    fun isRestless(navigation: FlightNavigation): Boolean {
        val home = home ?: return true
        return when (val aim = aim(navigation)) {
            // At rest on the page on top while a back gesture runs: followed frame by frame, because
            // the page can carry it off before the page behind is there to fly to.
            is Aim.At -> aim.anchor !== home || navigation.gesture() != null
            is Aim.Between -> true
            else -> false
        }
    }

    fun step(
        host: LayoutCoordinates,
        pagesOrigin: Offset,
        navigation: FlightNavigation,
        frameNanos: Long,
        dtNanos: Long,
        durationScale: Float,
        springs: FlightSprings,
        jumpPx: Float,
    ) {
        val aim = aim(navigation)
        val resting = home
        if (resting != null) {
            val takesOff = when (aim) {
                is Aim.At -> aim.anchor !== resting
                is Aim.Between -> true
                else -> false
            }
            val at = resting.actualBounds(host) ?: return
            if (!takesOff) {
                trail = at.center
                trailNanos = frameNanos
                return
            }
            // Take-off: where the element is drawn this very frame, page and all — and as fast as its
            // page was carrying it, if it was: a page can move on the first frame of a back gesture, a
            // frame before the page behind is there to fly to.
            body.snapTo(at)
            val from = trail
            if (from != null && frameNanos - trailNanos in 1..dtNanos * 2) {
                body.push((at.center - from) * (NanosPerSecond / (frameNanos - trailNanos)))
            }
            trail = null
            lookFrom = resting
            lookTo = resting
            body.morph = 1f
            body.morphVelocity = 0f
            fade = 1f
            fadeFrom = null
            underFinger = aim is Aim.Between
            scrolled = null
            lastPageOffset = null
            home = null
        }

        val target: Rect
        val morphTarget: Float
        var spring = springs.spatial
        val landsOn: FlightAnchor?
        when (aim) {
            is Aim.At -> {
                val anchor = aim.anchor
                val rest = anchor.restBounds(host, pagesOrigin) ?: return
                if (anchor !== aimedAt) lastPageOffset = null
                carry(anchor, rest, jumpPx)
                lookToward(anchor)
                morphTarget = 1f
                underFinger = false
                aimedAt = anchor
                // Its page still sliding or fading in: where the page will rest. Its page still: where
                // the page is — which is its rest, unless the page stopped a few dp short of it, and
                // the element must land where the page shows it. A page still but far from its rest
                // has not set off yet: its rest it is.
                val pageOffset = anchor.pageOffset(host, pagesOrigin)
                val pageStill = lastPageOffset?.let { (pageOffset - it).getDistance() < StillPx } == true
                lastPageOffset = pageOffset
                val arrived = pageStill && pageOffset.getDistance() <= jumpPx * StuckJumps
                target = if (arrived) rest.translate(pageOffset) else rest
                // It rests once its page does: before, the page is still moving, and the element
                // would move with it the moment it landed.
                landsOn = anchor.takeIf { arrived }
            }
            is Aim.Between -> {
                val from = aim.top.restBounds(host, pagesOrigin) ?: return
                val to = aim.back.restBounds(host, pagesOrigin) ?: return
                target = lerp(from, to, aim.progress)
                lookBetween(aim.top, aim.back)
                morphTarget = aim.progress
                if (underFinger) spring = springs.gesture
                aimedAt = aim.top
                scrolled = null
                landsOn = null
            }
            is Aim.Follow -> {
                target = aim.anchor.actualBounds(host) ?: return
                lookToward(aim.anchor)
                morphTarget = 1f
                underFinger = false
                scrolled = null
                landsOn = aim.anchor
            }
            Aim.Stay -> {
                // In the air with no place left to go: it rests on the end there is, which draws it if it can.
                if (home == null) home = anchors.maxByOrNull { it.order }
                return
            }
        }

        fadeWith(aim, dtNanos, durationScale)
        if (durationScale == 0f) {
            body.jumpTo(target, morphTarget)
        } else {
            body.step((dtNanos / durationScale).toLong(), target, morphTarget, spring, springs.effects)
        }
        // Landing: on its target and still, so the anchor draws it where it already is.
        if (landsOn != null && body.isAtRest(target, morphTarget)) home = landsOn
    }

    /**
     * Going home to a page that is leaving, it leaves with it: drawn in the air it is over the page on
     * top, and it would stay there, opaque, until it lands. From all there is of it, whatever the page's
     * own fade had reached, down to nothing together with the page. Called back on top, it recovers.
     */
    private fun fadeWith(aim: Aim, dtNanos: Long, durationScale: Float) {
        val shown = (aim as? Aim.Follow)?.anchor?.pageShown()
        if (shown != null) {
            val from = fadeFrom ?: shown.coerceAtLeast(MinFadeFrom).also { fadeFrom = it }
            fade = minOf(fade, (shown / from).coerceIn(0f, 1f))
        } else {
            fadeFrom = null
            fade = if (durationScale == 0f) 1f else (fade + dtNanos / durationScale / RecoverNanos).coerceAtMost(1f)
        }
    }

    /**
     * The target moving under the flight. What carries on from the frame before is carried one to
     * one — a list scrolling, something turning — so the element rides it the same frame and lands on
     * it even while it moves. What does not is the spring's: a layout that jumps (a banner appearing
     * above) moves only the target, and the element gets there smoothly.
     */
    private fun carry(anchor: FlightAnchor, target: Rect, jumpPx: Float) {
        val scroll = anchor.scrollOffset()
        if (scrolled === anchor) {
            val (carried, drift) = carriedSince(target.center, scroll, jumpPx)
            body.translate(carried)
            lastDrift = drift
        } else {
            lastDrift = Offset.Zero
        }
        scrolled = anchor
        lastTarget = target.center
        lastScroll = scroll
    }

    /**
     * How far the target has carried the element since the last step — the scroll, and its own motion
     * if that goes on from the frame before — and that motion of its own, for the next frame to be
     * compared with. A jump carries nothing but the scroll.
     */
    private fun carriedSince(target: Offset, scroll: Offset, jumpPx: Float): Pair<Offset, Offset> {
        val byScroll = scroll - lastScroll
        val drift = target - lastTarget - byScroll
        return if ((drift - lastDrift).getDistance() <= jumpPx) (byScroll + drift) to drift else byScroll to Offset.Zero
    }

    /**
     * Where to draw it this frame. The step ran before this frame's layout, so a target that has moved
     * since has carried the element a frame further: drawn there, it is where the anchor will draw it
     * the frame it lands, rather than a frame behind.
     */
    private fun drawnBody(host: LayoutCoordinates, pagesOrigin: Offset, jumpPx: Float): Rect {
        val bounds = body.bounds
        val anchor = scrolled?.takeIf { it === aimedAt } ?: return bounds
        val target = anchor.restBounds(host, pagesOrigin) ?: return bounds
        return bounds.translate(carriedSince(target.center, anchor.scrollOffset(), jumpPx).first)
    }

    /** Going to [anchor]: the look turns towards its, from the look there is now. */
    private fun lookToward(anchor: FlightAnchor) {
        if (anchor === lookTo) return
        if (anchor === lookFrom) {
            // Turning round: the same cross-fade, run back.
            lookFrom = lookTo
            lookTo = anchor
            body.morph = 1f - body.morph
            body.morphVelocity = -body.morphVelocity
            return
        }
        lookFrom = if (body.morph >= 0.5f) lookTo else lookFrom
        lookTo = anchor
        body.morph = 0f
        body.morphVelocity = 0f
    }

    /** Under a gesture the look goes from the page on top's to the one behind it, as the place does. */
    private fun lookBetween(top: FlightAnchor, back: FlightAnchor) {
        if (lookFrom === top && lookTo === back) return
        if (lookFrom === back && lookTo === top) {
            lookFrom = top
            lookTo = back
            body.morph = 1f - body.morph
            body.morphVelocity = -body.morphVelocity
            return
        }
        val showing = if (body.morph >= 0.5f) lookTo else lookFrom
        lookFrom = top
        lookTo = back
        body.morph = if (showing === back) 1f else 0f
        body.morphVelocity = 0f
    }

    fun DrawScope.draw(host: LayoutCoordinates, pagesOrigin: Offset, jumpPx: Float) {
        if (home != null || fade <= 0f) return
        val paint = lookTo.paint ?: return
        val bounds = drawnBody(host, pagesOrigin, jumpPx)
        // Inset rather than moved: the paint's own size and centre are the element's, as a Canvas's
        // are at rest, so a `drawCircle(color)` lands where the element is. Nothing is clipped.
        val drawn = {
            inset(bounds.left, bounds.top, size.width - bounds.right, size.height - bounds.bottom) {
                with(paint) { paint(bounds.size, lookFrom.look, lookTo.look, body.morph) }
            }
        }
        if (fade >= 1f) {
            drawn()
        } else {
            // A layer, for a paint of several strokes to fade as one. Only here: a flight on its way draws straight.
            drawIntoCanvas { it.withSaveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = fade }, drawn) }
        }
    }

    fun drawnBounds(host: LayoutCoordinates, pagesOrigin: Offset, jumpPx: Float): Rect? =
        home?.let { return it.actualBounds(host) } ?: drawnBody(host, pagesOrigin, jumpPx)

    private companion object {
        /** A page that moved less than this since the frame before is still. */
        const val StillPx = 0.5f

        /** How far short of its rest, in jumps, a page can be stuck and still count as arrived. */
        const val StuckJumps = 4f

        const val NanosPerSecond = 1_000_000_000f

        /** Of a page all but gone: the fade measured from this, not from nothing. */
        const val MinFadeFrom = 0.001f

        /** From nothing back to opaque, when the page it was left to comes back on top. */
        const val RecoverNanos = 100_000_000f
    }
}
