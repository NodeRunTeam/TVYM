package app.yummytv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
        val videoId: Long
    ) : Screen
}

@Composable
private fun TopBar(
    tab: Tab,
    randomBusy: Boolean,
    onTab: (Tab) -> Unit,
    onRandom: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FocusBox(
            modifier = Modifier.size(46.dp),
            onClick = { onTab(Tab.Home) },
            shape = RoundedCornerShape(10.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "Главная",
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            "Yummy TV",
            color = if (tab == Tab.Home) Accent else Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp)
        )
        TvButton("Каталог аниме", selected = tab == Tab.Catalog) { onTab(Tab.Catalog) }
        TvButton("Топ-100", selected = tab == Tab.Top) { onTab(Tab.Top) }
        TvButton(if (randomBusy) "Ищу…" else "Случайное") { onRandom() }
        Spacer(Modifier.weight(1f))
        TvButton("Аккаунт", selected = tab == Tab.Account) { onTab(Tab.Account) }
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
        Surface(Modifier.fillMaxSize(), color = Bg) {
            when {
                Config.APP_TOKEN.startsWith("PASTE") -> MissingToken()
                !loggedIn && !guest -> LoginScreen(
                    onLoggedIn = { loggedIn = true; guest = false },
                    onGuest = { Session.guest = true; guest = true }
                )
                else -> {
                    val top = stack.lastOrNull()
                    BackHandler(enabled = top !is Screen.Player && (stack.isNotEmpty() || tab != Tab.Home)) {
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.Home
                    }
                    when (top) {
                        is Screen.Detail -> DetailScreen(
                            top.url,
                            loggedIn,
                            onOpen = { stack.add(Screen.Detail(it)) }
                        ) { p -> stack.add(p) }
                        is Screen.Player -> PlayerScreen(top, loggedIn) {
                            stack.removeAt(stack.lastIndex)
                        }
                        null -> Column(Modifier.fillMaxSize().padding(bottom = 20.dp)) {
                            TopBar(tab, randomBusy, onTab = { tab = it }, onRandom = onRandom)
                            val open: (String) -> Unit = { stack.add(Screen.Detail(it)) }
                            when (tab) {
                                Tab.Home -> HomeScreen(open)
                                Tab.Catalog -> CatalogScreen(open)
                                Tab.Top -> TopScreen(open)
                                Tab.Account -> AccountScreen(
                                    loggedIn = loggedIn,
                                    onLogin = { Session.guest = false; guest = false },
                                    onLogout = {
                                        Session.clear()
                                        Cache.clearUser()
                                        loggedIn = false
                                        guest = false
                                        tab = Tab.Home
                                    },
                                    onOpen = open
                                )
                            }
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
