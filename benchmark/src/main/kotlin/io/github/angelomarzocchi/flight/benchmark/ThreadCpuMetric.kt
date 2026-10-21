package io.github.angelomarzocchi.flight.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * How long the app's threads ran on a CPU during the trace, from the scheduler's own record of it:
 * the main thread (`cpuMainThreadMs`), the RenderThread (`cpuRenderThreadMs`, where frames become GPU
 * commands) and the whole process (`cpuProcessMs`). Time spent waiting — for a frame, for the GPU, for
 * a lock — is not counted: this is work, not wall time.
 */
@OptIn(ExperimentalMetricApi::class)
class ThreadCpuMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: Metric.CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Metric.Measurement> {
        val threads = traceSession.query(
            """
            SELECT thread.tid = process.pid AS main, thread.name AS name, SUM(sched.dur) AS dur
            FROM sched
            JOIN thread USING (utid)
            JOIN process USING (upid)
            WHERE process.name = '${captureInfo.targetPackageName}' AND sched.dur > 0
            GROUP BY thread.utid
            """.trimIndent(),
        ).map { Thread(it.nullableLong("main") == 1L, it.nullableString("name"), it.long("dur")) }.toList()
        return listOf(
            Metric.Measurement("cpuMainThreadMs", threads.filter { it.main }.sumOf { it.nanos } / NanosPerMilli),
            Metric.Measurement("cpuRenderThreadMs", threads.filter { it.name == "RenderThread" }.sumOf { it.nanos } / NanosPerMilli),
            Metric.Measurement("cpuProcessMs", threads.sumOf { it.nanos } / NanosPerMilli),
        )
    }

    private class Thread(val main: Boolean, val name: String?, val nanos: Long)

    private companion object {
        const val NanosPerMilli = 1_000_000.0
    }
}
