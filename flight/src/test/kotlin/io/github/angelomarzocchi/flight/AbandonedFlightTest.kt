package io.github.angelomarzocchi.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.entryProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A grid of photos and a pager over them, one element per photo: going back and straight away opening
 * another photo leaves in the air the elements that were on their way to the grid. They have nowhere
 * left to go on the page on top, and must not stay drawn over it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AbandonedFlightTest {

    @get:Rule
    val rule = createComposeRule()

    private data object Grid
    private data class Photo(val index: Int)
    private data class PhotoKey(val index: Int)
    private data object Elsewhere

    private val host = FlightHostState()
    private lateinit var backStack: SnapshotStateList<Any>
    private val paint = FlightPaint { size, _, _, _ -> drawRect(Color.Red, size = size) }

    @Composable
    private fun Frame(index: Int, modifier: Modifier) {
        val anchor = rememberFlightAnchor(PhotoKey(index), paint)
        Box(modifier.flightAnchor(anchor).flightHole(anchor).background(Color.Red))
    }

    private fun start() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val stack = remember { mutableStateListOf<Any>(Grid) }
            backStack = stack
            FlightHost(state = host, modifier = Modifier.fillMaxSize()) {
                FlightNavDisplay(
                    backStack = stack,
                    onBack = { stack.removeAt(stack.lastIndex) },
                    entryProvider = entryProvider {
                        entry<Grid> {
                            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize()) {
                                items(Count, key = { it }) { Frame(it, Modifier.aspectRatio(0.75f)) }
                            }
                        }
                        // Composed but never laid out, as a page a pager prefetches: an end with no place.
                        entry<Elsewhere> { rememberFlightAnchor(PhotoKey(3), paint) }
                        entry<Photo> { photo ->
                            val pager = rememberPagerState(initialPage = photo.index) { Count }
                            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) {
                                Box(Modifier.fillMaxSize()) { Frame(it, Modifier.fillMaxSize()) }
                            }
                        }
                    },
                )
            }
        }
        run(5)
    }

    private fun run(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    /** Opens photo 4, whose neighbour is 3, goes back, and [between] frames later goes to [next]. */
    private fun backThenGoTo(next: Any, between: Int = 6) {
        start()
        rule.runOnUiThread { backStack.add(Photo(4)) }
        run(120)
        rule.runOnUiThread { backStack.removeAt(backStack.lastIndex) }
        run(between)
        rule.runOnUiThread { backStack.add(next) }
    }

    @Test
    fun anElementLeftToALeavingPageFadesWithIt() {
        backThenGoTo(Photo(1))
        val alphas = buildList {
            repeat(60) {
                run(1)
                rule.runOnIdle { host.airAlpha(PhotoKey(3)) }?.let(::add)
            }
        }
        assertTrue("not in the air at all", alphas.size > 5)
        assertEquals("opaque until the page starts to leave", 1f, alphas.first(), 0.15f)
        alphas.zipWithNext { a, b -> assertTrue("came back from $a to $b", b <= a) }
        assertTrue("no jumps: $alphas", alphas.zipWithNext { a, b -> a - b }.max() < 0.35f)
        assertTrue("still visible when it lands: $alphas", alphas.last() < 0.1f)
    }

    @Test
    fun theElementOpenedFliesOpaque() {
        backThenGoTo(Photo(1))
        repeat(60) {
            run(1)
            rule.runOnIdle { host.airAlpha(PhotoKey(1)) }?.let { assertEquals(1f, it, 0f) }
        }
    }

    @Test
    fun nothingStaysInTheAir() {
        backThenGoTo(Elsewhere, between = 3)
        run(600)
        assertFalse("still in the air after 10 s", rule.runOnIdle { host.isFlying })
    }

    private companion object {
        const val Count = 9
    }
}
