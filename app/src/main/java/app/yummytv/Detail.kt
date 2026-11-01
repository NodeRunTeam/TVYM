package app.yummytv

import androidx.compose.foundation.layout.height
import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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

private enum class Pick { Player, Dub, Episode, ListMenu, Order }

private fun VideoItem.playerName(): String = data?.player?.takeIf { it.isNotBlank() } ?: "Плеер"
private fun VideoItem.dubName(): String = data?.dubbing?.takeIf { it.isNotBlank() } ?: "Озвучка"
private val EpNumRe = Regex("\\d+(?:\\.\\d+)?")

/** Номер серии из строки вида «5», «5 серия», «Серия 5», «5.5» */
private fun epKey(s: String?): Double? = s?.let { EpNumRe.find(it)?.value?.toDoubleOrNull() }

private fun sameEp(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    if (a.trim() == b.trim()) return true
    val x = epKey(a)
    return x != null && x == epKey(b)
}

private fun VideoItem.sortKey(): Double = index ?: number?.toDoubleOrNull() ?: 0.0

@Composable
fun DetailScreen(
    url: String,
    loggedIn: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (Screen.Player) -> Unit
) {
    val (st, retry) = rememberLoad(url) {
        Cache.details.get(url)
            ?: Cache.details.put(url, Net.api.anime(url).response ?: error("Пустой ответ сервера"))
    }
    LoadView(st, retry) { a -> DetailContent(a, loggedIn, onBack, onOpen, onPlay) }
}

