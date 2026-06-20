package com.readest.app

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Readest Lite Android shell.
 *
 * 关键架构说明（基于 wry 0.55 源码逆向）：
 * - wry 在 Rust 侧创建 [RustWebView] 后调用 `activity.setWebView(webview)`，
 *   这会触发 [WryActivity.setWebView] → [onWebViewCreate] 回调。
 * - **关键时序**：onWebViewCreate 在 wry 的初始化序列中间被调用：
 *     1. wry 创建 RustWebView
 *     2. wry 调用 activity.setWebView(webview)  ← onWebViewCreate 在这里
 *     3. wry 调用 webview.loadUrl(初始URL)       ← 加载 tauri.conf.json 配置的 url
 *     4. wry 创建并 setWebViewClient(RustWebViewClient)  ← 会覆盖我们的 client
 *     5. wry 调用 setWebChromeClient(RustWebChromeClient) ← 会覆盖我们的 chrome client
 *     6. wry 调用 addJavascriptInterface(ipc)
 *     7. wry 调用 setContentView(webview)
 * - 因此在 onWebViewCreate 里直接 setWebViewClient 会被 wry 覆盖。
 * - **解决方案**：用 `webView.post { ... }` 把自定义 client 设置推到下一个事件循环，
 *   确保 wry 的初始化序列完全跑完后再覆盖回来。
 *
 * 初始 URL 由 tauri.conf.json 的 app.windows[0].url 决定（已配置为 HOME_URL）。
 * 我们在 Kotlin 侧也保留 HOME_URL 常量用于：
 * - 深链转换
 * - 剪贴板域名匹配
 * - 冷启动兜底 loadUrl（万一 wry 没加载）
 *
 * ⚠️ 部署前必改：把下方 HOME_URL 替换为你自己的阅读站点地址。
 *     同时也要修改 tauri.conf.json 的 app.windows[0].url 字段（两处必须一致）。
 *     剪贴板域名匹配会自动从 HOME_URL 提取 host，无需另外配置。
 */
class MainActivity : TauriActivity() {

