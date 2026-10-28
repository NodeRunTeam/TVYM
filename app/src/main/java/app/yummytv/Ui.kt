package app.yummytv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

val Accent = Color(0xFFFF5C8A)
val Bg = Color(0xFF0F1115)
val Panel = Color(0xFF232733)
val TextDim = Color(0xFF9AA1B0)

val ListNames = listOf(0 to "Смотрю", 1 to "В планах", 2 to "Просмотрено", 3 to "Брошено", 5 to "Отложено")

fun String?.fixUrl(): String? {
    val s = this ?: return null
    if (s.isBlank()) return null
    return when {
        s.startsWith("//") -> "https:$s"
        s.startsWith("/") -> Config.SITE + s
        else -> s
    }
}

data class CardData(val title: String, val subtitle: String?, val poster: String?, val url: String)

fun AnimeCard.toCard() = CardData(
    title = title ?: "—",
    subtitle = listOfNotNull(type?.name, year?.toString()).joinToString(" · ").ifEmpty { null },
    poster = poster?.medium.fixUrl(),
    url = anime_url ?: anime_id.toString()
)

// ---------- загрузка данных ----------

sealed interface Load<out T> {
    object Loading : Load<Nothing>
    data class Ok<out T>(val v: T) : Load<T>
    data class Err(val msg: String) : Load<Nothing>
}

@Composable
fun <T> rememberLoad(key: Any? = null, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key, attempt) {
        state = Load.Loading
        state = try {
            Load.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Load.Err(errText(e))
        }
    }
    val retry: () -> Unit = { attempt = attempt + 1 }
    return state to retry
}

@Composable
fun <T> LoadView(state: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        is Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent)
        }
        is Load.Err -> Column(
            Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(state.msg, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            TvButton("Повторить", onClick = retry)
        }
        is Load.Ok -> content(state.v)
    }
}

// ---------- элементы, управляемые пультом ----------

@Composable
fun FocusBox(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable (Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val s = if (focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = shape
            )
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        content(focused)
    }
}

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fill: Boolean = false,
    onClick: () -> Unit
) {
    FocusBox(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(10.dp)) {
        Box(
            Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .background(if (selected) Accent.copy(alpha = 0.45f) else Panel)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterCard(item: CardData, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        FocusBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).background(Panel),
            onClick = onClick
        ) {
            AsyncImage(
                model = item.poster,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            item.title,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        item.subtitle?.let {
            Text(it, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CardGrid(items: List<CardData>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ничего не найдено", color = TextDim, fontSize = 18.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { c ->
            PosterCard(c, Modifier.fillMaxWidth()) { onOpen(c.url) }
        }
    }
}
