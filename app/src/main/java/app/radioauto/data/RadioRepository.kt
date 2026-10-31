package app.radioauto.data

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class Band { FM, AM }

data class Station(
    val id: String,
    val name: String,
    val url: String,
    val band: Band,
    val frequency: String?,
    val place: String,
    val bitrate: Int,
    val logo: String? = null,
    val tags: String = "",
) {
    /** Ex.: "98.1 FM, São Paulo". Sem frequência conhecida: "FM, São Paulo". */
    val detail: String
        get() = listOfNotNull(
            frequency?.let { "$it ${band.name}" } ?: band.name,
            place.ifBlank { null },
        ).joinToString(", ")

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(detail)
                .setArtworkUri(logo?.let { Uri.parse(it) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
        )
        .build()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("band", band.name)
        .put("frequency", frequency ?: JSONObject.NULL).put("place", place).put("bitrate", bitrate)
        .put("logo", logo ?: JSONObject.NULL).put("tags", tags)

    companion object {
        fun fromJson(o: JSONObject) = Station(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            band = Band.valueOf(o.getString("band")),
            frequency = if (o.isNull("frequency")) null else o.getString("frequency"),
            place = o.optString("place"),
            bitrate = o.optInt("bitrate"),
            logo = if (o.isNull("logo")) null else o.optString("logo").ifBlank { null },
            tags = o.optString("tags"),
        )
    }
}

/** Cidade/estado resolvido pelo Geocoder do Android. */
data class Place(val label: String, val lat: Double, val lon: Double, val state: String?)

/**
 * Catálogo via Radio Browser (radio-browser.info): diretório aberto e comunitário,
 * com logo, estado, tags e coordenadas de muitas emissoras.
 */
object RadioRepository {
    private const val BASE = "https://de1.api.radio-browser.info/json/stations/search?hidebroken=true"

    private val cache = ConcurrentHashMap<String, Station>()
    private var top: List<Station>? = null
    private var appContext: Context? = null
    private var prefs: android.content.SharedPreferences? = null

    private val _favorites = MutableStateFlow<List<Station>>(emptyList())
    val favorites: StateFlow<List<Station>> = _favorites
    private val _recents = MutableStateFlow<List<Station>>(emptyList())
    val recents: StateFlow<List<Station>> = _recents

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        val p = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        prefs = p
        _favorites.value = load("favs")
        _recents.value = load("recents")
    }

    private fun load(key: String): List<Station> = runCatching {
        val arr = JSONArray(prefs?.getString(key, "[]") ?: "[]")
        (0 until arr.length()).map { Station.fromJson(arr.getJSONObject(it)) }
            .also { l -> l.forEach { cache[it.id] = it } }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Station>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs?.edit()?.putString(key, arr.toString())?.apply()
    }

    fun find(id: String): Station? = cache[id]

    fun toggleFavorite(s: Station) {
        val cur = _favorites.value
        _favorites.value = if (cur.any { it.id == s.id }) cur.filterNot { it.id == s.id } else cur + s
        save("favs", _favorites.value)
    }

    fun addRecent(s: Station) {
        _recents.value = (listOf(s) + _recents.value.filterNot { it.id == s.id }).take(15)
        save("recents", _recents.value)
    }

    // ---------------------------------------------------------------- consultas

    suspend fun top(force: Boolean = false): List<Station> {
        if (!force) top?.let { return it }
        return query("countrycode=BR&order=clickcount&reverse=true&limit=500").also { top = it }
    }

    suspend fun byGenre(tag: String): List<Station> =
        query("countrycode=BR&tag=${enc(tag)}&order=clickcount&reverse=true&limit=150")

    suspend fun byState(state: String): List<Station> =
        query("countrycode=BR&state=${enc(state)}&order=clickcount&reverse=true&limit=150")

    suspend fun nearby(lat: Double, lon: Double, km: Int): List<Station> =
        query("geo_lat=$lat&geo_long=$lon&geo_distance=${km * 1000}&order=clickcount&reverse=true&limit=100")

    /** Rádios da região: as com coordenadas perto, depois as do estado. */
    suspend fun local(lat: Double, lon: Double, state: String?): List<Station> = coroutineScope {
        val near = async { runCatching { nearby(lat, lon, 120) }.getOrDefault(emptyList()) }
        val inState = async { state?.let { runCatching { byState(it) }.getOrDefault(emptyList()) } ?: emptyList() }
        (near.await() + inState.await()).distinctBy { it.id }.take(80)
    }

    /**
     * Busca por nome, cidade ou estado. Para cidades, o Geocoder localiza o ponto e o
     * estado; entram primeiro as rádios que citam o termo, depois as próximas e as do estado.
     */
    suspend fun search(raw: String): List<Station> = coroutineScope {
        val term = raw.trim()
        val e = enc(term)
        val failures = AtomicInteger()

        suspend fun safe(block: suspend () -> List<Station>): List<Station> =
            try {
                block()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                failures.incrementAndGet()
                emptyList()
            }

        val byName = async { safe { query("name=$e&countrycode=BR&order=clickcount&reverse=true&limit=60") } }
        val byTag = async { safe { query("tag=$e&countrycode=BR&order=clickcount&reverse=true&limit=40") } }
        val world = async { safe { query("name=$e&order=clickcount&reverse=true&limit=40") } }
        val place = geocode(term)
        val near = async { if (place != null) safe { nearby(place.lat, place.lon, 60) } else emptyList() }
        val state = async { place?.state?.let { st -> safe { byState(st) } } ?: emptyList() }

        val n = norm(term)
        fun mentions(s: Station) = norm("${s.name} ${s.tags} ${s.place}").contains(n)
        val inState = state.await()

        val ordered = byName.await() + byTag.await() + inState.filter(::mentions) +
            near.await() + inState.filterNot(::mentions) + world.await()
        val out = ordered.distinctBy { it.id }
        if (out.isEmpty() && failures.get() >= 3) throw IOException("sem conexão")
        out.take(120)
    }

    // ---------------------------------------------------------------- lugares

    suspend fun geocode(term: String): Place? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        if (term.length < 3 || !Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale("pt", "BR")).getFromLocationName("$term, Brasil", 1)?.firstOrNull()
            val city = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            val n = norm(term)
            if (a == null || a.countryCode != "BR" || city == null) null
            else if (!norm(city).contains(n) && !n.contains(norm(city))) null
            else Place(listOfNotNull(city, a.adminArea).distinct().joinToString(", "), a.latitude, a.longitude, a.adminArea)
        }.getOrNull()
    }

    suspend fun placeAt(lat: Double, lon: Double): Place? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale("pt", "BR")).getFromLocation(lat, lon, 1)?.firstOrNull()
            val city = a?.locality ?: a?.subAdminArea
            if (a == null) null
            else Place(listOfNotNull(city, a.adminArea).distinct().joinToString(", "), lat, lon, a.adminArea)
        }.getOrNull()
    }

    // ---------------------------------------------------------------- rede

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private suspend fun query(params: String): List<Station> = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE&$params").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "RadioAuto/2.0")
        }
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Station>(arr.length())
            val names = HashSet<String>()
            for (i in 0 until arr.length()) {
                val s = parse(arr.getJSONObject(i)) ?: continue
                if (!names.add(norm(s.name))) continue
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

        val tags = o.optString("tags")
        val text = "$name $tags".lowercase()
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
            logo = o.optString("favicon").trim().takeIf { it.startsWith("http") },
            tags = tags,
        )
    }
}
