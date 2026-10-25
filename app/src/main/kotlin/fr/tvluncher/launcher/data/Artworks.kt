package fr.tvluncher.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A public-domain painting from the Art Institute of Chicago's open collection. */
data class Artwork(
    val id: Int,
    val title: String,
    val artist: String,
    val date: String,
    val imageId: String,
) {
    /** "Claude Monet, 1891" — whichever parts the museum actually recorded. */
    val credit: String
        get() = listOf(artist, date).filter { it.isNotBlank() }.joinToString(", ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("date", date)
        .put("image_id", imageId)

    companion object {
        fun fromJson(raw: String?): Artwork? = raw?.let {
            runCatching {
                val o = JSONObject(it)
                Artwork(
                    id = o.getInt("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    date = o.optString("date"),
                    imageId = o.getString("image_id"),
                )
            }.getOrNull()
        }
    }
}

/** A painting together with the image file it was downloaded to. */
class DisplayedArtwork(val artwork: Artwork, val file: File)

/**
 * The gallery wallpaper: one public-domain painting per day.
 *
 * The Art Institute of Chicago publishes its collection under CC0 with a keyless
 * API and an IIIF image server, so this needs no account and sends nothing about
 * the viewer. Everything is cached on disk, which keeps the last painting on
 * screen when the box is offline and limits traffic to one catalogue request a
 * week plus one image a day.
 */
object Artworks {

    private const val TAG = "Artworks"
    private const val API = "https://api.artic.edu/api/v1/artworks/search"
    private const val IIIF = "https://www.artic.edu/iiif/2"

    /** One of the two widths the museum asks clients to use, so its CDN cache hits. */
    private const val IMAGE_WIDTH = 1686
    private const val CATALOGUE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    private const val MAX_JSON_BYTES = 2L * 1024 * 1024
    private const val KEEP_IMAGES = 4

    /** Paintings landscape enough to fill a 16:9 screen without losing the subject. */
    private const val MIN_ASPECT = 1.2

    private val mutex = Mutex()

    private fun dir(context: Context): File =
        File(context.filesDir, "art").apply { mkdirs() }

    private fun catalogueFile(context: Context) = File(dir(context), "catalogue.json")

    private fun imageFile(context: Context, artwork: Artwork) =
        // image_id is a UUID from the API; keep only safe characters regardless.
        File(dir(context), artwork.imageId.filter { it.isLetterOrDigit() || it == '-' } + ".jpg")

    /** The last painting shown, straight from disk. No network: safe to call first. */
    suspend fun cached(context: Context): DisplayedArtwork? = withContext(Dispatchers.IO) {
        val artwork = Artwork.fromJson(Prefs.get(context).artworkCurrent) ?: return@withContext null
        val file = imageFile(context, artwork)
        if (file.isFile && file.length() > 0) DisplayedArtwork(artwork, file) else null
    }

    /**
     * Today's painting, downloading it if needed. Falls back to the last cached one
     * on any failure, and returns null only when nothing has ever been downloaded.
     */
    suspend fun today(context: Context): DisplayedArtwork? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context)
            val catalogue = catalogue(context)
            if (catalogue.isEmpty()) return@withContext cached(context)

            val index = Math.floorMod(LocalDate.now().toEpochDay() + prefs.artworkOffset, catalogue.size.toLong())
            val artwork = catalogue[index.toInt()]
            val file = imageFile(context, artwork)
            if (!file.isFile && !download(imageUrl(artwork), file)) {
                return@withContext cached(context)
            }
            prefs.artworkCurrent = artwork.toJson().toString()
            prune(context, keep = file)
            DisplayedArtwork(artwork, file)
        }
    }

    /** Moves on to the next painting in the catalogue; it then stays until tomorrow. */
    suspend fun next(context: Context): DisplayedArtwork? {
        Prefs.get(context).artworkOffset += 1
        return today(context)
    }

    private fun imageUrl(artwork: Artwork) =
        "$IIIF/${artwork.imageId}/full/$IMAGE_WIDTH,/0/default.jpg"

    private fun catalogue(context: Context): List<Artwork> {
        val file = catalogueFile(context)
        val fresh = file.isFile &&
            System.currentTimeMillis() - file.lastModified() in 0 until CATALOGUE_MAX_AGE_MS
        if (!fresh) {
            val url = API +
                "?q=landscape%20painting" +
                // Filter server-side to public domain; checked again below.
                "&query%5Bterm%5D%5Bis_public_domain%5D=true" +
                "&fields=id,title,artist_title,date_display,image_id,thumbnail," +
                "classification_title,is_public_domain" +
                "&limit=100"
            val body = getText(url)
            if (body != null && parse(body).isNotEmpty()) {
                writeAtomically(file, body.toByteArray())
            }
        }
        // A stale catalogue still beats none when the network is down.
        return if (file.isFile) parse(runCatching { file.readText() }.getOrDefault("")) else emptyList()
    }

    private fun parse(body: String): List<Artwork> = runCatching {
        val data: JSONArray = JSONObject(body).getJSONArray("data")
        val all = (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val imageId = o.optString("image_id").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            if (!o.optBoolean("is_public_domain", false)) return@mapNotNull null
            val thumb = o.optJSONObject("thumbnail")
            val w = thumb?.optDouble("width", 0.0) ?: 0.0
            val h = thumb?.optDouble("height", 0.0) ?: 0.0
            if (h <= 0.0 || w / h < MIN_ASPECT) return@mapNotNull null
            o to Artwork(
                id = o.optInt("id"),
                title = o.optString("title").cleanNull(),
                artist = o.optString("artist_title").cleanNull(),
                date = o.optString("date_display").cleanNull(),
                imageId = imageId,
            )
        }
        // The full-text search also matches prints and photographs of landscapes.
        // Prefer paintings, but never end up with an empty wall over it.
        val paintings = all.filter { (o, _) ->
            o.optString("classification_title").contains("painting", ignoreCase = true)
        }
        (if (paintings.size >= MIN_PAINTINGS) paintings else all).map { it.second }
    }.getOrElse {
        Log.w(TAG, "Unexpected catalogue payload", it)
        emptyList()
    }

    private const val MIN_PAINTINGS = 5

    private fun String.cleanNull(): String = if (this == "null") "" else trim()

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            // The museum asks API clients to identify themselves.
            setRequestProperty("AIC-User-Agent", "TV Luncher (https://github.com/DestroyerFAC/tvluncher)")
        }

    private fun getText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(url, "application/json")
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { readCapped(it, MAX_JSON_BYTES) } ?: return null
            String(bytes, Charsets.UTF_8)
        } catch (e: IOException) {
            Log.d(TAG, "Catalogue request failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun download(url: String, target: File): Boolean {
        var connection: HttpURLConnection? = null
        val temp = File(target.parentFile, target.name + ".part")
        return try {
            connection = open(url, "image/jpeg")
            if (connection.responseCode !in 200..299) return false
            val declared = connection.contentLengthLong
            if (declared > MAX_IMAGE_BYTES) return false
            val bytes = connection.inputStream.use { readCapped(it, MAX_IMAGE_BYTES) } ?: return false
            // Never hand the wallpaper something that is not a decodable image,
            // whatever the server or a captive portal answered with.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            temp.writeBytes(bytes)
            temp.renameTo(target)
        } catch (e: IOException) {
            Log.d(TAG, "Image download failed: ${e.message}")
            false
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }

    /** Reads at most [limit] bytes; returns null if the stream is larger. */
    private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".part")
        runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    /** Keeps the few most recent images so the cache never grows past a few MB. */
    private fun prune(context: Context, keep: File) {
        val images = dir(context).listFiles { f -> f.name.endsWith(".jpg") } ?: return
        images.filter { it != keep }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP_IMAGES - 1)
            .forEach { it.delete() }
    }
}
