// Photos for the plant screens. A photo the app ships with is read from its assets. A photo of a plant added after the
// app was released (named by newer downloaded data) is downloaded from the website once, at the same path, and kept in
// the app's cache folder after that. Until it is on the phone, the plant screens show the "No photograph yet" plate.
// The same as the iPhone app's PhotoCache.
package garden.hereabouts.app.services

import android.content.res.AssetManager
import garden.hereabouts.core.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `assets` holds the shipped images/ folder, `folder` the downloaded photos. `site` null downloads nothing. */
class PhotoCache(private val site: String?, private val assets: AssetManager, private val folder: File) {
    private val shipped = ConcurrentHashMap<String, Boolean>()

    /** Whether the photo ships with the app. */
    fun isShipped(photo: Photo): Boolean {
        if (!isPhotoPath(photo.url)) return false
        return shipped.getOrPut(photo.url) {
            try {
                assets.open(photo.url).close()
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    /** The downloaded copy of the photo, or null if it has not been downloaded. */
    fun cachedFile(photo: Photo): File? {
        if (!isPhotoPath(photo.url)) return null
        return File(folder, photo.url).takeIf { it.isFile }
    }

    /** The photo's bytes, shipped or downloaded earlier, or null if it is not on the phone yet. The caller closes it. */
    fun open(photo: Photo): InputStream? = try {
        when {
            isShipped(photo) -> assets.open(photo.url)
            else -> cachedFile(photo)?.inputStream()
        }
    } catch (e: IOException) {
        null
    }

    /**
     * Makes sure the photo is on the phone, downloading it from the website first if needed. False when it cannot be
     * had now: no signal, the website does not have it, or its path is not a plain file name under images/.
     */
    suspend fun load(photo: Photo): Boolean {
        if (!isPhotoPath(photo.url)) return false
        if (isShipped(photo) || cachedFile(photo) != null) return true
        val site = site ?: return false
        val url = Http.address(site, "/${photo.url}") ?: return false
        return withContext(Dispatchers.IO) {
            val answer = Http.get(url)
            if (answer == null || answer.status != 200 || answer.body.isEmpty()) return@withContext false
            val file = File(folder, photo.url)
            try {
                file.parentFile?.mkdirs()
                // Written whole to a new file first and then moved into place, so a half-written photo is never read.
                val fresh = File(file.parentFile, ".${UUID.randomUUID()}.part")
                fresh.writeBytes(answer.body)
                if (!fresh.renameTo(file)) {
                    fresh.delete()
                    return@withContext false
                }
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    companion object {
        /** True for "images/<name>" with a plain file name, so data from the network can never point outside the folders. */
        fun isPhotoPath(path: String): Boolean {
            if (!path.startsWith("images/")) return false
            val name = path.removePrefix("images/")
            return name.isNotEmpty() && !name.startsWith(".") &&
                name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }
        }
    }
}
