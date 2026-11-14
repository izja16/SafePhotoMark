package app.safephotomark

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.safephotomark.data.SettingsSnapshot
import app.safephotomark.data.SettingsStore
import app.safephotomark.processing.BatchProcessor
import app.safephotomark.processing.CancellationFlag
import app.safephotomark.processing.ExportItemResult
import app.safephotomark.processing.ExportWorkItem
import app.safephotomark.processing.ImageExporter
import app.safephotomark.processing.ImagePipelineError
import app.safephotomark.processing.ImagePipelineException
import app.safephotomark.processing.LoadedWatermark
import app.safephotomark.processing.OutputFormat
import app.safephotomark.processing.OutputPath
import app.safephotomark.processing.PhotoDecoder
import app.safephotomark.processing.PhotoStatus
import app.safephotomark.processing.PhotoStatusKind
import app.safephotomark.processing.PointD
import app.safephotomark.processing.SizeD
import app.safephotomark.processing.SupportedTypes
import app.safephotomark.processing.ThumbnailLoader
import app.safephotomark.processing.WatermarkCompositor
import app.safephotomark.processing.WatermarkLoader
import app.safephotomark.processing.WatermarkMode
import app.safephotomark.processing.WatermarkPlacement
import app.safephotomark.processing.WatermarkPosition
import app.safephotomark.processing.runBatch
import app.safephotomark.util.CompletionSound
import app.safephotomark.util.collectImageDocuments
import app.safephotomark.util.displayNameOf
import app.safephotomark.util.documentExists
import app.safephotomark.util.listChildNames
import app.safephotomark.util.persistRead
import app.safephotomark.util.persistReadWrite
import app.safephotomark.util.pipelineErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

data class PhotoItem(
    val id: UUID = UUID.randomUUID(),
    val uri: Uri,
    val filename: String,
    val status: PhotoStatus = PhotoStatus.Waiting,
    val thumbnail: Bitmap? = null,
    val placement: WatermarkPlacement? = null
)

data class ImportLimitEvent(val importedCap: Int, val discovered: Int)

data class UiMessage(val title: String, val body: String)

data class AppUiState(
    val photos: List<PhotoItem> = emptyList(),
    val selectedPhotoId: UUID? = null,
    val watermark: LoadedWatermark? = null,
    val sizePercent: Double = WatermarkCompositor.defaultSizePercent,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
    val watermarkMode: WatermarkMode = WatermarkMode.RANDOM,
    val opacity: Double = WatermarkCompositor.defaultOpacity,
    val outputFormat: OutputFormat = OutputFormat.SAME_AS_SOURCE,
    val quality: Double = 0.95,
    val playCompletionSound: Boolean = true,
    val stripMetadata: Boolean = true,
    val randomRename: Boolean = false,
    val rememberSettings: Boolean = true,
    val outputTreeUri: Uri? = null,
    val outputFolderName: String? = null,
    val previewImage: Bitmap? = null,
    val previewCanvasSize: SizeD = SizeD(0.0, 0.0),
    val previewNote: String? = null,
    val isProcessing: Boolean = false,
    val isCancelling: Boolean = false,
    val isImporting: Boolean = false,
    val processedCount: Int = 0,
    val processingTotal: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastRunSucceeded: List<String> = emptyList(),
    val ready: Boolean = false
) {
    val selectedPhoto: PhotoItem?
        get() = selectedPhotoId?.let { id -> photos.firstOrNull { it.id == id } } ?: photos.firstOrNull()

    val canStartProcessing: Boolean
        get() = photos.isNotEmpty() && watermark != null && outputTreeUri != null && !isProcessing

    val selectedPlacementIsManual: Boolean
        get() = selectedPhoto?.placement?.isManual == true
}

private const val PHOTO_LIMIT = 2000

object ImportServiceLimit {
    const val photoLimit = PHOTO_LIMIT
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val resolver = application.contentResolver

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message

    private val _importLimit = MutableStateFlow<ImportLimitEvent?>(null)
    val importLimit: StateFlow<ImportLimitEvent?> = _importLimit

    private var isRestoring = true
    private var persistJob: Job? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0
    private val previewCache = LinkedHashMap<String, Bitmap>()
    private var processingJob: Job? = null
    private var cancellationFlag: CancellationFlag? = null

