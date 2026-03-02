package com.tapstream.downloader.utils

import android.util.Log
import kotlin.system.measureTimeMillis

/**
 * Performance benchmarking utility for I/O operations
 */
object PerformanceBenchmark {
    const val TAG = "PerformanceBenchmark"
    
    data class BenchmarkResult(
        val operationName: String,
        val durationMs: Long,
        val bytesProcessed: Long,
        val throughputMBps: Double
    ) {
        override fun toString(): String {
            return """
                |📊 Benchmark: $operationName
                |⏱️  Duration: ${durationMs}ms
                |📦 Bytes: ${bytesProcessed / 1024}KB
                |🚀 Throughput: ${"%.2f".format(throughputMBps)}MB/s
            """.trimMargin()
        }
    }
    
    /**
     * Measure I/O operation performance
     */
    inline fun <T> measureIO(
        operationName: String,
        bytesProcessed: Long,
        operation: () -> T
    ): Pair<T, BenchmarkResult> {
        val result: T
        val duration = measureTimeMillis {
            result = operation()
        }
        
        val throughputMBps = if (duration > 0) {
            (bytesProcessed.toDouble() / 1024 / 1024) / (duration.toDouble() / 1000)
        } else {
            0.0
        }
        
        val benchmark = BenchmarkResult(
            operationName = operationName,
            durationMs = duration,
            bytesProcessed = bytesProcessed,
            throughputMBps = throughputMBps
        )
        
        Log.i(TAG, benchmark.toString())
        
        return Pair(result, benchmark)
    }
    
    /**
     * Compare two operations
     */
    fun compareOperations(baseline: BenchmarkResult, optimized: BenchmarkResult) {
        val improvement = ((baseline.durationMs - optimized.durationMs).toDouble() / baseline.durationMs) * 100
        val throughputImprovement = ((optimized.throughputMBps - baseline.throughputMBps) / baseline.throughputMBps) * 100
        
        Log.i(TAG, """
            |📈 Performance Comparison:
            |Baseline: ${baseline.operationName} - ${baseline.durationMs}ms
            |Optimized: ${optimized.operationName} - ${optimized.durationMs}ms
            |⚡ Speed Improvement: ${"%.1f".format(improvement)}%
            |🚀 Throughput Improvement: ${"%.1f".format(throughputImprovement)}%
        """.trimMargin())
    }
    
    /**
     * Log memory usage
     */
    fun logMemoryUsage(tag: String = "Memory") {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        
        Log.i(TAG, """
            |💾 $tag:
            |Used: ${usedMemory}MB
            |Free: ${freeMemory}MB
            |Max: ${maxMemory}MB
        """.trimMargin())
    }
}
