package app.safephotomark.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.safephotomark.processing.OutputFormat
import app.safephotomark.processing.WatermarkCompositor
import app.safephotomark.processing.WatermarkMode
import app.safephotomark.processing.WatermarkPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class SettingsSnapshot(
    val sizePercent: Double,
    val position: WatermarkPosition,
    val watermarkMode: WatermarkMode,
    val opacity: Double,
    val outputFormat: OutputFormat,
    val quality: Double,
    val playCompletionSound: Boolean,
    val stripMetadata: Boolean,
    val randomRename: Boolean,
    val rememberSettings: Boolean,
    val watermarkUri: String?,
    val outputTreeUri: String?
) {
    companion object {
        val defaultValue = SettingsSnapshot(
            sizePercent = WatermarkCompositor.defaultSizePercent,
            position = WatermarkPosition.BOTTOM_RIGHT,
            watermarkMode = WatermarkMode.RANDOM,
            opacity = WatermarkCompositor.defaultOpacity,
            outputFormat = OutputFormat.SAME_AS_SOURCE,
            quality = 0.95,
            playCompletionSound = true,
            stripMetadata = true,
            randomRename = false,
            rememberSettings = true,
            watermarkUri = null,
            outputTreeUri = null
        )
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "safephotomark_settings"
)

class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val snapshots: Flow<SettingsSnapshot> = dataStore.data.map { prefs -> prefs.toSnapshot() }

    suspend fun loadSnapshot(): SettingsSnapshot = snapshots.first()

    suspend fun saveSnapshot(snapshot: SettingsSnapshot) {
        if (!snapshot.rememberSettings) {
            clearPersistedSettings(keepRememberFlag = true)
            dataStore.edit { it[Keys.remember] = false }
            return
        }
        dataStore.edit { prefs ->
            prefs[Keys.remember] = true
            prefs[Keys.sizePercent] = snapshot.sizePercent
            prefs[Keys.position] = snapshot.position.name
            prefs[Keys.mode] = snapshot.watermarkMode.name
            prefs[Keys.opacity] = snapshot.opacity
            prefs[Keys.format] = snapshot.outputFormat.name
            prefs[Keys.quality] = snapshot.quality
            prefs[Keys.sound] = snapshot.playCompletionSound
            prefs[Keys.strip] = snapshot.stripMetadata
            prefs[Keys.rename] = snapshot.randomRename
            snapshot.watermarkUri?.let { prefs[Keys.watermark] = it } ?: prefs.remove(Keys.watermark)
            snapshot.outputTreeUri?.let { prefs[Keys.output] = it } ?: prefs.remove(Keys.output)
        }
    }

    suspend fun saveWatermarkUri(uri: Uri?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.watermark) else prefs[Keys.watermark] = uri.toString()
        }
    }

    suspend fun saveOutputTreeUri(uri: Uri?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.output) else prefs[Keys.output] = uri.toString()
        }
    }

    suspend fun clearPersistedSettings(keepRememberFlag: Boolean = false) {
        dataStore.edit { prefs ->
            val remember = prefs[Keys.remember] ?: true
            prefs.clear()
            if (keepRememberFlag) {
                prefs[Keys.remember] = remember
            }
        }
    }

    private fun Preferences.toSnapshot(): SettingsSnapshot {
        val defaults = SettingsSnapshot.defaultValue
        val remember = this[Keys.remember] ?: true
        if (!remember) return defaults.copy(rememberSettings = false)
        var size = this[Keys.sizePercent] ?: defaults.sizePercent
        if (size !in WatermarkCompositor.sizePercentRange) size = defaults.sizePercent
        var opacity = this[Keys.opacity] ?: defaults.opacity
        if (opacity !in 0.0..1.0) opacity = defaults.opacity
        var quality = this[Keys.quality] ?: defaults.quality
        if (quality !in 0.5..1.0) quality = defaults.quality
        val position = this[Keys.position]?.let { runCatching { WatermarkPosition.valueOf(it) }.getOrNull() }
            ?: defaults.position
        val mode = this[Keys.mode]?.let { runCatching { WatermarkMode.valueOf(it) }.getOrNull() }
            ?: defaults.watermarkMode
        val format = this[Keys.format]?.let { runCatching { OutputFormat.valueOf(it) }.getOrNull() }
            ?: defaults.outputFormat
        return SettingsSnapshot(
            sizePercent = size,
            position = position,
            watermarkMode = mode,
            opacity = opacity,
            outputFormat = format,
            quality = quality,
            playCompletionSound = this[Keys.sound] ?: defaults.playCompletionSound,
            stripMetadata = this[Keys.strip] ?: defaults.stripMetadata,
            randomRename = this[Keys.rename] ?: defaults.randomRename,
            rememberSettings = true,
            watermarkUri = this[Keys.watermark],
            outputTreeUri = this[Keys.output]
        )
    }

    private object Keys {
        val remember = booleanPreferencesKey("remember_settings")
        val sizePercent = doublePreferencesKey("size_percent")
        val position = stringPreferencesKey("position")
        val mode = stringPreferencesKey("watermark_mode")
        val opacity = doublePreferencesKey("opacity")
        val format = stringPreferencesKey("output_format")
        val quality = doublePreferencesKey("quality")
        val sound = booleanPreferencesKey("play_completion_sound")
        val strip = booleanPreferencesKey("strip_metadata")
        val rename = booleanPreferencesKey("random_rename")
        val watermark = stringPreferencesKey("watermark_uri")
        val output = stringPreferencesKey("output_tree_uri")
    }
}