    init {
        viewModelScope.launch {
            val snapshot = settingsStore.loadSnapshot()
            isRestoring = true
            applySnapshot(snapshot)
            if (snapshot.rememberSettings) {
                restoreUris(snapshot)
            }
            isRestoring = false
            _state.update { it.copy(ready = true) }
        }
    }

    fun consumeMessage() { _message.value = null }
    fun consumeImportLimit() { _importLimit.value = null }

    fun setSizePercent(value: Double) {
        _state.update { it.copy(sizePercent = value.coerceIn(WatermarkCompositor.sizePercentRange)) }
        persistSettings()
        schedulePreviewRefresh()
    }

    fun setOpacity(value: Double) {
        _state.update { it.copy(opacity = value.coerceIn(0.0, 1.0)) }
        persistSettings()
    }

    fun setQuality(value: Double) {
        _state.update { it.copy(quality = value.coerceIn(0.5, 1.0)) }
        persistSettings()
    }

    fun setPosition(value: WatermarkPosition) {
        _state.update { current ->
            current.copy(
                position = value,
                photos = current.photos.map { it.copy(placement = null) }
            )
        }
        persistSettings(immediate = true)
        schedulePreviewRefresh()
    }

    fun setWatermarkMode(value: WatermarkMode) {
        _state.update { current ->
            current.copy(
                watermarkMode = value,
                photos = current.photos.map { it.copy(placement = null) }
            )
        }
        persistSettings(immediate = true)
        schedulePreviewRefresh()
    }

    fun setOutputFormat(value: OutputFormat) {
        _state.update { it.copy(outputFormat = value) }
        persistSettings(immediate = true)
    }

    fun setStripMetadata(value: Boolean) {
        _state.update { it.copy(stripMetadata = value) }
        persistSettings(immediate = true)
    }

    fun setRandomRename(value: Boolean) {
        _state.update { it.copy(randomRename = value) }
        persistSettings(immediate = true)
    }

    fun setPlayCompletionSound(value: Boolean) {
        _state.update { it.copy(playCompletionSound = value) }
        persistSettings(immediate = true)
    }

    fun setRememberSettings(value: Boolean) {
        if (isRestoring) {
            _state.update { it.copy(rememberSettings = value) }
            return
        }
        _state.update { it.copy(rememberSettings = value) }
        viewModelScope.launch {
            if (value) {
                flushSettings()
            } else {
                settingsStore.clearPersistedSettings(keepRememberFlag = true)
                settingsStore.saveSnapshot(currentSnapshot().copy(rememberSettings = false, watermarkUri = null, outputTreeUri = null))
            }
        }
    }

    fun addPhotoUris(uris: List<Uri>) {
        if (_state.value.isProcessing || uris.isEmpty()) return
        viewModelScope.launch {
            val current = _state.value
            val remaining = PHOTO_LIMIT - current.photos.size
            val existing = current.photos.map { it.uri.toString() }.toSet()
            _state.update { it.copy(isImporting = true) }
            val collection = withContext(Dispatchers.IO) {
                collectImageDocuments(getApplication(), uris, existing, remaining)
            }
            val appended = ArrayList<PhotoItem>()
            for (ref in collection.items) {
                val thumb = withContext(Dispatchers.IO) {
                    ThumbnailLoader.load(resolver, ref.uri, ref.displayName)
                }
                appended += PhotoItem(uri = ref.uri, filename = ref.displayName, thumbnail = thumb)
            }
            _state.update { state ->
                val room = PHOTO_LIMIT - state.photos.size
                val kept = if (room <= 0) emptyList() else appended.take(room)
                val photos = state.photos + kept
                val selected = state.selectedPhotoId ?: photos.firstOrNull()?.id
                state.copy(
                    photos = photos,
                    selectedPhotoId = selected,
                    isImporting = false,
                    lastRunSucceeded = emptyList(),
                    successCount = 0,
                    failureCount = 0
                )
            }
            val totalFound = current.photos.size + collection.discoveredNew
            if (collection.truncated || totalFound > PHOTO_LIMIT) {
                _importLimit.value = ImportLimitEvent(PHOTO_LIMIT, maxOf(totalFound, PHOTO_LIMIT))
            }
            schedulePreviewRefresh()
        }
    }

