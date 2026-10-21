package io.github.angelomarzocchi.flight.demo

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Flight and Compose's shared elements side by side, playing the same script at the same instants:
 * the recording in the README. Start it with
 * `adb shell am start -n io.github.angelomarzocchi.flight.demo/.CompareActivity`.
 */
class CompareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Compare()
            }
        }
    }
}

private val Backdrop = Color(0xFF0B0E12)

private val SceneHeight = 560.dp

@Composable
private fun Compare() {
    val navigators = remember { Engine.entries.map { Navigator() } }
    var caption by remember { mutableStateOf("") }
    val context = LocalContext.current
    // The animator scale slows both engines for real; the script keeps pace with them.
    val pace = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).coerceAtLeast(1f)
    }
    LaunchedEffect(Unit) {
        delay(1000)
        while (true) {
            playScript(navigators, pace) { caption = it }
            caption = ""
            delay((800 * pace).toLong())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .safeDrawingPadding(),
    ) {
        // As tall as the scene needs, so the recording has no empty screen under the list.
        Row(Modifier.height(SceneHeight)) {
            Engine.entries.forEachIndexed { index, engine ->
                if (index > 0) Box(Modifier.width(2.dp).fillMaxHeight().background(Backdrop))
                Column(Modifier.weight(1f)) {
                    Header(engine)
                    // Each scene kept to its half: pages slide by its width, and nothing flies over the other.
                    Versus(engine, navigators[index], Modifier.fillMaxSize().clipToBounds())
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                caption,
                color = Color.White,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (pace > 1f) {
                Text(
                    "slow motion ×${"%.0f".format(pace)}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(engine: Engine) {
    val (title, subtitle) = when (engine) {
        Engine.Flight -> "Flight" to "FlightHost +\nFlightNavDisplay"
        Engine.Shared -> "Compose" to "SharedTransitionLayout +\nNavDisplay"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}
