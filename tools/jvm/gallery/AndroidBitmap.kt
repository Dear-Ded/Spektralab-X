package android.graphics

object BitmapFactory {
    class Options {
        var inJustDecodeBounds = false
        var outWidth = 0
        var outHeight = 0
    }
    fun decodeFile(path: String, options: Options) = Unit
}
