package app.yummytv

import androidx.compose.foundation.layout.height
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
import java.util.Calendar
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

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

/** Варианты сортировки каталога: название и значение параметра sort (null — по умолчанию) */
val SortModes = listOf("По умолчанию" to null, "По рейтингу" to "rating", "Сначала новые" to "year")

/** Каталог с поиском, жанрами, годом выпуска и сортировкой */
suspend fun loadCatalog(offset: Int, q: String, genres: List<Int>, year: Int?, sortIdx: Int): List<CardData> {
    val sort = SortModes.getOrNull(sortIdx)?.second
    val g = genres.takeIf { it.isNotEmpty() }
    val qq = q.ifBlank { null }
    // поиск без выбранной сортировки — в порядке релевантности, как отдаёт сайт
    if (sort == null && qq != null) {
        return Net.api.animeList(q = qq, genres = g, fromYear = year, toYear = year, limit = 40, offset = offset)
            .response.orEmpty().map { it.toCard() }
    }
    val eff = sort ?: "views"
    val value: (AnimeCard) -> Double? = when (eff) {
        "rating" -> ({ a: AnimeCard -> a.rating.ratingValue() })
        "year" -> ({ a: AnimeCard -> a.year?.toDouble() })
        else -> ({ a: AnimeCard -> a.views?.toDouble() })
    }
    return loadSorted("catalog-$eff", ascending = false, value = value) { fwd ->
        Net.api.animeList(
            sort = eff,
            sortForward = fwd,
            genres = g,
            q = qq,
            fromYear = year,
            toYear = year,
            limit = 40,
            offset = offset
        ).response.orEmpty()
    }.map { it.toCard() }
}

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

/** Запоминает загруженные страницы и позицию прокрутки, чтобы при возврате из аниме список не сбрасывался */
private class PagedMem {
    val items = mutableStateListOf<CardData>()
    var end by mutableStateOf(false)
    var index = 0
    var offset = 0
}

private object PagedMemory {
    private val map = LinkedHashMap<Any?, PagedMem>()

    fun get(key: Any?): PagedMem {
        val m = map.remove(key) ?: PagedMem()
        map[key] = m
        while (map.size > 6) map.remove(map.keys.first())
        return m
    }
}

/** Что введено в каталоге — хранится вне экрана, чтобы не терялось при заходе в аниме */
private object CatalogMemory {
    var q by mutableStateOf("")
    var submitted by mutableStateOf("")
    var selected by mutableStateOf<Set<Int>>(emptySet())
    var applied by mutableStateOf<Set<Int>>(emptySet())
    var year by mutableStateOf<Int?>(null)
    var sortIdx by mutableIntStateOf(0)
}

