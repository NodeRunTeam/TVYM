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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

object Cache {
    var feed: Feed? = null
}

private fun buildSections(feed: Feed): List<Pair<String, List<CardData>>> {
    val last = feed.last_watches.orEmpty().map {
        CardData(it.title ?: "—", it.ep_title, it.poster?.medium.fixUrl(), it.anime_url ?: it.anime_id.toString())
    }
    val newEps = feed.new_videos.orEmpty().map {
        CardData(
            it.title ?: "—",
            listOfNotNull(it.ep_title, it.dub_title).joinToString(" · ").ifEmpty { null },
            it.poster?.medium.fixUrl(),
            it.anime_url ?: it.anime_id.toString()
        )
    }
    val schedule = feed.schedule.orEmpty().map {
        val e = it.episodes
        val sub = if (e?.aired != null && e.count != null) "Вышло ${e.aired} из ${e.count}" else null
        CardData(it.title ?: "—", sub, it.poster?.medium.fixUrl(), it.anime_url ?: it.anime_id.toString())
    }
    return listOf(
        "Продолжить просмотр" to last,
        "Новые серии" to newEps,
        "Расписание онгоингов" to schedule,
        "Новинки" to feed.new.orEmpty().map { it.toCard() },
        "Анонсы" to feed.announcements.orEmpty().map { it.toCard() },
        "Рекомендации" to feed.recommends.orEmpty().map { it.toCard() }
    ).filter { it.second.isNotEmpty() }
}

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val (st, retry) = rememberLoad<Feed>(null) {
        Cache.feed ?: (Net.api.feed().response ?: error("Пустой ответ сервера")).also { Cache.feed = it }
    }
    LoadView(st, { Cache.feed = null; retry() }) { feed ->
        val sections = remember(feed) { buildSections(feed) }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(sections) { (title, list) ->
                Column {
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp)
                    ) {
                        items(list) { c ->
                            PosterCard(c, Modifier.width(150.dp)) { onOpen(c.url) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            singleLine = true,
            placeholder = { Text("Название аниме") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = q.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp)
        )
        if (submitted.isNotBlank()) {
            val (st, retry) = rememberLoad(submitted) {
                Net.api.search(submitted).response.orEmpty().map { it.toCard() }
            }
            LoadView(st, retry) { items -> CardGrid(items, onOpen) }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Введите название и нажмите «Поиск» на клавиатуре", color = TextDim, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun ListsScreen(loggedIn: Boolean, onOpen: (String) -> Unit) {
    if (!loggedIn || Session.userId == 0L) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Войдите в аккаунт (вкладка «Аккаунт»), чтобы видеть свои списки", color = TextDim, fontSize = 18.sp)
        }
        return
    }
    var sel by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ListNames.forEach { (id, name) ->
                TvButton(name, selected = sel == id) { sel = id }
            }
        }
        val (st, retry) = rememberLoad(sel) {
            Net.api.userList(Session.userId, sel).response.orEmpty().map { it.toCard() }
        }
        LoadView(st, retry) { items -> CardGrid(items, onOpen) }
    }
}

@Composable
fun AccountScreen(loggedIn: Boolean, onLogin: () -> Unit, onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loggedIn) {
            Text("Вы вошли как ${Session.nickname ?: "пользователь"}", color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.height(16.dp))
            TvButton("Выйти", onClick = onLogout)
        } else {
            Text("Вы не вошли в аккаунт", color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.height(16.dp))
            TvButton("Войти", onClick = onLogin)
        }
    }
}
