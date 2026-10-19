package io.github.angelomarzocchi.flight.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.entryProvider
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.angelomarzocchi.flight.FlightHost
import io.github.angelomarzocchi.flight.FlightHostState
import io.github.angelomarzocchi.flight.FlightNavDisplay
import io.github.angelomarzocchi.flight.FlightPaint
import io.github.angelomarzocchi.flight.flightAnchor
import io.github.angelomarzocchi.flight.flightHole
import io.github.angelomarzocchi.flight.rememberFlightAnchor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * How Flight works, played by itself: an element, its two anchors — target A on the first page,
 * target B on the second — and the spring between the element and the target it is aiming at, drawn.
 *
 * The script pushes, pops, interrupts a flight halfway both ways, and holds a predictive back gesture
 * between the two pages. The trail behind the element shows that its path never breaks: a new target
 * only changes where the spring pulls.
 *
 * Start it with `adb shell am start -n io.github.angelomarzocchi.flight.demo/.ExplainActivity`.
 */
class ExplainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Explain()
            }
        }
    }
}

private data object PageA
private data object PageB

private const val BallKey = "ball"

private val ColorA = Color(0xFF4FC3F7)
private val ColorB = Color(0xFFFFB74D)

/** Where the two targets sit on their pages, in dp from the top left of the content area. */
private val TargetA = DpRect(x = 40.dp, y = 470.dp, size = 64.dp)
private val TargetB = DpRect(x = 170.dp, y = 90.dp, size = 170.dp)

private data class DpRect(val x: Dp, val y: Dp, val size: Dp)

private val Ball = FlightPaint { size, from, to, morph ->
    val color = lerp(from as Color, to as Color, morph)
    drawBall(color, size)
}

private fun DrawScope.drawBall(color: Color, size: Size) {
    val r = size.minDimension / 2f
    drawCircle(color, radius = r, center = Offset(size.width / 2f, size.height / 2f))
    drawCircle(Color.White.copy(alpha = 0.35f), radius = r * 0.35f, center = Offset(size.width * 0.38f, size.height * 0.36f))
}

