package com.readest.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.system.exitProcess

/**
 * Readest Lite Android shell.
 *
 * 行为概述：
 * - 启动后直接加载硬编码首页 [HOME_URL]，全程使用系统 WebView 内核。
 * - 不自定义顶/底导航条，全屏渲染网页，竖屏锁定。
 * - _blank 链接统一在当前 WebView 内打开（supportMultipleWindows=false + shouldOverrideUrlLoading 拦截）。
 * - 前台激活时读取剪贴板，匹配 HOME_URL 所属域名的链接则弹系统原生询问框，进程内去重（不持久化）。
 * - 系统返回键优先弹出 WebView 页面栈，仅剩主页时弹退出确认框。
 * - 文件上传：拦截 onShowFileChooser 唤起系统文件选择器。
 * - 文件下载：拦截 setDownloadListener 调用系统 DownloadManager，落盘到公共 Download 目录。
 * - 深链：注册 readest:// scheme，share 路径自动转为 HOME_URL + #/share/... 在 WebView 内加载。
 *
 * ⚠️ 部署前必改：把下方 HOME_URL 替换为你自己的阅读站点地址。
 *     剪贴板域名匹配会自动从 HOME_URL 提取 host，无需另外配置。
 *
 * WebView 配置：硬件加速、JS 完整支持、DOM Storage、本地文件访问全部放开。
 */
class MainActivity : TauriActivity() {

    companion object {
        private const val TAG = "ReadestShell"

        /**
         * 硬编码首页地址，启动直接加载。
         *
         * ⚠️ 部署前必改：把下面这行换成你自己的阅读站点 URL。
         *     例如：private const val HOME_URL = "https://your-reader.example.com"
         *
         * 剪贴板分享链接的域名匹配会自动从 HOME_URL 解析 host，无需另外配置。
         */
        private const val HOME_URL = "https://YOUR_READER_DOMAIN.example.com"

        /** 剪贴板匹配域名（从 HOME_URL 自动派生，支持子域 / 路径 / 查询参数都算） */
        private val SHARE_LINK_REGEX: Regex by lazy {
            val host = try {
                Uri.parse(HOME_URL).host?.replace(".", "\\.") ?: ""
            } catch (_: Exception) { "" }
            Regex(host)  // host 为空时 regex 永远不匹配，等价于禁用剪贴板检测
        }

        /** 深链 scheme */
        private const val DEEP_LINK_SCHEME = "readest"

        /** 运行时权限请求码：WRITE_EXTERNAL_STORAGE（仅 API 26-28 用得到） */
        private const val REQ_STORAGE = 10086

        /** 临时保存被权限中断的下载请求 */
        private data class PendingDownload(
            val url: String,
            val userAgent: String,
            val contentDisposition: String,
            val mimetype: String,
            val contentLength: Long
        )
    }

    /** 当前 WebView 实例（MainActivity 全权管理） */
    private var webView: WebView? = null

    /** 首次主页面加载完成标记，用于在此之后 clearHistory 让返回栈从主页开始计数 */
    private var firstPageLoaded = false

    /** 文件上传回调（WebView → 系统文件选择器 → 回传 Uri） */
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    /** 文件选择器 launcher */
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Array<String>>

    /** 被权限中断的下载请求，权限授予后自动重试 */
    private var pendingDownload: PendingDownload? = null

