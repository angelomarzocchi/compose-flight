package io.github.angelomarzocchi.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A ball flies between two pages of a [FlightNavDisplay] inside a [FlightHost], with the clock driven
 * by hand one frame at a time: where it is drawn is measured every frame, so these tests say whether
 * the motion is continuous, not just whether it ends in the right place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class FlightHostTest {

    @get:Rule
    val rule = createComposeRule()

    private data object Home
    private data object Detail

    private val host = FlightHostState()
    private val gesture = DirectNavigationEventInput()
    private lateinit var backStack: SnapshotStateList<Any>

    private val ball = FlightPaint { size, _, _, _ -> drawRect(Color.Red, size = size) }

    /** Where the ball rests on each page, in the host's pixels (mdpi: 1dp = 1px). */
    private val onHome = Rect(Offset(20f, 700f), androidx.compose.ui.geometry.Size(40f, 40f))
    private val onDetail = Rect(Offset(100f, 100f), androidx.compose.ui.geometry.Size(200f, 200f))

    @Composable
    private fun Ball(size: Dp, x: Dp, y: Dp) {
        val anchor = rememberFlightAnchor(BallKey, ball)
        Box(
            Modifier
                .offset(x, y)
                .size(size)
                .flightAnchor(anchor)
                .flightHole(anchor)
                .background(Color.Red),
        )
    }

    private fun start() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val stack = remember { mutableStateListOf<Any>(Home) }
            backStack = stack
            val owner = LocalNavigationEventDispatcherOwner.current
            DisposableEffect(owner) {
                val dispatcher = owner?.navigationEventDispatcher
                dispatcher?.addInput(gesture)
                onDispose { dispatcher?.removeInput(gesture) }
            }
            FlightHost(state = host, modifier = Modifier.fillMaxSize()) {
                FlightNavDisplay(
                    backStack = stack,
                    onBack = { stack.removeAt(stack.lastIndex) },
                    entryProvider = entryProvider {
                        entry<Home> { Box(Modifier.fillMaxSize()) { Ball(40.dp, 20.dp, 700.dp) } }
                        entry<Detail> { Box(Modifier.fillMaxSize()) { Ball(200.dp, 100.dp, 100.dp) } }
                    },
                )
            }
        }
        frames(5)
    }

    private fun frames(n: Int) = repeat(n) { rule.mainClock.advanceTimeByFrame() }

    private fun now(action: () -> Unit) {
        rule.runOnUiThread(action)
    }

    /** The ball's centre for [n] frames, one per frame. */
    private fun track(n: Int): List<Offset> = List(n) {
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { host.drawnBounds(BallKey)!!.center }
    }

    /** Frames until nothing flies, at most [limit]. */
    private fun settle(limit: Int = 300) {
        var left = limit
        while (left-- > 0) {
            rule.mainClock.advanceTimeByFrame()
            if (rule.runOnIdle { !host.isFlying }) {
                frames(2)
                return
            }
        }
        error("still flying after $limit frames")
    }

    private fun List<Offset>.peakKick(): Float =
        zipWithNext { a, b -> b - a }.zipWithNext { a, b -> (b - a).getDistance() }.maxOrNull() ?: 0f

    /**
     * The ball rests on [page]: drawn by the anchor on it. A page is known by its entry's content key,
     * which Navigation 3 derives from the key rather than using the key itself.
     */
    private fun assertRestsOn(page: Any) {
        val drawnBy = host.drawnBy(BallKey)
        assertTrue("drawn by $drawnBy, not by $page", drawnBy.toString().startsWith("$page:"))
    }

    private fun assertNear(expected: Rect, actual: Rect?, tolerance: Float = 1f) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.width, actual.width, tolerance)
        assertEquals(expected.height, actual.height, tolerance)
    }

    @Test
    fun atRestTheBallIsDrawnByItsPage() {
        start()
        rule.runOnIdle {
            assertFalse(host.isInAir(BallKey))
            assertRestsOn(Home)
            assertNear(onHome, host.drawnBounds(BallKey))
        }
    }

    @Test
    fun aPushFliesTheBallAndLandsItOnTheNewPage() {
        start()
        now { backStack += Detail }
        val inAir = List(8) {
            frames(1)
            rule.runOnIdle { host.isInAir(BallKey) }
        }
        assertTrue("in the air within a few frames of the push: $inAir", inAir.any { it })
        settle()
        rule.runOnIdle {
            assertFalse(host.isInAir(BallKey))
            assertRestsOn(Detail)
            assertNear(onDetail, host.drawnBounds(BallKey))
        }
    }

    @Test
    fun goingBackHalfwayTurnsTheSameFlightRoundWithoutAJump() {
        start()
        now { backStack += Detail }
        val reference = track(60).peakKick()
        settle()
        now { backStack.removeAt(backStack.lastIndex) }
        settle()

        now { backStack += Detail }
        val out = track(6)
        now { backStack.removeAt(backStack.lastIndex) }
        val back = track(90)
        val path = out + back
        assertTrue(
            "turning round kicked ${path.peakKick()}px a frame, a whole flight ${reference}px",
            path.peakKick() <= 2.5f * reference,
        )
        settle()
        rule.runOnIdle {
            assertRestsOn(Home)
            assertNear(onHome, host.drawnBounds(BallKey))
        }
    }

    @Test
    fun aBackGestureHoldsTheBallBetweenTheTwoPages() {
        start()
        now { backStack += Detail }
        settle()
        now { gesture.backStarted(gestureAt(0f)) }
        for (step in 1..10) {
            now { gesture.backProgressed(gestureAt(step * 0.05f)) }
            frames(1)
        }
        frames(30)
        rule.runOnIdle {
            assertTrue("in the air under the finger", host.isInAir(BallKey))
            val center = host.drawnBounds(BallKey)!!.center
            // Half way, on the gesture's spring: between the two ends, not at either.
            assertTrue("between the pages: $center", center.y > onDetail.center.y + 50f && center.y < onHome.center.y - 50f)
        }
        now { gesture.backCancelled() }
        settle()
        rule.runOnIdle {
            assertRestsOn(Detail)
            assertNear(onDetail, host.drawnBounds(BallKey))
        }

        now { gesture.backStarted(gestureAt(0f)) }
        now { gesture.backProgressed(gestureAt(0.3f)) }
        frames(10)
        now { gesture.backCompleted() }
        settle()
        rule.runOnIdle {
            assertEquals(listOf<Any>(Home), backStack.toList())
            assertRestsOn(Home)
            assertNear(onHome, host.drawnBounds(BallKey))
        }
    }

    @Test
    fun aHeldElementStaysWithItsScreenUnderTheGestureAndTakesOffWhenItCompletes() {
        var drag by mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val stack = remember { mutableStateListOf<Any>(Home, Detail) }
            backStack = stack
            val owner = LocalNavigationEventDispatcherOwner.current
            DisposableEffect(owner) {
                val dispatcher = owner?.navigationEventDispatcher
                dispatcher?.addInput(gesture)
                onDispose { dispatcher?.removeInput(gesture) }
            }
            FlightHost(state = host, modifier = Modifier.fillMaxSize()) {
                FlightNavDisplay(
                    backStack = stack,
                    onBack = { stack.removeAt(stack.lastIndex) },
                    gestureMotion = PageMotion { _, _, _ -> },
                    entryProvider = entryProvider {
                        entry<Home> { Box(Modifier.fillMaxSize()) { Ball(40.dp, 20.dp, 700.dp) } }
                        entry<Detail> {
                            val held = rememberFlightAnchor(BallKey, ball)?.also { it.held = true }
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier
                                        .offset(100.dp, 100.dp)
                                        .size(200.dp)
                                        .graphicsLayer { translationY = drag }
                                        .flightAnchor(held)
                                        .flightHole(held),
                                )
                            }
                        }
                    },
                )
            }
        }
        frames(5)
        now { gesture.backStarted(gestureAt(0f)) }
        for (step in 1..10) {
            now {
                drag = step * 20f
                gesture.backProgressed(gestureAt(step * 0.05f))
            }
            frames(1)
        }
        frames(10)
        rule.runOnIdle {
            assertFalse("still on its screen", host.isInAir(BallKey))
            assertRestsOn(Detail)
            assertNear(onDetail.translate(0f, 200f), host.drawnBounds(BallKey))
        }

        now { gesture.backCompleted() }
        val path = track(8)
        // Take-off from where the finger left it, not from the anchor's place on the page.
        assertTrue("took off from the finger: $path", path.first().y > onDetail.center.y + 150f)
        settle()
        rule.runOnIdle {
            assertEquals(listOf<Any>(Home), backStack.toList())
            assertRestsOn(Home)
            assertNear(onHome, host.drawnBounds(BallKey))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Drawn for real: the legacy mode never draws at all.
    fun aPaintDrawsInTheElementsOwnBounds() {
        // What the paint sees of its DrawScope, frame by frame, against the size it is handed.
        val seen = mutableListOf<Pair<androidx.compose.ui.geometry.Size, androidx.compose.ui.geometry.Size>>()
        val probe = FlightPaint { size, _, _, _ -> seen += this.size to size }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val stack = remember { mutableStateListOf<Any>(Home) }
            backStack = stack
            FlightHost(state = host, modifier = Modifier.fillMaxSize()) {
                FlightNavDisplay(
                    backStack = stack,
                    entryProvider = entryProvider {
                        entry<Home> { Box(Modifier.fillMaxSize()) { Probe(probe, 40.dp, 20.dp, 700.dp) } }
                        entry<Detail> { Box(Modifier.fillMaxSize()) { Probe(probe, 200.dp, 100.dp, 100.dp) } }
                    },
                )
            }
        }
        frames(5)
        now { backStack += Detail }
        repeat(12) {
            frames(1)
            // Robolectric draws only when asked to: a capture draws the whole window.
            rule.onRoot().captureToImage()
        }
        rule.runOnIdle {
            assertTrue("drawn in the air", seen.isNotEmpty())
            // `size` and `center` in a paint are the element's, as a Canvas's are at rest: a
            // `drawCircle(color)` with no centre lands on the element, not on the middle of the screen.
            seen.forEach { (scope, handed) ->
                assertEquals(handed.width, scope.width, 0.5f)
                assertEquals(handed.height, scope.height, 0.5f)
            }
        }
    }

    @Composable
    private fun Probe(paint: FlightPaint, size: Dp, x: Dp, y: Dp) {
        val anchor = rememberFlightAnchor(BallKey, paint)
        Box(Modifier.offset(x, y).size(size).flightAnchor(anchor).flightHole(anchor))
    }

    @Test
    fun withoutAHostNothingFlies() {
        var anchor: FlightAnchor? = FlightAnchor(null, FlightLayer.Content)
        rule.setContent { anchor = rememberFlightAnchor(BallKey, ball) }
        rule.runOnIdle { assertNull(anchor) }
    }

    private fun gestureAt(progress: Float) = NavigationEvent(
        swipeEdge = NavigationEvent.EDGE_LEFT,
        progress = progress,
        touchX = progress * 400f,
        touchY = 400f,
    )

    private companion object {
        const val BallKey = "ball"
    }
}
