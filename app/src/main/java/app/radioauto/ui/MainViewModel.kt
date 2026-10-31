package app.radioauto.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.radioauto.data.Band
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

enum class Section(val label: String) { FM("FM"), AM("AM"), FAVS("Favoritas") }

data class BrowseState(
    val section: Section = Section.FM,
    val query: String = "",
    val brazil: List<Station> = emptyList(),
    val results: List<Station>? = null,
    val favorites: List<Station> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
) {
    val searching: Boolean get() = query.trim().length >= 2
    val visible: List<Station>
        get() = when {
            searching -> results ?: emptyList()
            section == Section.FAVS -> favorites
            section == Section.FM -> brazil.filter { it.band == Band.FM }
            else -> brazil.filter { it.band == Band.AM }
        }
}

data class PlayerState(
    val current: Station? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(BrowseState())
    val ui: StateFlow<BrowseState> = _ui

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player

    private var controller: MediaController? = null
    private var searchJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    init {
        RadioRepository.init(app)
        viewModelScope.launch {
            RadioRepository.favorites.collect { f -> _ui.update { it.copy(favorites = f) } }
        }
        load()

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

    fun load() {
        _ui.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            runCatching { RadioRepository.brazil(force = true) }
                .onSuccess { list -> _ui.update { it.copy(brazil = list, loading = false) } }
                .onFailure { _ui.update { it.copy(loading = false, error = true) } }
        }
    }

    fun selectSection(s: Section) = _ui.update { it.copy(section = s) }

    fun setQuery(q: String) {
        _ui.update { it.copy(query = q, results = null) }
        searchJob?.cancel()
        if (q.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(400)
            runCatching { RadioRepository.search(q) }
                .onSuccess { r -> _ui.update { if (it.query == q) it.copy(results = r, error = false) else it } }
                .onFailure { _ui.update { it.copy(results = emptyList(), error = true) } }
        }
    }

    fun toggleFavorite(s: Station) = RadioRepository.toggleFavorite(s)

    fun play(s: Station) {
        val c = controller ?: return
        val list = _ui.value.visible.ifEmpty { listOf(s) }
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
