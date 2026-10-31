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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.roundToInt

/** A border centred on the element's edge: half of it is outside the box, and stays there in the air. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordedSpillTest {

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

    private fun Modifier.bordered() = drawBehind {
        drawRect(Color.White, topLeft = Offset.Zero, size = size, style = Stroke(8f))
    }

    private fun start() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val stack = remember { mutableStateListOf<Any>(Page) }
            FlightHost(state = host, modifier = Modifier.fillMaxSize().background(Color.Black)) {
                FlightNavDisplay(
                    backStack = stack,
                    onBack = {},
                    entryProvider = entryProvider {
                        entry<Page> {
                            Box(Modifier.fillMaxSize()) {
                                val first = rememberFlightAnchor(Key, paint)
                                Box(Modifier.offset(40.dp, 40.dp).size(100.dp).recordedFlightAnchor(first, 8.dp).bordered())
                                if (second) {
                                    val end = rememberFlightAnchor(Key, paint)
                                    Box(Modifier.offset(40.dp, 300.dp).size(200.dp).recordedFlightAnchor(end, 8.dp).bordered())
                                }
                            }
                        }
                    },
                )
            }
        }
        run(5)
    }

    /** How white the left border is just outside the element's box, 0 to 1. */
    private fun outside(): Float {
        val bounds = rule.runOnIdle { host.drawnBounds(Key) }!!
        val image = rule.onRoot().captureToImage().toPixelMap()
        return image[bounds.left.roundToInt() - 2, bounds.center.y.roundToInt()].red
    }

    @Test
    fun aRestingElementIsWhereItWasNotMovedByItsSpill() {
        start()
        assertTrue("the border is not drawn at rest", outside() > 0.9f)
    }

    @Test
    fun theBorderOutsideTheBoxStaysWholeInTheAir() {
        start()
        second = true
        repeat(6) {
            run(2)
            if (rule.runOnIdle { host.isInAir(Key) }) {
                val white = outside()
                assertTrue("cut in the air: $white", white > 0.6f)
            }
        }
        assertTrue("never took off", rule.runOnIdle { host.isInAir(Key) })
    }
}
