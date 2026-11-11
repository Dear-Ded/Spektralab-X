package android.provider

import android.net.Uri

object MediaStore {
    const val VOLUME_EXTERNAL_PRIMARY = "external_primary"
    const val QUERY_ARG_MATCH_PENDING = "match_pending"
    const val MATCH_INCLUDE = 1
    const val MATCH_ONLY = 2
    object MediaColumns {
        const val DISPLAY_NAME = "name"
        const val MIME_TYPE = "mime"
        const val RELATIVE_PATH = "path"
        const val IS_PENDING = "pending"
        const val _ID = "id"
    }
    object Images { object Media { fun getContentUri(volume: String) = Uri.parse("content://media/$volume/images/media") } }
    object Downloads { fun getContentUri(volume: String) = Uri.parse("content://media/$volume/downloads") }
    fun setIncludePending(uri: Uri) = uri
}
