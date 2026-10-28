package app.yummytv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
class PlayerLayout(context: Context) : FrameLayout(context) {
    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private val web = WebView(context)
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

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
fun PlayerScreen(title: String, url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var layout by remember { mutableStateOf<PlayerLayout?>(null) }
    LaunchedEffect(url) { Toast.makeText(ctx, url, Toast.LENGTH_LONG).show() }
    BackHandler {
        if (layout?.exitFullscreen() != true) onClose()
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerLayout(ctx).also {
                it.load(url)
                layout = it
            }
        },
        onRelease = { it.destroyWeb() }
    )
}
