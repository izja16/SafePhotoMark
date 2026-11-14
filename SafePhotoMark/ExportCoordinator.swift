import Foundation

struct ExportWorkItem: Sendable {
    let id: UUID
    let url: URL
    let placement: WatermarkPlacement?
    let destination: URL
    let format: OutputFormat
    let pixelArea: Int
}

enum ExportItemResult: Sendable {
    case success(id: UUID, url: URL)
    case failure(id: UUID, message: String)
    case cancelled(id: UUID)
}

struct ExportOutcome: Sendable {
    var succeeded: [URL]
    var cancelled: Bool
}

/// Coordinates a batch export with memory-aware concurrency, sudden-termination
/// disabled for the duration, and cancellation surfaced as `ImagePipelineError.cancelled`.
///
/// Output URLs must be pre-allocated on the cooperative/main side and stored on
/// each `ExportWorkItem` so two workers cannot be assigned the same `-2` name.
/// Already-written files are not rolled back if the remaining batch is cancelled.
enum ExportCoordinator {
    /// Photos at or above this pixel area run alone so 3.0 does not regress
    /// memory versus 2.0's conservative two-worker cap.
    static let largePhotoPixelArea = 48_000_000

    /// 2 workers on 16 GB-class machines, up to 4 on larger ones.
    static func recommendedConcurrency(
        physicalMemory: UInt64 = ProcessInfo.processInfo.physicalMemory
    ) -> Int {
        let gib = physicalMemory / (1024 * 1024 * 1024)
        if gib >= 32 { return 4 }
        if gib >= 24 { return 3 }
        return 2
    }

    static func run(
        items: [ExportWorkItem],
        watermark: WatermarkPayload,
        settings: ExportSettings,
        isCancelled: @escaping @Sendable () -> Bool,
        onItemStart: @escaping @MainActor @Sendable (UUID) -> Void,
        onItemFinished: @escaping @MainActor @Sendable (ExportItemResult) -> Void
    ) async -> ExportOutcome {
        ProcessInfo.processInfo.disableSuddenTermination()
        defer { ProcessInfo.processInfo.enableSuddenTermination() }

        var succeeded: [URL] = []
        var cancelled = false
        let maxWorkers = recommendedConcurrency()

        await withTaskGroup(of: (UUID, Int, Result<URL, Error>).self) { group in
            var nextIndex = 0
            var inFlightSlots = 0

            func slotCost(_ item: ExportWorkItem) -> Int {
                item.pixelArea >= largePhotoPixelArea ? maxWorkers : 1
            }

            func enqueueAvailable() async {
                while nextIndex < items.count {
                    guard !cancelled, !isCancelled() else { return }
                    let item = items[nextIndex]
                    let cost = slotCost(item)
                    if inFlightSlots > 0, inFlightSlots + cost > maxWorkers {
                        return
                    }
                    nextIndex += 1
                    inFlightSlots += cost
                    await onItemStart(item.id)
                    group.addTask(priority: .userInitiated) {
                        if Task.isCancelled || isCancelled() {
                            return (item.id, cost, .failure(ImagePipelineError.cancelled))
                        }
                        let result: Result<URL, Error> = Result {
                            try autoreleasepool {
                                try BatchProcessor.processOne(
                                    source: item.url,
                                    destination: item.destination,
                                    format: item.format,
                                    watermark: watermark,
                                    settings: settings,
                                    placement: item.placement,
                                    isCancelled: isCancelled
                                )
                            }
                        }
                        return (item.id, cost, result)
                    }
                }
            }

            await enqueueAvailable()

            for await (id, cost, result) in group {
                inFlightSlots -= cost
                if isCancelled() || Task.isCancelled {
                    cancelled = true
                }
                switch result {
                case .success(let url):
                    succeeded.append(url)
                    await onItemFinished(.success(id: id, url: url))
                case .failure(let error):
                    if error is CancellationError || (error as? ImagePipelineError).map({ if case .cancelled = $0 { return true }; return false }) == true {
                        cancelled = true
                        await onItemFinished(.cancelled(id: id))
                    } else if cancelled || isCancelled() {
                        await onItemFinished(.cancelled(id: id))
                    } else {
                        let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                        await onItemFinished(.failure(id: id, message: message))
                    }
                }
                if cancelled || isCancelled() {
                    cancelled = true
                    group.cancelAll()
                } else {
                    await enqueueAvailable()
                }
            }
        }

        return ExportOutcome(succeeded: succeeded, cancelled: cancelled || isCancelled())
    }
}

/// Shared flag so export workers can observe cancellation without hopping actors.
final class CancellationFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    func cancel() {
        lock.lock()
        value = true
        lock.unlock()
    }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