@Composable
private fun Explain() {
    val backStack = remember { mutableStateListOf<Any>(PageA) }
    val host = remember { FlightHostState() }
    val gesture = remember { DirectNavigationEventInput() }
    var caption by remember { mutableStateOf("") }
    // How far a back gesture has gone, for the drawing: null with no gesture.
    var gestureProgress by remember { mutableStateOf<Float?>(null) }

    val owner = LocalNavigationEventDispatcherOwner.current
    DisposableEffect(owner) {
        val dispatcher = owner?.navigationEventDispatcher
        dispatcher?.addInput(gesture)
        onDispose { dispatcher?.removeInput(gesture) }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    // The system's animator scale slows the flights and the pages for real; the script keeps pace.
    val pace = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .coerceAtLeast(1f)
    }
    LaunchedEffect(Unit) {
        suspend fun delay(millis: Long) = kotlinx.coroutines.delay((millis * pace).toLong())
        fun push() { backStack += PageB }
        fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
        suspend fun backGesture(to: Float, millis: Long, from: Float) {
            val steps = (millis * pace / 16).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val p = from + (to - from) * i / steps
                gestureProgress = p
                gesture.backProgressed(NavigationEvent(progress = p, touchX = p * 1000f, touchY = 1200f, swipeEdge = NavigationEvent.EDGE_LEFT))
                kotlinx.coroutines.delay(16)
            }
        }
        delay(1200)
        while (true) {
            caption = "At rest on target A"
            delay(1300)
            caption = "Push: the target becomes B — the spring pulls it there"
            push(); delay(1900)
            caption = "Pop: the target is A again"
            pop(); delay(1900)

            caption = "Push…"
            push(); delay(260)
            caption = "…back halfway: the same flight turns round"
            pop(); delay(380)
            caption = "…and forward again: no jump, no restart"
            push(); delay(2000)

            caption = "Predictive back: held between B and A"
            gestureProgress = 0f
            gesture.backStarted(NavigationEvent(progress = 0f, touchX = 0f, touchY = 1200f, swipeEdge = NavigationEvent.EDGE_LEFT))
            backGesture(to = 0.55f, millis = 1100, from = 0f)
            delay(500)
            backGesture(to = 0.2f, millis = 600, from = 0.55f)
            delay(300)
            caption = "Let go without going back: home to B"
            gesture.backCancelled(); gestureProgress = null
            delay(1600)
            caption = "Back gesture, completed: home to A"
            gestureProgress = 0f
            gesture.backStarted(NavigationEvent(progress = 0f, touchX = 0f, touchY = 1200f, swipeEdge = NavigationEvent.EDGE_LEFT))
            backGesture(to = 0.4f, millis = 600, from = 0f)
            gesture.backCompleted(); gestureProgress = null
            delay(2200)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418)),
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            FlightHost(Modifier.fillMaxSize(), state = host) {
                FlightNavDisplay(
                    backStack = backStack,
                    onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                    entryProvider = entryProvider {
                        entry<PageA> { Page("Page A", Color(0xFF15202B), TargetA, ColorA) }
                        entry<PageB> { Page("Page B", Color(0xFF2B1F15), TargetB, ColorB) }
                    },
                )
            }
            Diagram(
                host = host,
                aimingAtB = backStack.last() == PageB,
                gestureProgress = gestureProgress,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Flight", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (pace > 1f) {
                Text("slow motion ×${"%.0f".format(pace)}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
            }
            Text(
                caption,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Page(title: String, background: Color, target: DpRect, color: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(background),
    ) {
        Text(title, color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp, modifier = Modifier.padding(24.dp))
        val anchor = rememberFlightAnchor(BallKey, Ball)
        anchor?.look = color
        Canvas(
            Modifier
                .offset(target.x, target.y)
                .size(target.size)
                .flightAnchor(anchor)
                .flightHole(anchor),
        ) { drawBall(color, size) }
    }
}

/**
 * Over the pages: the two targets, the one aimed at lit, a trail of where the element has been, and
 * the spring from the element to the point it is pulled towards.
 */
@Composable
private fun Diagram(host: FlightHostState, aimingAtB: Boolean, gestureProgress: Float?) {
    val density = LocalDensity.current
    val a = with(density) { Rect(Offset(TargetA.x.toPx(), TargetA.y.toPx()), Size(TargetA.size.toPx(), TargetA.size.toPx())) }
    val b = with(density) { Rect(Offset(TargetB.x.toPx(), TargetB.y.toPx()), Size(TargetB.size.toPx(), TargetB.size.toPx())) }
    val trail = remember { mutableStateListOf<Offset>() }
    var ball by remember { mutableStateOf<Rect?>(null) }
    // The lit target, eased so the switch reads.
    var towardB by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(host) {
        while (true) {
            withFrameNanos {
                val bounds = host.drawnBounds(BallKey)
                ball = bounds
                if (bounds != null) {
                    trail += bounds.center
                    if (trail.size > TrailLength) trail.removeAt(0)
                }
            }
        }
    }
    val wantB = when (gestureProgress) {
        null -> if (aimingAtB) 1f else 0f
        else -> 1f - gestureProgress
    }
    LaunchedEffect(wantB) {
        val start = towardB
        val steps = 8
        for (i in 1..steps) {
            towardB = start + (wantB - start) * i / steps
            withFrameNanos { }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 14.sp.toPx()
            color = android.graphics.Color.WHITE
        }
        listOf(Triple(a, ColorA, "target A"), Triple(b, ColorB, "target B")).forEachIndexed { index, (rect, color, label) ->
            val lit = if (index == 1) towardB else 1f - towardB
            drawCircle(color.copy(alpha = 0.10f + 0.18f * lit), radius = rect.width / 2f + 10f, center = rect.center)
            drawCircle(
                color.copy(alpha = 0.35f + 0.65f * lit),
                radius = rect.width / 2f + 10f,
                center = rect.center,
                style = Stroke(width = (2f + 3f * lit) * density.density / 2f, pathEffect = dash),
            )
            labelPaint.alpha = (110 + 145 * lit).toInt()
            drawContext.canvas.nativeCanvas.drawText(label, rect.left, rect.bottom + 22.sp.toPx(), labelPaint)
        }

        // The trail: where the centre has been, fading.
        trail.forEachIndexed { i, p ->
            val t = i / TrailLength.toFloat()
            drawCircle(Color.White.copy(alpha = 0.75f * t), radius = 4f + 5f * t, center = p)
        }

        // The spring: from the element to where it is pulled.
        val body = ball ?: return@Canvas
        val pull = when (gestureProgress) {
            null -> if (aimingAtB) b.center else a.center
            else -> lerp(b.center, a.center, gestureProgress)
        }
        drawSpring(body.center, pull, lerp(ColorA, ColorB, towardB))
        drawCircle(Color.White, radius = 11f, center = pull)
    }
}

/** A coil from [from] to [to]: always the same number of turns, so it stretches and squeezes. */
private fun DrawScope.drawSpring(from: Offset, to: Offset, color: Color) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    if (length < 4f) return
    val angle = atan2(dy, dx)
    val ux = cos(angle)
    val uy = sin(angle)
    val nx = -uy
    val ny = ux
    val path = Path().apply {
        moveTo(from.x, from.y)
        val lead = length * 0.08f
        lineTo(from.x + ux * lead, from.y + uy * lead)
        val coils = 14
        val amp = 18f
        for (i in 1..coils * 2) {
            val along = lead + (length - 2 * lead) * i / (coils * 2f)
            val side = if (i % 2 == 0) -amp else amp
            lineTo(from.x + ux * along + nx * side, from.y + uy * along + ny * side)
        }
        lineTo(to.x, to.y)
    }
    drawPath(path, color, style = Stroke(width = 7f))
}

private const val TrailLength = 70
