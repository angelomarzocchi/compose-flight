package io.github.angelomarzocchi.flight

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorizedSpringSpec
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/**
 * A spring, by its two numbers.
 *
 * Kept as the vectorized specs animation-core already has, rather than a spring solved here: those are
 * the analytic solution of the damped spring, exact whatever the frame rate, and the same one every
 * `animate*` call in an app runs on.
 */
@Immutable
public class FlightSpring(public val dampingRatio: Float, public val stiffness: Float) {
    internal val bounds = VectorizedSpringSpec<AnimationVector4D>(dampingRatio, stiffness, null)
    internal val scalar = VectorizedSpringSpec<AnimationVector1D>(dampingRatio, stiffness, null)

    override fun equals(other: Any?): Boolean =
        other is FlightSpring && other.dampingRatio == dampingRatio && other.stiffness == stiffness

    override fun hashCode(): Int = 31 * dampingRatio.hashCode() + stiffness.hashCode()

    override fun toString(): String = "FlightSpring(dampingRatio=$dampingRatio, stiffness=$stiffness)"

    public companion object {
        /**
         * [spec]'s two numbers if it is a spring, as a Material motion scheme's are; [fallback] if it
         * is not.
         */
        public fun of(spec: FiniteAnimationSpec<*>, fallback: FlightSpring): FlightSpring =
            (spec as? SpringSpec<*>)?.let { FlightSpring(it.dampingRatio, it.stiffness) } ?: fallback

        /** Material 3 Expressive's `defaultSpatialSpec`: a small bounce on arrival. */
        public val Spatial: FlightSpring = FlightSpring(0.8f, 380f)

        /** Material 3 Expressive's `defaultEffectsSpec`: no bounce. */
        public val Effects: FlightSpring = FlightSpring(Spring.DampingRatioNoBouncy, 1600f)

        /**
         * Under a finger: stiff enough that the element keeps up with it — a time constant under two
         * frames — and with no bounce, so it does not tremble with the gesture's own unevenness.
         */
        public val Gesture: FlightSpring = FlightSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
    }
}

/**
 * The three springs a host flies with: [spatial] for where an element is and how big, [effects] for
 * how far its look has morphed, and [gesture] while a back gesture holds it.
 */
@Immutable
public class FlightSprings(
    public val spatial: FlightSpring = FlightSpring.Spatial,
    public val effects: FlightSpring = FlightSpring.Effects,
    public val gesture: FlightSpring = FlightSpring.Gesture,
) {
    override fun equals(other: Any?): Boolean =
        other is FlightSprings && other.spatial == spatial && other.effects == effects && other.gesture == gesture

    override fun hashCode(): Int = (spatial.hashCode() * 31 + effects.hashCode()) * 31 + gesture.hashCode()

    public companion object {
        public val Default: FlightSprings = FlightSprings()
    }
}

/**
 * Where a flying element is and how fast it is going: its centre, its size, and how far its look has
 * gone from one end's to the other's ([morph]) — each with its velocity, per second.
 *
 * This is the whole of what an interruption has to keep: nothing here is a segment or a time. Going
 * somewhere else is a new target on the next [step], from the position and the velocity there are,
 * so a flight that turns round cannot jump or lose its speed.
 *
 * The centre, not the edges: the centre's path is the motion the eye reads, and the size has springs
 * of its own.
 */
internal class FlightBody {
    var cx = 0f
        private set
    var cy = 0f
        private set
    var w = 0f
        private set
    var h = 0f
        private set
    var vx = 0f
        private set
    var vy = 0f
        private set
    var vw = 0f
        private set
    var vh = 0f
        private set

    /** 0: the look of the end the flight left, 1: the look of the one it is going to. */
    var morph = 0f
    var morphVelocity = 0f

    /**
     * Time not yet stepped, under a millisecond. animation-core's spring takes whole milliseconds
     * (`FloatSpringSpec` truncates the nanos), so a 16.67 ms frame stepped as it comes would run the
     * spring 4% slow at 60 Hz and as much again at 120: the rest is carried to the next frame instead.
     */
    private var carryNanos = 0L

    val bounds: Rect
        get() = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

    val velocity: Offset
        get() = Offset(vx, vy)

    /** Where it takes off from: there, and still. */
    fun snapTo(at: Rect) {
        cx = at.center.x
        cy = at.center.y
        w = at.width
        h = at.height
        vx = 0f
        vy = 0f
        vw = 0f
        vh = 0f
        carryNanos = 0L
    }

    /** Already moving as it takes off, at [velocity] per second: its page was carrying it. */
    fun push(velocity: Offset) {
        vx = velocity.x
        vy = velocity.y
    }

    /** Carried by a scroll, one to one: the spring never sees it. */
    fun translate(by: Offset) {
        cx += by.x
        cy += by.y
    }

    /** No animations (the system's are off): straight to [target]. */
    fun jumpTo(target: Rect, morphTarget: Float) {
        snapTo(target)
        morph = morphTarget
        morphVelocity = 0f
    }

    fun step(dtNanos: Long, target: Rect, morphTarget: Float, spring: FlightSpring, morphSpring: FlightSpring) {
        val total = carryNanos + dtNanos
        val millis = total / NanosPerMilli
        carryNanos = total - millis * NanosPerMilli
        if (millis <= 0L) return
        val t = millis * NanosPerMilli

        val from = AnimationVector4D(cx, cy, w, h)
        val to = AnimationVector4D(target.center.x, target.center.y, target.width, target.height)
        val v = AnimationVector4D(vx, vy, vw, vh)
        // Both read at once: the spec hands back the same vector on every call.
        spring.bounds.getValueFromNanos(t, from, to, v).let {
            cx = it.v1
            cy = it.v2
            w = it.v3
            h = it.v4
        }
        spring.bounds.getVelocityFromNanos(t, from, to, v).let {
            vx = it.v1
            vy = it.v2
            vw = it.v3
            vh = it.v4
        }

        val m0 = AnimationVector1D(morph)
        val m1 = AnimationVector1D(morphTarget)
        val mv = AnimationVector1D(morphVelocity)
        morph = morphSpring.scalar.getValueFromNanos(t, m0, m1, mv).value
        morphVelocity = morphSpring.scalar.getVelocityFromNanos(t, m0, m1, mv).value
    }

    /**
     * Still, on [target]: within half a pixel, and moving less than half a pixel a frame — so handing
     * it to the anchor, which draws it exactly on [target], moves it by no more than that.
     */
    fun isAtRest(target: Rect, morphTarget: Float): Boolean =
        abs(cx - target.center.x) < RestPx && abs(cy - target.center.y) < RestPx &&
            abs(w - target.width) < RestPx && abs(h - target.height) < RestPx &&
            abs(vx) < RestPxPerSecond && abs(vy) < RestPxPerSecond &&
            abs(vw) < RestPxPerSecond && abs(vh) < RestPxPerSecond &&
            abs(morph - morphTarget) < RestMorph

    private companion object {
        const val NanosPerMilli = 1_000_000L
        const val RestPx = 0.5f
        const val RestPxPerSecond = 25f
        const val RestMorph = 0.01f
    }
}