@Composable
private fun SelectorButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(14.dp)) { focused ->
        Column(
            Modifier.fillMaxWidth()
                .background(if (focused) GlassHi else Glass)
                .padding(horizontal = 16.dp, vertical = 10.dp)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    a: AnimeDetail,
    loggedIn: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (Screen.Player) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val setBackdrop = LocalBackdrop.current
    val listState = rememberLazyListState()
    // фон страницы — постер этого аниме
    LaunchedEffect(a.anime_id) { setBackdrop(a.poster?.medium.fixUrl()) }

    val videos = remember(a) { a.videos.orEmpty() }
    val players = remember(videos) { videos.map { it.playerName() }.distinct() }

    val saved = remember(a) { Watched.choice(a.anime_id) }
    // Плеер по умолчанию: Alloha; если в ней нет серий — следующий по списку (Kodik), потом остальные.
    // Выбор плеера не запоминаем, запоминаем только озвучку
    var playerSel by remember(a) { mutableStateOf<String?>(null) }
    var dubSel by remember(a) { mutableStateOf(saved.second) }
    var epSel by remember(a) { mutableStateOf<String?>(null) }
    var sheet by remember(a) { mutableStateOf<Pick?>(null) }
    var myList by remember(a) { mutableStateOf<Int?>(null) }

    // Плеер -> озвучка -> серия: каждый следующий список зависит от предыдущего выбора
    // Плеер по умолчанию — Kodik; если его нет, то Alloha, затем первый из списка
    val player = playerSel?.takeIf { it in players }
        ?: players.firstOrNull { it.contains("kodik", ignoreCase = true) }
        ?: players.firstOrNull { it.contains("alloha", ignoreCase = true) }
        ?: players.firstOrNull()
    val isKodik = player?.contains("kodik", ignoreCase = true) == true
    val dubs = remember(videos, player) {
        videos.filter { it.playerName() == player }.map { it.dubName() }.distinct()
    }
    // Озвучка по умолчанию — AniLibria (если есть), иначе первая
    val dub = dubSel?.takeIf { it in dubs }
        ?: dubs.firstOrNull { it.contains("anilibria", true) || it.contains("анилибри", true) || it.contains("aniliberty", true) }
        ?: dubs.firstOrNull()
    val episodes = remember(videos, player, dub) {
        videos.filter { it.playerName() == player && it.dubName() == dub }.sortedBy { it.sortKey() }
    }

    val watched = Watched.ids
    // Серия по умолчанию — та, на которой остановились: последняя открытая,
    // иначе самая дальняя из просмотренных (например, из истории на сайте)
    // Берём более свежее: отметка на этом устройстве или данные сайта («Продолжить просмотр»)
    val localNum = remember(a) { Watched.lastEp(a.anime_id) }
    val localTime = remember(a) { Watched.lastEpTime(a.anime_id) }
    val serverVer = Cache.serverVersion // подписка: страница обновится, когда придут данные сайта
    val server = Cache.serverLast[a.anime_id]
    val lastNum: String? = when {
        localNum != null && (server == null || localTime >= server.date) -> localNum
        server != null -> server.ep
        else -> null
    }
    val resumeEp = episodes.firstOrNull { lastNum != null && sameEp(it.number, lastNum) }
        ?: episodes.filter { it.video_id in watched }.maxByOrNull { it.sortKey() }
    val ep = episodes.firstOrNull { it.number == epSel } ?: resumeEp ?: episodes.firstOrNull()
    val epNumber = ep?.number.orEmpty()
    val epInt = epNumber.toIntOrNull()
    val playLabel = when {
        ep == null || resumeEp == null -> "Смотреть"
        epInt != null -> "Смотреть $epInt серию"
        else -> "Смотреть $epNumber".trim()
    }

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

    // каждый раз при открытии аниме спрашиваем у сайта, на какой серии остановились (в т.ч. на других устройствах)
    LaunchedEffect(a.anime_id, loggedIn) {
        if (loggedIn) {
            try {
                Net.api.lastWatches().response.orEmpty().forEach { w ->
                    Cache.noteServerLast(w.anime_id, w.ep_title.orEmpty(), (w.date ?: 0.0).toLong(), w.video_id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
            }
        }
    }

    fun play(v: VideoItem) {
        val link = v.iframe_url.fixUrl() ?: return
        epSel = v.number
        Watched.setLastEp(a.anime_id, v.number)
        Watched.add(v.video_id)
        Cache.noteServerLast(a.anime_id, v.number.orEmpty(), System.currentTimeMillis() / 1000, v.video_id)
        if (loggedIn) appScope.launch { syncWatched(v.video_id, v.duration) }
        onPlay(
            Screen.Player(
                title = "${a.title ?: ""} — серия ${v.number ?: ""}",
                url = link,
                kodik = isKodik,
                episodes = episodes,
                videoId = v.video_id,
                animeId = a.anime_id
            )
        )
    }

    // «Порядок просмотра»: все части франшизы по порядку (если в ней больше одной части)
    val order = remember(a) { a.viewing_order.orEmpty() }
    val showOrder = order.isNotEmpty() && !(order.size == 1 && order[0].anime_id == a.anime_id)

    // Если частей много — показываем только ближайшие к текущей, остальные в боковом меню
    val orderShown: List<Int> = remember(order, a) {
        if (order.size <= 6) {
            order.indices.toList()
        } else {
            val cur = order.indexOfFirst { it.anime_id == a.anime_id }.let { if (it < 0) 2 else it }
            var from = (cur - 2).coerceAtLeast(0)
            var to = from + 5
            if (to > order.size) {
                to = order.size
                from = to - 5
            }
            (from until to).toList()
        }
    }

    // Похожие аниме: грузятся отдельно, страницу не задерживают
    val exclude = remember(a) { (order.mapNotNull { it.anime_url } + listOfNotNull(a.anime_url)).toSet() }
    val (similar, retrySimilar) = rememberLoad<List<CardData>>(a.anime_id) { loadSimilar(a.anime_id, exclude) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = if (isCompact()) 12.dp else 52.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = hPad()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTvButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Назад",
                    modifier = Modifier.onFocusChanged {
                        // вернулись к кнопке «Назад» — возвращаем страницу наверх, чтобы она не была обрезана
                        if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) }
                    },
                    onClick = onBack
                )
                val posterBox: @Composable (Modifier) -> Unit = { m ->
                    Box(
                        m
                            .aspectRatio(0.7f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Panel)
                    ) {
                        AsyncImage(
                            model = a.poster?.fullsize.fixUrl() ?: a.poster?.medium.fixUrl(),
                            contentDescription = a.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        a.rating.ratingValue()?.takeIf { it > 0.0 }
                            ?.let { RatingBadge(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
                    }
                }
                val infoContent: @Composable () -> Unit = {
                        Text(
                            a.title ?: "—",
                            color = Color.White,
                            fontSize = if (isCompact()) 24.sp else 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            a.type?.name?.let { Chip(it) }
                            a.year?.let { Chip(it.toString()) }
                            a.rating.ratingValue()?.takeIf { it > 0.0 }
                                ?.let { Chip("★ " + it.fmt(), color = Gold, bg = Gold.copy(alpha = 0.16f)) }
                            a.anime_status?.title?.takeIf { it.isNotBlank() }?.let {
                                val c = when (a.anime_status?.alias) {
                                    "ongoing" -> Accent2
                                    "released" -> Green
                                    else -> TextDim
                                }
                                Chip(it, color = c, bg = c.copy(alpha = 0.16f))
                            }
                        }
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
                                SelectorButton(
                                    "Плеер",
                                    player ?: "—",
                                    Modifier.weight(1f)
                                ) {
                                    sheet = Pick.Player
                                }
                                SelectorButton("Озвучка", dub ?: "—", Modifier.weight(1f)) {
                                    sheet = Pick.Dub
                                }
                            }
                        }

                        // Смотреть N серию | Список серий | Смотрю (цвет зависит от списка)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (ep != null) {
                                AccentButton(playLabel, icon = Icons.Default.PlayArrow) { play(ep) }
                                TvButton("Список серий") { sheet = Pick.Episode }
                            }
                            if (loggedIn) {
                                val current = ListNames.firstOrNull { it.first == myList }?.second ?: "Добавить в список"
                                TvButton(current + "  ▾", tint = myList?.let { ListColors[it] }) {
                                    sheet = Pick.ListMenu
                                }
                            }
                        }
                }
                if (isCompact()) {
                    // телефон: постер сверху по центру, ниже вся информация
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        posterBox(Modifier.width(170.dp))
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            infoContent()
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        posterBox(Modifier.width(240.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            infoContent()
                        }
                    }
                }
            }
        }

        if (showOrder) {
            item {
                SectionTitle(
                    "Порядок просмотра",
                    Modifier.padding(start = hPad(), end = hPad(), top = 24.dp, bottom = 8.dp)
                )
            }
            items(orderShown) { idx ->
                val o = order[idx]
                val isCurrent = o.anime_id == a.anime_id
                Box(Modifier.padding(horizontal = hPad())) {
                    AnimeRow(o.toCard(idx + 1), odd = idx % 2 == 1, current = isCurrent) {
                        if (!isCurrent) onOpen(o.anime_url ?: o.anime_id.toString())
                    }
                }
            }
            if (order.size > orderShown.size) {
                item {
                    Box(Modifier.padding(horizontal = hPad(), vertical = 4.dp)) {
                        TvButton("Показать все части (${order.size})", fill = true) { sheet = Pick.Order }
                    }
                }
            }
        }

        // «Похожие аниме»: пока грузятся — заглушки, при ошибке или пустом списке блок скрыт
        if (similar is Load.Loading || (similar is Load.Ok && similar.v.isNotEmpty())) {
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    CardRow("Похожие аниме", similar, retrySimilar, onOpen)
                }
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
        Pick.Order -> SideSheet(
            title = "Порядок просмотра",
            items = order.mapIndexed { i, o ->
                SheetItem(
                    "${i + 1}. ${o.title ?: "—"}",
                    selected = o.anime_id == a.anime_id,
                    mark = o.year?.toString()
                )
            },
            onDismiss = { sheet = null }
        ) { i ->
            sheet = null
            val o = order[i]
            if (o.anime_id != a.anime_id) onOpen(o.anime_url ?: o.anime_id.toString())
        }
        null -> {}
    }
}
