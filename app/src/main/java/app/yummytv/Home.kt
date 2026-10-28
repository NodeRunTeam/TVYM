package app.yummytv

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
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

    fun noteServerLast(animeId: Long, ep: String, date: Long, videoId: Long) {
        if (animeId == 0L || ep.isBlank()) return
        val old = serverLast[animeId]
        if (old == null || date >= old.date) serverLast[animeId] = ServerLast(ep.trim(), date, videoId)
    }

    val home = TtlCache<String, List<CardData>>(10 * 60 * 1000L)
    val top = TtlCache<String, List<CardData>>(30 * 60 * 1000L)
    val lists = TtlCache<Int, List<CardData>>(2 * 60 * 1000L)
    val details = TtlCache<String, AnimeDetail>(5 * 60 * 1000L)

    // Какое значение sort_forward даёт нужный порядок (в документации не описано — определяем сами)
    val forward = HashMap<String, Boolean>()

    fun clearUser() {
        serverLast.clear()
        home.remove("watching")
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
            url = anime_url ?: anime_id.toString()
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
private fun CardRow(
    title: String,
    state: Load<List<CardData>>,
    retry: () -> Unit,
    onOpen: (String) -> Unit
) {
    Column {
        Text(
            title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp)
        )
        when (state) {
            is Load.Loading -> Box(
                Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Accent) }
            is Load.Err -> Row(
                Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(state.msg, color = TextDim, fontSize = 16.sp)
                TvButton("Повторить", onClick = retry)
            }
            is Load.Ok -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 12.dp)
            ) {
                items(state.v) { c ->
                    PosterCard(c, Modifier.width(150.dp)) { onOpen(c.url) }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val (watching, retryWatching) = rememberLoad<List<CardData>>(null) {
        Cache.home.get("watching") ?: Cache.home.put("watching", loadWatching())
    }
    val (season, retrySeason) = rememberLoad<List<CardData>>(null) {
        Cache.home.get("season") ?: Cache.home.put("season", loadSeason())
    }
    val seasonTitle = "Аниме ${currentSeason().second} сезона"

    LazyColumn(
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Блок «Продолжить просмотр» скрываем, если смотреть нечего (например, гость)
        item { CardRow(seasonTitle, season, retrySeason, onOpen) }
        val emptyWatching = watching is Load.Ok && watching.v.isEmpty()
        if (!emptyWatching) {
            item { CardRow("Продолжить просмотр", watching, retryWatching, onOpen) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
