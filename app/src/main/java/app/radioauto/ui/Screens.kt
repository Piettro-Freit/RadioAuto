package app.radioauto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radioauto.data.Band
import app.radioauto.data.Station

// Números tabulares: as frequências ficam alinhadas em coluna sem precisar de fonte monoespaçada.
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun RadioApp(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var full by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = full) { full = false }

    val current = player.current
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        if (full && current != null) {
            PlayerScreen(
                station = current,
                player = player,
                favorite = ui.favorites.any { it.id == current.id },
                onClose = { full = false },
                onToggle = vm::toggle,
                onNext = vm::next,
                onPrevious = vm::previous,
                onFavorite = { vm.toggleFavorite(current) },
            )
        } else {
            BrowseScreen(vm, ui, player, onOpenPlayer = { full = true })
        }
    }
}

// ---------------------------------------------------------------- lista

@Composable
private fun BrowseScreen(
    vm: MainViewModel,
    ui: BrowseState,
    player: PlayerState,
    onOpenPlayer: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SearchField(ui.query, vm::setQuery)
        if (!ui.searching) Sections(ui.section, vm::selectSection)
        HorizontalDivider(color = cs.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = ui.visible
            when {
                (ui.loading && !ui.searching) || (ui.searching && ui.results == null && !ui.error) ->
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(28.dp),
                        color = cs.primary, strokeWidth = 2.dp,
                    )
                ui.error && list.isEmpty() ->
                    Message("Sem conexão com a internet.", "Tentar de novo") { if (ui.searching) vm.setQuery(ui.query) else vm.load() }
                list.isEmpty() -> Message(
                    when {
                        ui.searching -> "Nenhuma emissora encontrada."
                        ui.section == Section.FAVS -> "Toque no coração de uma emissora para guardá-la aqui."
                        else -> "Nenhuma emissora ${ui.section.label} na lista."
                    }
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { s ->
                        StationRow(
                            station = s,
                            selected = s.id == player.current?.id,
                            favorite = ui.favorites.any { it.id == s.id },
                            onPlay = { vm.play(s) },
                            onFavorite = { vm.toggleFavorite(s) },
                        )
                    }
                }
            }
        }

        player.current?.let { MiniPlayer(it, player, vm::toggle, onOpenPlayer) }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Buscar emissora", color = cs.onSurfaceVariant, fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "Limpar busca",
                Modifier.size(20.dp).clickable { onChange("") },
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Sections(selected: Section, onSelect: (Section) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Section.entries.forEach { s ->
            val on = s == selected
            Column(
                Modifier.clickable { onSelect(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    s.label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = if (on) cs.primary else cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.height(2.dp).width(24.dp).background(if (on) cs.primary else Color.Transparent))
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    selected: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp).height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                station.label,
                Modifier.width(68.dp),
                style = Tabular,
                fontSize = if (station.frequency != null) 20.sp else 16.sp,
                fontWeight = if (station.frequency != null) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) cs.primary else cs.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    station.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) cs.primary else cs.onSurface,
                )
                if (station.place.isNotBlank()) {
                    Text(
                        station.place, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            IconButton(onFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (favorite) "Remover das favoritas" else "Adicionar às favoritas",
                    tint = if (favorite) cs.primary else cs.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = cs.outlineVariant)
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = cs.onSurfaceVariant, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                action, color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onAction).padding(12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- player

@Composable
private fun MiniPlayer(station: Station, player: PlayerState, onToggle: () -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = cs.outline)
        Row(
            Modifier.fillMaxWidth().background(cs.surfaceVariant).clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 12.dp).height(68.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                station.label, Modifier.width(68.dp), style = Tabular, color = cs.primary,
                fontSize = if (station.frequency != null) 22.sp else 16.sp, fontWeight = FontWeight.Medium,
            )
            Column(Modifier.weight(1f)) {
                Text(station.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (player.buffering) "Conectando…" else if (player.playing) "No ar" else "Pausado",
                    fontSize = 13.sp, color = cs.onSurfaceVariant,
                )
            }
            PlayButton(44.dp, player, onToggle)
        }
    }
}

@Composable
private fun PlayerScreen(
    station: Station,
    player: PlayerState,
    favorite: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFavorite: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Fechar player", Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            IconButton(onFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (favorite) "Remover das favoritas" else "Adicionar às favoritas",
                    tint = if (favorite) cs.primary else cs.onSurfaceVariant,
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    station.label, style = Tabular, color = cs.primary,
                    fontSize = if (station.frequency != null) 84.sp else 56.sp, fontWeight = FontWeight.Light,
                )
                if (station.frequency != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(station.unit, color = cs.onSurfaceVariant, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Dial(station)
            Spacer(Modifier.height(28.dp))
            Text(station.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (station.place.isNotBlank()) {
                Text(station.place, fontSize = 15.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (player.buffering) "Conectando…" else if (player.playing) "No ar" else "Pausado",
                fontSize = 14.sp, color = cs.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onPrevious, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Emissora anterior", Modifier.size(32.dp)) }
            PlayButton(76.dp, player, onToggle)
            IconButton(onNext, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Próxima emissora", Modifier.size(32.dp)) }
        }
    }
}

/** Escala do dial: a agulha marca a frequência dentro da faixa da banda. */
@Composable
private fun Dial(station: Station) {
    val fm = station.band == Band.FM
    val min = if (fm) 87.5f else 530f
    val max = if (fm) 108f else 1710f
    val f = station.frequency?.toFloatOrNull()
    val needle = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val steps = 60
        for (i in 0..steps) {
            val x = size.width * i / steps
            val h = if (i % 5 == 0) size.height * 0.55f else size.height * 0.3f
            drawLine(tick, Offset(x, size.height - h), Offset(x, size.height), strokeWidth = 2f)
        }
        if (f != null && f in min..max) {
            val x = size.width * (f - min) / (max - min)
            drawLine(needle, Offset(x, 0f), Offset(x, size.height), strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun PlayButton(size: Dp, player: PlayerState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(cs.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (player.buffering) {
            CircularProgressIndicator(Modifier.size(size * 0.45f), color = cs.onPrimary, strokeWidth = 2.5.dp)
        } else {
            Icon(
                if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (player.playing) "Pausar" else "Tocar",
                Modifier.size(size * 0.55f), tint = cs.onPrimary,
            )
        }
    }
}
