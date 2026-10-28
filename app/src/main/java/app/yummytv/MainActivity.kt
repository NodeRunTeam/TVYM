package app.yummytv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(applicationContext)
        setContent { App() }
    }
}

enum class Tab(val title: String) {
    Home("Главная"),
    Search("Поиск"),
    Lists("Мои списки"),
    Account("Аккаунт")
}

sealed interface Screen {
    data class Detail(val url: String) : Screen
    data class Player(val title: String, val url: String) : Screen
}

@Composable
fun App() {
    var loggedIn by remember { mutableStateOf(Session.token != null) }
    var guest by remember { mutableStateOf(Session.guest) }
    var tab by remember { mutableStateOf(Tab.Home) }
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
                        is Screen.Detail -> DetailScreen(top.url, loggedIn) { title, url ->
                            stack.add(Screen.Player(title, url))
                        }
                        is Screen.Player -> PlayerScreen(top.title, top.url) {
                            stack.removeAt(stack.lastIndex)
                        }
                        null -> Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Tab.values().forEach { t ->
                                    TvButton(t.title, selected = tab == t) { tab = t }
                                }
                            }
                            val open: (String) -> Unit = { stack.add(Screen.Detail(it)) }
                            when (tab) {
                                Tab.Home -> HomeScreen(open)
                                Tab.Search -> SearchScreen(open)
                                Tab.Lists -> ListsScreen(loggedIn, open)
                                Tab.Account -> AccountScreen(
                                    loggedIn = loggedIn,
                                    onLogin = { Session.guest = false; guest = false },
                                    onLogout = {
                                        Session.clear()
                                        Cache.feed = null
                                        loggedIn = false
                                        guest = false
                                    }
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
