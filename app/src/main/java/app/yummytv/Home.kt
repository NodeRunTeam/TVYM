package app.yummytv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import java.util.Calendar

/** Последняя серия, на которой пользователь остановился (по данным сайта) */
class ServerLast(val ep: String, val date: Long, val videoId: Long)

object Cache {
    val serverLast = HashMap<Long, ServerLast>()

    /** Меняется при каждом обновлении serverLast — по нему экран аниме перерисовывается */
    var serverVersion by androidx.compose.runtime.mutableIntStateOf(0)

    fun noteServerLast(animeId: Long, ep: String, date: Long, videoId: Long) {
        if (animeId == 0L || ep.isBlank()) return
        val old = serverLast[animeId]
        if (old == null || date >= old.date) {
            serverLast[animeId] = ServerLast(ep.trim(), date, videoId)
            serverVersion++
        }
    }

    val home = TtlCache<String, List<CardData>>(10 * 60 * 1000L)
    val top = TtlCache<String, List<CardData>>(30 * 60 * 1000L)
    val lists = TtlCache<Int, List<CardData>>(2 * 60 * 1000L)
    val details = TtlCache<String, AnimeDetail>(5 * 60 * 1000L)
    val genres = TtlCache<String, List<Genre>>(60 * 60 * 1000L)

    // Какое значение sort_forward даёт нужный порядок (в документации не описано — определяем сами)
    val forward = HashMap<String, Boolean>()

    fun clearUser() {
        serverLast.clear()
        home.remove("watching")
        home.remove("recommends")
        lists.clear()
        details.clear()
    }
}

/**
 * Запрашивает отсортированный список. Направление sort_forward в swagger не описано,
 * поэтому при первом запросе проверяем порядок по значению [value] и при необходимости
 * переключаем направление (результат запоминаем).
 */
suspend fun loadSorted(
    key: String,
    ascending: Boolean,
    value: (AnimeCard) -> Double?,
    req: suspend (Boolean) -> List<AnimeCard>
): List<AnimeCard> {
    val known = Cache.forward[key]
    if (known != null) return req(known)
    val first = req(ascending)
    val v = first.mapNotNull(value)
    if (v.size < 2) return first
    val ok = if (ascending) v.first() <= v.last() else v.first() >= v.last()
    if (ok) {
        Cache.forward[key] = ascending
        return first
    }
    Cache.forward[key] = !ascending
    return req(!ascending)
}

// ---------- данные главной ----------

/** (значение для API, название в родительном падеже) по текущей дате */
fun currentSeason(): Triple<String, String, Int> {
    val c = Calendar.getInstance()
    val year = c.get(Calendar.YEAR)
    return when (c.get(Calendar.MONTH)) {
        0, 1, 2 -> Triple("winter", "зимнего", year)
        3, 4, 5 -> Triple("spring", "весеннего", year)
        6, 7, 8 -> Triple("summer", "летнего", year)
        else -> Triple("fall", "осеннего", year)
    }
}

suspend fun loadSeason(): List<CardData> {
    val (season, _, year) = currentSeason()
    suspend fun fetch(s: String): List<AnimeCard> =
        loadSorted("season-views", ascending = false, value = { it.views?.toDouble() }) { fwd ->
            Net.api.animeList(sort = "views", sortForward = fwd, season = s, fromYear = year, toYear = year, limit = 30)
                .response.orEmpty()
        }

    var list = fetch(season)
    if (list.isEmpty() && season == "fall") list = fetch("autumn") // в swagger встречаются оба варианта
    return list.map { it.toCard() }
}

/** Персональные рекомендации сайта (блок recommends в /feed, только для вошедшего пользователя) */
suspend fun loadRecommends(): List<CardData> =
    Cache.home.get("recommends") ?: Cache.home.put(
        "recommends",
        Net.api.feed().response?.recommends.orEmpty().map { it.toCard() }
    )

/** Похожие аниме (сначала подборка сайта, если пусто — рекомендации пользователей) */
suspend fun loadSimilar(animeId: Long, exclude: Set<String>): List<CardData> {
    val key = "similar-$animeId"
    val all = Cache.home.get(key) ?: run {
        val ai = Net.api.recommendations(animeId, 24, true).response.orEmpty()
        val list = if (ai.isNotEmpty()) ai else Net.api.recommendations(animeId, 24, false).response.orEmpty()
        Cache.home.put(key, list.map { it.toCard() })
    }
    return all.filter { it.url !in exclude }
}

