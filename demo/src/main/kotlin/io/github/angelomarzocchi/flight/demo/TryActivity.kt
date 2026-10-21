package io.github.angelomarzocchi.flight.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The scene of the side-by-side recording, one engine at a time and full screen, for a finger: tap a
 * planet, go back with the back button or a predictive back gesture, and change your mind halfway. The
 * switch at the top swaps Flight for Compose's `SharedTransitionLayout` + `NavDisplay`, same pages,
 * same springs.
 *
 * `adb shell am start -n io.github.angelomarzocchi.flight.demo/.TryActivity [--es engine Shared]`
 */
class TryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var engine by rememberSaveable {
                        mutableStateOf(Engine.valueOf(intent.getStringExtra("engine") ?: Engine.Flight.name))
                    }
                    Column(Modifier.safeDrawingPadding()) {
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Engine.entries.forEachIndexed { index, each ->
                                SegmentedButton(
                                    selected = engine == each,
                                    onClick = { engine = each },
                                    shape = SegmentedButtonDefaults.itemShape(index, Engine.entries.size),
                                ) {
                                    Text(if (each == Engine.Flight) "Flight" else "Compose")
                                }
                            }
                        }
                        // A fresh back stack for each engine: the switch starts again from the list.
                        key(engine) {
                            Scene(engine, remember { Navigator() }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