    fun setWatermark(uri: Uri, displayName: String? = null) {
        if (_state.value.isProcessing) return
        viewModelScope.launch {
            resolver.persistRead(uri)
            try {
                val loaded = withContext(Dispatchers.IO) {
                    WatermarkLoader.load(resolver, uri, displayName ?: resolver.displayNameOf(uri, "watermark"))
                }
                _state.update { it.copy(watermark = loaded) }
                if (_state.value.rememberSettings) {
                    settingsStore.saveWatermarkUri(uri)
                }
                schedulePreviewRefresh()
            } catch (e: Exception) {
                _state.update { it.copy(watermark = null) }
                val ctx = getApplication<Application>()
                _message.value = UiMessage(
                    title = ctx.getString(R.string.alert_watermark_title),
                    body = ctx.pipelineErrorMessage(e)
                )
            }
        }
    }

    fun setOutputTree(uri: Uri) {
        if (_state.value.isProcessing) return
        viewModelScope.launch {
            resolver.persistReadWrite(uri)
            val name = DocumentFile.fromTreeUri(getApplication(), uri)?.name
                ?: resolver.displayNameOf(uri, getApplication<Application>().getString(R.string.output_folder))
            _state.update { it.copy(outputTreeUri = uri, outputFolderName = name) }
            if (_state.value.rememberSettings) {
                settingsStore.saveOutputTreeUri(uri)
            }
        }
    }

    fun removePhoto(id: UUID) {
        if (_state.value.isProcessing) return
        _state.update { state ->
            val photos = state.photos.filter { it.id != id }
            val selected = if (state.selectedPhotoId == id) photos.firstOrNull()?.id else state.selectedPhotoId
            state.copy(photos = photos, selectedPhotoId = selected)
        }
        schedulePreviewRefresh()
    }

    fun clearPhotos() {
        if (_state.value.isProcessing) return
        previewJob?.cancel()
        previewGeneration += 1
        previewCache.clear()
        _state.update {
            it.copy(
                photos = emptyList(),
                selectedPhotoId = null,
                previewImage = null,
                previewCanvasSize = SizeD(0.0, 0.0),
                lastRunSucceeded = emptyList(),
                previewNote = getApplication<Application>().getString(R.string.preview_empty),
                successCount = 0,
                failureCount = 0
            )
        }
    }

    fun selectPhoto(id: UUID) {
        _state.update { it.copy(selectedPhotoId = id) }
        schedulePreviewRefresh()
    }

    fun rerollSelectedPlacement() {
        if (_state.value.isProcessing) return
        val id = _state.value.selectedPhoto?.id ?: return
        _state.update { state ->
            state.copy(photos = state.photos.map { if (it.id == id) it.copy(placement = null) else it })
        }
        schedulePreviewRefresh(forceNewRandom = true)
    }

    fun resetSelectedPlacement() {
        if (_state.value.isProcessing) return
        val id = _state.value.selectedPhoto?.id ?: return
        _state.update { state ->
            state.copy(photos = state.photos.map { if (it.id == id) it.copy(placement = null) else it })
        }
        schedulePreviewRefresh()
    }

    fun setManualCenter(point: PointD, canvas: SizeD, mark: SizeD) {
        if (_state.value.isProcessing) return
        val id = _state.value.selectedPhoto?.id ?: return
        val clamped = WatermarkCompositor.clampedCenter(canvas, mark, point)
        val placement = WatermarkPlacement(normalizedCenter = clamped, isManual = true)
        _state.update { state ->
            state.copy(photos = state.photos.map { if (it.id == id) it.copy(placement = placement) else it })
        }
    }

    fun displayLayout(canvas: SizeD): Triple<SizeD, app.safephotomark.processing.RectD, WatermarkPlacement>? {
        val state = _state.value
        if (state.watermarkMode == WatermarkMode.RANDOM && state.selectedPhoto?.placement == null) {
            return null
        }
        return resolvedLayout(canvas, assignIfNeeded = false)
    }