    companion object {
        private const val TAG = "ReadestShell"

        /**
         * 硬编码首页地址。必须与 tauri.conf.json 的 app.windows[0].url 保持一致。
         *
         * ⚠️ 部署前必改：把下面这行换成你自己的阅读站点 URL。
         *     同时修改 src-tauri/tauri.conf.json 的 app.windows[0].url。
         */
        private const val HOME_URL = "https://YOUR_READER_DOMAIN.example.com"

        /**
         * 剪贴板分享链接匹配：HOME_URL 域名 或 readest:// 深链，任一命中即弹窗。
         * - https://YOUR_READER_DOMAIN.example.com/s/ID        ← Readest 网页内"分享"按钮复制的格式
         * - https://YOUR_READER_DOMAIN.example.com/...        ← 同域名任意路径
         * - readest://share/ID                  ← App 深链格式
         */
        private val SHARE_LINK_REGEX: Regex by lazy {
            val host = try {
                Uri.parse(HOME_URL).host?.replace(".", "\\.") ?: ""
            } catch (_: Exception) { "" }
            // host 为空时只匹配深链；否则两者都匹配
            if (host.isEmpty()) {
                Regex("readest://", RegexOption.IGNORE_CASE)
            } else {
                Regex("$host|readest://", RegexOption.IGNORE_CASE)
            }
        }

        private const val DEEP_LINK_SCHEME = "readest"
        private const val REQ_STORAGE = 10086

        /** JavascriptInterface 名称，注入给 WebView 用于回传 blob 数据 */
        private const val BLOB_BRIDGE_NAME = "AndroidBlobDownloader"

        /** JavascriptInterface 名称，注入给错误页用于触发重新加载 */
        private const val ERROR_PAGE_BRIDGE_NAME = "AndroidErrorPage"

        /** 错误页 URL 标记，用于在 onPageFinished 识别"当前在错误页" */
        private const val ERROR_PAGE_URL = "about:error-page"

        private data class PendingDownload(
            val url: String,
            val userAgent: String,
            val contentDisposition: String,
            val mimetype: String,
            val contentLength: Long
        )

        /**
         * 注入到 WebView 的 JS 拦截脚本。
         *
         * 拦截策略：
         * 1. 监听 document 的 click 事件（捕获阶段），拦截 <a download> 点击
         * 2. 劫持 HTMLAnchorElement.prototype.click（有些库直接调 a.click()）
         * 3. 拦截到 blob: 或 data: URL 后，fetch 出 Blob，FileReader.readAsDataURL 转 base64
         * 4. 通过 AndroidBlobDownloader.saveBlob(fileName, mime, base64) 回传给 Kotlin 写文件
         *
         * 幂等：用 window.__blobIntercepted 标记防止重复注入。
         */
        private const val BLOB_INTERCEPT_SCRIPT = """
(function() {
  if (window.__blobIntercepted) return;
  window.__blobIntercepted = true;

  var bridge = window.AndroidBlobDownloader;
  if (!bridge) { console.warn('[BlobDownload] bridge not found'); return; }

  function readAsBase64(url, cb) {
    try {
      fetch(url).then(function(resp) {
        return resp.blob();
      }).then(function(blob) {
        var reader = new FileReader();
        reader.onloadend = function() {
          var dataUrl = reader.result || '';
          var idx = dataUrl.indexOf(',');
          if (idx < 0) { bridge.log('[BlobDownload] dataUrl has no comma'); return; }
          var header = dataUrl.substring(5, idx);
          var parts = header.split(';');
          var mime = parts[0] || 'application/octet-stream';
          var b64 = dataUrl.substring(idx + 1);
          cb(mime, b64);
        };
        reader.onerror = function(e) {
          bridge.log('[BlobDownload] FileReader error: ' + e);
        };
        reader.readAsDataURL(blob);
      }).catch(function(e) {
        bridge.log('[BlobDownload] fetch error: ' + (e && e.message || e));
      });
    } catch(e) {
      bridge.log('[BlobDownload] readAsBase64 exception: ' + e);
    }
  }

  function ensureExtension(fileName, mime) {
    if (/\.[a-z0-9]+$/i.test(fileName)) return fileName;
    var extMap = {
      'application/epub+zip': 'epub',
      'application/pdf': 'pdf',
      'application/vnd.amazon.ebook': 'azw',
      'application/x-mobipocket-ebook': 'mobi',
      'text/plain': 'txt',
      'application/zip': 'zip',
      'image/jpeg': 'jpg',
      'image/png': 'png',
      'image/webp': 'webp'
    };
    var ext = extMap[mime] || 'bin';
    return fileName + '.' + ext;
  }

  document.addEventListener('click', function(e) {
    var a = e.target;
    while (a && a.tagName !== 'A') a = a.parentNode;
    if (!a || a.tagName !== 'A') return;
    var href = a.href || '';
    if (!href) return;
    var isBlob = href.indexOf('blob:') === 0;
    var isData = href.indexOf('data:') === 0;
    if (!isBlob && !isData) return;
    if (!a.hasAttribute('download')) return;
    e.preventDefault();
    e.stopPropagation();
    var downloadName = a.getAttribute('download') || 'download';
    bridge.log('[BlobDownload] intercepted <a download> click: ' + href.substring(0, 60));
    readAsBase64(href, function(mime, b64) {
      var fileName = ensureExtension(downloadName, mime);
      bridge.saveBlob(fileName, mime, b64);
    });
  }, true);

  var origAnchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function() {
    var href = this.href || '';
    var isBlob = href.indexOf('blob:') === 0;
    var isData = href.indexOf('data:') === 0;
    if ((isBlob || isData) && this.hasAttribute && this.hasAttribute('download')) {
      var downloadName = (this.getAttribute && this.getAttribute('download')) || 'download';
      bridge.log('[BlobDownload] intercepted a.click(): ' + href.substring(0, 60));
      readAsBase64(href, function(mime, b64) {
        var fileName = ensureExtension(downloadName, mime);
        bridge.saveBlob(fileName, mime, b64);
      });
      return;
    }
    return origAnchorClick.apply(this, arguments);
  };

  bridge.log('[BlobDownload] intercept script installed');
})();
"""
    }

