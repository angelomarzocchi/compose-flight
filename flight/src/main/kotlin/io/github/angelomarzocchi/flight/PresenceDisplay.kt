package io.github.angelomarzocchi.flight

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SceneState
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationEventState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs

/**
 * How a page moves as its presence goes from 0 to 1, on its way in, or from 1 to 0, on its way out:
 * slide, fade and scale as pure functions of the presence.
 *
 * [arriving] and [back] say how the page's current trip began — coming to the top or leaving it, on
 * a push or on a pop — and stay as they were if the trip is turned round halfway: a page that was
 * coming in and is sent back goes back the way it came, along the same curve. Nothing can jump,
 * because nothing but the presence changes.
 *
 * The presence can go a little past 0 or 1 when a spring is let go of fast: alpha must be clamped.
 */
public fun interface PageMotion {
    public fun GraphicsLayerScope.place(presence: Float, arriving: Boolean, back: Boolean)

    /**
     * Painted inside the page's layer, under its content: what a page that is not opaque needs behind
     * it when it is drawn over another one rather than beside it. On a layer of its own, so a frame of
     * it never redraws the page.
     */
    public fun DrawScope.under(presence: Float, arriving: Boolean, back: Boolean) {}

    /** Painted inside the page's layer, over its content: a veil or a dimming. Its own layer too. */
    public fun DrawScope.over(presence: Float, arriving: Boolean, back: Boolean) {}

    /**
     * The page coming to the top is drawn over the one leaving it, a pop too: for a motion where the
     * new page grows over the old one, whichever way the stack happened to go.
     */
    public val covers: Boolean get() = false
}

/**
 * The navigation's pages, shown by their presence rather than by a transition: what
 * `NavDisplay` does, with every page a presence on a spring.
 *
 * - Each composed scene has a presence: 1 on top, 0 gone. A change of page changes targets, never a
 *   presence, so back halfway through a push turns the pages round from where they are, at the speed
 *   they have, and the same page pushed again while it is leaving comes back up — the page
 *   itself, with its state, not a new one.
 * - Under a back gesture the presences follow the finger, on a stiff spring if the gesture found
 *   them still; let go, they carry on at the speed the finger gave them.
 * - A page stays composed until its presence is 0; the pages are RESUMED only once nothing moves,
 *   as `NavDisplay` does.
 * - What is on a page reads its presence from [LocalPagePresence]. There is no
 *   `LocalNavAnimatedContentScope` here, and reading it would throw.
 *
 * The scenes, the back handler and the gesture state are the caller's, as for `NavDisplay`'s
 * `(SceneState, NavigationEventState)` overload. [pageMotion] is asked, once, how a change of page
 * moves; [gestureMotion] is how the pages move under a back gesture.
 */
