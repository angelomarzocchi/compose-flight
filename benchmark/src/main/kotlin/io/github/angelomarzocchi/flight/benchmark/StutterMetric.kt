package io.github.angelomarzocchi.flight.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import kotlin.math.roundToLong

/**
 * Stutters as the eye sees them: in the middle of a motion, the screen showing the same frame for two
 * refreshes or more, because the app's next one was not there. Read from the instants the app's frames
 * reached the screen, in Android's FrameTimeline: a gap of one refresh is smooth motion, a gap of 1.5 to
 * 6 refreshes is a stutter, a longer one is a pause between two motions.
 *
 * `stutters` counts them, `stutterRefreshesLost` the refreshes they held a frame still. The refresh is
 * the display's, read from the same gaps — the commonest one — so the metric holds at 60, 90 or 120 Hz.
 * Needs API 31; on older phones it reports nothing.
 */
@OptIn(ExperimentalMetricApi::class)
class StutterMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: Metric.CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Metric.Measurement> {
        val presented = traceSession.query(
            """
            SELECT actual_frame_timeline_slice.ts + actual_frame_timeline_slice.dur AS presented
            FROM actual_frame_timeline_slice
            JOIN process USING (upid)
            WHERE process.name = '${captureInfo.targetPackageName}'
              AND present_type != 'Dropped Frame'
            ORDER BY presented
            """.trimIndent(),
        ).map { it.long("presented") }.toList()
        val gaps = presented.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (gaps.size < 10) return emptyList()

        // The display's refresh: the gap most frames of a motion have, to the nearest half millisecond.
        val refresh = gaps.groupingBy { (it / 500_000.0).roundToLong() }.eachCount()
            .maxBy { it.value }.key * 500_000.0
        val stutters = gaps.map { it / refresh }.filter { it >= 1.5 && it < 6 }
        return listOf(
            Metric.Measurement("stutters", stutters.size.toDouble()),
            Metric.Measurement("stutterRefreshesLost", stutters.sumOf { it.roundToLong() - 1 }.toDouble()),
        )
    }
}
