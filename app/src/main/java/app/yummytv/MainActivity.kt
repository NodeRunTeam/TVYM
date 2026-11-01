package app.yummytv

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // приложение рисуется на весь экран, под строкой состояния и панелью навигации
        // (только на телефоне; на телевизоре и планшете всё как раньше)
        if (!isTvDevice(this) && resources.configuration.smallestScreenWidthDp < 600) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            )
        }
        Session.init(applicationContext)
        Watched.init(applicationContext)
        setContent { App() }
    }
}

enum class Tab {
    Home, Catalog, Top, Account
}

sealed interface Screen {
    data class Detail(val url: String) : Screen
    data class Player(
        val title: String,
        val url: String,
        val kodik: Boolean,
        val episodes: List<VideoItem>,
        val videoId: Long,
        val animeId: Long = 0L
    ) : Screen
}

@Composable
private fun TopBar(
    tab: Tab,
    randomBusy: Boolean,
    loggedIn: Boolean,
    onTab: (Tab) -> Unit,
    onRandom: () -> Unit,
    onSettings: () -> Unit
) {
    val compact = isCompact()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = hPad(), vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FocusBox(
            modifier = Modifier.size(if (compact) 38.dp else 48.dp),
            onClick = { onTab(Tab.Home) },
            shape = RoundedCornerShape(14.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "Главная",
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            "Yummy TV",
            style = brandStyle(if (compact) 20 else 24),
            modifier = Modifier.padding(start = 4.dp, end = if (compact) 0.dp else 20.dp)
        )
        if (compact) {
            // на телефоне разделы вынесены в нижнюю панель
            Spacer(Modifier.weight(1f))
            NavPill(if (randomBusy) "Ищу…" else "Случайное", false) { onRandom() }
        } else {
            NavPill("Каталог", tab == Tab.Catalog) { onTab(Tab.Catalog) }
            NavPill("Топ-100", tab == Tab.Top) { onTab(Tab.Top) }
            NavPill(if (randomBusy) "Ищу…" else "Случайное", false) { onRandom() }
            Spacer(Modifier.weight(1f))
            NavPill("Аккаунт", tab == Tab.Account) { onTab(Tab.Account) }
        }
        if (loggedIn) IconTvButton(Icons.Default.Settings, "Настройки") { onSettings() }
    }
}

