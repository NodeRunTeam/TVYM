package app.yummytv

import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
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

/** Каталог с фильтром по жанрам (и, при желании, с текстом) */
suspend fun loadFiltered(offset: Int, q: String, genres: List<Int>): List<CardData> =
    loadSorted("catalog-views", ascending = false, value = { it.views?.toDouble() }) { fwd ->
        Net.api.animeList(
            sort = "views",
            sortForward = fwd,
            genres = genres,
            q = q.ifBlank { null },
            limit = 40,
            offset = offset
        ).response.orEmpty()
    }.map { it.toCard() }

suspend fun loadGenres(): List<Genre> =
    Cache.genres.get("all") ?: Cache.genres.put(
        "all",
        Net.api.genres().response?.genres.orEmpty()
            .filter { it.value != null && !it.title.isNullOrBlank() }
            .sortedBy { it.title.orEmpty() }
    )

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

/** Поле поиска: сначала это обычная кнопка, по нажатию превращается в поле ввода */
@Composable
private fun SearchBox(
    value: String,
    onValue: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }

    if (editing) {
        LaunchedEffect(Unit) {
            delay(100)
            try {
                fieldFocus.requestFocus()
            } catch (e: Exception) {
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            placeholder = { Text("Найти аниме по названию") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextDim) },
            shape = RoundedCornerShape(16.dp),
            colors = tvFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                hadFocus = false
                restore = true
                editing = false
                onSubmit()
            }),
            modifier = modifier
                .focusRequester(fieldFocus)
                .onFocusChanged {
                    if (it.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        // ушли с поля — применяем введённое и снова показываем кнопку
                        hadFocus = false
                        editing = false
                        onSubmit()
                    }
                }
        )
    } else {
        LaunchedEffect(restore) {
            if (restore) {
                delay(80)
                try {
                    buttonFocus.requestFocus()
                } catch (e: Exception) {
                }
                restore = false
            }
        }
        FocusBox(
            modifier = modifier.focusRequester(buttonFocus),
            onClick = { editing = true },
            shape = RoundedCornerShape(16.dp)
        ) { focused ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (focused) GlassHi else Glass)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = TextDim)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (value.isBlank()) "Найти аниме по названию" else value,
                    color = if (value.isBlank()) TextDim else Color.White,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun CatalogScreen(onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }   // отмечаем в меню
    var applied by remember { mutableStateOf<Set<Int>>(emptySet()) }    // применено к каталогу
    var sheet by remember { mutableStateOf(false) }
    var genreList by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var genreError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            genreList = loadGenres()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            genreError = true
        }
    }

    val ids = applied.sorted()
    val names = genreList.filter {
        val v = it.value?.toInt()
        v != null && v in applied
    }.map { it.title.orEmpty() }
    val genreLabel = if (names.isEmpty()) "Жанры" else "Жанры: " + names.joinToString(", ")

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchBox(
                value = q,
                onValue = { q = it },
                onSubmit = { submitted = q.trim() },
                modifier = Modifier.weight(1f)
            )
            TvButton(genreLabel, Modifier.widthIn(max = 420.dp), selected = applied.isNotEmpty()) {
                selected = applied
                sheet = true
            }
        }
        PagedGrid(key = submitted to ids, onOpen = onOpen) { offset ->
            val s = submitted
            when {
                ids.isNotEmpty() -> loadFiltered(offset, s, ids)
                s.isBlank() -> loadCatalogPage(offset)
                else -> Net.api.search(s, 40, offset).response.orEmpty().map { it.toCard() }
            }
        }
    }

    if (sheet) {
        val items = if (genreList.isEmpty()) {
            listOf(SheetItem(if (genreError) "Не удалось загрузить жанры" else "Загрузка…"))
        } else {
            buildList {
                add(SheetItem(if (selected.isEmpty()) "Показать всё" else "Показать результаты (${selected.size})"))
                add(SheetItem("Сбросить выбор"))
                genreList.forEach { g ->
                    val id = g.value?.toInt()
                    val on = id != null && id in selected
                    add(SheetItem(g.title.orEmpty(), selected = on, mark = if (on) "✓" else null))
                }
            }
        }
        SideSheet(
            title = "Жанры",
            items = items,
            onDismiss = {
                applied = selected
                sheet = false
            }
        ) { i ->
            if (genreList.isEmpty() || i == 0) {
                applied = selected
                sheet = false
            } else if (i == 1) {
                selected = emptySet()
            } else {
                val id = genreList[i - 2].value?.toInt()
                if (id != null) {
                    selected = if (id in selected) selected - id else selected + id
                }
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