@Composable
internal fun <T : Any> PresenceDisplay(
    sceneState: SceneState<T>,
    navigationEventState: NavigationEventState<SceneInfo<T>>,
    pageMotion: () -> PageMotion,
    gestureMotion: PageMotion,
    modifier: Modifier = Modifier,
) {
    val state = remember { PresenceState<T>() }
    val scene = sceneState.currentScene
    val previousScene = sceneState.previousScenes.lastOrNull()
    val inGesture by remember(navigationEventState) {
        derivedStateOf { navigationEventState.transitionState is NavigationEventTransitionState.InProgress }
    }
    // As composed: the page a change brings is on screen in this very frame.
    state.arrange(
        current = scene,
        gestureBack = previousScene.takeIf { inGesture },
        entryKeys = sceneState.entries.map { it.contentKey },
        pageMotion = pageMotion,
        gestureMotion = gestureMotion,
    )

    LaunchedEffect(state, navigationEventState) {
        var frameNanos = NominalFrameNanos
        while (true) {
            state.awaitRestless()
            state.moving = true
            var last = -1L
            do {
                val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                val progress = (navigationEventState.transitionState as? NavigationEventTransitionState.InProgress)
                    ?.latestEvent?.progress
                withFrameNanos { now ->
                    if (last >= 0L) frameNanos = (now - last).coerceIn(1L, NominalFrameNanos * 4)
                    state.step(if (last < 0L) frameNanos else now - last, scale, progress)
                    last = now
                }
            } while (state.isRestless())
            state.moving = false
        }
    }

    val overlayScenes = sceneState.overlayScenes
    val shownOverlays = remember { mutableStateListOf<OverlayScene<T>>() }
    LaunchedEffect(overlayScenes) {
        // NavDisplay's own rule: every overlay once, in the order the stack has them.
        overlayScenes.forEach { scene -> if (shownOverlays.none { it.key == scene.key }) shownOverlays += scene }
    }
    // RESUMED only once nothing moves, as NavDisplay: read from the pages too, which know it in the
    // very composition a change of page makes them move, a frame before the frame loop does.
    val settled = !state.moving && state.pages.all { it.atRest } && overlayScenes.isEmpty() && shownOverlays.isEmpty()

    Box(modifier, propagateMinConstraints = true) {
        state.pages.forEach { page ->
            key(page.key) {
                val lifecycleOwner = rememberLifecycleOwner(
                    maxLifecycle = if (settled && page.key == state.currentKey) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
                )
                val presence = remember(page) { SpringPresence(page, state) }
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycleOwner,
                    LocalPagePresence provides presence,
                ) {
                    Box(
                        Modifier
                            .zIndex(page.z)
                            .graphicsLayer { with(page.motion) { place(page.presence, page.arriving, page.back) } },
                        propagateMinConstraints = true,
                    ) {
                        // Each on a layer of its own: they change every frame, the page does not.
                        Spacer(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer()
                                .drawBehind { with(page.motion) { under(page.presence, page.arriving, page.back) } },
                        )
                        page.scene.content()
                        Spacer(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer()
                                .drawBehind { with(page.motion) { over(page.presence, page.arriving, page.back) } },
                        )
                    }
                }
            }
        }
        shownOverlays.asReversed().forEach { overlay ->
            key(overlay.key) {
                val lifecycleOwner = rememberLifecycleOwner(
                    maxLifecycle = if (overlayScenes.firstOrNull() == overlay) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
                )
                val presence = remember { OverlayPresence() }
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycleOwner,
                    LocalPagePresence provides presence,
                ) {
                    // Above every page: an overlay draws its own sheet, scrim and exit.
                    Box(Modifier.zIndex(OverlayZ), propagateMinConstraints = true) { overlay.content() }
                }
            }
            if (overlayScenes.none { it.key == overlay.key }) {
                LaunchedEffect(overlay.key) {
                    overlay.onRemove()
                    shownOverlays.remove(overlay)
                }
            }
        }
    }
}

/** A scene, as NavDisplay tells two apart: its class and its key. */
private data class PageKey(val type: Class<*>, val key: Any) {
    constructor(scene: Scene<*>) : this(scene.javaClass, scene.key)
}

/** One composed page: its scene, its presence on a spring, and how its current trip moves it. */
@Stable
private class PresencePage<T : Any>(val key: PageKey, scene: Scene<T>, presence: Float, val z: Float) {
    var scene by mutableStateOf(scene)

    private val presenceState = mutableFloatStateOf(presence)
    var presence: Float
        get() = presenceState.floatValue
        set(value) {
            presenceState.floatValue = value
        }
    var velocity = 0f
    var target = presence

    /** At rest on its target: written only when that changes, so it can be read in composition. */
    var atRest by mutableStateOf(true)

    /** On the gesture's spring: a gesture that found the page still. */
    var underFinger = false

    var motion: PageMotion = NoMotion
    var arriving = true
    var back = false

    private var carryNanos = 0L

    /** Still, where it is going. */
    fun isStill(): Boolean = abs(presence - target) < RestPresence && abs(velocity) < RestVelocity

    /**
     * Still on top: a trip over, so the next one starts here with a curve of its own. A page held
     * halfway by a finger is still too, but on a trip it has not finished: that one keeps its curve.
     */
    fun isHome(): Boolean = isStill() && abs(presence - 1f) < RestPresence

