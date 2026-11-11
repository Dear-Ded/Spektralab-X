package android.content

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.OutputStream

open class Context(val contentResolver: ContentResolver)
class ContentValues {
    val values = mutableMapOf<String, Any>()
    fun put(key: String, value: String) { values[key] = value }
    fun put(key: String, value: Int) { values[key] = value }
}
object ContentUris {
    fun withAppendedId(uri: Uri, id: Long) = Uri.parse("$uri/$id")
}
abstract class ContentResolver {
    companion object {
        const val QUERY_ARG_SQL_SELECTION = "selection"
        const val QUERY_ARG_SQL_SELECTION_ARGS = "args"
    }
    abstract fun insert(uri: Uri, values: ContentValues): Uri?
    abstract fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<String>?): Int
    abstract fun delete(uri: Uri, selection: String?, args: Array<String>?): Int
    abstract fun query(uri: Uri, projection: Array<String>, args: Bundle?, signal: Any?): Cursor?
    fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String?): Cursor? =
        query(uri, projection, null, null)
    abstract fun openOutputStream(uri: Uri, mode: String): OutputStream?
    abstract fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor?
}
