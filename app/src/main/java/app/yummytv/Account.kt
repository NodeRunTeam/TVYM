package app.yummytv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// порядок как на сайте; id 4 — «Любимые» (в swagger id списков 0–5, 4 не подписан)
private val AccountTabs = listOf(
    0 to "Смотрю",
    1 to "В планах",
    2 to "Просмотрено",
    5 to "Отложено",
    3 to "Брошено",
    4 to "Любимые"
)
val AccountSortNames = listOf("По названию", "По рейтингу", "По дате добавления")

@Composable
fun AnimeRow(item: CardData, odd: Boolean, current: Boolean = false, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().peekOnFocus(peekY = 56.dp),
        onClick = onClick,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        current -> Accent.copy(alpha = 0.35f)
                        odd -> Panel
                        else -> Panel.copy(alpha = 0.55f)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Bg)
            )
            Spacer(Modifier.width(14.dp))
            val dot = when (item.status) {
                "ongoing" -> Color(0xFFA855F7)
                "released" -> Green
                else -> TextDim
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.let { Text(it, color = TextDim, fontSize = 13.sp, maxLines = 1) }
            }
            item.rating?.let {
                Text("★ " + it.fmt(), color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AnimeRows(items: List<CardData>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("В этом списке пока пусто", color = TextDim, fontSize = 18.sp)
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(items) { i, c ->
            AnimeRow(c, i % 2 == 1) { onOpen(c.url) }
        }
    }
}

@Composable
fun AccountScreen(
    loggedIn: Boolean,
    sort: Int,
    onLogin: () -> Unit,
    onOpen: (String) -> Unit
) {
    if (!loggedIn || Session.userId == 0L) {
        Column(
            Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Вы не вошли в аккаунт", color = Color.White, fontSize = 22.sp)
            Text("Войдите, чтобы видеть свои списки", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            TvButton("Войти", onClick = onLogin)
        }
        return
    }

    var sel by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)
        ) {
            itemsIndexed(AccountTabs) { i, tab ->
                TvButton(tab.second, selected = sel == i) { sel = i }
            }
        }

        val listId = AccountTabs[sel].first
        val (st, retry) = rememberLoad(listId) {
            Cache.lists.get(listId) ?: Cache.lists.put(
                listId,
                Net.api.userList(Session.userId, listId).response.orEmpty().map { it.toCard() }
            )
        }
        LoadView(st, retry) { items ->
            val sorted = remember(items, sort) {
                when (sort) {
                    0 -> items.sortedBy { it.title.lowercase() }
                    1 -> items.sortedByDescending { it.rating ?: 0.0 }
                    else -> items.sortedByDescending { it.added }
                }
            }
            AnimeRows(sorted, onOpen)
        }
    }
}
