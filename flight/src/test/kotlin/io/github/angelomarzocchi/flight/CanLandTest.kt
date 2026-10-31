package io.github.angelomarzocchi.flight

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Two ends on one page, the second fading in: the element waits in the air until it can land there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanLandTest {

    @get:Rule
    val rule = createComposeRule()

    private data object Page
    private data object Key

    private val host = FlightHostState()
    private val paint = FlightPaint { size, _, _, _ -> drawRect(Color.Red, size = size) }
    private var second by mutableStateOf(false)
    private var landable = false

    private fun run(n: Int) = repeat(n) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun start() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val stack = remember { mutableStateListOf<Any>(Page) }
            FlightHost(state = host, modifier = Modifier.fillMaxSize()) {
                FlightNavDisplay(
                    backStack = stack,
                    onBack = {},
                    entryProvider = entryProvider {
                        entry<Page> {
                            Box(Modifier.fillMaxSize()) {
                                val first = rememberFlightAnchor(Key, paint)
                                Box(Modifier.size(40.dp).flightAnchor(first).flightHole(first))
                                if (second) {
                                    val end = rememberFlightAnchor(Key, paint)
                                    end?.canLand = { landable }
                                    Box(Modifier.offset(y = 200.dp).size(40.dp).flightAnchor(end).flightHole(end))
                                }
                            }
                        }
                    },
                )
            }
        }
        run(5)
    }

    @Test
    fun theElementWaitsInTheAirUntilItCanLand() {
        start()
        second = true
        run(300)
        assertTrue("landed on an end that cannot show it", rule.runOnIdle { host.isInAir(Key) })
        landable = true
        run(5)
        assertFalse("still in the air once it can land", rule.runOnIdle { host.isInAir(Key) })
    }
}
