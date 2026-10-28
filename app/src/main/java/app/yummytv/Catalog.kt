package app.yummytv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

// ---------- загрузка ----------

suspend fun loadCatalogPage(offset: Int): List<CardData> =
    loadSorted("catalog-views", ascending = false, value = { it.views?.toDouble() }) { fwd ->
        Net.api.animeList(sort = "views", sortForward = fwd, limit = 40, offset = offset).response.orEmpty()
    }.map { it.toCard() }

suspend fun loadTop(type: String): List<CardData> {
    val all = ArrayList<AnimeCard>()
    for (page in 0 until 2) {
        val offset = page * 50
        val part = loadSorted("top", ascending = true, value = { it.top?.category?.toDouble() }) { fwd ->
            Net.api.animeList(sort = "top", sortForward = fwd, types = type, limit = 50, offset = offset)
                .response.orEmpty()
        }
        all.addAll(part)
        if (part.size < 50) break
    }
    return all.mapIndexed { i, a -> a.toCard(rank = i + 1) }
}

// ---------- сетка с подгрузкой страниц ----------

@Composable
fun PagedGrid(
    key: Any?,
    onOpen: (String) -> Unit,
    pageSize: Int = 40,
    fetch: suspend (Int) -> List<CardData>
) {
    val items = remember(key) { mutableStateListOf<CardData>() }
    var loading by remember(key) { mutableStateOf(false) }
    var end by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var request by remember(key) { mutableIntStateOf(0) }

    LaunchedEffect(key, request) {
        loading = true
        error = null
        try {
            val page = fetch(items.size)
            items.addAll(page)
            if (page.size < pageSize) end = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = errText(e)
        } finally {
            loading = false
        }
    }

    if (items.isEmpty() && loading) {
        SkeletonGrid()
        return
    }
    if (items.isEmpty() && error != null) {
        Column(
            Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(error ?: "", color = Color.White, fontSize = 18.sp)
            TvButton("Повторить", Modifier.padding(top = 16.dp)) { request = request + 1 }
        }
        return
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ничего не найдено", color = TextDim, fontSize = 18.sp)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { c ->
            PosterCard(c, Modifier.fillMaxWidth()) { onOpen(c.url) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(color = Accent)
                    error != null -> TvButton("Ошибка — повторить") { request = request + 1 }
                    !end -> TvButton("Показать ещё") { request = request + 1 }
                }
            }
        }
    }
}

// ---------- экраны ----------

@Composable
fun CatalogScreen(onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            singleLine = true,
            placeholder = { Text("Найти аниме по названию") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextDim) },
            shape = RoundedCornerShape(16.dp),
            colors = tvFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = q.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp)
        )
        PagedGrid(key = submitted, onOpen = onOpen) { offset ->
            val s = submitted
            if (s.isBlank()) {
                loadCatalogPage(offset)
            } else {
                Net.api.search(s, 40, offset).response.orEmpty().map { it.toCard() }
            }
        }
    }
}

private val TopTabs = listOf("Сериалы" to "tv", "Фильмы" to "movie", "ONA" to "ona")

@Composable
fun TopScreen(onOpen: (String) -> Unit) {
    var sel by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Топ-100 аниме", Modifier.padding(end = 16.dp))
            TopTabs.forEachIndexed { i, tab ->
                TvButton(tab.first, selected = sel == i) { sel = i }
            }
        }
        val type = TopTabs[sel].second
        val (st, retry) = rememberLoad(type) {
            Cache.top.get(type) ?: Cache.top.put(type, loadTop(type))
        }
        LoadView(st, retry, skeleton = { SkeletonGrid() }) { items -> CardGrid(items, onOpen) }
    }
}
