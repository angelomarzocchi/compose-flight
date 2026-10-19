package io.github.angelomarzocchi.flight

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The body of a flight: the spring under every flight, without Compose. */
class FlightBodyTest {

    private val home = Rect(Offset(100f, 1500f), Size(24f, 24f))
    private val detail = Rect(Offset(400f, 300f), Size(260f, 260f))

    private fun body(at: Rect = home) = FlightBody().apply { snapTo(at) }

    private fun FlightBody.frames(n: Int, target: Rect, frameNanos: Long = Frame60) =
        repeat(n) { step(frameNanos, target, 1f, FlightSpring.Spatial, FlightSpring.Effects) }

    @Test
    fun theSameSecondEitherWay() {
        // One step of a second, sixty of a sixtieth, a hundred and twenty of a hundred-and-twentieth:
        // the spring is where the clock says, not where the frame count says.
        val once = body().apply { step(1_000_000_000L, detail, 1f, FlightSpring.Spatial, FlightSpring.Effects) }
        val at60 = body().apply { frames(60, detail, Frame60) }
        val at120 = body().apply { frames(120, detail, Frame120) }
        listOf(at60, at120).forEach {
            assertEquals(once.cx, it.cx, 0.5f)
            assertEquals(once.cy, it.cy, 0.5f)
            assertEquals(once.w, it.w, 0.5f)
        }
        // Short of a second, where the spring still moves fast: truncating each frame to whole
        // milliseconds would leave it 5% behind, tens of pixels here.
        val partOnce = body().apply { step(150_000_000L, detail, 1f, FlightSpring.Spatial, FlightSpring.Effects) }
        val partFrames = body().apply { frames(9, detail, Frame60) }
        assertEquals(partOnce.cy, partFrames.cy, 0.5f)
    }

    /** The centre frame by frame, from where it is, towards [targets] one per frame. */
    private fun FlightBody.path(targets: List<Rect>): List<Offset> =
        listOf(bounds.center) + targets.map { target ->
            step(Frame60, target, 1f, FlightSpring.Spatial, FlightSpring.Effects)
            bounds.center
        }

    /** The largest change of step from one frame to the next: a jerk the eye reads as a jump. */
    private fun List<Offset>.peakKick(): Float =
        zipWithNext { a, b -> b - a }.zipWithNext { a, b -> (b - a).getDistance() }.max()

    @Test
    fun turningRoundKeepsWhereItIsAndHowFastItGoes() {
        val reference = body().path(List(60) { detail }).peakKick()

        for (at in listOf(3, 6, 9, 12)) {
            val flight = body()
            val there = flight.path(List(at) { detail })
            assertTrue("it is on its way", flight.velocity.getDistance() > 500f)
            // Back home after [at] frames: a new target, nothing else.
            val back = there + flight.path(List(60) { home }).drop(1)
            // No jump: the velocity changes no more from frame to frame than it does in a whole flight.
            // At full speed the spatial spring turns it round within about a frame: the pull back is
            // as strong as the one that set it off.
            assertTrue("turned at frame $at: kick ${back.peakKick()} against $reference", back.peakKick() <= 2f * reference)
            // And it gets home.
            assertEquals(home.center.y, back.last().y, 0.5f)
        }
    }

    @Test
    fun itComesHomeAndRests() {
        val flight = body()
        flight.frames(6, detail)
        assertFalse(flight.isAtRest(home, 1f))
        flight.frames(120, home)
        assertTrue(flight.isAtRest(home, 1f))
        assertEquals(home.center.x, flight.cx, 0.5f)
        assertEquals(home.center.y, flight.cy, 0.5f)
    }

    @Test
    fun aScrollCarriesItWithoutTheSpring() {
        val flight = body(detail)
        flight.translate(Offset(0f, -40f))
        assertEquals(detail.center.y - 40f, flight.cy, 0f)
        assertEquals(0f, flight.velocity.getDistance(), 0f)
    }

    @Test
    fun theSpatialSpringOvershootsALittleAndTheGestureOneDoesNot() {
        // The spatial spring, with its little bounce, for flights; none under a finger.
        fun overshoot(spring: FlightSpring): Float {
            val flight = body()
            var most = 0f
            repeat(120) {
                flight.step(Frame60, detail, 1f, spring, FlightSpring.Effects)
                most = maxOf(most, detail.center.y - flight.cy)
            }
            return most
        }
        assertTrue(overshoot(FlightSpring.Spatial) > 1f)
        assertTrue(abs(overshoot(FlightSpring.Gesture)) < 0.5f)
    }

    private companion object {
        // Rounded up, so that so many frames add up to at least the time they stand for.
        const val Frame60 = 16_666_667L
        const val Frame120 = 8_333_334L
    }
}