    /** 禁用 WryActivity 默认返回键逻辑，自己实现 */
    override val handleBackNavigation: Boolean = false

    private var webView: WebView? = null
    private var firstPageLoaded = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Array<String>>
    private var pendingDownload: PendingDownload? = null
    private var coldStartDone = false

    /**
     * 当前是否正在加载自定义错误页。用于防止 onReceivedError 死循环：
     * 错误页本身也可能加载失败（虽然不太可能，因为它是 data: URL），如果失败，
     * 不要再次触发错误页加载。
     */
    @Volatile
    private var isLoadingErrorPage = false

    /**
     * 上一次加载失败的 URL，用于"重新加载"按钮。如果为空则用 HOME_URL。
     */
    @Volatile
    private var lastFailedUrl: String? = null

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        // registerForActivityResult 必须在 super.onCreate 之前注册
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            val cb = fileUploadCallback
            fileUploadCallback = null
            if (uris != null && uris.isNotEmpty()) {
                uris.forEach { u ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                cb?.onReceiveValue(uris.toTypedArray())
            } else {
                cb?.onReceiveValue(null)
            }
        }

        // 注意：不再调用 enableEdgeToEdge()，那是让系统栏透明可见的 API；
        // 我们要的是相反效果——隐藏状态栏 + 导航栏，进入沉浸式全屏。
        super.onCreate(savedInstanceState)