    /** 用于判断 onResume 是冷启动还是切回前台：冷启动后第一次 onResume 跳过剪贴板检测（已在 onCreate 处理） */
    private var coldStartDone = false

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 注册文件选择器
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            val cb = fileUploadCallback
            fileUploadCallback = null
            if (uris != null && uris.isNotEmpty()) {
                // 尝试获取持久化读权限（部分文档提供方支持，便于 WebView 长时间读取）
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
                // 用户取消
                cb?.onReceiveValue(null)
            }
        }

        // 注册系统返回键回调
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

        // 创建并配置 WebView，替换 Tauri 默认内容视图
        val wv = WebView(this)
        configureWebView(wv)
        setContentView(wv)
        this.webView = wv

        // 处理冷启动深链
        val initialUrl = resolveInitialUrl(intent)
        wv.loadUrl(initialUrl)

        // 冷启动剪贴板检测（与切回前台共用一套逻辑，但只执行一次）
        checkClipboardForShareLink()

        coldStartDone = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val wv = webView ?: return
        val url = resolveInitialUrl(intent)
        if (url != HOME_URL || wv.url != url) {
            wv.loadUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        // 仅在切回前台时检测（冷启动已在 onCreate 检测过一次）
        if (coldStartDone) {
            checkClipboardForShareLink()
        }
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    // =====================================================================================
    // WebView 配置
    // =====================================================================================

    private fun configureWebView(wv: WebView) {
        // 硬件加速（Activity 层已默认开启 windowFullscreen=false，这里靠 AndroidManifest 的 hardwareAccelerated=true）
        wv.isVerticalScrollBarEnabled = true
        wv.isHorizontalScrollBarEnabled = false

        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true            // localStorage / sessionStorage - 持久化登录态、阅读偏好
        s.databaseEnabled = true              // WebSQL（已废弃但部分网页仍用）
        s.allowFileAccess = true              // 本地文件访问
        s.allowContentAccess = true           // content:// 访问
        s.allowFileAccessFromFileURLs = true  // file:// 上下文里也能读 file://
        s.allowUniversalAccessFromFileURLs = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportMultipleWindows(false)     // 关键：_blank 不开新窗口，统一在当前 WebView 加载
        // User-Agent 不修改，使用系统 WebView 默认 UA

        // Cookie 持久化（登录会话）
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = ShellWebViewClient()
        wv.webChromeClient = ShellWebChromeClient()
        wv.setDownloadListener(ShellDownloadListener())
    }

    // =====================================================================================
    // WebViewClient：URL 路由 / _blank 拦截 / 历史栈管理
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
                // 内部 http/https：直接在 WebView 加载（返回 false）
                scheme == "http" || scheme == "https" -> {
                    // 第三方外站链接可选在外部浏览器打开；本需求要求"不会跳出 App"，所以一律内部加载
                    false
                }

                // readest:// 深链：转换为 web URL 在 WebView 内加载
                scheme == DEEP_LINK_SCHEME -> {
                    val webUrl = convertReadestDeepLink(uri)
                    view?.loadUrl(webUrl)
                    true
                }

                // intent:// scheme：尝试解析为 Intent 并交给系统
                scheme == "intent" -> {
                    try {
                        val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (parsed != null) {
                            if (packageManager.resolveActivity(parsed, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                                startActivity(parsed)
                            } else {
                                // 跳应用商店
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

                // tel: mailto: sms: etc. 交给系统
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
            if (!firstPageLoaded) {
                view?.clearHistory()
                firstPageLoaded = true
            }
            // 同步 Cookie 长期持久化
            CookieManager.getInstance().flush()
        }
    }

    // =====================================================================================
    // WebChromeClient：文件上传选择器
    // =====================================================================================

    private inner class ShellWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // 取消上一次未完成的回调
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
    // DownloadListener：调用系统 DownloadManager
    // =====================================================================================

    private inner class ShellDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimetype: String,
            contentLength: Long
        ) {
            // API < 29 需要运行时申请 WRITE_EXTERNAL_STORAGE；API 29+ 走 DownloadManager 公共 Download 目录无需权限
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
            // 退化：尝试交给系统浏览器下载
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (_: Exception) {
                Toast.makeText(this, "下载失败：$url", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =====================================================================================
    // 剪贴板分享链接检测
    // =====================================================================================

    private fun checkClipboardForShareLink() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val raw = clip.getItemAt(0).coerceToText(this).toString().trim()
            if (raw.isEmpty()) return

            // 提取第一个看起来像 URL 的子串
            val urlCandidate = extractFirstUrl(raw) ?: return
            // 仅当 URL 包含目标域名才视为有效分享链接
            if (!SHARE_LINK_REGEX.containsMatchIn(urlCandidate)) return

            // 内存去重：本次进程已弹窗过此链接则静默跳过
            if (ClipboardMemory.wasShown(urlCandidate)) return

            // 弹系统原生询问框
            AlertDialog.Builder(this)
                .setTitle("检测到书籍分享链接")
                .setMessage("是否打开？\n$urlCandidate")
                .setPositiveButton("确定") { _, _ ->
                    ClipboardMemory.markShown(urlCandidate)
                    webView?.loadUrl(urlCandidate)
                }
                .setNegativeButton("取消") { _, _ ->
                    // 取消也更新标记，避免重复弹窗；维持当前主页不变
                    ClipboardMemory.markShown(urlCandidate)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "clipboard check failed", e)
        }
    }

    /** 从剪贴板文本里抽出第一段 URL（http/https），找不到则返回 null */
    private fun extractFirstUrl(text: String): String? {
        val pattern = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.value
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
     * readest://share/ID  →  HOME_URL + #/share/ID
     * readest://PATH      →  HOME_URL + #/PATH
     */
    private fun convertReadestDeepLink(uri: Uri): String {
        val host = uri.host ?: ""
        val path = uri.path ?: ""
        val query = uri.query?.let { "?$it" } ?: ""
        val fragment = uri.fragment?.let { "#$it" } ?: ""
        val composed = if (path.startsWith("/")) "$host$path$query$fragment" else "$host/$path$query$fragment"
        return "$HOME_URL/#/$composed"
    }
}
