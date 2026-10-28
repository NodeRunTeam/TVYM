package app.yummytv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

val Accent = Color(0xFFFF5C8A)
val Bg = Color(0xFF0B0B1A)
val Panel = Color(0xFF1B1C36)
val TextDim = Color(0xFFA3A9BC)
val Gold = Color(0xFFF2A900)
val Green = Color(0xFF3DDC84)

// цвет кнопки списка на странице аниме (id списка -> цвет)
val ListColors = mapOf(
    0 to Color(0xFF22A559), // Смотрю — зелёный
    1 to Color(0xFF8B5CF6), // В планах — фиолетовый
    2 to Color(0xFF2F7DE1), // Просмотрено — синий
    3 to Color(0xFF6B7280), // Брошено — серый
    5 to Color(0xFFD98E04)  // Отложено — янтарный
)

// id списка на сервере -> название (для кнопки «в список» на странице аниме)
val ListNames = listOf(0 to "Смотрю", 1 to "В планах", 2 to "Просмотрено", 3 to "Брошено", 5 to "Отложено")

fun Double.fmt(): String = String.format(Locale.US, "%.2f", this)

fun String?.fixUrl(): String? {
    val s = this ?: return null
    if (s.isBlank()) return null
    return when {
        s.startsWith("//") -> "https:$s"
        s.startsWith("/") -> Config.SITE + s
        else -> s
    }
}

data class CardData(
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val url: String,
    val rating: Double? = null,
    val rank: Int? = null,
    val status: String? = null,
    val added: Long = 0L,
    val description: String? = null,
    val banner: String? = null,
    val progress: Float? = null
)

fun AnimeCard.toCard(rank: Int? = null) = CardData(
    title = title ?: "—",
    subtitle = listOfNotNull(type?.name, year?.toString()).joinToString(" · ").ifEmpty { null },
    poster = poster?.medium.fixUrl(),
    url = anime_url ?: anime_id.toString(),
    rating = rating.ratingValue()?.takeIf { it > 0.0 },
    rank = rank,
    status = anime_status?.alias,
    added = date ?: 0L,
    description = description?.takeIf { it.isNotBlank() },
    banner = poster?.big.fixUrl() ?: poster?.medium.fixUrl()
)

fun ViewingItem.toCard(pos: Int) = CardData(
    title = "$pos. ${title ?: "—"}",
    subtitle = listOfNotNull(type?.name, year?.toString(), data?.text?.takeIf { it.isNotBlank() })
        .joinToString(" · ").ifEmpty { null },
    poster = poster?.medium.fixUrl(),
    url = anime_url ?: anime_id.toString(),
    status = anime_status?.alias
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
fun <T> LoadView(
    state: Load<T>,
    retry: () -> Unit,
    skeleton: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    when (state) {
        is Load.Loading -> if (skeleton != null) {
            skeleton()
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
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
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable (Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(160),
        label = "focusScale"
    )
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused) Modifier.border(3.dp, AccentBrush, shape) else Modifier)
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
    tint: Color? = null,
    onClick: () -> Unit
) {
    FocusBox(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(14.dp)) { focused ->
        val bg = when {
            tint != null -> Modifier.background(tint.copy(alpha = if (focused) 1f else 0.85f))
            selected -> Modifier.background(AccentBrush)
            else -> Modifier.background(if (focused) GlassHi else Glass)
        }
        Box(
            Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .then(bg)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AccentButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    FocusBox(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier.background(AccentBrush).padding(horizontal = 24.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            Text(text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Кнопка-иконка (шестерёнка, «назад» и т.п.) */
@Composable
fun IconTvButton(icon: ImageVector, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(modifier = modifier, onClick = onClick, shape = RoundedCornerShape(50)) { focused ->
        Box(
            Modifier.background(if (focused) GlassHi else Glass).padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun RatingBadge(value: Double, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3000000))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("★", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(value.fmt(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Text(
        "#$rank",
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(AccentBrush)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

/**
 * При фокусе прокручивает список так, чтобы элемент был виден целиком
 * и рядом оставался кусочек соседних (понятно, что список не закончился).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.peekOnFocus(peekX: Dp = 0.dp, peekY: Dp = 0.dp): Modifier {
    val bring = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var hasFocus by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(hasFocus, size) {
        if (hasFocus && size != IntSize.Zero) {
            val px = with(density) { peekX.toPx() }
            val py = with(density) { peekY.toPx() }
            bring.bringIntoView(Rect(-px, -py, size.width + px, size.height + py))
        }
    }
    return this
        .onSizeChanged { size = it }
        .bringIntoViewRequester(bring)
        .onFocusChanged { hasFocus = it.hasFocus }
}

@Composable
fun PosterCard(item: CardData, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val setBackdrop = LocalBackdrop.current
    Column(
        modifier
            .peekOnFocus(peekX = 56.dp, peekY = 48.dp)
            .onFocusChanged { if (it.hasFocus) setBackdrop(item.poster) }
    ) {
        FocusBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
            onClick = onClick,
            shape = RoundedCornerShape(18.dp)
        ) {
            Box(Modifier.fillMaxSize().background(Panel)) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // название поверх постера на тёмном градиенте
                val p = item.progress?.takeIf { it > 0f }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2070710))))
                        .padding(start = 10.dp, end = 10.dp, top = 30.dp, bottom = if (p != null) 12.dp else 10.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.subtitle?.let {
                        Text(it, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item.rating?.let { RatingBadge(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
                item.rank?.let { RankBadge(it, Modifier.align(Alignment.TopEnd).padding(8.dp)) }
                if (p != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x66FFFFFF))
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(p.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(AccentBrush)
                    )
                }
            }
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
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { c ->
            PosterCard(c, Modifier.fillMaxWidth()) { onOpen(c.url) }
        }
    }
}

// ---------- выезжающее справа меню со списком ----------

data class SheetItem(val text: String, val selected: Boolean = false, val mark: String? = null)

@Composable
fun SideSheet(
    title: String,
    items: List<SheetItem>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }

        val selIdx = items.indexOfFirst { it.selected }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selIdx - 3).coerceAtLeast(0))
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(300)
            try {
                focus.requestFocus()
            } catch (e: Exception) {
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        ) {
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn()
            ) {
                Column(
                    Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF14142B), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(items) { i, item ->
                            FocusBox(
                                modifier = (if (i == selIdx) Modifier.focusRequester(focus) else Modifier).fillMaxWidth(),
                                onClick = { onPick(i) },
                                shape = RoundedCornerShape(14.dp)
                            ) { focused ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (item.selected) Accent.copy(alpha = 0.35f) else if (focused) GlassHi else Glass)
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        item.text,
                                        color = Color.White,
                                        fontSize = 17.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    item.mark?.let {
                                        Text(it, color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
