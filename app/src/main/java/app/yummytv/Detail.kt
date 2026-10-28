package app.yummytv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

@Composable
fun DetailScreen(url: String, loggedIn: Boolean, onPlay: (String, String) -> Unit) {
    val (st, retry) = rememberLoad(url) {
        Net.api.anime(url).response ?: error("Пустой ответ сервера")
    }
    LoadView(st, retry) { a -> DetailContent(a, loggedIn, onPlay) }
}

@Composable
private fun DetailContent(a: AnimeDetail, loggedIn: Boolean, onPlay: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val groups = remember(a) {
        a.videos.orEmpty().groupBy { v ->
            val d = v.data?.dubbing?.takeIf { it.isNotBlank() } ?: "Озвучка"
            val p = v.data?.player
            if (p.isNullOrBlank()) d else "$d · $p"
        }
    }
    val names = remember(groups) { groups.keys.toList() }
    var selected by remember(a) { mutableStateOf(names.firstOrNull()) }
    var myList by remember(a) { mutableStateOf<Int?>(null) }

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

    val episodes = remember(selected, groups) {
        groups[selected].orEmpty().sortedBy { it.index ?: it.number?.toDoubleOrNull() ?: 0.0 }
    }

    val meta = listOfNotNull(
        a.type?.name,
        a.year?.toString(),
        a.rating.ratingValue()?.let { "★ " + String.format("%.1f", it) },
        a.anime_status?.title
    ).joinToString(" · ")

    Row(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        AsyncImage(
            model = a.poster?.fullsize.fixUrl() ?: a.poster?.medium.fixUrl(),
            contentDescription = a.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(260.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp))
        )
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(a.title ?: "—", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta, color = TextDim, fontSize = 15.sp)
            Text(a.description.orEmpty(), color = Color(0xFFD0D4DD), fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)

            if (loggedIn) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                    items(ListNames) { (id, name) ->
                        TvButton(name, selected = myList == id) {
                            scope.launch {
                                try {
                                    Net.api.setList(a.anime_id, SetListBody(id, System.currentTimeMillis() / 1000))
                                    myList = id
                                    Toast.makeText(ctx, "Список: $name", Toast.LENGTH_SHORT).show()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    Toast.makeText(ctx, errText(e), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    items(listOf("Убрать из списков")) { label ->
                        TvButton(label) {
                            scope.launch {
                                try {
                                    Net.api.removeFromList(a.anime_id)
                                    myList = null
                                    Toast.makeText(ctx, "Убрано из списков", Toast.LENGTH_SHORT).show()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    Toast.makeText(ctx, errText(e), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }

            if (names.isEmpty()) {
                Text("Для этого аниме нет доступных серий", color = TextDim, fontSize = 16.sp)
            } else {
                Text("Озвучка", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                    items(names) { n ->
                        TvButton(n, selected = n == selected) { selected = n }
                    }
                }
                Text("Серии", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(130.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(episodes) { v ->
                        TvButton("Серия ${v.number ?: "?"}", Modifier.fillMaxWidth(), fill = true) {
                            val link = v.iframe_url.fixUrl()
                            if (link != null) onPlay("${a.title ?: ""} — серия ${v.number ?: ""}", link)
                        }
                    }
                }
            }
        }
    }
}
