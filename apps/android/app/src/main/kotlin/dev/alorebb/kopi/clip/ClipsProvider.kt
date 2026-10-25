package dev.alorebb.kopi.clip

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.alorebb.kopi.daemon.DaemonPaths
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access to the Mac → Android image the daemon wrote: `content://dev.alorebb.kopi.clips/<uuid>`
 * serves `DaemonPaths.CLIPS/<uuid>.png` if it exists. Exported so the app that pastes can read it;
 * spec §10 risk: any app that knows the URI can read the latest image while it exists.
 */
class ClipsProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val id = uri.pathSegments.singleOrNull()?.takeIf { UUID.matches(it) } ?: return null
        return File(DaemonPaths.CLIPS, "$id.png").takeIf { it.isFile }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val file = fileFor(uri) ?: throw FileNotFoundException("no clip")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val file = fileFor(uri) ?: return null
        val head = file.inputStream().use { s -> ByteArray(3).also { s.read(it) } }
        return if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = fileFor(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else if (it == OpenableColumns.DISPLAY_NAME) file.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
