package io.github.angelomarzocchi.flight.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import io.github.angelomarzocchi.flight.FlightHost
import io.github.angelomarzocchi.flight.FlightNavDisplay
import io.github.angelomarzocchi.flight.FlightPaint
import io.github.angelomarzocchi.flight.LocalPagePresence
import io.github.angelomarzocchi.flight.RecordedPaint
import io.github.angelomarzocchi.flight.flightAnchor
import io.github.angelomarzocchi.flight.flightHole
import io.github.angelomarzocchi.flight.recordedFlightAnchor
import io.github.angelomarzocchi.flight.rememberFlightAnchor

/**
 * A list of planets, and a page for each. Tap one: the planet flies into the page and its name grows
 * into the title. Go back halfway through — with the back button or a predictive back gesture — and
 * the same flight turns round from where it is. Hold the gesture and the planet hangs between the two
 * pages, under your finger.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    PlanetsApp()
                }
            }
        }
    }
}

private data class Planet(val name: String, val color: Color, val blurb: String)

private val Planets = listOf(
    Planet("Mercury", Color(0xFF9E9E9E), "The smallest, and the closest to the Sun."),
    Planet("Venus", Color(0xFFE8B04B), "Wrapped in clouds of sulphuric acid."),
    Planet("Earth", Color(0xFF3D7BD9), "Home: the only planet known to hold life."),
    Planet("Mars", Color(0xFFD0533A), "Red with iron oxide, and two small moons."),
    Planet("Jupiter", Color(0xFFC99A6B), "A storm older than any map of it."),
    Planet("Saturn", Color(0xFFE3C778), "Rings of ice, wider than the planet."),
    Planet("Uranus", Color(0xFF7FD3DD), "Rolls round the Sun on its side."),
    Planet("Neptune", Color(0xFF3F5BD9), "The windiest place in the Solar System."),
)

private data object Home
private data class Detail(val planet: Planet)

/** A key per planet and per part that flies: the same key on both pages makes one element. */
private data class PlanetKey(val name: String)
private data class TitleKey(val name: String)

/** A planet drawn in the air: a sphere lit from the top left. One look at both ends, so no morph. */
private val PlanetPaint = FlightPaint { size, from, to, morph ->
    val a = from as? Color ?: return@FlightPaint
    val b = to as? Color ?: return@FlightPaint
    drawSphere(lerp(a, b, morph), size.minDimension / 2f)
}

private fun DrawScope.drawSphere(color: Color, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to lerp(color, Color.White, 0.35f),
            1f to lerp(color, Color.Black, 0.35f),
            center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f),
            radius = radius * 1.6f,
        ),
        radius = radius,
    )
}

@Composable
private fun PlanetsApp() {
    val backStack = remember { mutableStateListOf<Any>(Home) }
    FlightHost(Modifier.fillMaxSize()) {
        FlightNavDisplay(
            backStack = backStack,
            entryProvider = entryProvider {
                entry<Home> { PlanetList(onOpen = { backStack += Detail(it) }) }
                entry<Detail> { PlanetPage(it.planet) }
            },
        )
    }
}

@Composable
private fun PlanetList(onOpen: (Planet) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.safeDrawingPadding(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    "Planets",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            items(Planets, key = { it.name }) { planet ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(planet) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PlanetSphere(planet, Modifier.size(48.dp))
                    PlanetName(planet, MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun PlanetPage(planet: Planet) {
    // What is not flying comes in with the page: here the text under the title.
    val shown by LocalPagePresence.current?.rememberShown() ?: remember { mutableFloatStateOf(1f) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            PlanetSphere(planet, Modifier.size(220.dp))
            Spacer(Modifier.height(32.dp))
            PlanetName(planet, MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                planet.blurb,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.graphicsLayer {
                    alpha = shown
                    translationY = (1f - shown) * 24.dp.toPx()
                },
            )
        }
    }
}

/** The planet: drawn by the page at rest, by the host in the air. */
@Composable
private fun PlanetSphere(planet: Planet, modifier: Modifier) {
    val anchor = rememberFlightAnchor(PlanetKey(planet.name), PlanetPaint)
    anchor?.look = planet.color
    Canvas(
        modifier
            .flightAnchor(anchor)
            .flightHole(anchor),
    ) { drawSphere(planet.color, size.minDimension / 2f) }
}

/** The name: recorded at both sizes, and cross-faded from one to the other as it grows. */
@Composable
private fun PlanetName(planet: Planet, style: TextStyle) {
    val anchor = rememberFlightAnchor(TitleKey(planet.name), remember { RecordedPaint(single = false) })
    Text(planet.name, style = style, modifier = Modifier.recordedFlightAnchor(anchor))
}
