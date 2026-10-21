package io.github.angelomarzocchi.flight.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One engine alone, full screen, playing the script [rounds] times when "Play" is tapped and saying
 * "Done" when it has finished: what `:benchmark` measures, Flight against Compose's shared elements.
 *
 * `adb shell am start -n io.github.angelomarzocchi.flight.demo/.BenchActivity --es engine Shared`
 */
class BenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val engine = Engine.valueOf(intent.getStringExtra(ExtraEngine) ?: Engine.Flight.name)
        val rounds = intent.getIntExtra(ExtraRounds, 2)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val navigator = remember { Navigator() }
                    var state by remember { mutableStateOf(State.Ready) }
                    if (state == State.Playing) {
                        LaunchedEffect(Unit) {
                            // Real time: a benchmark measures the motion as users get it.
                            repeat(rounds) { playScript(listOf(navigator), pace = 1f) {} }
                            state = State.Done
                        }
                    }
                    Column(Modifier.safeDrawingPadding()) {
                        Versus(engine, navigator, Modifier.weight(1f))
                        Text(
                            state.label,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = state == State.Ready) { state = State.Playing }
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    private enum class State(val label: String) {
        Ready("Play"),
        Playing("Playing"),
        Done("Done"),
    }

    companion object {
        const val ExtraEngine = "engine"
        const val ExtraRounds = "rounds"
    }
}
