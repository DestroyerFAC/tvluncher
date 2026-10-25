package fr.tvluncher.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.text.HtmlCompat
import fr.tvluncher.launcher.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where the gallery wallpaper comes from. */
enum class ImageSource(val key: String, @StringRes val labelRes: Int) {
    PAINTINGS("paintings", R.string.source_paintings),
    NATURE("nature", R.string.source_nature),
    SPACE("space", R.string.source_space),
    MIX("mix", R.string.source_mix);

    companion object {
        fun of(key: String?): ImageSource = entries.firstOrNull { it.key == key } ?: MIX
    }
}

/**
 * One wallpaper image and the label shown under it. [credit] carries the author
 * and, for photos, the licence, which CC BY / BY-SA require to be displayed.
 */
data class Artwork(
    val key: String,
    val title: String,
    val credit: String,
    val imageUrl: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("title", title)
        .put("credit", credit)
        .put("url", imageUrl)

    companion object {
        fun fromJson(o: JSONObject): Artwork? = runCatching {
            Artwork(
                key = o.getString("key"),
                title = o.optString("title"),
                credit = o.optString("credit"),
                imageUrl = o.getString("url"),
            )
        }.getOrNull()

        fun fromJson(raw: String?): Artwork? =
            raw?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }
}

/** An image together with the file it was downloaded to. */
class DisplayedArtwork(val artwork: Artwork, val file: File)

/**
 * The gallery wallpaper: one image per day, from free collections that need no
 * key or account and receive nothing about the viewer.
 *
 * - Paintings: the Art Institute of Chicago's public-domain collection (CC0).
 * - Nature and space: Wikimedia Commons featured pictures, i.e. photos the
 *   community already vetted for quality, under free licences.
 *
 * Everything is cached on disk, which keeps the last image on screen offline and
 * limits traffic to one catalogue request per source a week plus one image a day.
 */
object Artworks {

    private const val TAG = "Artworks"
    private const val USER_AGENT = "TVLuncher/1.1 (https://github.com/DestroyerFAC/tvluncher)"

    private const val AIC_API = "https://api.artic.edu/api/v1/artworks/search"
    private const val AIC_IIIF = "https://www.artic.edu/iiif/2"
    /** One of the two widths the museum asks clients to use, so its CDN cache hits. */
    private const val AIC_WIDTH = 1686

    private const val COMMONS_API = "https://commons.wikimedia.org/w/api.php"
    private const val COMMONS_UPLOADS = "https://upload.wikimedia.org/"
    /** A standard Commons thumbnail step, so it is served from cache, not rendered. */
    private const val COMMONS_WIDTH = 1920
    private const val FEATURED = "incategory:Featured_pictures_on_Wikimedia_Commons"

    // Tried in order until one returns images: the OR query is richer, the plain
    // one is the fallback should the search syntax ever be rejected.
    private val NATURE_QUERIES = listOf(
        "$FEATURED (landscape OR mountain OR forest OR lake OR waterfall OR coast)",
        "$FEATURED landscape",
    )
    private val SPACE_QUERIES = listOf(
        "$FEATURED (nebula OR galaxy OR \"Milky Way\" OR aurora)",
        "$FEATURED nebula",
    )

    private const val CATALOGUE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    private const val MAX_JSON_BYTES = 3L * 1024 * 1024
    private const val KEEP_IMAGES = 4
    private const val MAX_ATTEMPTS = 3
    private const val MAX_CREDIT_CHARS = 120

    /** Wide enough to fill a 16:9 screen without cropping the subject away. */
    private const val MIN_ASPECT = 1.2
    private const val MIN_PAINTINGS = 5

    private val mutex = Mutex()

    private fun dir(context: Context): File =
        File(context.filesDir, "art").apply { mkdirs() }

    private fun catalogueFile(context: Context, source: ImageSource) =
        File(dir(context), "catalogue-${source.key}.json")

    private fun imageFile(context: Context, artwork: Artwork) =
        File(dir(context), artwork.key.filter { it.isLetterOrDigit() || it == '-' } + ".jpg")

    /** The last image shown, straight from disk. No network: safe to call first. */
    suspend fun cached(context: Context): DisplayedArtwork? = withContext(Dispatchers.IO) {
        val artwork = Artwork.fromJson(Prefs.get(context).artworkCurrent) ?: return@withContext null
        val file = imageFile(context, artwork)
        if (file.isFile && file.length() > 0) DisplayedArtwork(artwork, file) else null
    }

