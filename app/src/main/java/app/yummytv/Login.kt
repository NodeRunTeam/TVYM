package app.yummytv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onGuest: () -> Unit) {
    var login by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun doLogin() {
        if (busy || login.isBlank() || pass.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                val r = Net.api.login(LoginBody(login.trim(), pass))
                if (r.code() == 420) {
                    error = "Сайт требует капчу (hCaptcha) — вход по паролю сейчас недоступен. Нажмите «Продолжить без входа»."
                } else if (!r.isSuccessful) {
                    error = "Не удалось войти (код ${r.code()}). Проверьте логин и пароль."
                } else {
                    val t = r.body()?.response?.token
                    if (t.isNullOrBlank()) {
                        error = "Не удалось войти: неверный логин или пароль"
                    } else {
                        Session.token = t
                        Session.tokenTime = System.currentTimeMillis()
                        val p = Net.api.profile().response
                        Session.userId = p?.id ?: 0L
                        Session.nickname = p?.nickname
                        Session.guest = false
                        onLoggedIn()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = errText(e)
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painter = painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text("Yummy TV", color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            singleLine = true,
            label = { Text("Логин или email") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.width(480.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            singleLine = true,
            label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.width(480.dp)
        )
        Spacer(Modifier.height(16.dp))
        error?.let {
            Text(it, color = Color(0xFFFF8A8A), fontSize = 15.sp, modifier = Modifier.width(480.dp))
            Spacer(Modifier.height(12.dp))
        }
        TvButton(if (busy) "Вход…" else "Войти", Modifier.width(480.dp), fill = true) { doLogin() }
        Spacer(Modifier.height(10.dp))
        TvButton("Продолжить без входа", Modifier.width(480.dp), fill = true) { onGuest() }
    }
}
