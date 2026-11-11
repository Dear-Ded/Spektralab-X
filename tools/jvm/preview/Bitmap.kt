package android.graphics

class Bitmap(val width: Int, val height: Int = width) {
    var profile = "sRGB"
    val isRecycled = false
    fun prepareToDraw() {}
    fun recycle() {}
}
