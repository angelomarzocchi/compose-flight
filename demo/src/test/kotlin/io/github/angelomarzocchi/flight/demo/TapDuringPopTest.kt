package io.github.angelomarzocchi.flight.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back from a page, and the list tapped again before the way back is over: the tap belongs to the list
 * coming back, not to the page leaving, which is still drawn over it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TapDuringPopTest {

    @get:Rule
    val rule = createComposeRule()

    private val navigator = Navigator()

    @Test
    fun aTapRightAfterBackReachesTheList() {
        val saturn = showList()
        navigator.open()
        frame(120)
        navigator.back()
        frame(6)
        rule.onRoot().performTouchInput { click(saturn) }
        frame(2)
        assertEquals("the tap was taken by the page leaving", 2, navigator.backStack.size)
    }

    @Test
    fun aTapAfterAPushSentBackHalfwayReachesTheList() {
        val saturn = showList()
        // The page opened, sent back before it is in, and the planet tapped again a quarter of a second on.
        navigator.open()
        frame(15)
        navigator.back()
        frame(15)
        rule.onRoot().performTouchInput { click(saturn) }
        frame(2)
        assertEquals("the tap was taken by the page leaving", 2, navigator.backStack.size)
    }

    /**
     * Both engines, Saturn tapped 1 to 30 frames after going back: from a page at rest, and from a page
     * sent back before it was in. Compose's `NavDisplay` keeps the page leaving on top for touches too,
     * and loses the tap while that page covers the list; Flight hands it to the list as soon as the
     * list is on screen. The README's numbers.
     */
    @Test
    fun theEnginesComparedFrameByFrame() {
        var engine by mutableStateOf(Engine.Flight)
        var each by mutableStateOf(navigator)
        val saturn = showList { key(engine, each) { Versus(engine, each, Modifier.fillMaxSize()) } }
        val frames = listOf(1, 2, 3, 4, 6, 8, 10, 15, 20, 30)
        val report = StringBuilder()
        report.appendLine("Saturn tapped n frames after going back (60 Hz): ok, the list took it")
        report.appendLine(frames.joinToString(" ") { "%3d".format(it) } + "   frames after back")
        for ((scene, openFor) in listOf("from a page at rest" to 120, "from a page sent back halfway in" to 15)) {
            report.appendLine(scene)
            for (which in Engine.entries) {
                val taken = frames.filter { framesAfterBack ->
                    engine = which
                    each = Navigator()
                    frame(10)
                    each.open()
                    frame(openFor)
                    each.back()
                    frame(framesAfterBack)
                    rule.onRoot().performTouchInput { click(saturn) }
                    frame(2)
                    each.backStack.size == 2
                }
                report.appendLine(frames.joinToString(" ") { if (it in taken) " ok" else "  -" } + "   " + which.title)
                if (which == Engine.Flight) {
                    // The first frame may come before the list is composed again; from the next one, every tap.
                    assertTrue("Flight lost a tap $scene: took only $taken", taken.containsAll(frames.drop(1)))
                }
            }
        }
        println(report)
    }

    /** The list, at rest: where a finger finds Saturn, as the eye remembers it. */
    private fun showList(
        content: @Composable () -> Unit = { Versus(Engine.Flight, navigator, Modifier.fillMaxSize()) },
    ): Offset {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { content() }
        }
        frame(10)
        val row = rule.onNodeWithText("Saturn").fetchSemanticsNode().boundsInRoot
        return Offset(row.left + 30f, row.center.y)
    }

    private fun frame(times: Int = 1) = repeat(times) {
        rule.mainClock.advanceTimeByFrame()
        rule.onRoot().captureToImage()
    }
}
