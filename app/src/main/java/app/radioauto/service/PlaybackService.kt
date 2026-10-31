package app.radioauto.service

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.radioauto.data.Band
import app.radioauto.data.LocationHelper
import app.radioauto.data.RadioRepository
import app.radioauto.data.Station
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Toca o stream e expõe o catálogo ao Android Auto. No carro, o Android Auto
 * desenha a própria tela a partir da árvore de pastas abaixo.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        RadioRepository.init(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { RadioRepository.find(it) }?.let(RadioRepository::addRecent)
            }
        })
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .build()
        )
        .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Rádio"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                val items: List<MediaItem> = when (parentId) {
                    ROOT -> listOf(
                        folder(FAVS, "Favoritas"),
                        folder(RECENTS, "Recentes"),
                        folder(NEAR, "Perto de você"),
                        folder(TOP, "Mais ouvidas"),
                        folder(FM, "FM"),
                        folder(AM, "AM"),
                    )
                    FAVS -> RadioRepository.favorites.value.map(Station::toMediaItem)
                    RECENTS -> RadioRepository.recents.value.map(Station::toMediaItem)
                    NEAR -> nearStations().map(Station::toMediaItem)
                    TOP -> RadioRepository.top().map(Station::toMediaItem)
                    FM -> RadioRepository.top().filter { it.band == Band.FM }.map(Station::toMediaItem)
                    AM -> RadioRepository.top().filter { it.band == Band.AM }.map(Station::toMediaItem)
                    else -> emptyList()
                }
                // O Android Auto limita listas longas; 100 itens por pasta basta.
                LibraryResult.ofItemList(ImmutableList.copyOf(items.take(100)), params)
            } catch (e: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = RadioRepository.find(mediaId)?.toMediaItem()
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        // Android Auto e Assistente mandam só o mediaId ou uma busca por voz;
        // aqui resolvemos para o item com a URL do stream.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                val query = item.requestMetadata.searchQuery
                if (!query.isNullOrBlank()) {
                    runCatching { RadioRepository.search(query).take(1).map(Station::toMediaItem) }
                        .getOrDefault(emptyList())
                } else {
                    listOf(RadioRepository.find(item.mediaId)?.toMediaItem() ?: item)
                }
            }.toMutableList()
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            try {
                val count = RadioRepository.search(query).size
                session.notifySearchResultChanged(browser, query, count, params)
                LibraryResult.ofVoid()
            } catch (e: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                val items = RadioRepository.search(query).take(50).map(Station::toMediaItem)
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            } catch (e: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }
    }

    private suspend fun nearStations(): List<Station> {
        val loc = LocationHelper.current(this) ?: return emptyList()
        val place = RadioRepository.placeAt(loc.first, loc.second)
        return RadioRepository.local(loc.first, loc.second, place?.state)
    }

    private companion object {
        const val ROOT = "root"
        const val FM = "fm"
        const val AM = "am"
        const val FAVS = "favs"
        const val RECENTS = "recents"
        const val NEAR = "near"
        const val TOP = "top"
    }
}
