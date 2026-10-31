package app.radioauto.data

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

enum class Band { FM, AM }

data class Station(
    val id: String,
    val name: String,
    val url: String,
    val band: Band,
    val frequency: String?,
    val place: String,
    val bitrate: Int,
) {
    /** Texto do dial: "98.1" ou, sem frequência conhecida, "FM"/"AM". */
    val label: String get() = frequency ?: band.name
    val unit: String get() = if (band == Band.FM) "MHz" else "kHz"

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(if (place.isBlank()) band.name else place)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
        )
        .build()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("band", band.name)
        .put("frequency", frequency ?: JSONObject.NULL).put("place", place).put("bitrate", bitrate)

    companion object {
        fun fromJson(o: JSONObject) = Station(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            band = Band.valueOf(o.getString("band")),
            frequency = if (o.isNull("frequency")) null else o.getString("frequency"),
            place = o.optString("place"),
            bitrate = o.optInt("bitrate"),
        )
    }
}

/**
 * Catálogo via Radio Browser (radio-browser.info), diretório aberto e comunitário.
 * A lista do Brasil são as emissoras mais ouvidas do país. Banda e frequência são
 * lidas do nome/tags da emissora; as que não informam ficam sem número no dial.
 */
object RadioRepository {
    private const val BASE = "https://de1.api.radio-browser.info/json"

    private val cache = ConcurrentHashMap<String, Station>()
    private var brazil: List<Station>? = null
    private var prefs: android.content.SharedPreferences? = null

    private val _favorites = MutableStateFlow<List<Station>>(emptyList())
    val favorites: StateFlow<List<Station>> = _favorites

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val arr = JSONArray(p.getString("favs", "[]"))
            val list = (0 until arr.length()).map { Station.fromJson(arr.getJSONObject(it)) }
            list.forEach { cache[it.id] = it }
            _favorites.value = list
        }
    }

    fun find(id: String): Station? = cache[id]

    fun toggleFavorite(s: Station) {
        val cur = _favorites.value
        _favorites.value = if (cur.any { it.id == s.id }) cur.filterNot { it.id == s.id } else cur + s
        val arr = JSONArray().also { a -> _favorites.value.forEach { a.put(it.toJson()) } }
        prefs?.edit()?.putString("favs", arr.toString())?.apply()
    }

    suspend fun brazil(force: Boolean = false): List<Station> {
        if (!force) brazil?.let { return it }
        val list = fetch("$BASE/stations/search?countrycode=BR&hidebroken=true&order=clickcount&reverse=true&limit=500")
        brazil = list
        return list
    }

    suspend fun search(query: String): List<Station> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return fetch("$BASE/stations/search?name=$q&hidebroken=true&order=clickcount&reverse=true&limit=80")
    }

    private suspend fun fetch(url: String): List<Station> = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "RadioAuto/1.0")
        }
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Station>(arr.length())
            val names = HashSet<String>()
            for (i in 0 until arr.length()) {
                val s = parse(arr.getJSONObject(i)) ?: continue
                if (!names.add(s.name.lowercase())) continue
                cache[s.id] = s
                out += s
            }
            out
        } finally {
            conn.disconnect()
        }
    }

    private val explicitFreq = Regex("""(\d{2,4}(?:[.,]\d)?)\s*(?:fm|am|mhz|khz)""")
    private val looseFmFreq = Regex("""\b((?:8[7-9]|9\d|10[0-8])[.,]\d)\b""")
    private val amWords = Regex("""\b(am|ondas? médias?|khz)\b""")

    private fun parse(o: JSONObject): Station? {
        val url = o.optString("url_resolved").ifBlank { o.optString("url") }
        val name = o.optString("name").trim()
        val uuid = o.optString("stationuuid")
        if (url.isBlank() || name.isBlank() || uuid.isBlank()) return null

        val text = "$name ${o.optString("tags")}".lowercase()
        val raw = (explicitFreq.find(text)?.groupValues?.get(1) ?: looseFmFreq.find(text)?.groupValues?.get(1))
            ?.replace(',', '.')
        val n = raw?.toFloatOrNull()
        val isAm = amWords.containsMatchIn(text) ||
            (n != null && !raw.contains('.') && n in 530f..1710f)
        val valid = n != null && (if (isAm) n in 530f..1710f else n in 87.5f..108f)

        return Station(
            id = "rb_$uuid",
            name = name,
            url = url,
            band = if (isAm) Band.AM else Band.FM,
            frequency = if (valid) raw else null,
            place = o.optString("state").ifBlank { o.optString("country") },
            bitrate = o.optInt("bitrate"),
        )
    }
}
