package app.tether.client.report

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serves the problem report (a `.txt` file in the app's cache) to the app the user shares it with, read-only and only
 * with the permission the share grants. Not exported; nothing else of the app is reachable through it.
 */
class ReportProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        // Only plain report names in the report folder: no path tricks.
        if (!NAME.matches(name)) throw FileNotFoundException(name)
        return File(File(ctx.cacheDir, DIR), name).takeIf { it.isFile } ?: throw FileNotFoundException(name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "text/plain"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply { addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val DIR = "reports"
        private val NAME = Regex("tether-report-[0-9-]+\\.txt")

        fun authority(ctx: Context) = ctx.packageName + ".report"

        /** Writes [text] as a new report file (older ones are removed) and returns it. */
        fun write(ctx: Context, text: String, now: Long = System.currentTimeMillis()): File {
            val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val name = "tether-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".txt"
            return File(dir, name).apply { writeText(text) }
        }

        /** The newest report file (debug hook). */
        fun latest(ctx: Context): File? = File(ctx.cacheDir, DIR).listFiles()?.filter { NAME.matches(it.name) }?.maxByOrNull { it.lastModified() }

        fun uri(ctx: Context, f: File): Uri = Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(f.name).build()
    }
}
