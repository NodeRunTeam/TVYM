package app.yummytv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// ---------- палитра ----------

val Accent2 = Color(0xFF8B5CF6)
val PanelHi = Color(0xFF2B2D52)
val Glass = Color(0x14FFFFFF)
val GlassHi = Color(0x2EFFFFFF)

val AccentBrush: Brush = Brush.horizontalGradient(listOf(Accent, Accent2))

/** Фон приложения: тёмный, с еле заметным вертикальным градиентом (дёшево для телевизора) */
fun Modifier.appBackground(): Modifier = this.background(
    Brush.verticalGradient(listOf(Color(0xFF15122E), Bg))
)

/** Кому показывать постер на фоне: карточки сообщают сюда, какая из них в фокусе */
val LocalBackdrop = staticCompositionLocalOf<(String?) -> Unit> { { _ -> } }

/** Фон приложения повторяет постер карточки, на которой стоит фокус */
@Composable
fun Backdrop(state: State<String?>) {
    val url = state.value
    Crossfade(targetState = url, animationSpec = tween(600), label = "backdrop") { u ->
        if (u != null) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = u,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.55f)
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0x730B0B1A), Color(0xE80B0B1A)))
                    )
                )
            }
        }
    }
}

/** Стиль текста с градиентом (логотип) */
fun brandStyle(size: Int): TextStyle =
    TextStyle(brush = AccentBrush, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)

// ---------- мерцающие заглушки вместо крутилки ----------

@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerX"
    )
    // значение читается только при рисовании — без перекомпоновки каждый кадр
    return this.drawBehind {
        val shift = x.value * 1600f
        drawRect(
            Brush.linearGradient(
                colors = listOf(Panel, PanelHi, Panel),
                start = Offset(shift - 600f, 0f),
                end = Offset(shift, 400f)
            )
        )
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(0.68f).clip(RoundedCornerShape(18.dp)).shimmer())
}

@Composable
fun SkeletonRow() {
    LazyRow(
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 20.dp)
    ) {
        items(8) { SkeletonCard(Modifier.width(150.dp)) }
    }
}

@Composable
fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        userScrollEnabled = false,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(18) { SkeletonCard() }
    }
}

// ---------- мелкие элементы ----------

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(5.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.verticalGradient(listOf(Accent, Accent2)))
        )
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Chip(text: String, color: Color = Color.White, bg: Color = Glass) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

/** Пункт верхнего меню: «пилюля», у выбранного — градиент */
@Composable
fun NavPill(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50)) { focused ->
        Box(
            Modifier
                .then(
                    when {
                        selected -> Modifier.background(AccentBrush)
                        focused -> Modifier.background(GlassHi)
                        else -> Modifier
                    }
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
fun tvFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = GlassHi,
    unfocusedContainerColor = Glass,
    cursorColor = Accent,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedPlaceholderColor = TextDim,
    unfocusedPlaceholderColor = TextDim,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextDim
)
