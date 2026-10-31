package app.radioauto.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.radioauto.data.Band
import app.radioauto.data.LocationHelper
import app.radioauto.data.RadioRepository
import app.radioauto.data.Station
import app.radioauto.service.PlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab { HOME, SEARCH, FAVS }
enum class LocalStatus { LOADING, NEED_PERMISSION, READY, EMPTY }
enum class BandFilter(val label: String) { ALL("Todas"), FM("FM"), AM("AM") }

/** Rótulo exibido e termo usado na busca por tag do Radio Browser. */
val Genres = listOf(
    "Sertanejo" to "sertanejo",
    "Pop" to "pop",
    "Rock" to "rock",
    "Notícias" to "news",
    "Esportes" to "sports",
    "Gospel" to "gospel",
    "MPB" to "mpb",
    "Pagode" to "pagode",
    "Forró" to "forr",
    "Eletrônica" to "electronic",
)

data class UiState(
    val tab: Tab = Tab.HOME,
    val top: List<Station> = emptyList(),
    val topLoading: Boolean = true,
    val topError: Boolean = false,
    val local: List<Station> = emptyList(),
    val localLabel: String? = null,
    val localStatus: LocalStatus = LocalStatus.LOADING,
    val genre: Pair<String, String>? = null,
    val genreList: List<Station>? = null,
    val bandFilter: BandFilter = BandFilter.ALL,
    val query: String = "",
    val results: List<Station>? = null,
    val searchError: Boolean = false,
    val favorites: List<Station> = emptyList(),
    val recents: List<Station> = emptyList(),
) {
    val searching: Boolean get() = query.trim().length >= 2
    val homeList: List<Station>
        get() {
            val base = genreList ?: top
            return when (bandFilter) {
                BandFilter.ALL -> base
                BandFilter.FM -> base.filter { it.band == Band.FM }
                BandFilter.AM -> base.filter { it.band == Band.AM }
            }
        }
}

data class PlayerState(
    val current: Station? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player

    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var genreJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    init {
        RadioRepository.init(app)
        viewModelScope.launch {
            RadioRepository.favorites.collect { f -> _ui.update { it.copy(favorites = f) } }
        }
        viewModelScope.launch {
            RadioRepository.recents.collect { r -> _ui.update { it.copy(recents = r) } }
        }
        loadTop()
        refreshLocal()

        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                c.addListener(listener)
                sync(c)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun sync(p: Player) {
        val id = p.currentMediaItem?.mediaId
        _player.value = PlayerState(
            current = id?.let { RadioRepository.find(it) },
            playing = p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED,
            buffering = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING,
        )
    }

    // ------------------------------------------------------------ início

    fun loadTop() {
        _ui.update { it.copy(topLoading = true, topError = false) }
        viewModelScope.launch {
            runCatching { RadioRepository.top(force = true) }
                .onSuccess { list -> _ui.update { it.copy(top = list, topLoading = false) } }
                .onFailure { _ui.update { it.copy(topLoading = false, topError = true) } }
        }
    }

    /** Descobre onde o usuário está e busca as rádios da região. */
    fun refreshLocal() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (!LocationHelper.hasPermission(app)) {
                _ui.update { it.copy(localStatus = LocalStatus.NEED_PERMISSION) }
                return@launch
            }
            _ui.update { it.copy(localStatus = LocalStatus.LOADING) }
            val loc = LocationHelper.current(app)
            if (loc == null) {
                _ui.update { it.copy(localStatus = LocalStatus.EMPTY, localLabel = null) }
                return@launch
            }
            val place = RadioRepository.placeAt(loc.first, loc.second)
            val list = RadioRepository.local(loc.first, loc.second, place?.state)
            _ui.update {
                it.copy(
                    local = list,
                    localLabel = place?.label,
                    localStatus = if (list.isEmpty()) LocalStatus.EMPTY else LocalStatus.READY,
                )
            }
        }
    }

    fun selectGenre(genre: Pair<String, String>?) {
        genreJob?.cancel()
        _ui.update { it.copy(genre = genre, genreList = null) }
        if (genre == null) return
        genreJob = viewModelScope.launch {
            runCatching { RadioRepository.byGenre(genre.second) }
                .onSuccess { r -> _ui.update { if (it.genre == genre) it.copy(genreList = r, topError = false) else it } }
                .onFailure { _ui.update { it.copy(genreList = emptyList(), topError = true) } }
        }
    }

    fun selectBand(b: BandFilter) = _ui.update { it.copy(bandFilter = b) }
    fun selectTab(t: Tab) = _ui.update { it.copy(tab = t) }

    // ------------------------------------------------------------ busca

    fun setQuery(q: String) {
        _ui.update { it.copy(query = q, results = null, searchError = false) }
        searchJob?.cancel()
        if (q.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(450)
            runCatching { RadioRepository.search(q) }
                .onSuccess { r -> _ui.update { if (it.query == q) it.copy(results = r) else it } }
                .onFailure { _ui.update { it.copy(results = emptyList(), searchError = true) } }
        }
    }

    // ------------------------------------------------------------ player

    fun toggleFavorite(s: Station) = RadioRepository.toggleFavorite(s)

    fun play(s: Station, queue: List<Station>) {
        val c = controller ?: return
        val list = queue.take(300).let { if (it.any { x -> x.id == s.id }) it else listOf(s) }
        val index = list.indexOfFirst { it.id == s.id }.coerceAtLeast(0)
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (_player.value.playing) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