    /** A new trip, from a page at rest: how it moves until it rests again. */
    fun begin(motion: PageMotion, arriving: Boolean, back: Boolean) {
        this.motion = motion
        this.arriving = arriving
        this.back = back
    }

    fun aim(target: Float) {
        this.target = target
        val still = isStill()
        if (atRest != still) atRest = still
    }

    fun step(dtNanos: Long, spring: FlightSpring) {
        val total = carryNanos + dtNanos
        val millis = total / NanosPerMilli
        carryNanos = total - millis * NanosPerMilli
        if (millis <= 0L) return
        val t = millis * NanosPerMilli
        val from = AnimationVector1D(presence)
        val to = AnimationVector1D(target)
        val v = AnimationVector1D(velocity)
        val next = spring.scalar.getValueFromNanos(t, from, to, v).value
        velocity = spring.scalar.getVelocityFromNanos(t, from, to, v).value
        presence = next
    }

    fun jump() {
        presence = target
        velocity = 0f
    }
}

@Stable
private class PresenceState<T : Any> {
    val pages = mutableStateListOf<PresencePage<T>>()

    /** The page on top. */
    var currentKey: PageKey? by mutableStateOf(null)
        private set

    /** Pages moving: the frame loop is running. Read in composition, for the lifecycle. */
    var moving by mutableStateOf(false)

    // The two pages of a back gesture in progress: the one on top and the one it goes back to.
    private var gestureTop: PresencePage<T>? = null
    private var gestureBack: PresencePage<T>? = null

    /** A gesture that has already gone back, while its state has not yet caught up. */
    private var spentGesture = false

    private var entryKeys: List<Any> = emptyList()
    private var lastWasPop = false

    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    private fun page(key: PageKey?): PresencePage<T>? = pages.firstOrNull { it.key == key }

    /**
     * Brings the pages in line with the navigation: the page on top and, while a gesture runs, the
     * one behind it. Only changes are applied, so it is safe to call on every composition.
     */
    fun arrange(
        current: Scene<T>,
        gestureBack: Scene<T>?,
        entryKeys: List<Any>,
        pageMotion: () -> PageMotion,
        gestureMotion: PageMotion,
    ) {
        if (entryKeys != this.entryKeys) {
            if (this.entryKeys.isNotEmpty()) lastWasPop = isPop(this.entryKeys, entryKeys)
            this.entryKeys = entryKeys
        }
        val key = PageKey(current)

        if (pages.isEmpty()) {
            // The first page is simply there, as NavDisplay's first scene is.
            pages += PresencePage(key, current, presence = 1f, z = 0f)
            currentKey = key
        }

        if (key != currentKey) {
            val pop = lastWasPop
            val motion by lazy(LazyThreadSafetyMode.NONE) { pageMotion() }
            // A gesture that went back ends here: its two pages are aimed below, like any change.
            if (gestureTop != null) {
                gestureTop?.underFinger = false
                this.gestureBack?.underFinger = false
                gestureTop = null
                this.gestureBack = null
                // The gesture's state can trail the stack by a frame: until it says the gesture is over,
                // the one in progress is this one, not a new gesture from the page now on top.
                spentGesture = true
            }
            pages.forEach { page ->
                if (page.key == key) return@forEach
                // Leaving the top: from rest a new trip; halfway through one, the same trip turned round.
                if (page.isHome()) page.begin(motion, arriving = false, back = pop)
                page.aim(0f)
            }
            val arriving = page(key) ?: PresencePage(
                key = key,
                scene = current,
                presence = 0f,
                // A push comes in over the page it covers; a pop is uncovered by the page leaving —
                // unless the motion grows the new page over the old one.
                z = if (pop && !motion.covers) {
                    (pages.minOfOrNull { it.z } ?: 0f) - 1f
                } else {
                    (pages.maxOfOrNull { it.z } ?: 0f) + 1f
                },
            ).also {
                it.begin(motion, arriving = true, back = pop)
                pages += it
            }
            arriving.aim(1f)
            currentKey = key
            wake()
        }
        page(key)?.let { if (it.scene != current) it.scene = current }

        val gestureKey = gestureBack?.let(::PageKey)
        if (gestureKey == null) spentGesture = false
        if (gestureKey != null && !spentGesture && gestureKey != this.gestureBack?.key) {
            val top = page(key) ?: return
            val still = top.isHome()
            if (still) top.begin(gestureMotion, arriving = false, back = true)
            val back = page(gestureKey) ?: PresencePage(gestureKey, gestureBack, presence = 0f, z = top.z - 1f).also {
                it.begin(gestureMotion, arriving = true, back = true)
                pages += it
            }
            top.underFinger = still
            back.underFinger = still
            this.gestureTop = top
            this.gestureBack = back
            wake()
        } else if (gestureKey == null && this.gestureBack != null) {
            val top = gestureTop
            val back = this.gestureBack
            // Let go without going back: the stack did not change, so the two go back where they were.
            // Gone back, the change of page above has already aimed them.
            if (top != null && top.key == currentKey) {
                top.aim(1f)
                back?.aim(0f)
            }
            top?.underFinger = false
            back?.underFinger = false
            gestureTop = null
            this.gestureBack = null
            wake()
        }
    }

