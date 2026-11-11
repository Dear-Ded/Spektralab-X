package android.database

interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getInt(column: Int): Int
    fun getLong(column: Int): Long
    fun getString(column: Int): String?
    override fun close() {}
}
