package io.github.akiig.dose

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets Sottovoce on the same phone read your medicines and doses (dose.json, read only), for its
 * day view and the doctor report. Only apps you've allowed ("Read your Dose medicines" in
 * Android's settings) can open it: Sottovoce asks for it first.
 *
 *   content://io.github.akiig.dose.data/dose.json
 */
class DoseProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment != "dose.json" || mode != "r") throw FileNotFoundException("only dose.json, read only")
        val file = File(context!!.filesDir, "dose.json")
        if (!file.exists()) file.writeText("{\"medicines\":[],\"doses\":[],\"deleted\":[]}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
