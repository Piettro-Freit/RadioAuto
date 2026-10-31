package app.radioauto.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radioauto.data.Station
import coil.compose.AsyncImage

private val LogoTile = Color(0xFFF2F2F2)

@Composable
fun RadioApp(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var full by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = full) { full = false }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshLocal()
    }
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
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (ui.tab) {
                        Tab.HOME -> HomeScreen(vm, ui, player) { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                        Tab.SEARCH -> SearchScreen(vm, ui, player)
                        Tab.FAVS -> FavoritesScreen(vm, ui, player)
                    }
                }
                current?.let { MiniPlayer(it, player, vm::toggle) { full = true } }
                BottomBar(ui.tab, vm::selectTab)
            }
        }
    }
}

// ---------------------------------------------------------------- início

@Composable
private fun HomeScreen(vm: MainViewModel, ui: UiState, player: PlayerState, onAskLocation: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val list = ui.homeList
    val loadingList = ui.topLoading || (ui.genre != null && ui.genreList == null)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text(
                "Rádio", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }

        item { SectionTitle("Perto de você", ui.localLabel) }
        item {
            when (ui.localStatus) {
                LocalStatus.NEED_PERMISSION -> InfoCard(
                    "Veja as rádios da sua região. A localização só é usada para achar emissoras próximas.",
                    "Usar minha localização", onAskLocation,
                )
                LocalStatus.LOADING -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = cs.primary, strokeWidth = 2.dp)
                }
                LocalStatus.EMPTY -> InfoCard(
                    "Não encontrei rádios perto de você. Tente buscar pelo nome da sua cidade.",
                    "Buscar cidade", { vm.selectTab(Tab.SEARCH) },
                )
                LocalStatus.READY -> CardRow(ui.local, player.current?.id) { vm.play(it, ui.local) }
            }
        }

        if (ui.recents.isNotEmpty()) {
            item { SectionTitle("Tocadas recentemente") }
            item { CardRow(ui.recents, player.current?.id) { vm.play(it, ui.recents) } }
        }

        item { SectionTitle("Gêneros") }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip("Todos", ui.genre == null) { vm.selectGenre(null) } }
                items(Genres) { g -> Chip(g.first, ui.genre == g) { vm.selectGenre(g) } }
            }
        }

        item { SectionTitle(ui.genre?.first ?: "Mais ouvidas no Brasil") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BandFilter.entries.forEach { b -> Chip(b.label, ui.bandFilter == b) { vm.selectBand(b) } }
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            loadingList -> item {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = cs.primary, strokeWidth = 2.dp)
                }
            }
            list.isEmpty() -> item {
                if (ui.topError) Message("Sem conexão com a internet.", "Tentar de novo") { vm.loadTop(); vm.selectGenre(ui.genre) }
                else Message("Nenhuma emissora encontrada.")
            }
            else -> items(list, key = { it.id }) { s ->
                StationRow(
                    s, s.id == player.current?.id, ui.favorites.any { it.id == s.id },
                    onPlay = { vm.play(s, list) }, onFavorite = { vm.toggleFavorite(s) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, fontSize = 14.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CardRow(stations: List<Station>, currentId: String?, onPlay: (Station) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(stations, key = { it.id }) { s -> StationCard(s, s.id == currentId) { onPlay(s) } }
    }
}

@Composable
private fun StationCard(s: Station, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(108.dp).clip(shape).clickable(onClick = onClick)) {
        Box {
            Logo(s, 108.dp, radius = 14.dp)
            if (selected) Box(Modifier.size(108.dp).border(2.dp, cs.primary, shape))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            s.name, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (selected) cs.primary else cs.onSurface,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .then(if (selected) Modifier.background(cs.primary) else Modifier.border(1.dp, cs.outline, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (selected) cs.onPrimary else cs.onSurface,
        )
    }
}

@Composable
private fun InfoCard(text: String, action: String, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).padding(16.dp),
    ) {
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            action, color = cs.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primary).clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

// ---------------------------------------------------------------- busca e favoritas

@Composable
private fun SearchScreen(vm: MainViewModel, ui: UiState, player: PlayerState) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        SearchField(ui.query, vm::setQuery)
        HorizontalDivider(color = cs.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val results = ui.results
            when {
                !ui.searching -> Message("Busque pelo nome da rádio, da cidade ou do estado.\nEx.: Franca, Minas Gerais, Jovem Pan.")
                results == null -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(28.dp), color = cs.primary, strokeWidth = 2.dp,
                )
                ui.searchError && results.isEmpty() ->
                    Message("Sem conexão com a internet.", "Tentar de novo") { vm.setQuery(ui.query) }
                results.isEmpty() -> Message("Nenhuma emissora encontrada.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { s ->
                        StationRow(
                            s, s.id == player.current?.id, ui.favorites.any { it.id == s.id },
                            onPlay = { vm.play(s, results) }, onFavorite = { vm.toggleFavorite(s) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(vm: MainViewModel, ui: UiState, player: PlayerState) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Favoritas", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
        )
        if (ui.favorites.isEmpty()) {
            Message("Toque no coração de uma emissora para guardá-la aqui.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.favorites, key = { it.id }) { s ->
                    StationRow(
                        s, s.id == player.current?.id, true,
                        onPlay = { vm.play(s, ui.favorites) }, onFavorite = { vm.toggleFavorite(s) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Rádio, cidade ou estado", color = cs.onSurfaceVariant, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "Limpar busca",
                Modifier.size(20.dp).clickable { onChange("") }, tint = cs.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- componentes

@Composable
private fun StationRow(
    station: Station,
    selected: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp).height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(station, 48.dp, radius = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) cs.primary else cs.onSurface,
            )
            Text(
                station.detail, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = cs.onSurfaceVariant,
            )
        }
        IconButton(onFavorite) {
            Icon(
                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (favorite) "Remover das favoritas" else "Adicionar às favoritas",
                tint = if (favorite) cs.primary else cs.onSurfaceVariant,
            )
        }
    }
}

/** Logo da emissora. Sem logo (ou se ela falhar ao carregar), mostra a inicial do nome. */
@Composable
fun Logo(station: Station, size: Dp, modifier: Modifier = Modifier, radius: Dp = 10.dp) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius)
    val logo = station.logo
    var failed by remember(logo) { mutableStateOf(false) }

    if (logo.isNullOrBlank() || failed) {
        val initial = station.name.removePrefix("Rádio ").trim().firstOrNull()?.uppercase() ?: "R"
        Box(modifier.size(size).clip(shape).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(initial, color = cs.primary, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
        }
    } else {
        // Fundo claro: muitas logos têm fundo transparente e texto escuro.
        AsyncImage(
            model = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = modifier.size(size).clip(shape).background(LogoTile).padding(size * 0.1f),
        )
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = cs.onSurfaceVariant, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                action, color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onAction).padding(12.dp),
            )
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = cs.onPrimary,
        selectedTextColor = cs.primary,
        indicatorColor = cs.primary,
        unselectedIconColor = cs.onSurfaceVariant,
        unselectedTextColor = cs.onSurfaceVariant,
    )
    Column {
        HorizontalDivider(color = cs.outlineVariant)
        NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp) {
            NavigationBarItem(tab == Tab.HOME, { onSelect(Tab.HOME) }, { Icon(Icons.Rounded.Home, null) }, label = { Text("Início") }, colors = colors)
            NavigationBarItem(tab == Tab.SEARCH, { onSelect(Tab.SEARCH) }, { Icon(Icons.Rounded.Search, null) }, label = { Text("Buscar") }, colors = colors)
            NavigationBarItem(tab == Tab.FAVS, { onSelect(Tab.FAVS) }, { Icon(Icons.Rounded.Favorite, null) }, label = { Text("Favoritas") }, colors = colors)
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
                .padding(start = 12.dp, end = 12.dp).height(68.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(station, 46.dp, radius = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusText(player), fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
            PlayButton(44.dp, player, onToggle)
        }
    }
}

private fun statusText(p: PlayerState) = if (p.buffering) "Conectando…" else if (p.playing) "No ar" else "Pausado"

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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
            if (wide) {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    Logo(station, minOf(maxHeight * 0.6f, 300.dp), radius = 20.dp)
                    Column(Modifier.weight(1f)) {
                        PlayerInfo(station, player, Alignment.Start)
                        Spacer(Modifier.height(28.dp))
                        PlayerControls(player, onToggle, onNext, onPrevious, Arrangement.Start)
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Logo(station, minOf(maxWidth - 48.dp, 300.dp), radius = 22.dp)
                    Spacer(Modifier.height(28.dp))
                    PlayerInfo(station, player, Alignment.CenterHorizontally)
                    Spacer(Modifier.weight(1f))
                    PlayerControls(player, onToggle, onNext, onPrevious, Arrangement.SpaceEvenly)
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayerInfo(station: Station, player: PlayerState, align: Alignment.Horizontal) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            station.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (align == Alignment.Start) TextAlign.Start else TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(station.detail, fontSize = 15.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(statusText(player), fontSize = 14.sp, color = cs.primary)
    }
}

@Composable
private fun PlayerControls(
    player: PlayerState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    arrangement: Arrangement.Horizontal,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (arrangement == Arrangement.Start) Arrangement.spacedBy(20.dp) else arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onPrevious, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Emissora anterior", Modifier.size(32.dp)) }
        PlayButton(76.dp, player, onToggle)
        IconButton(onNext, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Próxima emissora", Modifier.size(32.dp)) }
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