    fun watermarkLayout(canvas: SizeD): Triple<SizeD, app.safephotomark.processing.RectD, WatermarkPlacement>? {
        return resolvedLayout(canvas, assignIfNeeded = true)
    }

    private fun resolvedLayout(
        canvas: SizeD,
        assignIfNeeded: Boolean
    ): Triple<SizeD, app.safephotomark.processing.RectD, WatermarkPlacement>? {
        val state = _state.value
        val watermark = state.watermark ?: return null
        if (canvas.width <= 0.0 || canvas.height <= 0.0) return null
        val mark = WatermarkCompositor.markSize(canvas, state.sizePercent, watermark.aspectRatio)
        val (rect, placement) = WatermarkCompositor.resolvePlacement(
            canvas = canvas,
            mark = mark,
            position = state.position,
            random = state.watermarkMode.placesRandomly,
            existing = state.selectedPhoto?.placement,
            rng = Random.Default
        )
        if (assignIfNeeded) {
            val id = state.selectedPhoto?.id
            if (id != null) {
                _state.update { current ->
                    current.copy(
                        photos = current.photos.map {
                            if (it.id == id && it.placement != placement) it.copy(placement = placement) else it
                        }
                    )
                }
            }
        }
        return Triple(mark, rect, placement)
    }

    fun startProcessing() {
        val state = _state.value
        if (state.isProcessing) return
        if (state.watermark == null || state.photos.isEmpty()) return
        val treeUri = state.outputTreeUri ?: return
        val folder = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return
        if (!folder.canWrite()) {
            _message.value = UiMessage(
                getApplication<Application>().getString(R.string.error_cannot_create_destination),
                getApplication<Application>().getString(R.string.error_output_folder_missing)
            )
            return
        }

        _state.update { current ->
            current.copy(
                photos = current.photos.map { it.copy(status = PhotoStatus.Waiting) },
                isProcessing = true,
                isCancelling = false,
                processedCount = 0,
                processingTotal = current.photos.size,
                lastRunSucceeded = emptyList(),
                successCount = 0,
                failureCount = 0
            )
        }

        val settings = app.safephotomark.processing.ExportSettings(
            sizePercent = state.sizePercent,
            position = state.position,
            watermarkMode = state.watermarkMode,
            opacity = state.opacity,
            outputFormat = state.outputFormat,
            quality = state.quality,
            stripMetadata = state.stripMetadata,
            randomRename = state.randomRename
        )
        val payload = state.watermark!!.payload
        val photos = _state.value.photos
        val cacheDir = getApplication<Application>().cacheDir
        val flag = CancellationFlag()
        cancellationFlag = flag

        processingJob = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                val existing = listChildNames(folder).toMutableSet()
                val reserved = mutableSetOf<String>()
                reserved.addAll(existing)
                photos.map { photo ->
                    val kind = PhotoDecoder.sourceKind(resolver, photo.uri, photo.filename)
                    val format = ImageExporter.resolvedFormat(settings.outputFormat, kind)
                    val ext = ImageExporter.fileExtension(format)
                    val stem = photo.filename.substringBeforeLast('.', photo.filename)
                    val destName = OutputPath.uniqueName(
                        originalStem = stem,
                        ext = ext,
                        randomRename = settings.randomRename,
                        reserved = reserved,
                        exists = { name -> documentExists(folder, name) }
                    )
                    val area = PhotoDecoder.pixelArea(resolver, photo.uri, photo.filename) ?: 0
                    ExportWorkItem(
                        id = photo.id,
                        source = photo.uri,
                        sourceName = photo.filename,
                        placement = photo.placement,
                        destName = destName,
                        format = format,
                        pixelArea = area
                    )
                }
            }

            var success = 0
            var failure = 0
            val succeededNames = mutableListOf<String>()

