package io.github.angelomarzocchi.flight.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The README's script — open and back, a flight turned round halfway, one turned round twice, a quick
 * swipe back with the element tapped again straight after, a back gesture completed — played on a real
 * phone by Flight and by Compose's `SharedTransitionLayout` + `NavDisplay`, with the same pages, the
 * same springs and the same instants.
 *
 * Measured on the demo's `benchmark` build (R8, not debuggable), fully compiled ahead of time so that
 * neither engine pays for the JIT, from a cold process each time. What each number means is in the
 * README, "How light is it".
 */
@RunWith(Parameterized::class)
class FlightBenchmark(private val engine: String) {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun script() = rule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(
            // Frame times: CPU per frame (UI thread + RenderThread), and how late each frame was.
            FrameTimingMetric(),
            // Frames late through the app's own fault, as Android's FrameTimeline judges them.
            AppJankMetric(),
            // Stutters the eye sees: a frame held still for two refreshes or more in the middle of a motion.
            StutterMetric(),
            // CPU on the app's threads over the whole script: the main thread, the RenderThread that
            // turns frames into GPU commands, and every thread of the process.
            ThreadCpuMetric(),
            // Where the main thread's time goes: composing, laying out, recording the drawing.
            TraceSectionMetric("Recomposer:recompose", TraceSectionMetric.Mode.Sum, label = "recompose"),
            TraceSectionMetric("AndroidOwner:measureAndLayout", TraceSectionMetric.Mode.Sum, label = "measureAndLayout"),
            TraceSectionMetric("AndroidOwner:draw", TraceSectionMetric.Mode.Sum, label = "drawRecord"),
            // Heap, RSS and GPU memory, at their highest.
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        iterations = 5,
        // No startup mode: COLD would kill the app after the setup, for the measured block to launch it.
        // The setup kills it itself, so every run still starts from a cold process.
        startupMode = null,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            killProcess()
            startActivityAndWait(
                Intent()
                    .setClassName(TargetPackage, "$TargetPackage.BenchActivity")
                    .putExtra("engine", engine)
                    .putExtra("rounds", 1),
            )
            // Compose's tree reaches UiAutomator a moment after the first frame: wait for the button.
            check(device.wait(Until.hasObject(By.text("Play")), 5_000)) { "no Play button" }
        },
    ) {
        // Only the script is traced: the launch above is not.
        val play = device.findObject(By.text("Play"))
        val x = play.visibleCenter.x
        val y = play.visibleCenter.y
        play.click()
        // A finger rests on the label, which no longer reacts, until the script is over. Phones with an
        // adaptive display drop to their lowest rate a moment after the last touch, whatever the app
        // asks: without a finger the script would be measured at 60 Hz, not at the rate users see
        // under their fingers.
        device.executeShellCommand("input motionevent DOWN $x $y")
        try {
            check(device.wait(Until.hasObject(By.text("Done")), 60_000)) { "the script did not finish" }
        } finally {
            device.executeShellCommand("input motionevent UP $x $y")
        }
    }

    companion object {
        const val TargetPackage = "io.github.angelomarzocchi.flight.demo"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines() = listOf("Flight", "Shared")
    }
}