/** Нижняя панель разделов — только для телефона */
@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    val items = listOf(
        Triple(Tab.Home, "Главная", Icons.Default.Home),
        Triple(Tab.Catalog, "Каталог", Icons.Default.Search),
        Triple(Tab.Top, "Топ-100", Icons.Default.Star),
        Triple(Tab.Account, "Аккаунт", Icons.Default.Person)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF2101024))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (t, label, icon) ->
            val sel = tab == t
            FocusBox(
                modifier = Modifier.weight(1f),
                onClick = { onTab(t) },
                shape = RoundedCornerShape(14.dp)
            ) { focused ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (sel) Accent.copy(alpha = 0.16f) else if (focused) GlassHi else Color.Transparent
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = if (sel) Accent else TextDim,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        label,
                        color = if (sel) Color.White else TextDim,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(Session.token != null) }
    var guest by remember { mutableStateOf(Session.guest) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var randomBusy by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var accountSort by remember { mutableIntStateOf(0) }
    val homeState = rememberLazyListState()
    val backdrop = remember { mutableStateOf<String?>(null) }
    val setBackdrop = remember { { u: String? -> backdrop.value = u } }
    val stack = remember { mutableStateListOf<Screen>() }

    // обновляем токен раз в 2 дня (он живёт 2 недели)
    LaunchedEffect(loggedIn) {
        if (loggedIn && System.currentTimeMillis() - Session.tokenTime > 2L * 24 * 3600 * 1000) {
            try {
                val t = Net.api.refreshToken().response?.token
                if (!t.isNullOrBlank()) {
                    Session.token = t
                    Session.tokenTime = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    Session.clear()
                    loggedIn = false
                    guest = false
                }
            } catch (e: Throwable) {
            }
        }
    }

    // подтягиваем с сайта, какие серии уже просмотрены
    LaunchedEffect(loggedIn) {
        if (loggedIn) importWatchHistory()
    }

    val onRandom: () -> Unit = {
        if (!randomBusy) {
            randomBusy = true
            scope.launch {
                try {
                    val a = Net.api.animeList(sort = "random", limit = 1).response?.firstOrNull()
                    val u = a?.anime_url ?: a?.anime_id?.takeIf { it != 0L }?.toString()
                    if (u != null) {
                        stack.add(Screen.Detail(u))
                    } else {
                        Toast.makeText(ctx, "Не удалось подобрать аниме", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Toast.makeText(ctx, errText(e), Toast.LENGTH_LONG).show()
                } finally {
                    randomBusy = false
                }
            }
        }
    }

    val compact = isCompact()
    val inPlayer = stack.lastOrNull() is Screen.Player
    DisposableEffect(compact, inPlayer) {
        val act = findActivity(ctx)
        if (act != null && compact) {
            act.requestedOrientation = if (inPlayer) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (inPlayer) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // отступы под системные панели; в плеере они скрыты, и контент идёт на весь экран
    val barsPad = if (!compact || inPlayer) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars)

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        CompositionLocalProvider(LocalBackdrop provides setBackdrop) {
        Surface(Modifier.fillMaxSize().appBackground(), color = Color.Transparent) {
            Backdrop(backdrop)
            Box(Modifier.fillMaxSize().then(barsPad).then(if (compact) Modifier.imePadding() else Modifier)) {
            when {
                Config.APP_TOKEN.startsWith("PASTE") -> MissingToken()
                !loggedIn && !guest -> LoginScreen(
                    onLoggedIn = { Cache.clearUser(); loggedIn = true; guest = false },
                    onGuest = { Session.guest = true; guest = true }
                )
                else -> {
                    val top = stack.lastOrNull()
                    BackHandler(enabled = top !is Screen.Player && (stack.isNotEmpty() || tab != Tab.Home)) {
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.Home
                    }
                    // отступы под «безопасную зону» телевизора (по краям экран часто обрезается)
                    val safe = if (compact) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(top = 20.dp, bottom = 28.dp)
                    when (top) {
                        is Screen.Detail -> Box(Modifier.fillMaxSize()) {
                            DetailScreen(
                                url = top.url,
                                loggedIn = loggedIn,
                                onBack = { stack.removeAt(stack.lastIndex) },
                                onOpen = { stack.add(Screen.Detail(it)) },
                                onPlay = { p -> stack.add(p) }
                            )
                        }
                        is Screen.Player -> PlayerScreen(top, loggedIn) {
                            stack.removeAt(stack.lastIndex)
                        }
                        null -> Column(safe) {
                            Box(
                                Modifier.onFocusChanged {
                                    if (it.hasFocus && tab == Tab.Home) {
                                        scope.launch { homeState.animateScrollToItem(0) }
                                    }
                                }
                            ) {
                                TopBar(
                                    tab = tab,
                                    randomBusy = randomBusy,
                                    loggedIn = loggedIn,
                                    onTab = { tab = it },
                                    onRandom = onRandom,
                                    onSettings = { settings = true }
                                )
                            }
                            val open: (String) -> Unit = { stack.add(Screen.Detail(it)) }
                            Box(Modifier.weight(1f)) {
                            when (tab) {
                                Tab.Home -> HomeScreen(open, homeState, loggedIn)
                                Tab.Catalog -> CatalogScreen(open)
                                Tab.Top -> TopScreen(open)
                                Tab.Account -> AccountScreen(
                                    loggedIn = loggedIn,
                                    sort = accountSort,
                                    onLogin = { Session.guest = false; guest = false },
                                    onOpen = open
                                )
                            }
                            }
                            if (compact) BottomBar(tab) { tab = it }
                        }
                    }
                }
            }
            }

            // боковые меню рисуются поверх всего экрана
            SheetOverlayHost()

            // настройки (шестерёнка справа от «Аккаунт»)
            if (settings) {
                val onAccount = tab == Tab.Account && stack.isEmpty()
                val items = buildList {
                    if (onAccount) add(SheetItem("Сортировка: " + AccountSortNames[accountSort]))
                    add(SheetItem("Выйти из аккаунта"))
                }
                SideSheet(
                    title = Session.nickname ?: "Настройки",
                    items = items,
                    onDismiss = { settings = false }
                ) { i ->
                    settings = false
                    if (onAccount && i == 0) {
                        accountSort = (accountSort + 1) % AccountSortNames.size
                    } else {
                        Session.clear()
                        Cache.clearUser()
                        stack.clear()
                        loggedIn = false
                        guest = false
                        tab = Tab.Home
                    }
                }
            }
        }
        }
    }
}

@Composable
fun MissingToken() {
    Column(
        Modifier.fillMaxSize().padding(hPad()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Не указан токен приложения", color = Color.White, fontSize = 26.sp)
        Text(
            "Откройте файл Config.kt, вставьте токен с yummyani.me/dev/applications и пересоберите приложение.",
            color = TextDim,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
