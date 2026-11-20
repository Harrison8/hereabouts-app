// Photos for the plant screens. Not built yet: only shipped photos are found.
package garden.hereabouts.app.services

import android.content.res.AssetManager
import garden.hereabouts.core.Photo
import java.io.File
import java.io.InputStream

class PhotoCache(private val site: String?, private val assets: AssetManager, private val folder: File) {
    fun isShipped(photo: Photo): Boolean = false

    fun cachedFile(photo: Photo): File? = null

    fun open(photo: Photo): InputStream? = null

    suspend fun load(photo: Photo): Boolean = false

    companion object {
        fun isPhotoPath(path: String): Boolean = false
    }
}