    fun step(dtNanos: Long, durationScale: Float, progress: Float?) {
        val top = gestureTop
        val back = gestureBack
        if (progress != null && top != null && back != null) {
            top.aim(1f - progress)
            back.aim(progress)
        }
        pages.forEach { page ->
            val spring = if (page.underFinger) PageGestureSpring else PageSpring
            if (durationScale == 0f) page.jump() else page.step((dtNanos / durationScale).toLong(), spring)
            val still = page.isStill()
            // Exactly there once still: a page left a fraction of a pixel off draws its text blurred.
            if (still && page.presence != page.target) page.jump()
            if (page.atRest != still) page.atRest = still
        }
        // Gone: at rest out of sight.
        pages.removeAll { page ->
            page.key != currentKey && page !== top && page !== back && page.target <= 0f && page.isStill()
        }
    }

    fun isRestless(): Boolean = gestureTop != null || pages.any {
        !it.isStill() || (it.target <= 0f && it.key != currentKey)
    }

    fun wake() {
        wakeups.trySend(Unit)
    }

    suspend fun awaitRestless() {
        while (!isRestless()) {
            merge(snapshotFlow { isRestless() }.filter { it }, wakeups.receiveAsFlow()).first()
        }
    }
}

/** NavDisplay's rule for which way a change of stack goes. */
private fun isPop(old: List<Any>, new: List<Any>): Boolean {
    if (old.firstOrNull() != new.firstOrNull()) return false
    if (new.size >= old.size) return false
    return new.indices.all { new[it] == old[it] }
}

private class SpringPresence(
    private val page: PresencePage<*>,
    private val state: PresenceState<*>,
) : PagePresence {
    override val isSettled: Boolean
        get() = page.key == state.currentKey && page.atRest && page.target >= 1f

    @Composable
    override fun rememberShown(): State<Float> = remember(page) { derivedStateOf { page.presence.coerceIn(0f, 1f) } }
}

/** An overlay draws its own coming and going: as far as the page under it knows, it is just there. */
private class OverlayPresence : PagePresence {
    override val isSettled = true

    @Composable
    override fun rememberShown(): State<Float> = remember { mutableFloatStateOf(1f) }
}

private val NoMotion = PageMotion { _, _, _ -> }

/**
 * The pages' spring: `NavDisplay`'s own slide and fade, `spring(stiffness = MediumLow)` with no
 * bounce — one spring for both, since both are now the same presence.
 */
private val PageSpring = FlightSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

/** Under the finger: the same as an element's. */
private val PageGestureSpring = FlightSpring.Gesture

/** A page at rest: a thousandth of a full width is under a pixel on any phone. */
private const val RestPresence = 0.0005f
private const val RestVelocity = 0.02f

private const val NanosPerMilli = 1_000_000L
private const val NominalFrameNanos = 16_666_667L
private const val OverlayZ = 1_000_000f
