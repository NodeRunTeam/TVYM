package app.yummytv

import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/** Круглый курсор, которым управляют стрелками пульта */
class CursorView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 92, 138)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    var cx = 0f
    var cy = 0f

    override fun onDraw(canvas: Canvas) {
        val r = 9f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
    }
}

/** Перехватывает кнопки пульта раньше всех остальных */
private class KeyHook(
    private val orig: Window.Callback,
    private val handler: (KeyEvent) -> Boolean
) : Window.Callback by orig {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return handler(event) || orig.dispatchKeyEvent(event)
    }
}

fun findActivity(c: Context): Activity? {
    var cur: Context? = c
    while (cur is ContextWrapper) {
        if (cur is Activity) return cur
        cur = cur.baseContext
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
class PlayerLayout(context: Context, private val customControls: Boolean) : FrameLayout(context) {
    /** Вызывается, когда нужно открыть меню выбора серии */
    var onMenu: (() -> Unit)? = null

    /** Вызывается при касании экрана плеера (чтобы показать кнопки управления) */
    var onTouched: (() -> Unit)? = null

    /** Следующая (1) / предыдущая (-1) серия — по кнопкам наушников и пульта */
    var onStep: ((Int) -> Unit)? = null
    private var headset: HeadsetControls? = null
    private var upLong = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouched?.invoke()
        return super.dispatchTouchEvent(ev)
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private val web = WebView(context)
    private val cursor = CursorView(context)
    private val hint = TextView(context)
    private val density = resources.displayMetrics.density

    private var cursorMode = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var activityWindow: Window? = null
    private var origCallback: Window.Callback? = null

    private val chrome = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            web.visibility = View.GONE
            addView(view, matchParent())
            cursor.bringToFront()
            hint.bringToFront()
            view.requestFocus()
        }

        override fun onHideCustomView() {
            customView?.let { removeView(it) }
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            web.visibility = View.VISIBLE
            web.requestFocus()
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
        addView(web, matchParent())
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = chrome

        cursor.visibility = View.GONE
        if (customControls) addView(cursor, matchParent())

        hint.text = "Курсор: стрелки — двигать, ОК — нажать, Назад — выключить курсор"
        hint.setTextColor(Color.WHITE)
        hint.setBackgroundColor(Color.argb(170, 0, 0, 0))
        hint.setPadding(24, 12, 24, 12)
        hint.textSize = 14f
        hint.visibility = View.GONE
        if (customControls) {
            addView(
                hint,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursor.cx == 0f && cursor.cy == 0f) {
            cursor.cx = w / 2f
            cursor.cy = h / 2f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (headset == null) {
            headset = HeadsetControls(
                context,
                onToggle = { sendKey(KeyEvent.KEYCODE_SPACE) },
                onStep = { dir -> onStep?.invoke(dir) },
                onSeek = { dir -> seekBy(dir) }
            )
            if (nowTitle.isNotEmpty()) headset?.setNowPlaying(nowTitle, nowSub)
        }
        val act = findActivity(context) ?: return
        val w = act.window
        val orig = w.callback ?: return
        if (orig is KeyHook) return
        activityWindow = w
        origCallback = orig
        w.callback = KeyHook(orig) { e -> handleKey(e) }
    }

    override fun onDetachedFromWindow() {
        headset?.release()
        headset = null
        val w = activityWindow
        val orig = origCallback
        if (w != null && orig != null) w.callback = orig
        activityWindow = null
        origCallback = null
        super.onDetachedFromWindow()
    }

    fun load(url: String) {
        // Открываем плеер внутри страницы с адресом сайта — так, как это делает сам сайт
        val safe = url.replace("\"", "%22")
        val html = "<!DOCTYPE html><html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style>" +
            "</head><body><iframe src=\"$safe\" allow=\"autoplay; fullscreen; encrypted-media\" allowfullscreen></iframe></body></html>"
        web.loadDataWithBaseURL(Config.SITE + "/", html, "text/html", "UTF-8", null)
        web.requestFocus()
    }

    // ---------- управление пультом ----------

    private fun target(): View = customView ?: web

    private fun setCursorMode(on: Boolean) {
        cursorMode = on
        val v = if (on) View.VISIBLE else View.GONE
        cursor.visibility = v
        hint.visibility = v
        cursor.bringToFront()
        hint.bringToFront()
        if (on) hover()
    }

    private fun hover() {
        try {
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cursor.cx, cursor.cy, 0)
            ev.source = InputDevice.SOURCE_MOUSE
            target().dispatchGenericMotionEvent(ev)
            ev.recycle()
        } catch (e: Exception) {
        }
    }

    private fun move(dx: Int, dy: Int, repeat: Int) {
        val step = 22f * density * (1f + minOf(repeat, 12) / 3f)
        cursor.cx = (cursor.cx + dx * step).coerceIn(0f, width.toFloat())
        cursor.cy = (cursor.cy + dy * step).coerceIn(0f, height.toFloat())
        cursor.invalidate()
        hover()
    }

    private fun tap() {
        val x = cursor.cx
        val y = cursor.cy
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        target().dispatchTouchEvent(down)
        down.recycle()
        postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            target().dispatchTouchEvent(up)
            up.recycle()
        }, 60)
    }

    private fun sendKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        target().dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        target().dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
    }

    /** Перемотка примерно на 90 секунд (9 нажатий «вправо» по 10 сек) */
    private fun skip90() {
        for (i in 0 until 9) {
            postDelayed({ sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }, i * 90L)
        }
    }

    /** Название для экрана блокировки и дисплея наушников/авто */
    private var nowTitle = ""
    private var nowSub = ""

    fun setNowPlaying(title: String, subtitle: String) {
        nowTitle = title
        nowSub = subtitle
        headset?.setNowPlaying(title, subtitle)
    }

    /** Перемотка примерно на 30 секунд (3 нажатия «вправо/влево» по 10 сек) */
    private fun seekBy(dir: Int) {
        val code = if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        for (i in 0 until 3) postDelayed({ sendKey(code) }, i * 90L)
    }

    fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        // Медиа-кнопки (наушники, пульты с медиа-клавишами) — одинаково в обоих плеерах
        val firstDown = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (firstDown) {
                    sendKey(KeyEvent.KEYCODE_SPACE)
                    headset?.flip()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (firstDown) onStep?.invoke(1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (firstDown) onStep?.invoke(-1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (firstDown) seekBy(1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (firstDown) seekBy(-1)
                return true
            }
        }
        // После удержания ▲ отпускание клавиши уходит в окно меню серий — сбрасываем флаг заранее
        if (down && e.keyCode == KeyEvent.KEYCODE_DPAD_UP && e.repeatCount == 0) upLong = false
        // Кнопка «Меню» — список серий (в обоих плеерах)
        if (e.keyCode == KeyEvent.KEYCODE_MENU) {
            if (down && e.repeatCount == 0) onMenu?.invoke()
            return true
        }
        return if (customControls) handleCustom(e, down) else handleStandard(e, down)
    }

    /** Alloha: стандартное управление, перехватываем только удержание «вверх» (список серий) */
    private fun handleStandard(e: KeyEvent, down: Boolean): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_DPAD_UP) return false
        if (down) {
            if (e.repeatCount >= 1) {
                if (e.repeatCount == 1) {
                    upLong = true
                    onMenu?.invoke()
                }
                return true
            }
            return false
        }
        if (upLong) {
            upLong = false
            return true
        }
        return false
    }

    /** Kodik: курсор-«мышка», ОК — пауза, ▼ — перемотка на 90 сек */
    private fun handleCustom(e: KeyEvent, down: Boolean): Boolean {
        val first = down && e.repeatCount == 0
        return when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (cursorMode) {
                    if (!down) setCursorMode(false)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (first) {
                    if (cursorMode) tap() else sendKey(KeyEvent.KEYCODE_SPACE)
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) sendKey(KeyEvent.KEYCODE_SPACE)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorMode) {
                    if (down) move(0, -1, e.repeatCount)
                } else if (down) {
                    // удержание — список серий, короткое нажатие (при отпускании) — курсор
                    if (e.repeatCount == 1) {
                        upLong = true
                        onMenu?.invoke()
                    }
                } else if (upLong) {
                    upLong = false
                } else {
                    setCursorMode(true)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorMode) {
                    if (down) move(0, 1, e.repeatCount)
                } else if (first) {
                    skip90()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (cursorMode) {
                    if (down) move(-1, 0, e.repeatCount)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (cursorMode) {
                    if (down) move(1, 0, e.repeatCount)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /** true — если вышли из полноэкранного режима плеера */
    fun exitFullscreen(): Boolean {
        if (customView != null) {
            chrome.onHideCustomView()
            return true
        }
        return false
    }

    fun destroyWeb() {
        web.stopLoading()
        removeAllViews()
        web.destroy()
    }
}

@Composable
fun PlayerScreen(req: Screen.Player, loggedIn: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var layout by remember { mutableStateOf<PlayerLayout?>(null) }
    var currentId by remember { mutableLongStateOf(req.videoId) }
    var showEps by remember { mutableStateOf(false) }
    val watched = Watched.ids

    // кнопки на телефоне показываются по касанию экрана и сами скрываются через несколько секунд
    var controls by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controls, touchTick, showEps) {
        if (controls && !showEps) {
            delay(3500)
            controls = false
        }
    }

    val phone = isCompact()
    LaunchedEffect(req.url) {
        Toast.makeText(
            ctx,
            if (phone) {
                "Коснитесь экрана — появятся кнопки: назад, серии, предыдущая и следующая"
            } else if (req.kodik) {
                "ОК — пауза · ▲ курсор · ▼ +90 сек · Меню или удержание ▲ — список серий · Назад — выход"
            } else {
                "Меню или удержание ▲ — список серий · Назад — выход"
            },
            Toast.LENGTH_LONG
        ).show()
    }
    BackHandler {
        if (layout?.exitFullscreen() != true) onClose()
    }

    val curIdx = req.episodes.indexOfFirst { it.video_id == currentId }

    fun openEpisode(v: VideoItem) {
        val link = v.iframe_url.fixUrl()
        if (link != null && v.video_id != currentId) {
            currentId = v.video_id
            Watched.setLastEp(req.animeId, v.number)
            Watched.add(v.video_id)
            Cache.noteServerLast(req.animeId, v.number.orEmpty(), System.currentTimeMillis() / 1000, v.video_id)
            if (loggedIn) appScope.launch { syncWatched(v.video_id, v.duration) }
            layout?.load(link)
        }
    }

    // предыдущая/следующая серия — ближайшая, у которой есть ссылка на видео
    fun step(dir: Int) {
        if (curIdx < 0) return
        var i = curIdx + dir
        while (i in req.episodes.indices) {
            if (!req.episodes[i].iframe_url.isNullOrBlank()) {
                openEpisode(req.episodes[i])
                return
            }
            i += dir
        }
        Toast.makeText(ctx, if (dir > 0) "Это последняя серия" else "Это первая серия", Toast.LENGTH_SHORT).show()
    }

    // кнопки наушников/пульта «следующая/предыдущая серия»; обновляем при каждой смене серии
    SideEffect { layout?.onStep = { dir -> step(dir) } }
    LaunchedEffect(currentId, layout) {
        val num = req.episodes.firstOrNull { it.video_id == currentId }?.number
        layout?.setNowPlaying(req.title, if (num != null) "Серия $num" else "")
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerLayout(c, req.kodik).also {
                    it.onMenu = { showEps = true }
                    it.onTouched = {
                        controls = true
                        touchTick++
                    }
                    it.load(req.url)
                    layout = it
                }
            },
            onRelease = { it.destroyWeb() }
        )
        if (phone && controls) {
            // у пульта есть кнопки, у телефона — свои, полупрозрачные, у левого края
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 10.dp).alpha(0.65f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconTvButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад") {
                    if (layout?.exitFullscreen() != true) onClose()
                }
                if (req.episodes.isNotEmpty()) {
                    IconTvButton(Icons.Default.Menu, "Серии") { showEps = true }
                    IconTvButton(Icons.Default.KeyboardArrowLeft, "Предыдущая серия") {
                        touchTick++
                        step(-1)
                    }
                    IconTvButton(Icons.Default.KeyboardArrowRight, "Следующая серия") {
                        touchTick++
                        step(1)
                    }
                }
            }
        }
    }

    if (showEps && req.episodes.isNotEmpty()) {
        SideSheet(
            title = "Серия",
            items = req.episodes.map {
                SheetItem(
                    "Серия ${it.number ?: "?"}",
                    selected = it.video_id == currentId,
                    mark = if (it.video_id in watched) "✓ просмотрено" else null
                )
            },
            onDismiss = { showEps = false },
            hideBars = true
        ) { i ->
            showEps = false
            openEpisode(req.episodes[i])
        }
    }
}
