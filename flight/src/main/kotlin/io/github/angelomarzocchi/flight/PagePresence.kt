package io.github.angelomarzocchi.flight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf

/**
 * How much of a page is there, for what on the page moves with it.
 *
 * The one thing a page tells what it holds about its own coming and going: [FlightNavDisplay] gives
 * every page a presence on a spring. What would read `LocalNavAnimatedContentScope` under
 * `NavDisplay` reads this instead — that local does not exist here.
 */
@Stable
public interface PagePresence {
    /** On top and still: the page has arrived and nothing is moving it. Reads snapshot state. */
    public val isSettled: Boolean

    /**
     * How much of the page is shown, from 0 to 1, smoothly: for what fades with the page. It is the
     * presence itself, on the finger during a back gesture.
     */
    @Composable
    public fun rememberShown(): State<Float>
}

/** Null outside a navigation page: previews and screenshot tests, where nothing comes or goes. */
public val LocalPagePresence: ProvidableCompositionLocal<PagePresence?> = compositionLocalOf { null }
