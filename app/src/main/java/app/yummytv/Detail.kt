package app.yummytv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Pick { Player, Dub, Episode, ListMenu }

private fun VideoItem.playerName(): String = data?.player?.takeIf { it.isNotBlank() } ?: "Плеер"
private fun VideoItem.dubName(): String = data?.dubbing?.takeIf { it.isNotBlank() } ?: "Озвучка"
private fun VideoItem.sortKey(): Double = index ?: number?.toDoubleOrNull() ?: 0.0

@Composable
fun DetailScreen(
    url: String,
    loggedIn: Boolean,
    onOpen: (String) -> Unit,
    onPlay: (Screen.Player) -> Unit
) {
    val (st, retry) = rememberLoad(url) {
        Cache.details.get(url)
            ?: Cache.details.put(url, Net.api.anime(url).response ?: error("Пустой ответ сервера"))
    }
    LoadView(st, retry) { a -> DetailContent(a, loggedIn, onOpen, onPlay) }
}

@Composable
private fun SelectorButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(10.dp)) {
        Column(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(label, color = TextDim, fontSize = 12.sp)
            Text(
                "$value  ▾",
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DetailContent(
    a: AnimeDetail,
    loggedIn: Boolean,
    onOpen: (String) -> Unit,
    onPlay: (Screen.Player) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val videos = remember(a) { a.videos.orEmpty() }
    val players = remember(videos) { videos.map { it.playerName() }.distinct() }

    val saved = remember(a) { Watched.choice(a.anime_id) }
    // Плеер по умолчанию всегда Alloha (выбор плеера не запоминаем), запоминаем только озвучку
    var playerSel by remember(a) { mutableStateOf<String?>(null) }
    var dubSel by remember(a) { mutableStateOf(saved.second) }
    var epSel by remember(a) { mutableStateOf<String?>(null) }
    var sheet by remember(a) { mutableStateOf<Pick?>(null) }
    var myList by remember(a) { mutableStateOf<Int?>(null) }

    // Плеер -> озвучка -> серия: каждый следующий список зависит от предыдущего выбора
    val player = playerSel?.takeIf { it in players }
        ?: players.firstOrNull { it.contains("alloha", ignoreCase = true) }
        ?: players.firstOrNull()
    val isKodik = player?.contains("kodik", ignoreCase = true) == true
    val dubs = remember(videos, player) {
        videos.filter { it.playerName() == player }.map { it.dubName() }.distinct()
    }
    val dub = dubSel?.takeIf { it in dubs } ?: dubs.firstOrNull()
    val episodes = remember(videos, player, dub) {
        videos.filter { it.playerName() == player && it.dubName() == dub }.sortedBy { it.sortKey() }
    }

    val watched = Watched.ids
    val ep = episodes.firstOrNull { it.number == epSel }
        ?: episodes.firstOrNull { it.video_id !in watched }
        ?: episodes.firstOrNull()
    val hasWatched = episodes.any { it.video_id in watched }

    LaunchedEffect(a.anime_id, loggedIn) {
        if (loggedIn) {
            try {
                myList = Net.api.myList(a.anime_id).response?.list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
            }
        }
    }

    fun play(v: VideoItem) {
        val link = v.iframe_url.fixUrl() ?: return
        epSel = v.number
        Watched.add(v.video_id)
        if (loggedIn) appScope.launch { syncWatched(v.video_id, v.duration) }
        onPlay(
            Screen.Player(
                title = "${a.title ?: ""} — серия ${v.number ?: ""}",
                url = link,
                kodik = isKodik,
                episodes = episodes,
                videoId = v.video_id
            )
        )
    }

    // «Порядок просмотра»: все части франшизы по порядку (если в ней больше одной части)
    val order = remember(a) { a.viewing_order.orEmpty() }
    val showOrder = order.isNotEmpty() && !(order.size == 1 && order[0].anime_id == a.anime_id)

    val meta = listOfNotNull(
        a.type?.name,
        a.year?.toString(),
        a.rating.ratingValue()?.let { "★ " + it.fmt() },
        a.anime_status?.title
    ).joinToString(" · ")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Box(Modifier.width(240.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp))) {
                    AsyncImage(
                        model = a.poster?.fullsize.fixUrl() ?: a.poster?.medium.fixUrl(),
                        contentDescription = a.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    a.rating.ratingValue()?.takeIf { it > 0.0 }
                        ?.let { RatingBadge(it, Modifier.align(Alignment.TopStart)) }
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        a.title ?: "—",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(meta, color = TextDim, fontSize = 15.sp)
                    Text(
                        a.description.orEmpty(),
                        color = Color(0xFFD0D4DD),
                        fontSize = 14.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (videos.isEmpty()) {
                        Text("Для этого аниме нет доступных серий", color = TextDim, fontSize = 16.sp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SelectorButton("Плеер", player ?: "—", Modifier.widthIn(min = 150.dp, max = 260.dp)) {
                                sheet = Pick.Player
                            }
                            SelectorButton("Озвучка", dub ?: "—", Modifier.widthIn(min = 180.dp, max = 340.dp)) {
                                sheet = Pick.Dub
                            }
                            SelectorButton(
                                "Серия",
                                ep?.let { "№ ${it.number ?: "?"}" } ?: "—",
                                Modifier.widthIn(min = 130.dp, max = 220.dp)
                            ) { sheet = Pick.Episode }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (ep != null) {
                            AccentButton(
                                (if (hasWatched) "▶  Продолжить — серия " else "▶  Смотреть — серия ") + (ep.number ?: "")
                            ) { play(ep) }
                        }
                        if (loggedIn) {
                            val current = ListNames.firstOrNull { it.first == myList }?.second ?: "Добавить в список"
                            TvButton(current + "  ▾") { sheet = Pick.ListMenu }
                        }
                    }
                }
            }
        }

        if (showOrder) {
            item {
                Text(
                    "Порядок просмотра",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)
                )
            }
            itemsIndexed(order) { i, o ->
                val isCurrent = o.anime_id == a.anime_id
                AnimeRow(o.toCard(i + 1), odd = i % 2 == 1, current = isCurrent) {
                    if (!isCurrent) onOpen(o.anime_url ?: o.anime_id.toString())
                }
            }
        }
    }

    // ---------- выезжающие меню ----------
    when (sheet) {
        Pick.Player -> SideSheet(
            title = "Плеер",
            items = players.map { SheetItem(it, selected = it == player) },
            onDismiss = { sheet = null }
        ) { i ->
            playerSel = players[i]
            Watched.setChoice(a.anime_id, null, dubSel)
            sheet = null
        }
        Pick.Dub -> SideSheet(
            title = "Озвучка",
            items = dubs.map { SheetItem(it, selected = it == dub) },
            onDismiss = { sheet = null }
        ) { i ->
            dubSel = dubs[i]
            Watched.setChoice(a.anime_id, player, dubSel)
            sheet = null
        }
        Pick.Episode -> SideSheet(
            title = "Серия",
            items = episodes.map {
                SheetItem(
                    "Серия ${it.number ?: "?"}",
                    selected = it.number == ep?.number,
                    mark = if (it.video_id in watched) "✓ просмотрено" else null
                )
            },
            onDismiss = { sheet = null }
        ) { i ->
            sheet = null
            play(episodes[i])
        }
        Pick.ListMenu -> SideSheet(
            title = "Список",
            items = ListNames.map { SheetItem(it.second, selected = it.first == myList) } +
                SheetItem("Убрать из списков"),
            onDismiss = { sheet = null }
        ) { i ->
            sheet = null
            scope.launch {
                try {
                    if (i < ListNames.size) {
                        val (id, name) = ListNames[i]
                        Net.api.setList(a.anime_id, SetListBody(id, System.currentTimeMillis() / 1000))
                        myList = id
                        Toast.makeText(ctx, "Список: $name", Toast.LENGTH_SHORT).show()
                    } else {
                        Net.api.removeFromList(a.anime_id)
                        myList = null
                        Toast.makeText(ctx, "Убрано из списков", Toast.LENGTH_SHORT).show()
                    }
                    Cache.lists.clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Toast.makeText(ctx, errText(e), Toast.LENGTH_LONG).show()
                }
            }
        }
        null -> {}
    }
}