        // 配置沉浸式全屏：WindowCompat.setDecorFitsDecorFitsSystemWindows(false) 让内容延伸到系统栏下方，
        // WindowInsetsControllerCompat.hide() 真正隐藏系统栏。
        hideSystemBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView ?: return
                if (wv.canGoBack()) {
                    wv.goBack()
                } else {
                    showExitConfirmDialog()
                }
            }
        })

        // 冷启动剪贴板检测
        checkClipboardForShareLink()
        coldStartDone = true
    }

    /**
     * 隐藏状态栏 + 导航栏，进入沉浸式全屏。
     *
     * 行为说明：
     * - 系统栏完全隐藏，WebView 内容占据整块屏幕
     * - 用户从屏幕边缘向内滑动会短暂唤出系统栏（沉浸式粘性 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）
     * - 唤出后无操作 1-2 秒自动重新隐藏
     * - 弹窗（文件选择器、对话框等）关闭后系统栏可能恢复，需要在 onWindowFocusChanged 重新调用
     * - 从后台切回前台也会恢复，需要在 onResume 重新调用
     *
     * 兼容性：WindowInsetsControllerCompat 是 androidx.core 1.5+ 提供的统一 API，
     * 内部会自动按 API 版本分发到 WindowInsetsController（API 30+）或老的 systemUiFlags（API 26-29）。
     */
    private fun hideSystemBars() {
        val window = window ?: return
        // 让内容延伸到系统栏下方（不预留空间）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // 隐藏状态栏 + 导航栏
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // 沉浸式粘性：滑动可短暂唤出，自动隐藏
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 弹窗/通知/其他 Activity 返回后，系统栏可能恢复，重新隐藏
        if (hasFocus) {
            hideSystemBars()
        }
    }

    /**
     * Tauri 创建完 RustWebView 后回调此方法。
     *
     * 时序说明：此回调在 wry 初始化序列的中间被调用，此时 wry 还没设置
     * RustWebViewClient / RustWebChromeClient。如果我们在这里直接 setWebViewClient，
     * 会被 wry 后续的 setWebViewClient 覆盖。
     *
     * 解决方案：用 post { } 把自定义 client 设置推到下一个事件循环，
     * 确保 wry 的初始化序列完全跑完后再覆盖回来。
     */
    override fun onWebViewCreate(webView: WebView) {
        super.onWebViewCreate(webView)
        this.webView = webView

        // 1. WebSettings 可以立即设置（不会被覆盖）
        configureWebSettings(webView)

        // 2. client / download listener 用 post 推到下一个事件循环
        //    这样 wry 的 setWebViewClient / setWebChromeClient 会先执行
        webView.post {
            installCustomClients(webView)
            // 兜底：如果 wry 没自动加载 URL（或加载了 tauri://），强制加载 HOME_URL
            val current = webView.url
            if (current.isNullOrBlank() || current.startsWith("tauri://") || current.startsWith("about:blank")) {
                Log.i(TAG, "current url is $current, fallback to $HOME_URL")
                webView.loadUrl(HOME_URL)
            }
            // 处理冷启动深链
            val initialUrl = resolveInitialUrl(intent)
            if (initialUrl != HOME_URL) {
                Log.i(TAG, "deep link detected, loading $initialUrl")
                webView.loadUrl(initialUrl)
            }
            // WebView 创建完成后再隐藏一次系统栏（wry 的 setContentView 之后 decorView 才稳定）
            hideSystemBars()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val wv = webView ?: return
        val url = resolveInitialUrl(intent)
        wv.post { wv.loadUrl(url) }
    }

    override fun onResume() {
        super.onResume()
        // 从后台切回前台时系统栏会自动恢复，重新隐藏
        hideSystemBars()
        if (coldStartDone) {
            checkClipboardForShareLink()
        }
    }

    // =====================================================================================
    // WebView 配置
    // =====================================================================================

    private fun configureWebSettings(wv: WebView) {
        val s: WebSettings = wv.settings
        // RustWebView init 已设置：javaScriptEnabled, domStorageEnabled, databaseEnabled,
        // mediaPlaybackRequiresUserGesture, javaScriptCanOpenWindowsAutomatically, geolocation
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.allowFileAccessFromFileURLs = true
        s.allowUniversalAccessFromFileURLs = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportMultipleWindows(false)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    /**
     * 设置自定义 WebViewClient / WebChromeClient / DownloadListener。
     * 必须在 wry 设置完它自己的 client 之后调用（用 post { } 推迟）。
     */
    private fun installCustomClients(wv: WebView) {
        wv.webViewClient = ShellWebViewClient()
        wv.webChromeClient = ShellWebChromeClient()
        wv.setDownloadListener(ShellDownloadListener())

        // 注入 blob 下载桥：JS 端拦截 <a download> 点击和 createObjectURL，
        // 把 blob 数据 base64 编码后通过 JavascriptInterface 回传给 Kotlin。
        // 注意：wry 之前调过 addJavascriptInterface(ipc, "ipc")，我们用不同的名字
        // AndroidBlobDownloader，避免冲突。
        wv.addJavascriptInterface(BlobDownloadBridge(wv), BLOB_BRIDGE_NAME)
        // 注入拦截脚本（在每个页面加载前/后执行）
        wv.evaluateJavascript(BLOB_INTERCEPT_SCRIPT, null)

        Log.i(TAG, "custom clients installed (with blob download bridge)")
    }

    // =====================================================================================
    // WebViewClient
    // =====================================================================================

    private inner class ShellWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val uri = request?.url ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            val url = uri.toString()

            return when {
                // tauri:// 协议：不拦截（理论上不应该出现，因为 tauri.conf.json 配置了 External URL）
                scheme == "tauri" -> false

                // http/https：直接在 WebView 加载（返回 false）
                scheme == "http" || scheme == "https" -> false

                // readest:// 深链：转换为 web URL 在 WebView 内加载
                scheme == DEEP_LINK_SCHEME -> {
                    val webUrl = convertReadestDeepLink(uri)
                    view?.post { view?.loadUrl(webUrl) }
                    true
                }

                // intent:// scheme
                scheme == "intent" -> {
                    try {
                        val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (parsed != null) {
                            if (packageManager.resolveActivity(parsed, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                                startActivity(parsed)
                            } else {
                                parsed.`package`?.let {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$it")))
                                }
                            }
                        }
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                // tel: mailto: sms: etc.
                else -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // 首页加载完成后清空历史，让返回键的"页面栈仅剩主页"判定生效
            if (!firstPageLoaded && url != null && (url == HOME_URL || url.startsWith(HOME_URL))) {
                view?.post {
                    view?.clearHistory()
                    firstPageLoaded = true
                }
            }
            // 每次页面加载完成后重新注入 blob 拦截脚本（SPA 路由切换后会失效）
            view?.evaluateJavascript(BLOB_INTERCEPT_SCRIPT, null)
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(TAG, "main frame error: ${error?.description} (${request.url})")
            }
        }
    }

    // =====================================================================================
    // WebChromeClient：文件上传
    // =====================================================================================

    private inner class ShellWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = filePathCallback

            val acceptTypes = fileChooserParams?.acceptTypes?.takeIf { it.isNotEmpty() && it.any { t -> t.isNotBlank() } }
                ?: arrayOf("*/*")

            return try {
                fileChooserLauncher.launch(acceptTypes)
                true
            } catch (e: Exception) {
                Log.w(TAG, "file chooser launch failed", e)
                fileUploadCallback = null
                false
            }
        }
    }

    // =====================================================================================
    // DownloadListener
    // =====================================================================================

    private inner class ShellDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimetype: String,
            contentLength: Long
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    pendingDownload = PendingDownload(url, userAgent, contentDisposition, mimetype, contentLength)
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQ_STORAGE
                    )
                    return
                }
            }
            enqueueDownload(url, userAgent, contentDisposition, mimetype, contentLength)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val pending = pendingDownload
            pendingDownload = null
            if (granted && pending != null) {
                enqueueDownload(
                    pending.url, pending.userAgent,
                    pending.contentDisposition, pending.mimetype, pending.contentLength
                )
            } else if (!granted) {
                Toast.makeText(this, "存储权限被拒绝，无法下载文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimetype: String,
        contentLength: Long
    ) {
        // blob: URL 不能直接交给 DownloadManager（它走 HTTP 协议，拿不到 blob 数据）。
        // 这种情况由注入的 BLOB_INTERCEPT_SCRIPT 拦截处理：JS 端读取 blob 内容、
        // base64 编码后通过 BlobDownloadBridge.saveBlob() 回传到 Kotlin 写文件。
        // 这里只做兜底提示。
        if (url.startsWith("blob:", ignoreCase = true)) {
            Log.w(TAG, "blob: URL reached DownloadListener, JS intercept may have failed: $url")
            Toast.makeText(this, "正在准备下载…", Toast.LENGTH_SHORT).show()
            return
        }

        // data: URL（base64 内联）：解码后直接写文件
        if (url.startsWith("data:", ignoreCase = true)) {
            saveDataUrl(url, contentDisposition, mimetype)
            return
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype.takeIf { it.isNotBlank() } ?: "*/*")
                addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
                setDescription("通过阅读下载")
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "开始下载：${URLUtil.guessFileName(url, contentDisposition, mimetype)}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "enqueue download failed", e)
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (_: Exception) {
                Toast.makeText(this, "下载失败：$url", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 处理 data: URL（base64 内联数据），解码后写入 Download 目录。
     * 格式：data:[<mimetype>][;base64],<data>
     */
    private fun saveDataUrl(dataUrl: String, contentDisposition: String, fallbackMime: String) {
        try {
            val commaIdx = dataUrl.indexOf(',')
            if (commaIdx < 0) {
                Toast.makeText(this, "下载失败：data URL 格式错误", Toast.LENGTH_SHORT).show()
                return
            }
            val header = dataUrl.substring(5, commaIdx)  // 去掉 "data:" 前缀
            val payload = dataUrl.substring(commaIdx + 1)
            val parts = header.split(";")
            val mime = parts.firstOrNull { it.contains("/") } ?: fallbackMime
            val isBase64 = parts.any { it.equals("base64", true) }

            val fileName = URLUtil.guessFileName("", contentDisposition, mime)
            val bytes = if (isBase64) Base64.decode(payload, Base64.DEFAULT) else payload.toByteArray()
            writeBytesToDownloads(fileName, bytes, mime)
        } catch (e: Exception) {
            Log.e(TAG, "saveDataUrl failed", e)
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 将字节数组写入公共 Download 目录，并通过 MediaScanner 让它立刻出现在系统文件管理器里。
     * API 29+ 不需要 WRITE_EXTERNAL_STORAGE 权限。
     */
    private fun writeBytesToDownloads(fileName: String, bytes: ByteArray, mime: String) {
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            // 防止重名覆盖，加序号
            var target = File(downloads, fileName)
            var n = 1
            val dotIdx = fileName.lastIndexOf('.')
            val base = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
            val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""
            while (target.exists()) {
                target = File(downloads, "$base ($n)$ext")
                n++
            }
            FileOutputStream(target).use { it.write(bytes) }

            // 通知 MediaScanner 扫描，让文件立刻在系统文件管理器里可见
            val mimeFixed = mime.takeIf { it.isNotBlank() } ?: "*/*"
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(target)
                setPackage("com.android.providers.media")
            }
            sendBroadcast(intent)

            Toast.makeText(this, "已保存到 Download/${target.name}", Toast.LENGTH_LONG).show()
            Log.i(TAG, "blob/data saved: ${target.absolutePath} (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "writeBytesToDownloads failed", e)
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =====================================================================================
    // Blob 下载桥：JS → Kotlin 回传 blob 数据
    // =====================================================================================

    /**
     * JavascriptInterface，注入到 WebView 中供 JS 调用。
     *
     * JS 端流程：
     * 1. 拦截 <a download> 点击 或 createObjectURL 调用
     * 2. fetch(blobUrl) → response.blob() → FileReader.readAsDataURL → base64
     * 3. 调用 AndroidBlobDownloader.saveBlob(fileName, mimeType, base64)
     *
     * ⚠️ @JavascriptInterface 注解的方法会被暴露给网页 JS，仅保留必要的入口。
     */
    inner class BlobDownloadBridge(private val webView: WebView) {
        @JavascriptInterface
        fun saveBlob(fileName: String, mimeType: String, base64Data: String) {
            Log.i(TAG, "saveBlob: fileName=$fileName, mime=$mimeType, base64Length=${base64Data.length}")
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                runOnUiThread {
                    writeBytesToDownloads(fileName, bytes, mimeType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveBlob decode failed", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** 调试用：JS 端可以调这个方法打印日志到 logcat */
        @JavascriptInterface
        fun log(msg: String) {
            Log.i(TAG, "[JS] $msg")
        }
    }

    /**
     * 注入到 WebView 的 JS 拦截脚本。
     *
     * 拦截策略：
     * 1. 劫持 window.URL.createObjectURL，记录 blob URL → Blob 的映射，便于后续读取
     * 2. 监听 document 的 click 事件（捕获阶段），拦截 <a download> 点击：
     *    - 如果 href 是 blob: 或 data:，fetch 出 Blob 数据，base64 编码，调 saveBlob
     *    - 阻止默认跳转（避免触发 DownloadListener 报错）
     * 3. 同时劫持 a.click，覆盖更多触发路径
     *
     * 脚本是 IIFE，避免污染全局；幂等，重复注入无副作用（用 window.__blobIntercepted 标记）。
     */

    // =====================================================================================
    // 剪贴板分享链接检测
    // =====================================================================================

    private fun checkClipboardForShareLink() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip == null) {
                Log.d(TAG, "clipboard: primaryClip is null")
                return
            }
            if (clip.itemCount == 0) {
                Log.d(TAG, "clipboard: itemCount == 0")
                return
            }
            val raw = clip.getItemAt(0).coerceToText(this).toString().trim()
            if (raw.isEmpty()) {
                Log.d(TAG, "clipboard: raw text empty")
                return
            }
            Log.d(TAG, "clipboard raw (first 100 chars): ${raw.take(100)}")

            // 提取 URL：先找 https?:// 链接，再找 readest:// 深链
            val urlCandidate = extractFirstUrl(raw)
            if (urlCandidate == null) {
                Log.d(TAG, "clipboard: no URL found in text")
                return
            }
            Log.d(TAG, "clipboard extracted url: $urlCandidate")

            // 匹配 HOME_URL 域名 或 readest:// 深链
            if (!SHARE_LINK_REGEX.containsMatchIn(urlCandidate)) {
                Log.d(TAG, "clipboard: URL does not match share link regex ($SHARE_LINK_REGEX)")
                return
            }

            if (ClipboardMemory.wasShown(urlCandidate)) {
                Log.d(TAG, "clipboard: already shown this URL in this process, skip")
                return
            }

            Log.i(TAG, "clipboard: showing share link dialog for $urlCandidate")
            AlertDialog.Builder(this)
                .setTitle("检测到书籍分享链接")
                .setMessage("是否打开？\n$urlCandidate")
                .setPositiveButton("确定") { _, _ ->
                    ClipboardMemory.markShown(urlCandidate)
                    // 如果是 readest:// 深链，走深链转换；否则直接 loadUrl
                    val loadTarget = if (urlCandidate.startsWith("readest://", ignoreCase = true)) {
                        convertReadestDeepLink(Uri.parse(urlCandidate))
                    } else {
                        urlCandidate
                    }
                    webView?.post { webView?.loadUrl(loadTarget) }
                }
                .setNegativeButton("取消") { _, _ ->
                    ClipboardMemory.markShown(urlCandidate)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "clipboard check failed", e)
        }
    }

    /**
     * 从剪贴板文本中提取第一个 URL：
     * - 优先匹配 https?:// 链接
     * - 其次匹配 readest:// 深链
     * - 找不到返回 null
     */
    private fun extractFirstUrl(text: String): String? {
        val httpPattern = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
        httpPattern.find(text)?.let { return it.value }
        val deepLinkPattern = Regex("readest://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
        deepLinkPattern.find(text)?.let { return it.value }
        return null
    }

    // =====================================================================================
    // 退出确认弹窗
    // =====================================================================================

    private fun showExitConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("确定退出应用？")
            .setPositiveButton("确定") { _, _ ->
                finishAffinity()
                exitProcess(0)
            }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .show()
    }

    // =====================================================================================
    // 深链 readest:// → HOME_URL + #/...
    // =====================================================================================

    private fun resolveInitialUrl(intent: Intent?): String {
        val data = intent?.data ?: return HOME_URL
        if (data.scheme?.equals(DEEP_LINK_SCHEME, ignoreCase = true) == true) {
            return convertReadestDeepLink(data)
        }
        return HOME_URL
    }

    /**
     * 深链转 web URL。Readest 实际分享链接格式为 `https://reader.example.com/s/{ID}`，
     * 因此深链规则：
     *   readest://share/ID       →  HOME_URL + /s/ID
     *   readest://share/ID?q=1   →  HOME_URL + /s/ID?q=1
     *   readest://PATH           →  HOME_URL + /PATH  （兼容其他路径）
     *
     * 注意：HOME_URL 末尾不带斜杠（如 https://YOUR_READER_DOMAIN.example.com）。
     */
    private fun convertReadestDeepLink(uri: Uri): String {
        val host = uri.host ?: ""
        val path = uri.path ?: ""
        val query = uri.query?.let { "?$it" } ?: ""
        val fragment = uri.fragment?.let { "#$it" } ?: ""

        // 拼接 path：readest://share/ID  →  host="share", path="/ID"
        // 我们要的最终 URL 是 HOME_URL + "/s/ID"
        val composed = when {
            // host == "share" 且 path 形如 "/ID"：转成 /s/ID
            host.equals("share", ignoreCase = true) && path.startsWith("/") -> {
                "/s" + path + query + fragment
            }
            // host == "share" 且 path 为空：直接转成 /s
            host.equals("share", ignoreCase = true) && path.isEmpty() -> {
                "/s" + query + fragment
            }
            // 其他 host（如 readest://books/xxx）：原样拼接成 /host/path
            path.startsWith("/") -> "/$host$path$query$fragment"
            path.isNotEmpty() -> "/$host/$path$query$fragment"
            else -> "/$host$query$fragment"
        }
        return HOME_URL.trimEnd('/') + composed
    }
}
