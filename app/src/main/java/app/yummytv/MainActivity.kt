package app.yummytv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FocusBox(
            modifier = Modifier.size(48.dp),
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
            style = brandStyle(24),
            modifier = Modifier.padding(start = 4.dp, end = 20.dp)
        )
        NavPill("Каталог", tab == Tab.Catalog) { onTab(Tab.Catalog) }
        NavPill("Топ-100", tab == Tab.Top) { onTab(Tab.Top) }
        NavPill(if (randomBusy) "Ищу…" else "Случайное", false) { onRandom() }
        Spacer(Modifier.weight(1f))
        NavPill("Аккаунт", tab == Tab.Account) { onTab(Tab.Account) }
        if (loggedIn) IconTvButton(Icons.Default.Settings, "Настройки") { onSettings() }
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

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        Surface(Modifier.fillMaxSize().appBackground(), color = Color.Transparent) {
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
                    val safe = Modifier.fillMaxSize().padding(top = 20.dp, bottom = 28.dp)
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
                            TopBar(
                                tab = tab,
                                randomBusy = randomBusy,
                                loggedIn = loggedIn,
                                onTab = { tab = it },
                                onRandom = onRandom,
                                onSettings = { settings = true }
                            )
                            val open: (String) -> Unit = { stack.add(Screen.Detail(it)) }
                            when (tab) {
                                Tab.Home -> HomeScreen(open)
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
                    }
                }
            }

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

@Composable
fun MissingToken() {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
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
