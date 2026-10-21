package io.github.angelomarzocchi.flight.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a flight costs the app: the script of the README's recording, played by both engines side by
 * side at the same instants, with every piece of the pages counting how many times it is composed,
 * measured, placed and drawn.
 *
 * Counted, not timed: the numbers are the same on every machine, so they say what each engine asks
 * of the app per frame rather than how fast this computer is. The frame times on a real phone are
 * `:benchmark`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Drawn for real: the legacy mode never draws at all.
class CostTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aFlightCostsThePagesNothing() {
        val meters = Engine.entries.associateWith { Meter() }
        val navigators = Engine.entries.associateWith { Navigator() }
        var playing by mutableStateOf(false)
        var done = false
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Row(Modifier.fillMaxSize()) {
                    Engine.entries.forEach { engine ->
                        Box(Modifier.weight(1f).clipToBounds()) {
                            CompositionLocalProvider(LocalMeter provides meters.getValue(engine)) {
                                Versus(engine, navigators.getValue(engine), Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            if (playing) {
                LaunchedEffect(Unit) {
                    playScript(navigators.values.toList(), pace = 1f) {}
                    done = true
                }
            }
        }
        frame(times = 10)
        // From here on, only what the script costs: not the first composition.
        meters.values.forEach { it.reset() }
        rule.runOnIdle { playing = true }

        var frames = 0
        while (!done) {
            frame()
            frames++
            check(frames < 2_000) { "the script did not end" }
        }

        val flight = meters.getValue(Engine.Flight)
        val shared = meters.getValue(Engine.Shared)
        println(
            """
            |The README's script, $frames frames, same instants on both engines:
            |                SharedTransitionLayout   Flight
            |  compositions  ${"%22d".format(shared.compositions)}   ${"%6d".format(flight.compositions)}
            |  measures      ${"%22d".format(shared.measures)}   ${"%6d".format(flight.measures)}
            |  placements    ${"%22d".format(shared.placements)}   ${"%6d".format(flight.placements)}
            |  draws         ${"%22d".format(shared.draws)}   ${"%6d".format(flight.draws)}
            """.trimMargin(),
        )

        // Flight works when the page changes, not on every frame of the flight: the pages slide on a
        // layer and the element is drawn by the overlay, so their content is not laid out or drawn
        // again. The margins are wide on purpose — these fail on a regression, not on a new Compose.
        assertTrue("Flight measured ${flight.measures} times in $frames frames", flight.measures < frames / 4)
        assertTrue("Flight placed ${flight.placements} times in $frames frames", flight.placements < frames / 4)
        assertTrue("Flight drew ${flight.draws} times in $frames frames", flight.draws < frames)
        // And against Compose's own: a fraction of its layout and drawing, for the same motion.
        assertTrue("measures: $flight against $shared", flight.measures * 5 < shared.measures)
        assertTrue("placements: $flight against $shared", flight.placements * 5 < shared.placements)
        assertTrue("draws: $flight against $shared", flight.draws * 3 < shared.draws)
        // Neither recomposes per frame: composing is the same work on both.
        assertTrue("compositions: $flight against $shared", flight.compositions <= shared.compositions * 1.2)
    }

    /** One frame, drawn: Robolectric draws only when asked to, and a capture draws the window. */
    private fun frame(times: Int = 1) = repeat(times) {
        rule.mainClock.advanceTimeByFrame()
        rule.onRoot().captureToImage()
    }

    private fun Meter.reset() {
        compositions = 0
        measures = 0
        placements = 0
        draws = 0
    }
}
