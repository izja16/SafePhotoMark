package app.safephotomark.processing

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ExportWorkItem(
    val id: UUID,
    val source: Uri,
    val sourceName: String,
    val placement: WatermarkPlacement?,
    val destName: String,
    val format: OutputFormat,
    val pixelArea: Int
)

sealed class ExportItemResult {
    data class Success(val id: UUID, val destName: String) : ExportItemResult()
    data class Failure(val id: UUID, val message: String) : ExportItemResult()
    data class Cancelled(val id: UUID) : ExportItemResult()
}

data class ExportOutcome(
    val succeeded: List<String>,
    val cancelled: Boolean
)

/**
 * Coordinates a batch export with memory-aware concurrency.
 * Already-written files are not rolled back if the remaining batch is cancelled.
 */
object ExportCoordinator {
    /** Photos at or above this pixel area run alone. */
    const val largePhotoPixelArea = 48_000_000

    /** 2 workers on 16 GB-class machines, up to 4 on larger ones. */
    fun recommendedConcurrency(physicalMemoryBytes: Long): Int {
        val gib = physicalMemoryBytes / (1024L * 1024L * 1024L)
        return when {
            gib >= 32L -> 4
            gib >= 24L -> 3
            else -> 2
        }
    }

    fun recommendedConcurrency(): Int {
        val bytes = try {
            val info = android.app.ActivityManager.MemoryInfo()
            // Fallback to Runtime if ActivityManager is unavailable in tests.
            Runtime.getRuntime().maxMemory().coerceAtLeast(512L * 1024L * 1024L)
        } catch (_: Exception) {
            Runtime.getRuntime().maxMemory()
        }
        // Runtime.maxMemory is the heap, not physical RAM. Prefer /proc/meminfo.
        val physical = readPhysicalMemory() ?: (bytes * 4)
        return recommendedConcurrency(physical)
    }

    fun readPhysicalMemory(): Long? {
        return try {
            FileMemInfo.totalBytes()
        } catch (_: Exception) {
            null
        }
    }
}

private object FileMemInfo {
    fun totalBytes(): Long? {
        val f = java.io.File("/proc/meminfo")
        if (!f.exists()) return null
        val line = f.useLines { it.firstOrNull { row -> row.startsWith("MemTotal:") } } ?: return null
        val kb = line.filter { it.isDigit() }.toLongOrNull() ?: return null
        return kb * 1024L
    }
}

class CancellationFlag {
    private val value = AtomicBoolean(false)
    fun cancel() { value.set(true) }
    val isCancelled: Boolean get() = value.get()
}

suspend fun <T> runBatch(
    items: List<ExportWorkItem>,
    isCancelled: () -> Boolean,
    onItemStart: suspend (UUID) -> Unit,
    onItemFinished: suspend (ExportItemResult) -> Unit,
    process: (ExportWorkItem) -> T
): ExportOutcome {
    val succeeded = mutableListOf<String>()
    var cancelled = false
    val maxWorkers = ExportCoordinator.recommendedConcurrency()

    // Sequential scheduling with a limited number of in-flight slots.
    // Large photos take every slot so they run alone.
    data class SlotResult(val id: UUID, val cost: Int, val result: Result<String>)

    val pending = ArrayDeque(items)
    val inFlight = mutableListOf<kotlinx.coroutines.Deferred<SlotResult>>()

    coroutineScope {
        suspend fun drainOne() {
            val deferred = inFlight.removeAt(0)
            val slot = deferred.await()
            if (isCancelled()) cancelled = true
            slot.result.fold(
                onSuccess = { name ->
                    succeeded += name
                    onItemFinished(ExportItemResult.Success(slot.id, name))
                },
                onFailure = { error ->
                    val cancelledError = error is CancellationException ||
                        (error is ImagePipelineException && error.kind == ImagePipelineError.CANCELLED)
                    if (cancelledError || cancelled || isCancelled()) {
                        cancelled = true
                        onItemFinished(ExportItemResult.Cancelled(slot.id))
                    } else {
                        val message = error.message ?: error::class.java.simpleName
                        onItemFinished(ExportItemResult.Failure(slot.id, message))
                    }
                }
            )
        }

        fun slotCost(item: ExportWorkItem): Int {
            return if (item.pixelArea >= ExportCoordinator.largePhotoPixelArea) maxWorkers else 1
        }

        var inFlightSlots = 0
        while (pending.isNotEmpty() || inFlight.isNotEmpty()) {
            if (cancelled || isCancelled()) {
                cancelled = true
                inFlight.forEach { it.cancel() }
                // Surface remaining queued items as cancelled without starting them.
                while (inFlight.isNotEmpty()) {
                    runCatching { drainOne() }
                }
                while (pending.isNotEmpty()) {
                    val item = pending.removeFirst()
                    onItemFinished(ExportItemResult.Cancelled(item.id))
                }
                break
            }
            while (pending.isNotEmpty()) {
                val item = pending.first()
                val cost = slotCost(item)
                if (inFlightSlots > 0 && inFlightSlots + cost > maxWorkers) break
                pending.removeFirst()
                inFlightSlots += cost
                onItemStart(item.id)
                inFlight += async(Dispatchers.Default) {
                    if (isCancelled()) {
                        SlotResult(item.id, cost, Result.failure(ImagePipelineException(ImagePipelineError.CANCELLED)))
                    } else {
                        val result = runCatching {
                            process(item)
                            item.destName
                        }
                        SlotResult(item.id, cost, result)
                    }
                }
            }
            if (inFlight.isNotEmpty()) {
                val slot = inFlight.removeAt(0).await()
                inFlightSlots -= slot.cost
                if (isCancelled()) cancelled = true
                slot.result.fold(
                    onSuccess = { name ->
                        succeeded += name
                        onItemFinished(ExportItemResult.Success(slot.id, name))
                    },
                    onFailure = { error ->
                        val cancelledError = error is CancellationException ||
                            (error is ImagePipelineException && error.kind == ImagePipelineError.CANCELLED)
                        if (cancelledError || cancelled || isCancelled()) {
                            cancelled = true
                            onItemFinished(ExportItemResult.Cancelled(slot.id))
                        } else {
                            val message = (error as? ImagePipelineException)?.message
                                ?: error.message
                                ?: error::class.java.simpleName
                            onItemFinished(ExportItemResult.Failure(slot.id, message))
                        }
                    }
                )
            }
        }
    }

    return ExportOutcome(succeeded = succeeded, cancelled = cancelled || isCancelled())
}
