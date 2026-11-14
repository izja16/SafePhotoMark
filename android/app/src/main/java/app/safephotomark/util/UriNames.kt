package app.safephotomark.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import app.safephotomark.processing.SupportedTypes

fun ContentResolver.displayNameOf(uri: Uri, fallback: String = "photo"): String {
    var name: String? = null
    try {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
    } catch (_: Exception) {
    }
    if (name.isNullOrBlank()) {
        name = uri.lastPathSegment?.substringAfterLast('/') ?: fallback
    }
    return name ?: fallback
}

fun ContentResolver.persistRead(uri: Uri) {
    try {
        takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
    } catch (_: Exception) {
    }
}

fun ContentResolver.persistReadWrite(uri: Uri) {
    try {
        takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (_: SecurityException) {
    } catch (_: Exception) {
    }
}

fun documentExists(folder: DocumentFile, name: String): Boolean {
    return folder.findFile(name) != null
}

fun listChildNames(folder: DocumentFile): Set<String> {
    return try {
        folder.listFiles().mapNotNull { it.name }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

fun collectImageDocuments(
    context: Context,
    roots: List<Uri>,
    existingKeys: Set<String>,
    remainingCapacity: Int
): ImportCollection {
    val results = ArrayList<ImportedRef>()
    var truncated = false
    var discoveredNew = 0
    val seen = existingKeys.toMutableSet()
    val cap = remainingCapacity.coerceAtLeast(0)

    fun consider(file: DocumentFile) {
        val uri = file.uri
        val name = file.name ?: context.contentResolver.displayNameOf(uri)
        val mime = file.type
        if (!SupportedTypes.isSupportedImage(name, mime)) return
        val key = uri.toString()
        if (!seen.add(key)) return
        discoveredNew += 1
        if (results.size < cap) {
            results += ImportedRef(uri, name, mime)
        } else {
            truncated = true
        }
    }

    fun walk(doc: DocumentFile) {
        if (doc.isDirectory) {
            val children = try {
                doc.listFiles()
            } catch (_: Exception) {
                emptyArray()
            }
            for (child in children) {
                if (child.isDirectory) walk(child) else consider(child)
            }
        } else {
            consider(doc)
        }
    }

    for (root in roots) {
        val doc = if (DocumentsContract.isTreeUri(root)) {
            DocumentFile.fromTreeUri(context, root)
        } else {
            DocumentFile.fromSingleUri(context, root)
        }
        if (doc != null) walk(doc) else {
            val name = context.contentResolver.displayNameOf(root)
            val mime = context.contentResolver.getType(root)
            if (SupportedTypes.isSupportedImage(name, mime)) {
                val key = root.toString()
                if (seen.add(key)) {
                    discoveredNew += 1
                    if (results.size < cap) {
                        results += ImportedRef(root, name, mime)
                    } else {
                        truncated = true
                    }
                }
            }
        }
    }
    return ImportCollection(results, truncated, discoveredNew)
}

data class ImportedRef(
    val uri: Uri,
    val displayName: String,
    val mime: String?
)

data class ImportCollection(
    val items: List<ImportedRef>,
    val truncated: Boolean,
    val discoveredNew: Int
)
