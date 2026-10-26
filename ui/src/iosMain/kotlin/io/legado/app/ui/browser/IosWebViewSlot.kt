@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.legado.app.help.http.IosWebViewCookieSession
import io.legado.app.utils.canonicalizeUri
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.setValue
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * iOS 真实 WebView slot: WKWebView 经 UIKitView 嵌入 Compose。
 *
 * 对照 app 端 AndroidWebView: JS/domStorage + onPageFinished cookie 同步。
 * WKWebView 默认启用 JS / DOM storage / mixed content, 无需显式配置;
 * didFinish 将当前站点的完整 Cookie 属性写入书源隔离存储，网络发送时再按 URL 匹配。
 *
 * 加载语义对照 AndroidWebView: [WebViewConfig.headerMap] 注入 loadRequest 请求头,
 * [WebViewConfig.html] 非空时 loadHTMLString (POST body/data: 解包/原始 HTML);
 * [WebViewCallbacks.host] 暴露 evaluateJavaScript/canGoBack/goBack 供路由执行验证回传。
 *
 * delegate 弱引用: WKWebView.navigationDelegate 是 weak, 用 remember 持有 navDelegate
 * 强引用防 GC 回收 (模式同 IosFilePicker activeDelegates)。
 */
@Composable
fun IosWebViewSlot(
    config: WebViewConfig,
    modifier: Modifier = Modifier,
    callbacks: WebViewCallbacks = WebViewCallbacks(),
) {
    val webView = remember(config.sourceKey) {
        WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        })
    }
    val cookies = remember(webView) { IosWebViewCookieSession(config.sourceKey, config.url) }
    val navDelegate = remember(webView, callbacks) { WebViewNavDelegate(callbacks, cookies) }
    // WKWebView.UIDelegate 同为 weak, 一并 remember 持强引用
    val uiDelegate = remember { WebViewUiDelegate(callbacks) }

    SideEffect {
        webView.navigationDelegate = navDelegate
        webView.UIDelegate = uiDelegate
        callbacks.host = WebViewHostImpl(webView)
    }

    UIKitView(
        factory = { webView },
        modifier = modifier,
        update = { view ->
            val html = config.html
            if (html == null) {
                val cur = view.URL?.absoluteString
                if (cur != config.url) {
                    cookies.seed(view.configuration.websiteDataStore.httpCookieStore) { loadWithHeaders(view, config) }
                }
            } else if (view.tag != HTML_LOADED_TAG) {
                view.tag = HTML_LOADED_TAG
                cookies.seed(view.configuration.websiteDataStore.httpCookieStore) { view.loadHTMLString(
                    html,
                    baseURL = config.url.takeIf { it.isNotBlank() }
                        ?.let { NSURL.URLWithString(it.canonicalizeUri()) },
                ) }
            }
        },
    )
}

/** 带 headerMap 的请求加载 (loadRequest 请求头注入, 对照 Android loadUrl(url, headers))。 */
private fun loadWithHeaders(webView: WKWebView, config: WebViewConfig) {
    val nsUrl = NSURL.URLWithString(config.url.canonicalizeUri()) ?: return
    val request = NSMutableURLRequest(uRL = nsUrl)
    config.headerMap.filterKeys { !io.legado.app.help.http.isInternalHttpHeader(it) }
        .forEach { (key, value) -> request.setValue(value, forHTTPHeaderField = key) }
    webView.loadRequest(request)
}

/** html 模式已加载标记 (避免每次重组重复 loadHTMLString); UIView.tag 为 NSInteger (Long)。 */
private const val HTML_LOADED_TAG = 1L

/**
 * WKNavigationDelegate: 仅监听 didFinish, 取 WKHTTPCookieStore cookie 同步到业务层 store,
 * 并回调 [WebViewCallbacks.onPageFinished]。
 *
 * 空快照也会同步以处理注销；跨站登录 Cookie 保留原域，不复制到书源 URL。
 */
private class WebViewNavDelegate(
    private val callbacks: WebViewCallbacks,
    private val cookieSession: IosWebViewCookieSession,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        val url = webView.URL?.absoluteString ?: return
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            if (webView.URL?.absoluteString != url) return@getAllCookies
            cookieSession.sync(cookies.orEmpty().filterIsInstance<NSHTTPCookie>())
            callbacks.onPageFinished?.invoke(url)
        }
        // 页面 URL 状态同步 (页面内跳转后菜单取最新链接)
        callbacks.onUrlChanged?.invoke(url)
    }
}

/**
 * WKUIDelegate: 网页 `window.close()` → [WebViewCallbacks.onCloseWindow]
 * (对照 Android CommonWebChromeClient.onCloseWindow; 未接线时不做任何事, 等价 super)。
 */
private class WebViewUiDelegate(
    private val callbacks: WebViewCallbacks,
) : NSObject(), WKUIDelegateProtocol {
    override fun webViewDidClose(webView: WKWebView) {
        callbacks.onCloseWindow?.invoke()
    }
}

/** [WebViewHost] 的 iOS 实现, 直通 WKWebView。 */
private class WebViewHostImpl(private val webView: WKWebView) : WebViewHost {
    override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        webView.evaluateJavaScript(script) { result, error ->
            onResult(
                when {
                    error != null -> null
                    // 布尔结果 (如 `!!window._cf_chl_opt`) 归一为 "true"/"false", 对齐 Android
                    result is Boolean -> if (result) "true" else "false"
                    result is NSNumber -> if (result.boolValue) "true" else "false"
                    else -> result?.toString()
                }
            )
        }
    }

    override fun canGoBack(): Boolean = webView.canGoBack

    override fun goBack() {
        webView.goBack()
    }

    override fun getUrl(): String? = webView.URL?.absoluteString

    override fun reload() {
        webView.reload()
    }
}