@Composable
fun PagedGrid(
    key: Any?,
    onOpen: (String) -> Unit,
    pageSize: Int = 40,
    fetch: suspend (Int) -> List<CardData>
) {
    val mem = remember(key) { PagedMemory.get(key) }
    val items = mem.items
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var request by remember(key) { mutableIntStateOf(0) }
    val gridState = remember(mem) { LazyGridState(mem.index, mem.offset) }
    DisposableEffect(mem, gridState) {
        onDispose {
            mem.index = gridState.firstVisibleItemIndex
            mem.offset = gridState.firstVisibleItemScrollOffset
        }
    }

    LaunchedEffect(key, request) {
        // уже загружено (вернулись из аниме) — ничего не грузим заново
        if (request == 0 && items.isNotEmpty()) return@LaunchedEffect
        loading = true
        error = null
        try {
            val page = fetch(items.size)
            items.addAll(page)
            if (page.size < pageSize) mem.end = true
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
            Modifier.fillMaxSize().padding(hPad()),
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
        state = gridState,
        columns = GridCells.Adaptive(gridMin()),
        contentPadding = PaddingValues(start = hPad(), end = hPad(), top = 16.dp, bottom = 24.dp),
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
                    !mem.end -> TvButton("Показать ещё") { request = request + 1 }
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
    // при каждом входе в режим ввода курсор ставим в конец текста
    var tfv by remember(editing) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }

    if (editing) {
        LaunchedEffect(Unit) {
            delay(100)
            try {
                fieldFocus.requestFocus()
            } catch (e: Exception) {
            }
        }
        OutlinedTextField(
            value = tfv,
            onValueChange = {
                tfv = it
                onValue(it.text)
            },
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
    var q by CatalogMemory::q
    var submitted by CatalogMemory::submitted
    var selected by CatalogMemory::selected   // отмечаем в меню
    var applied by CatalogMemory::applied     // применено к каталогу
    var year by CatalogMemory::year
    var sortIdx by CatalogMemory::sortIdx
    var yearSheet by remember { mutableStateOf(false) }
    var sortSheet by remember { mutableStateOf(false) }
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
    val yearLabel = if (year == null) "Год" else "Год: $year"
    val sortLabel = if (sortIdx == 0) "Сортировка" else SortModes[sortIdx].first

    Column(Modifier.fillMaxSize()) {
        val openGenres = {
            selected = applied
            sheet = true
        }
        if (isCompact()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = hPad(), vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchBox(
                    value = q,
                    onValue = { q = it },
                    onSubmit = { submitted = q.trim() },
                    modifier = Modifier.fillMaxWidth()
                )
                TvButton(genreLabel, Modifier.fillMaxWidth(), selected = applied.isNotEmpty(), fill = true) {
                    openGenres()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(yearLabel, Modifier.weight(1f), selected = year != null, fill = true) {
                        yearSheet = true
                    }
                    TvButton(sortLabel, Modifier.weight(1f), selected = sortIdx != 0, fill = true) {
                        sortSheet = true
                    }
                }
            }
        } else {
            // телевизор: поиск на всю ширину, ниже — кнопки фильтров
            Column(
                Modifier.fillMaxWidth().padding(horizontal = hPad(), vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SearchBox(
                    value = q,
                    onValue = { q = it },
                    onSubmit = { submitted = q.trim() },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvButton(genreLabel, Modifier.widthIn(max = 420.dp), selected = applied.isNotEmpty()) {
                        openGenres()
                    }
                    TvButton(yearLabel, selected = year != null) { yearSheet = true }
                    TvButton(sortLabel, selected = sortIdx != 0) { sortSheet = true }
                }
            }
        }
        PagedGrid(key = Triple(submitted, ids, year to sortIdx), onOpen = onOpen) { offset ->
            loadCatalog(offset, submitted, ids, year, sortIdx)
        }
    }

    if (yearSheet) {
        val cy = Calendar.getInstance().get(Calendar.YEAR)
        val years = (cy + 1 downTo 1960).toList()
        SideSheet(
            title = "Год выпуска",
            items = listOf(SheetItem("Любой год", selected = year == null)) +
                years.map { SheetItem(it.toString(), selected = it == year) },
            onDismiss = { yearSheet = false }
        ) { i ->
            year = if (i == 0) null else years[i - 1]
            yearSheet = false
        }
    }

    if (sortSheet) {
        SideSheet(
            title = "Сортировка",
            items = SortModes.mapIndexed { i, m -> SheetItem(m.first, selected = i == sortIdx) },
            onDismiss = { sortSheet = false }
        ) { i ->
            sortIdx = i
            sortSheet = false
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
private fun TopTabButtons(sel: Int, onSel: (Int) -> Unit) {
    TopTabs.forEachIndexed { i, tab ->
        TvButton(tab.first, selected = sel == i) { onSel(i) }
    }
}

@Composable
fun TopScreen(onOpen: (String) -> Unit) {
    var sel by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (isCompact()) {
            Column(Modifier.padding(horizontal = hPad(), vertical = 8.dp)) {
                SectionTitle("Топ-100 аниме")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopTabButtons(sel) { sel = it }
                }
            }
        } else {
            Row(
                Modifier.padding(horizontal = hPad(), vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Топ-100 аниме", Modifier.padding(end = 16.dp))
                TopTabButtons(sel) { sel = it }
            }
        }
        val type = TopTabs[sel].second
        val (st, retry) = rememberLoad(type) {
            Cache.top.get(type) ?: Cache.top.put(type, loadTop(type))
        }
        LoadView(st, retry, skeleton = { SkeletonGrid() }) { items -> CardGrid(items, onOpen) }
    }
}
