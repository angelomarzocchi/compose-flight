package io.github.angelomarzocchi.flight.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Frames that reached the screen late through the app's own fault, as the system judged them: Android's
 * FrameTimeline marks each frame "App Deadline Missed" when the app was late, and something else when
 * the lateness was the system's — SurfaceFlinger, or a queue of buffers full because the app was ahead
 * of the display. `appLateFrames` and `appLateFramesPercent` of every frame drawn.
 *
 * The one count of jank that is the app's alone: `frameOverrunMs` and `frameDurationCpuMs` also count
 * the time a RenderThread spends waiting for the system to hand it a buffer. Needs API 31; on older
 * phones it reports nothing.
 */
@OptIn(ExperimentalMetricApi::class)
class AppJankMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: Metric.CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Metric.Measurement> {
        val row = traceSession.query(
            """
            SELECT COUNT(*) AS frames,
                   SUM(CASE WHEN jank_type LIKE '%App Deadline Missed%' THEN 1 ELSE 0 END) AS late
            FROM actual_frame_timeline_slice
            JOIN process USING (upid)
            WHERE process.name = '${captureInfo.targetPackageName}'
            """.trimIndent(),
        ).first()
        val frames = row.long("frames")
        if (frames == 0L) return emptyList()
        val late = row.nullableLong("late") ?: 0L
        return listOf(
            Metric.Measurement("appLateFrames", late.toDouble()),
            Metric.Measurement("appLateFramesPercent", 100.0 * late / frames),
        )
    }
}
