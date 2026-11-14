import AppKit
import Combine
import Foundation
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class AppModel: ObservableObject {
    @Published var photos: [PhotoItem] = []
    @Published var selectedPhotoID: PhotoItem.ID?
    @Published var watermark: LoadedWatermark?
    @Published var sizePercent: Double = WatermarkCompositor.defaultSizePercent {
        didSet { persistSettings(); schedulePreviewRefresh() }
    }
    @Published var position: WatermarkPosition = .bottomRight {
        didSet {
            persistSettings(immediate: true)
            clearAllPlacements()
            schedulePreviewRefresh()
        }
    }
    @Published var watermarkMode: WatermarkMode = .random {
        didSet {
            persistSettings(immediate: true)
            clearAllPlacements()
            schedulePreviewRefresh()
        }
    }
    @Published var opacity: Double = WatermarkCompositor.defaultOpacity {
        didSet { persistSettings() }
    }
    @Published var outputFormat: OutputFormat = .sameAsSource {
        didSet { persistSettings(immediate: true) }
    }
    @Published var quality: Double = 0.95 {
        didSet { persistSettings() }
    }
    @Published var playCompletionSound: Bool = true {
        didSet { persistSettings(immediate: true) }
    }
    @Published var stripMetadata: Bool = true {
        didSet { persistSettings(immediate: true) }
    }
    @Published var randomRename: Bool = false {
        didSet { persistSettings(immediate: true) }
    }
    @Published var rememberSettings: Bool = true {
        didSet {
            guard !isRestoringSettings else { return }
            settingsStore.rememberSettings = rememberSettings
            if rememberSettings {
                persistSettings(immediate: true)
                saveCurrentBookmarks()
            } else {
                settingsStore.clearPersistedSettings()
            }
        }
    }
    @Published var outputFolder: URL?
    @Published var previewImage: NSImage?
    @Published var previewCanvasSize: CGSize = .zero
    @Published var isProcessing = false
    @Published var isCancelling = false
    @Published var processedCount = 0
    /// Photo count snapshotted when a run starts. Progress UI must use this,
    /// never the live `photos.count`, so adding files mid-run cannot move the total.
    @Published var processingTotal = 0
    @Published var lastRunSucceeded: [URL] = []
    @Published var previewNote: String?
    @Published var isImporting = false

    var selectedPhoto: PhotoItem? {
        if let selectedPhotoID, let item = photos.first(where: { $0.id == selectedPhotoID }) {
            return item
        }
        return photos.first
    }

    var canStartProcessing: Bool {
        !photos.isEmpty && watermark != nil && !isProcessing
    }

    var waitingCount: Int { photos.filter { if case .waiting = $0.status { return true }; return false }.count }
    var successCount: Int { photos.filter { if case .succeeded = $0.status { return true }; return false }.count }
    var failureCount: Int { photos.filter { if case .failed = $0.status { return true }; return false }.count }

    var outputFolderDisplayName: String {
        outputFolder?.path.replacingOccurrences(of: FileManager.default.homeDirectoryForCurrentUser.path, with: "~")
            ?? L10n.string(.outputFolderNone)
    }

    var selectedPlacementIsManual: Bool {
        selectedPhoto?.placement?.isManual == true
    }

    private let settingsStore = SettingsStore()
    private var isRestoringSettings = false
    private var scopedAccess: [URL: SecurityScopedAccess] = [:]
    private var outputFolderAccess: SecurityScopedAccess?
    private var watermarkAccess: SecurityScopedAccess?
    private let previewCache = ImageByteCache()
    private var previewGeneration = 0
    private var processingTask: Task<Void, Never>?
    private var previewWorkItem: DispatchWorkItem?
    private var cancellationFlag: CancellationFlag?
    private var importTask: Task<Void, Never>?
    private var persistWorkItem: DispatchWorkItem?
    private let persistDebounce: TimeInterval = 0.4

    init() {
        isRestoringSettings = true
        rememberSettings = settingsStore.rememberSettings
        if rememberSettings {
            apply(settingsStore.loadSnapshot())
            restoreBookmarks()
        }
        isRestoringSettings = false
    }

    // MARK: - Import

    func choosePhotos() {
        guard !isProcessing else { return }
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowedContentTypes = SupportedTypes.photoContentTypes
        panel.prompt = L10n.string(.panelChoose)
        panel.message = L10n.string(.panelPhotosMessage)
        panel.canCreateDirectories = false
        guard panel.runModal() == .OK else { return }
        addPhotoURLs(panel.urls)
    }

    func chooseWatermark() {
        guard !isProcessing else { return }
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowedContentTypes = SupportedTypes.watermarkContentTypes
        panel.prompt = L10n.string(.panelChoose)
        panel.message = L10n.string(.panelWatermarkMessage)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        setWatermark(url: url)
    }

    func chooseOutputFolder() {
        guard !isProcessing else { return }
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = L10n.string(.panelChoose)
        panel.message = L10n.string(.panelOutputMessage)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        setOutputFolder(url)
    }

    func handlePhotoDrop(providers: [NSItemProvider]) -> Bool {
        guard !isProcessing else { return false }
        Task { await importURLs(from: providers, asWatermark: false) }
        return true
    }

    func handleWatermarkDrop(providers: [NSItemProvider]) -> Bool {
        guard !isProcessing else { return false }
        Task { await importURLs(from: providers, asWatermark: true) }
        return true
    }

    /// Finder “Open With” and `application(_:open:)` enter here, same path as a drop.
    func handleOpenedURLs(_ urls: [URL]) {
        guard !isProcessing else { return }
        guard !urls.isEmpty else { return }
        var photos: [URL] = []
        var watermarks: [URL] = []
        for url in urls {
            var isDirectory: ObjCBool = false
            FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)
            if isDirectory.boolValue || SupportedTypes.isSupportedImage(url) {
                photos.append(url)
            } else if SupportedTypes.isWatermarkFile(url) {
                watermarks.append(url)
            }
        }
        if !photos.isEmpty {
            addPhotoURLs(photos)
        }
        if let watermarkURL = watermarks.first {
            setWatermark(url: watermarkURL)
        }
    }

    func addPhotoURLs(_ urls: [URL]) {
        guard !isProcessing else { return }
        guard !urls.isEmpty else { return }
        for url in urls {
            retainAccess(to: url)
        }
        let existingCount = photos.count
        let remaining = ImportService.photoLimit - existingCount
        let existing = Set(photos.map(\.url.standardizedFileURL))
        isImporting = true
        importTask = Task { [weak self] in
            let outcome = await ImportService.importPhotos(
                roots: urls,
                existing: existing,
                remainingCapacity: remaining
            )
            guard let self, !Task.isCancelled else { return }
            var appended: [PhotoItem] = []
            appended.reserveCapacity(outcome.items.count)
            for imported in outcome.items {
                var item = PhotoItem(
                    id: UUID(),
                    url: imported.url,
                    filename: imported.filename
                )
                if let thumb = imported.thumbnail {
                    item.thumbnail = NSImageRef(
                        image: NSImage(
                            cgImage: thumb.image,
                            size: NSSize(width: thumb.image.width, height: thumb.image.height)
                        )
                    )
                }
                appended.append(item)
            }
            let room = ImportService.photoLimit - self.photos.count
            if appended.count > max(0, room) {
                appended = Array(appended.prefix(max(0, room)))
            }
            if !appended.isEmpty {
                self.photos.append(contentsOf: appended)
                if self.selectedPhotoID == nil {
                    self.selectedPhotoID = appended.first?.id
                }
                self.schedulePreviewRefresh()
            }
            self.isImporting = false
            let totalFound = existingCount + outcome.discoveredNew
            if outcome.truncated || appended.count < outcome.discoveredNew {
                self.presentImportLimitAlert(totalFound: totalFound)
            }
        }
    }

    func removePhoto(id: PhotoItem.ID) {
        guard !isProcessing else { return }
        guard let index = photos.firstIndex(where: { $0.id == id }) else { return }
        let url = photos[index].url
        photos.remove(at: index)
        previewCache.remove(url)
        releaseUnusedAccess()
        if selectedPhotoID == id {
            selectedPhotoID = photos.first?.id
        }
        schedulePreviewRefresh()
    }

    func clearPhotos() {
        guard !isProcessing else { return }
        previewWorkItem?.cancel()
        previewGeneration += 1
        photos.removeAll()
        selectedPhotoID = nil
        previewCache.removeAll()
        scopedAccess.removeAll()
        previewImage = nil
        previewCanvasSize = .zero
        lastRunSucceeded = []
        previewNote = L10n.string(.previewEmpty)
    }

    func selectPhoto(id: PhotoItem.ID) {
        selectedPhotoID = id
        schedulePreviewRefresh()
    }

    func rerollSelectedPlacement() {
        guard !isProcessing else { return }
        guard let id = selectedPhoto?.id, let index = photos.firstIndex(where: { $0.id == id }) else { return }
        photos[index].placement = nil
        schedulePreviewRefresh(forceNewRandom: true)
    }

    func resetSelectedPlacement() {
        guard !isProcessing else { return }
        guard let id = selectedPhoto?.id, let index = photos.firstIndex(where: { $0.id == id }) else { return }
        photos[index].placement = nil
        schedulePreviewRefresh()
    }

    /// Updates the selected photo's watermark center from a drag in normalized image coordinates.
    func setManualCenter(_ point: CGPoint, canvas: CGSize, mark: CGSize) {
        guard !isProcessing else { return }
        guard let id = selectedPhoto?.id, let index = photos.firstIndex(where: { $0.id == id }) else { return }
        let clamped = WatermarkCompositor.clampedCenter(canvas: canvas, mark: mark, normalized: point)
        photos[index].placement = WatermarkPlacement(normalizedCenter: clamped, isManual: true)
    }

    /// Read-only layout for the preview overlay. Does not persist a new random position.
    /// If random placement is on but this photo has no stored position yet, returns nil
    /// so the overlay does not jump on every view refresh.
    func displayLayout(canvas: CGSize) -> (mark: CGSize, rect: CGRect, placement: WatermarkPlacement)? {
        if watermarkMode == .random, selectedPhoto?.placement == nil {
            return nil
        }
        return resolvedLayout(canvas: canvas, assignIfNeeded: false)
    }

    @discardableResult
    func watermarkLayout(canvas: CGSize) -> (mark: CGSize, rect: CGRect, placement: WatermarkPlacement)? {
        resolvedLayout(canvas: canvas, assignIfNeeded: true)
    }

    private func resolvedLayout(canvas: CGSize, assignIfNeeded: Bool) -> (mark: CGSize, rect: CGRect, placement: WatermarkPlacement)? {
        guard let watermark, canvas.width > 0, canvas.height > 0 else { return nil }
        let mark = WatermarkCompositor.markSize(
            canvas: canvas,
            percent: sizePercent,
            aspect: watermark.aspectRatio
        )
        var rng = SystemRandomNumberGenerator()
        let resolved = WatermarkCompositor.resolvePlacement(
            canvas: canvas,
            mark: mark,
            position: position,
            random: watermarkMode.placesRandomly,
            existing: selectedPhoto?.placement,
            rng: &rng
        )
        if assignIfNeeded, let id = selectedPhoto?.id, let index = photos.firstIndex(where: { $0.id == id }) {
            if photos[index].placement != resolved.placement {
                photos[index].placement = resolved.placement
            }
        }
        return (mark, resolved.rect, resolved.placement)
    }

    // MARK: - Processing

    func startProcessing() {
        guard !isProcessing else { return }
        guard watermark != nil else { return }
        if outputFolder == nil {
            chooseOutputFolder()
        }
        guard let outputFolder, let payload = watermark?.payload else { return }

        for index in photos.indices {
            photos[index].status = .waiting
        }
        isProcessing = true
        isCancelling = false
        processedCount = 0
        processingTotal = photos.count
        lastRunSucceeded = []

        let settings = ExportSettings(
            sizePercent: sizePercent,
            position: position,
            watermarkMode: watermarkMode,
            opacity: opacity,
            outputFormat: outputFormat,
            quality: quality,
            stripMetadata: stripMetadata,
            randomRename: randomRename
        )
        var reserved = Set<String>()
        var items: [ExportWorkItem] = []
        items.reserveCapacity(photos.count)
        for photo in photos {
            let format = ImageExporter.resolvedFormat(
                requested: settings.outputFormat,
                source: ImageDecoder.sourceKind(of: photo.url)
            )
            let destination = OutputPath.allocateUniqueURL(
                original: photo.url,
                folder: outputFolder,
                ext: ImageExporter.fileExtension(for: format),
                randomRename: settings.randomRename,
                reserved: &reserved
            )
            items.append(
                ExportWorkItem(
                    id: photo.id,
                    url: photo.url,
                    placement: photo.placement,
                    destination: destination,
                    format: format,
                    pixelArea: ImageDecoder.pixelArea(of: photo.url) ?? 0
                )
            )
        }
        let flag = CancellationFlag()
        cancellationFlag = flag

        processingTask = Task { [weak self] in
            let outcome = await ExportCoordinator.run(
                items: items,
                watermark: payload,
                settings: settings,
                isCancelled: { flag.isCancelled || Task.isCancelled },
                onItemStart: { id in
                    self?.setStatus(id: id, .processing)
                },
                onItemFinished: { result in
                    guard let self else { return }
                    switch result {
                    case .success(let id, let url):
                        self.setStatus(id: id, .succeeded(url))
                    case .failure(let id, let message):
                        self.setStatus(id: id, .failed(message))
                    case .cancelled(let id):
                        self.setStatus(id: id, .failed(L10n.string(.errorCancelled)))
                    }
                    self.processedCount += 1
                }
            )
            guard let self else { return }
            self.isProcessing = false
            self.isCancelling = false
            self.lastRunSucceeded = outcome.succeeded
            self.processingTask = nil
            self.cancellationFlag = nil
            if !outcome.cancelled {
                self.playFinishedSoundIfNeeded()
            }
        }
    }

    func cancelProcessing() {
        isCancelling = true
        cancellationFlag?.cancel()
        processingTask?.cancel()
    }

    func revealOutputInFinder() {
        if !lastRunSucceeded.isEmpty {
            NSWorkspace.shared.activateFileViewerSelecting(lastRunSucceeded)
            return
        }
        if let outputFolder {
            NSWorkspace.shared.open(outputFolder)
        }
    }

    // MARK: - Preview

    private func schedulePreviewRefresh(forceNewRandom: Bool = false) {
        if forceNewRandom, let id = selectedPhoto?.id, let index = photos.firstIndex(where: { $0.id == id }) {
            if photos[index].placement?.isManual != true {
                photos[index].placement = nil
            }
        }
        previewWorkItem?.cancel()
        previewWorkItem = nil

        // Cache hits (and layout-only changes) must update synchronously so the
        // watermark does not flicker away for 50 ms while switching photos.
        if let photo = selectedPhoto, previewCache.image(for: photo.url) != nil {
            refreshPreview()
            return
        }
        if selectedPhoto == nil {
            refreshPreview()
            return
        }

        // Cache miss: start the decode immediately; no debounce is needed
        // because subsequent size/position tweaks will hit the cache above.
        refreshPreview()
    }

    private func refreshPreview() {
        previewGeneration += 1
        let generation = previewGeneration
        guard let photo = selectedPhoto else {
            previewImage = nil
            previewCanvasSize = .zero
            previewNote = photos.isEmpty ? L10n.string(.previewEmpty) : nil
            return
        }
        if watermark == nil {
            previewNote = L10n.string(.previewNeedWatermark)
        } else {
            previewNote = nil
        }

        let url = photo.url
        if let cached = previewCache.image(for: url) {
            previewCanvasSize = CGSize(width: cached.width, height: cached.height)
            previewImage = NSImage(
                cgImage: cached,
                size: NSSize(width: cached.width, height: cached.height)
            )
            _ = watermarkLayout(canvas: previewCanvasSize)
            return
        }

        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let decoded = try ImageDecoder.decode(url: url, maxPixelSize: 1600)
                let base = SendableCGImage(image: decoded.cgImage)
                await MainActor.run {
                    guard let self, self.previewGeneration == generation else { return }
                    self.previewCache.insert(base.image, for: url)
                    self.previewCanvasSize = CGSize(width: base.image.width, height: base.image.height)
                    self.previewImage = NSImage(
                        cgImage: base.image,
                        size: NSSize(width: base.image.width, height: base.image.height)
                    )
                    _ = self.watermarkLayout(canvas: self.previewCanvasSize)
                }
            } catch {
                await MainActor.run {
                    guard let self, self.previewGeneration == generation else { return }
                    self.previewImage = ThumbnailLoader.load(url: url, maxPixel: 1600)
                    self.previewCanvasSize = self.previewImage?.size ?? .zero
                    self.previewNote = L10n.format(
                        .previewFailed,
                        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                    )
                }
            }
        }
    }

    // MARK: - Internals

    private func clearAllPlacements() {
        for index in photos.indices {
            photos[index].placement = nil
        }
    }

    private func playFinishedSoundIfNeeded() {
        guard playCompletionSound else { return }
        NSSound(named: "Glass")?.play()
    }

    private func setStatus(id: PhotoItem.ID, _ status: PhotoStatus) {
        guard let index = photos.firstIndex(where: { $0.id == id }) else { return }
        photos[index].status = status
    }

    func setWatermark(url: URL) {
        guard !isProcessing else { return }
        retainAccess(to: url)
        watermarkAccess = scopedAccess[url.standardizedFileURL] ?? SecurityScopedAccess(url: url)
        do {
            // Always rebuild the payload so a same-path file replace cannot
            // reuse a stale raster or SVG from the previous selection.
            watermark = try WatermarkLoader.load(url: url)
            settingsStore.saveWatermarkBookmark(url)
            schedulePreviewRefresh()
        } catch {
            watermark = nil
            presentAlert(title: L10n.string(.alertWatermarkTitle), message: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    private func setOutputFolder(_ url: URL) {
        outputFolderAccess = SecurityScopedAccess(url: url)
        outputFolder = url
        settingsStore.saveOutputFolderBookmark(url)
    }

    private func retainAccess(to url: URL) {
        let key = url.standardizedFileURL
        if scopedAccess[key] == nil {
            scopedAccess[key] = SecurityScopedAccess(url: url)
        }
    }

    private func releaseUnusedAccess() {
        let keys = Array(scopedAccess.keys)
        for key in keys {
            let stillNeeded = photos.contains { photo in
                let path = photo.url.standardizedFileURL.path
                let root = key.path
                return path == root || path.hasPrefix(root.hasSuffix("/") ? root : root + "/")
            } || watermark?.url.standardizedFileURL == key
            if !stillNeeded {
                scopedAccess[key] = nil
            }
        }
    }

    private func importURLs(from providers: [NSItemProvider], asWatermark: Bool) async {
        var urls: [URL] = []
        for provider in providers {
            if let url = await Self.loadFileURL(from: provider) {
                urls.append(url)
            }
        }
        guard !urls.isEmpty else { return }
        if asWatermark {
            if let match = urls.first(where: { SupportedTypes.isWatermarkFile($0) }) {
                setWatermark(url: match)
            }
        } else {
            addPhotoURLs(urls)
        }
    }

    private static func loadFileURL(from provider: NSItemProvider) async -> URL? {
        guard provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) else { return nil }
        return await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                let url: URL?
                if let value = item as? URL {
                    url = value
                } else if let value = item as? NSURL {
                    url = value as URL
                } else if let data = item as? Data {
                    url = URL(dataRepresentation: data, relativeTo: nil)
                } else if let string = item as? String {
                    url = URL(fileURLWithPath: string.hasPrefix("file:") ? (URL(string: string)?.path ?? string) : string)
                } else {
                    url = nil
                }
                continuation.resume(returning: url)
            }
        }
    }

    private func apply(_ snapshot: SettingsSnapshot) {
        sizePercent = snapshot.sizePercent
        position = snapshot.position
        watermarkMode = snapshot.watermarkMode
        opacity = snapshot.opacity
        outputFormat = snapshot.outputFormat
        quality = snapshot.quality
        playCompletionSound = snapshot.playCompletionSound
        stripMetadata = snapshot.stripMetadata
        randomRename = snapshot.randomRename
    }

    private func currentSnapshot() -> SettingsSnapshot {
        SettingsSnapshot(
            sizePercent: sizePercent,
            position: position,
            watermarkMode: watermarkMode,
            opacity: opacity,
            outputFormat: outputFormat,
            quality: quality,
            playCompletionSound: playCompletionSound,
            stripMetadata: stripMetadata,
            randomRename: randomRename
        )
    }

    private func saveCurrentBookmarks() {
        if let outputFolder {
            settingsStore.saveOutputFolderBookmark(outputFolder)
        }
        if let watermark {
            settingsStore.saveWatermarkBookmark(watermark.url)
        }
    }

    private func persistSettings(immediate: Bool = false) {
        guard !isRestoringSettings, rememberSettings else { return }
        persistWorkItem?.cancel()
        persistWorkItem = nil
        if immediate {
            settingsStore.saveSnapshot(currentSnapshot())
            return
        }
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                self?.flushSettings()
            }
        }
        persistWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + persistDebounce, execute: work)
    }

    /// Write pending settings immediately: slider/drag end, leaving a control,
    /// and application termination.
    func flushSettings() {
        persistWorkItem?.cancel()
        persistWorkItem = nil
        guard !isRestoringSettings, rememberSettings else { return }
        settingsStore.saveSnapshot(currentSnapshot())
    }

    private func restoreBookmarks() {
        if let resolved = settingsStore.resolveOutputFolder() {
            outputFolderAccess = resolved.access
            outputFolder = resolved.url
        }
        if let resolved = settingsStore.resolveWatermark() {
            watermarkAccess = resolved.access
            do {
                watermark = try WatermarkLoader.load(url: resolved.url)
            } catch {
                watermark = nil
            }
        }
    }

    private func presentImportLimitAlert(totalFound: Int) {
        presentAlert(
            title: L10n.string(.alertImportLimitTitle),
            message: L10n.format(
                .alertImportLimitMessage,
                Int64(ImportService.photoLimit),
                Int64(max(totalFound, ImportService.photoLimit))
            )
        )
    }

    private func presentAlert(title: String, message: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.alertStyle = .warning
        alert.addButton(withTitle: L10n.string(.ok))
        alert.runModal()
    }
}