/** Анонсы: сначала блок announcements из ленты сайта, если пусто — каталог со статусом «анонс» */
suspend fun loadAnnouncements(): List<CardData> {
    val fromFeed = try {
        Net.api.feed().response?.announcements.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyList()
    }
    val list = fromFeed.ifEmpty {
        Net.api.animeList(sort = "views", sortForward = false, status = "announcement", limit = 30).response.orEmpty()
    }
    return list.map { it.toCard() }
}

suspend fun loadWatching(): List<CardData> {
    fun LastWatch.toCard(): CardData {
        val ep = ep_title?.trim().orEmpty()
        val n = ep.toIntOrNull()
        val d = duration
        val t = end_time
        // серия досмотрена почти до конца -> «Просмотрено серий: N», иначе «Продолжить с N серии»
        val finished = d != null && t != null && d > 0 && t >= d * 0.9
        val sub = when {
            n != null && finished -> "Просмотрено серий: $n"
            n != null -> "Продолжить с $n серии"
            ep.isNotEmpty() -> ep
            else -> null
        }
        return CardData(
            title = title ?: "—",
            subtitle = sub,
            poster = poster?.medium.fixUrl(),
            url = anime_url ?: anime_id.toString(),
            progress = if (d != null && t != null && d > 0) (t / d).toFloat().coerceIn(0f, 1f) else null
        )
    }

    val direct = try {
        Net.api.lastWatches().response
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }
    val list = if (!direct.isNullOrEmpty()) direct else Net.api.feed().response?.last_watches.orEmpty()
    // запоминаем, где остановились: это нужно странице аниме («Смотреть N серию») и отметкам ✓
    for (w in list) {
        Cache.noteServerLast(w.anime_id, w.ep_title.orEmpty(), (w.date ?: 0.0).toLong(), w.video_id)
    }
    Watched.addAll(list.map { it.video_id }.filter { it != 0L })
    return list.map { it.toCard() }
}

// ---------- экран ----------

@Composable
fun CardRow(
    title: String,
    state: Load<List<CardData>>,
    retry: () -> Unit,
    onOpen: (String) -> Unit
) {
    Column {
        SectionTitle(title, Modifier.padding(start = hPad()))
        when (state) {
            is Load.Loading -> SkeletonRow()
            is Load.Err -> Row(
                Modifier.padding(horizontal = hPad(), vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(state.msg, color = TextDim, fontSize = 16.sp)
                TvButton("Повторить", onClick = retry)
            }
            is Load.Ok -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = hPad(), end = hPad(), top = 16.dp, bottom = 20.dp)
            ) {
                items(state.v) { c ->
                    PosterCard(c, Modifier.width(cardW())) { onOpen(c.url) }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    loggedIn: Boolean = false
) {
    val (watching, retryWatching) = rememberLoad<List<CardData>>(null) {
        Cache.home.get("watching") ?: Cache.home.put("watching", loadWatching())
    }
    val (season, retrySeason) = rememberLoad<List<CardData>>(null) {
        Cache.home.get("season") ?: Cache.home.put("season", loadSeason())
    }
    val (announce, retryAnnounce) = rememberLoad<List<CardData>>(null) {
        Cache.home.get("announcements") ?: Cache.home.put("announcements", loadAnnouncements())
    }
    val (recs, retryRecs) = rememberLoad<List<CardData>>(loggedIn) {
        if (loggedIn) loadRecommends() else emptyList()
    }
    val seasonTitle = "Аниме ${currentSeason().second} сезона"

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Блок «Продолжить просмотр» скрываем, если смотреть нечего (например, гость)
        val emptyWatching = watching is Load.Ok && watching.v.isEmpty()
        if (!emptyWatching) {
            item { CardRow("Продолжить просмотр", watching, retryWatching, onOpen) }
        }
        item { CardRow(seasonTitle, season, retrySeason, onOpen) }
        // Анонсы: при ошибке или пустом списке блок скрываем
        val hideAnnounce = announce is Load.Err || (announce is Load.Ok && announce.v.isEmpty())
        if (!hideAnnounce) {
            item { CardRow("Анонсы", announce, retryAnnounce, onOpen) }
        }
        // Рекомендации — только после входа; при ошибке или пустом списке блок скрываем
        if (loggedIn) {
            val hideRecs = recs is Load.Err || (recs is Load.Ok && recs.v.isEmpty())
            if (!hideRecs) {
                item { CardRow("Рекомендации для вас", recs, retryRecs, onOpen) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