            val outcome = runBatch(
                items = items,
                isCancelled = { flag.isCancelled },
                onItemStart = { id ->
                    _state.update { current ->
                        current.copy(
                            photos = current.photos.map {
                                if (it.id == id) it.copy(status = PhotoStatus.Processing) else it
                            }
                        )
                    }
                },
                onItemFinished = { result ->
                    when (result) {
                        is ExportItemResult.Success -> {
                            success += 1
                            succeededNames += result.destName
                            _state.update { current ->
                                current.copy(
                                    photos = current.photos.map {
                                        if (it.id == result.id) it.copy(status = PhotoStatus.succeeded(result.destName)) else it
                                    },
                                    processedCount = current.processedCount + 1,
                                    successCount = success,
                                    failureCount = failure
                                )
                            }
                        }
                        is ExportItemResult.Failure -> {
                            failure += 1
                            _state.update { current ->
                                current.copy(
                                    photos = current.photos.map {
                                        if (it.id == result.id) it.copy(status = PhotoStatus.failed(result.message)) else it
                                    },
                                    processedCount = current.processedCount + 1,
                                    successCount = success,
                                    failureCount = failure
                                )
                            }
                        }
                        is ExportItemResult.Cancelled -> {
                            _state.update { current ->
                                current.copy(
                                    photos = current.photos.map {
                                        if (it.id == result.id && it.status.kind == PhotoStatusKind.PROCESSING) {
                                            it.copy(status = PhotoStatus.Waiting)
                                        } else it
                                    }
                                )
                            }
                        }
                    }
                }
            ) { item ->
                if (item.source.toString() == folder.uri.toString()) {
                    throw ImagePipelineException(ImagePipelineError.REFUSE_OVERWRITE)
                }
                val created = folder.createFile(ImageExporter.mimeType(item.format), item.destName)
                    ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_DESTINATION)
                // Some providers append an extra extension; prefer the name we allocated
                // if the created file already matches, otherwise keep whatever was created.
                val destUri = created.uri
                if (destUri == item.source) {
                    created.delete()
                    throw ImagePipelineException(ImagePipelineError.REFUSE_OVERWRITE)
                }
                try {
                    resolver.openOutputStream(destUri)
                        ?: throw ImagePipelineException(ImagePipelineError.CANNOT_CREATE_DESTINATION)
                } catch (e: ImagePipelineException) {
                    created.delete()
                    throw e
                }.use { stream ->
                    BatchProcessor.processOne(
                        resolver = resolver,
                        source = item.source,
                        sourceName = item.sourceName,
                        destStream = stream,
                        format = item.format,
                        watermark = payload,
                        settings = settings,
                        placement = item.placement,
                        cacheDir = cacheDir,
                        isCancelled = { flag.isCancelled }
                    )
                }
            }

            _state.update {
                it.copy(
                    isProcessing = false,
                    isCancelling = false,
                    lastRunSucceeded = succeededNames.toList(),
                    successCount = success,
                    failureCount = failure
                )
            }
            if (state.playCompletionSound) {
                CompletionSound.play(getApplication())
            }
            cancellationFlag = null
            processingJob = null
        }
    }

    fun cancelProcessing() {
        if (!_state.value.isProcessing) return
        _state.update { it.copy(isCancelling = true) }
        cancellationFlag?.cancel()
        processingJob?.cancel()
    }

    fun outputFolderViewIntent(): Intent? {
        val uri = _state.value.outputTreeUri ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun flushSettings() {
        persistJob?.cancel()
        if (isRestoring || !_state.value.rememberSettings) return
        viewModelScope.launch { settingsStore.saveSnapshot(currentSnapshot()) }
    }

    private fun persistSettings(immediate: Boolean = false) {
        if (isRestoring || !_state.value.rememberSettings) return
        persistJob?.cancel()
        if (immediate) {
            viewModelScope.launch { settingsStore.saveSnapshot(currentSnapshot()) }
            return
        }
        persistJob = viewModelScope.launch {
            delay(400)
            settingsStore.saveSnapshot(currentSnapshot())
        }
    }

    private fun currentSnapshot(): SettingsSnapshot {
        val s = _state.value
        return SettingsSnapshot(
            sizePercent = s.sizePercent,
            position = s.position,
            watermarkMode = s.watermarkMode,
            opacity = s.opacity,
            outputFormat = s.outputFormat,
            quality = s.quality,
            playCompletionSound = s.playCompletionSound,
            stripMetadata = s.stripMetadata,
            randomRename = s.randomRename,
            rememberSettings = s.rememberSettings,
            watermarkUri = s.watermark?.uri?.toString(),
            outputTreeUri = s.outputTreeUri?.toString()
        )
    }

    private fun applySnapshot(snapshot: SettingsSnapshot) {
        _state.update {
            it.copy(
                sizePercent = snapshot.sizePercent,
                position = snapshot.position,
                watermarkMode = snapshot.watermarkMode,
                opacity = snapshot.opacity,
                outputFormat = snapshot.outputFormat,
                quality = snapshot.quality,
                playCompletionSound = snapshot.playCompletionSound,
                stripMetadata = snapshot.stripMetadata,
                randomRename = snapshot.randomRename,
                rememberSettings = snapshot.rememberSettings
            )
        }
    }

    private suspend fun restoreUris(snapshot: SettingsSnapshot) {
        snapshot.outputTreeUri?.let { raw ->
            val uri = Uri.parse(raw)
            val folder = DocumentFile.fromTreeUri(getApplication(), uri)
            if (folder != null && folder.exists()) {
                val name = folder.name ?: resolver.displayNameOf(uri)
                _state.update { it.copy(outputTreeUri = uri, outputFolderName = name) }
            }
        }
        snapshot.watermarkUri?.let { raw ->
            val uri = Uri.parse(raw)
            runCatching {
                val name = resolver.displayNameOf(uri, "watermark")
                val loaded = withContext(Dispatchers.IO) { WatermarkLoader.load(resolver, uri, name) }
                _state.update { it.copy(watermark = loaded) }
            }
        }
    }

    private fun schedulePreviewRefresh(forceNewRandom: Boolean = false) {
        if (forceNewRandom) {
            val id = _state.value.selectedPhoto?.id
            if (id != null) {
                _state.update { state ->
                    state.copy(
                        photos = state.photos.map {
                            if (it.id == id && it.placement?.isManual != true) it.copy(placement = null) else it
                        }
                    )
                }
            }
        }
        previewJob?.cancel()
        val photo = _state.value.selectedPhoto
        if (photo != null && previewCache.containsKey(photo.uri.toString())) {
            refreshPreview()
            return
        }
        refreshPreview()
    }

    private fun refreshPreview() {
        previewGeneration += 1
        val generation = previewGeneration
        val state = _state.value
        val photo = state.selectedPhoto
        val ctx = getApplication<Application>()
        if (photo == null) {
            _state.update {
                it.copy(
                    previewImage = null,
                    previewCanvasSize = SizeD(0.0, 0.0),
                    previewNote = if (it.photos.isEmpty()) ctx.getString(R.string.preview_empty) else null
                )
            }
            return
        }
        val note = if (state.watermark == null) ctx.getString(R.string.preview_need_watermark) else null
        val cached = previewCache[photo.uri.toString()]
        if (cached != null) {
            val size = SizeD(cached.width.toDouble(), cached.height.toDouble())
            _state.update { it.copy(previewImage = cached, previewCanvasSize = size, previewNote = note) }
            watermarkLayout(size)
            return
        }
        previewJob = viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    PhotoDecoder.decode(resolver, photo.uri, photo.filename, maxPixelSize = 1600)
                }
                if (generation != previewGeneration) return@launch
                if (previewCache.size > 8) {
                    val first = previewCache.keys.first()
                    previewCache.remove(first)
                }
                previewCache[photo.uri.toString()] = decoded.bitmap
                val size = SizeD(decoded.bitmap.width.toDouble(), decoded.bitmap.height.toDouble())
                _state.update {
                    it.copy(
                        previewImage = decoded.bitmap,
                        previewCanvasSize = size,
                        previewNote = if (it.watermark == null) ctx.getString(R.string.preview_need_watermark) else null
                    )
                }
                watermarkLayout(size)
            } catch (e: Exception) {
                if (generation != previewGeneration) return@launch
                val thumb = withContext(Dispatchers.IO) {
                    ThumbnailLoader.load(resolver, photo.uri, photo.filename, 1600)
                }
                _state.update {
                    it.copy(
                        previewImage = thumb,
                        previewCanvasSize = SizeD((thumb?.width ?: 0).toDouble(), (thumb?.height ?: 0).toDouble()),
                        previewNote = ctx.getString(R.string.preview_failed, ctx.pipelineErrorMessage(e))
                    )
                }
            }
        }
    }
}
