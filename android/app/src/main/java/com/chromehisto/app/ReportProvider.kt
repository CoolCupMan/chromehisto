package com.chromehisto.app

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

/** Read-only provider so reports can be shared with other apps. */
class ReportProvider : ContentProvider() {

    companion object {
        /** Authority is "<applicationId>.reports" so parallel installs do not clash. */
        fun uriFor(ctx: Context, name: String): Uri =
            Uri.parse("content://${ctx.packageName}.reports/${Uri.encode(name)}")
    }

    private fun fileFor(uri: Uri): File {
        val name = File(uri.lastPathSegment ?: throw FileNotFoundException()).name
        val f = File(ReportBuilder.reportsDir(context!!), name)
        if (!f.isFile) throw FileNotFoundException(name)
        return f
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".html") == true) "text/html" else "text/plain"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?) = 0
}
