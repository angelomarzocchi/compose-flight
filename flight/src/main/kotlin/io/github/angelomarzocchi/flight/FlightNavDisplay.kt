package io.github.angelomarzocchi.flight

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberSceneState
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * A drop-in for Navigation 3's `NavDisplay` whose pages are presences on springs, and which tells a
 * [FlightHost] what the navigation knows: which pages are on screen, which are behind them, and the
 * back gesture as it happens.
 *
 * The pieces and their order are `NavDisplay`'s own (the `backStack` and `entries` overloads): the
 * entries decorated, the scenes worked out, the gesture state, the back handler. Owning the gesture
 * state is the point — the `backStack` overload keeps it to itself. The pages are presences on
 * springs rather than `NavDisplay`'s transitions, which cannot be interrupted: here, going back
 * halfway through a push turns the pages round from where they are, at the speed they have.
 *
 * Every page is also wrapped so that an anchor on it knows which page it is on, where that page is,
 * and whether it has finished arriving. Put a [FlightHost] round it for elements to fly; without one
 * it is just a navigation display with interruptible page motion.
 *
 * @param backStack the back stack, as for `NavDisplay`. Must not be empty.
 * @param onBack pops the stack: by default, the last entry of a [MutableList].
 * @param entryDecorators decorators for the entries, as for `NavDisplay`.
 * @param sceneStrategies how entries are grouped into scenes, as for `NavDisplay`.
 * @param pageMotion how a change of page moves, asked once per change.
 * @param gestureMotion how the pages move under a predictive back gesture.
 * @param entryProvider the content of each key, as for `NavDisplay`.
 */
@Composable
public fun <T : Any> FlightNavDisplay(
    backStack: List<T>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {
        if (backStack is MutableList<T>) {
            backStack.removeLastOrNull()
        }
    },
    entryDecorators: List<NavEntryDecorator<T>> = listOf(rememberSaveableStateHolderNavEntryDecorator()),
    sceneStrategies: List<SceneStrategy<T>> = listOf(SinglePaneSceneStrategy()),
    pageMotion: () -> PageMotion = { DefaultPageMotion },
    gestureMotion: PageMotion = DefaultPageMotion,
    entryProvider: (key: T) -> NavEntry<T>,
) {
    require(backStack.isNotEmpty()) { "FlightNavDisplay backStack cannot be empty" }
    val host = LocalFlightHost.current
    // First, so the outermost: what the other decorators put on a page is on it too.
    val pages = remember { flightPages<T>() }
    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = listOf(pages) + entryDecorators,
        entryProvider = entryProvider,
    )
    val sceneState = rememberSceneState(
        entries = entries,
        sceneStrategies = sceneStrategies,
        onBack = onBack,
    )
    val scene = sceneState.currentScene
    val gestureState = rememberNavigationEventState(
        currentInfo = SceneInfo(scene),
        backInfo = sceneState.previousScenes.map { SceneInfo(it) },
    )
    NavigationBackHandler(
        state = gestureState,
        isBackEnabled = scene.previousEntries.isNotEmpty(),
        onBackCompleted = {
            // NavDisplay's own guard: a stale `isBackEnabled` pops nothing rather than crashing.
            repeat(entries.size - scene.previousEntries.size) { onBack() }
        },
    )

    if (host != null) {
        val previous = sceneState.previousScenes.lastOrNull()
        SideEffect {
            val navigation = host.navigation
            val current = scene.entries.mapTo(HashSet()) { it.contentKey }
            if (current != navigation.current) host.wake()
            navigation.current = current
            navigation.back = previous?.entries?.mapTo(HashSet()) { it.contentKey } ?: emptySet()
            // As NavDisplay reads it: a gesture is a predictive back only with somewhere to go back to.
            navigation.gesture = {
                (gestureState.transitionState as? NavigationEventTransitionState.InProgress)
                    ?.takeIf { navigation.back.isNotEmpty() }
                    ?.latestEvent
                    ?.progress
            }
        }
    }

    PresenceDisplay(
        sceneState = sceneState,
        navigationEventState = gestureState,
        pageMotion = pageMotion,
        gestureMotion = gestureMotion,
        modifier = modifier.onPlaced { host?.restingPages = it },
    )
}

/**
 * A slide and a fade: the page coming to the top from half a width towards the start, the one
 * leaving going a whole width — towards the start on a push, the end on a pop.
 */
public val DefaultPageMotion: PageMotion = PageMotion { presence, arriving, back ->
    val width = size.width
    translationX = (1f - presence) * when {
        arriving -> -width / 2f
        back -> width
        else -> -width
    }
    alpha = presence.coerceIn(0f, 1f)
}

/**
 * One page of the navigation, as Flight sees it: the entry's [key], and where its content is laid
 * out — inside the page's own slide, fade and scale. Whether a page has arrived is read from where
 * it is, frame to frame.
 */
@Stable
internal class FlightPage(val key: Any) {
    var coordinates: LayoutCoordinates? = null
}

/** The page an anchor is on; null outside a navigation entry, where an anchor is on no page. */
internal val LocalFlightPage = staticCompositionLocalOf<FlightPage?> { null }

/**
 * Wraps every page in a box that reports where the page's content is, and tells what is inside which
 * page it is on. The box passes its constraints through untouched: the page is laid out exactly as it
 * would be without it.
 */
private fun <T : Any> flightPages(): NavEntryDecorator<T> = NavEntryDecorator { entry ->
    val page = remember(entry.contentKey) { FlightPage(entry.contentKey) }
    // The presence is PresenceDisplay's, already provided round the page.
    CompositionLocalProvider(LocalFlightPage provides page) {
        Box(Modifier.onPlaced { page.coordinates = it }, propagateMinConstraints = true) {
            entry.Content()
        }
    }
}
