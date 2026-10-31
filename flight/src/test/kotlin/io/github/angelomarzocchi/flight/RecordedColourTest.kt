package io.github.angelomarzocchi.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** The same drawing at both ends: cross-fading from one to the other must not change its colour at any point. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordedColourTest {

    @get:Rule
    val rule = createComposeRule()

    private data object Page
    private data object Key

    private val host = FlightHostState()
    private val paint = RecordedPaint(single = false, overflow = true)
    private var second by mutableStateOf(false)

    private fun run(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun start(colour: Color, spill: Int) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val stack = remember { mutableStateListOf<Any>(Page) }
            FlightHost(state = host, modifier = Modifier.fillMaxSize().background(Color(0xFF202020))) {
                FlightNavDisplay(
                    backStack = stack,
                    onBack = {},
                    entryProvider = entryProvider {
                        entry<Page> {
                            Box(Modifier.fillMaxSize()) {
                                val first = rememberFlightAnchor(Key, paint)
                                Box(Modifier.offset(40.dp, 40.dp).size(100.dp).recordedFlightAnchor(first, spill.dp).drawBehind { drawRect(colour) })
                                if (second) {
                                    val end = rememberFlightAnchor(Key, paint)
                                    Box(Modifier.offset(40.dp, 300.dp).size(200.dp).recordedFlightAnchor(end, spill.dp).drawBehind { drawRect(colour) })
                                }
                            }
                        }
                    },
                )
            }
        }
        run(5)
    }

    private fun centre(): Float {
        val bounds = rule.runOnIdle { host.drawnBounds(Key) }!!
        val image = rule.onRoot().captureToImage().toPixelMap()
        return image[bounds.center.x.roundToInt(), bounds.center.y.roundToInt()].red
    }

    private fun holdsItsColour(colour: Color, spill: Int) {
        start(colour, spill)
        val rest = centre()
        second = true
        val seen = buildList {
            repeat(40) {
                run(1)
                if (rule.runOnIdle { host.isInAir(Key) }) add(centre())
            }
        }
        assertTrue("never in the air", seen.isNotEmpty())
        assertTrue("at rest $rest, in the air $seen", seen.all { abs(it - rest) < 0.02f })
    }

    @Test
    fun anOpaqueElementHoldsItsColour() = holdsItsColour(Color(0xFF808080), 0)

    @Test
    fun aTranslucentElementHoldsItsColour() = holdsItsColour(Color.White.copy(alpha = 0.13f), 0)

    @Test
    fun aTranslucentElementWithSpillHoldsItsColour() = holdsItsColour(Color.White.copy(alpha = 0.13f), 2)
}