    /**
     * Today's image, downloading it if needed. A failed download moves on to the
     * next entries; when all fail the last cached image is returned, and null only
     * when nothing has ever been downloaded.
     */
    suspend fun today(context: Context): DisplayedArtwork? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context)
            val list = catalogue(context, ImageSource.of(prefs.imageSource))
            if (list.isEmpty()) return@withContext cached(context)

            val start = Math.floorMod(LocalDate.now().toEpochDay() + prefs.artworkOffset, list.size.toLong()).toInt()
            repeat(minOf(MAX_ATTEMPTS, list.size)) { attempt ->
                val artwork = list[(start + attempt) % list.size]
                val file = imageFile(context, artwork)
                if (file.isFile || download(artwork.imageUrl, file)) {
                    prefs.artworkCurrent = artwork.toJson().toString()
                    prune(context, keep = file)
                    return@withContext DisplayedArtwork(artwork, file)
                }
            }
            cached(context)
        }
    }

    /** Moves on to the next image; it then stays until tomorrow. */
    suspend fun next(context: Context): DisplayedArtwork? {
        Prefs.get(context).artworkOffset += 1
        return today(context)
    }

    // --- Catalogues --------------------------------------------------------

    private fun catalogue(context: Context, source: ImageSource): List<Artwork> {
        if (source == ImageSource.MIX) {
            // Round-robin, so consecutive days alternate painting / nature / space.
            val lists = listOf(ImageSource.PAINTINGS, ImageSource.NATURE, ImageSource.SPACE)
                .map { catalogue(context, it) }
                .filter { it.isNotEmpty() }
            val longest = lists.maxOfOrNull { it.size } ?: 0
            return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        }

        val file = catalogueFile(context, source)
        val fresh = file.isFile &&
            System.currentTimeMillis() - file.lastModified() in 0 until CATALOGUE_MAX_AGE_MS
        if (!fresh) {
            val fetched = when (source) {
                ImageSource.PAINTINGS -> fetchPaintings()
                ImageSource.NATURE -> NATURE_QUERIES.firstNotEmpty(::fetchCommons)
                ImageSource.SPACE -> SPACE_QUERIES.firstNotEmpty(::fetchCommons)
                ImageSource.MIX -> emptyList()
            }
            if (fetched.isNotEmpty()) {
                val json = JSONArray().apply { fetched.forEach { put(it.toJson()) } }
                writeAtomically(file, json.toString().toByteArray())
            }
        }
        // A stale catalogue still beats none when the network is down.
        return readCatalogue(file)
    }

    private fun List<String>.firstNotEmpty(fetch: (String) -> List<Artwork>): List<Artwork> {
        for (query in this) {
            val result = fetch(query)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun readCatalogue(file: File): List<Artwork> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { Artwork.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    private fun fetchPaintings(): List<Artwork> {
        val url = AIC_API +
            "?q=landscape%20painting" +
            // Filtered server-side to public domain, and checked again below.
            "&query%5Bterm%5D%5Bis_public_domain%5D=true" +
            "&fields=id,title,artist_title,date_display,image_id,thumbnail," +
            "classification_title,is_public_domain" +
            "&limit=100"
        val body = getText(url) ?: return emptyList()
        return runCatching {
            val data = JSONObject(body).getJSONArray("data")
            val all = (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                val imageId = o.optString("image_id").clean().takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (!o.optBoolean("is_public_domain", false)) return@mapNotNull null
                val thumb = o.optJSONObject("thumbnail")
                if (!wideEnough(thumb?.optDouble("width", 0.0), thumb?.optDouble("height", 0.0))) {
                    return@mapNotNull null
                }
                val credit = listOf(o.optString("artist_title").clean(), o.optString("date_display").clean())
                    .filter { it.isNotEmpty() }
                    .joinToString(", ")
                o to Artwork(
                    key = "aic-$imageId",
                    title = o.optString("title").clean(),
                    credit = credit,
                    imageUrl = "$AIC_IIIF/$imageId/full/$AIC_WIDTH,/0/default.jpg",
                )
            }
            // The full-text search also matches prints and photographs. Prefer
            // paintings, but never end up with an empty wall over it.
            val paintings = all.filter { (o, _) ->
                o.optString("classification_title").contains("painting", ignoreCase = true)
            }
            (if (paintings.size >= MIN_PAINTINGS) paintings else all).map { it.second }
        }.getOrElse {
            Log.w(TAG, "Unexpected AIC payload", it)
            emptyList()
        }
    }

    private fun fetchCommons(query: String): List<Artwork> {
        val url = COMMONS_API +
            "?action=query&format=json&formatversion=2" +
            "&generator=search&gsrnamespace=6&gsrlimit=50" +
            "&gsrsearch=" + URLEncoder.encode(query, "UTF-8") +
            "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata" +
            "&iiurlwidth=$COMMONS_WIDTH" +
            "&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName"
        val body = getText(url) ?: return emptyList()
        return runCatching {
            val pages = JSONObject(body).optJSONObject("query")?.optJSONArray("pages")
                ?: return@runCatching emptyList()
            (0 until pages.length()).mapNotNull { i ->
                val page = pages.optJSONObject(i) ?: return@mapNotNull null
                val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
                if (info.optString("mime") != "image/jpeg") return@mapNotNull null
                if (!wideEnough(info.optDouble("width", 0.0), info.optDouble("height", 0.0))) {
                    return@mapNotNull null
                }
                // Only ever fetch from Wikimedia's own image servers, over HTTPS.
                val thumb = info.optString("thumburl").takeIf { it.startsWith(COMMONS_UPLOADS) }
                    ?: return@mapNotNull null
                val meta = info.optJSONObject("extmetadata")
                fun field(name: String) = meta?.optJSONObject(name)?.optString("value").orEmpty().plainText()

                val title = field("ObjectName").ifEmpty {
                    page.optString("title").removePrefix("File:").substringBeforeLast('.')
                }
                val credit = listOf(field("Artist"), field("LicenseShortName"), "Wikimedia Commons")
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                    .take(MAX_CREDIT_CHARS)
                Artwork(
                    key = "commons-${page.optLong("pageid")}",
                    title = title,
                    credit = credit,
                    imageUrl = thumb,
                )
            }
        }.getOrElse {
            Log.w(TAG, "Unexpected Commons payload", it)
            emptyList()
        }
    }

    private fun wideEnough(width: Double?, height: Double?): Boolean {
        if (width == null || height == null || height <= 0.0) return false
        return width / height >= MIN_ASPECT
    }

    private fun String.clean(): String = if (this == "null") "" else trim()

    /** Commons metadata is HTML (links, spans); the label needs plain text. */
    private fun String.plainText(): String =
        HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ")
            .trim()

    // --- Network -----------------------------------------------------------

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            // Both Wikimedia and the museum ask clients to identify themselves.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("AIC-User-Agent", USER_AGENT)
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
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) return false
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
    private fun readCapped(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
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
