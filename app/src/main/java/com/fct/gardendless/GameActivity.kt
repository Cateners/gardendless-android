// gardendless-android

// Copyright (C) 2026  Caten Hu

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

package com.fct.gardendless

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import androidx.core.net.toUri

class GameActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var aspectContainer: AspectRatioFrameLayout

    private val prefs by lazy { getSharedPreferences("app_data", MODE_PRIVATE) }

    private val FILE_CHOOSER_RESULT_CODE = 101
    private val EXPORT_SAVE_RESULT_CODE = 102

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 导出流程中解出的存档内容，等待用户在保存对话框里选定目标后再写入
    private var pendingExport: ByteArray? = null
    // 游戏经 Tauri dialog.save 给出的文件名，可能覆盖默认建议名
    private var pendingExportName: String? = null

    // 网页全屏（HTML5 Fullscreen API）回调，非空表示当前处于全屏
    private var fullscreenCallback: CustomViewCallback? = null

    private companion object {
        const val PREF_FULLSCREEN = "webview_fullscreen"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setupFullScreen()

        fun setupWebview() {

// 2. 初始化 WebView
            webView = MouseGameWebView(this)

            // 黑色背景容器，重写测量逻辑实现比例动态适配（最小16:10，最大17:9）
            aspectContainer = AspectRatioFrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // 沿用上次退出时的全屏状态，避免先小后大的尺寸跳变
                fullscreen = prefs.getBoolean(PREF_FULLSCREEN, false)
                // 设置 WebView 居中显示
                addView(
                    webView,
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.CENTER }
                )
            }

            // 将容器设置为 Content View
            setContentView(aspectContainer)

            // 3. 配置 AssetLoader (关键步骤)
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler(
                    "/",
                    InternalStoragePathHandler(this, File(filesDir, "pvzge_web-master/docs"))
                )
                .build()

            // 4. 设置 WebView 参数
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true // 很多游戏需要存储数据
                allowFileAccess = false  // 使用 AssetLoader 后可以关闭文件访问，更安全
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
            }

            // 网页 → 原生桥接：游戏跑在 Tauri polyfill 上，全屏和保存文件名这两件事
            // 在 WebView 里都会丢失（前者被实现为空函数，后者用 prompt 实现而 WebView 不响应），
            // 故在 JS 层截获意图后经此桥转发
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun setFullscreen(value: Boolean) {
                    webView.post { setWebviewFullscreen(value) }
                }

                @JavascriptInterface
                fun setExportName(name: String) {
                    pendingExportName = name
                }
            }, "GardendlessBridge")

            webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (!url.startsWith("data:")) return@setDownloadListener

                // 1. 解析 Data URI（格式通常为 data:application/json;base64,XXXXX）
                val parts = url.split(",")
                if (parts.size < 2) return@setDownloadListener
                pendingExport = Uri.decode(parts.subList(1, parts.size).joinToString(",")).toByteArray()

                // 2. 交给系统保存对话框，由用户决定位置和文件名
                val fallbackType = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val name = pendingExportName ?: suggestFileName(contentDisposition, fallbackType)
                pendingExportName = null
                // 文件名通常已自带扩展名（由游戏给出），mime 必须与之匹配，
                // 否则 SAF 会按 mime 再追加一个后缀（例如 .json 变成 .json.txt）
                val type = mimeFromExtension(name) ?: fallbackType
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    this.type = type
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                startActivityForResult(intent, EXPORT_SAVE_RESULT_CODE)
            }

            webView.webViewClient = object : WebViewClient() {
                // 关键：拦截 URL 跳转逻辑
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // 1. 如果是内部游戏资源路径，允许在 WebView 中加载
                    if (url.startsWith("https://appassets.androidplatform.net/")) {
                        return false
                    }

                    // 2. 如果是外部链接，跳转到外部浏览器
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        startActivity(intent)
                        return true // 表示我们已经处理了该跳转
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return false
                    }
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(gameBridgeHookJs, null)
                    val js = """
(function() {
    const target = document.getElementById("GameCanvas");
    if (!target) return;

    target.addEventListener("touchstart", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();
    }, { capture: true, passive: false });
    target.addEventListener("touchmove", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();
    }, { capture: true, passive: false });
    target.addEventListener("touchend", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();
    }, { capture: true, passive: false });
})();
                        """.trimIndent()
                    view?.postDelayed({
                        view.evaluateJavascript(js, null)
                    }, 8000)
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    // 拦截并交给 AssetLoader 处理
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }
            webView.webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // 保存回调以便在 onActivityResult 中使用
                    this@GameActivity.filePathCallback = filePathCallback

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)

                        // 1. 设置主类型为通配符，以便能够显示更多文件
                        type = "*/*"

                        // 2. 显式指定允许的多种 MIME 类型
                        val mimeTypes = arrayOf(
                            "application/json",
                            "application/octet-stream", // 很多系统把 json5 识别为 bin
                            "text/plain"               // 有些系统把 json5 识别为纯文本
                        )
                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    }
                    try {
                        startActivityForResult(intent!!, FILE_CHOOSER_RESULT_CODE)
                    } catch (e: Exception) {
                        this@GameActivity.filePathCallback = null
                        return false
                    }
                    return true
                }

                // 网页调用 element.requestFullscreen() 时触发
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreenCallback != null) {
                        // 已在全屏，拒绝重复进入并立刻通知网页
                        callback.onCustomViewHidden()
                        return
                    }
                    fullscreenCallback = callback
                    setWebviewFullscreen(true)
                }

                // 网页调用 document.exitFullscreen() 时触发
                override fun onHideCustomView() {
                    val callback = fullscreenCallback ?: return
                    // 必须回调 onCustomViewHidden，否则 WebView 内部会一直停留在全屏状态
                    callback.onCustomViewHidden()
                    fullscreenCallback = null
                    setWebviewFullscreen(false)
                }
            }

            // 5. 加载入口文件
            // 映射关系：https://appassets.androidplatform.net/ -> gameDir/
            webView.loadUrl("https://appassets.androidplatform.net/index.html")

            setupBackNavigation()
        }

        fun checkAndExtractAssets(currentVersion: Int) {
            // 1. 创建一个简单的进度对话框
            val progressBar = ProgressBar(this).apply {
                isIndeterminate = true // 设置为不确定模式（循环转圈）
                setPadding(50, 50, 50, 50)
            }

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unzipping) // 建议在 strings.xml 定义“正在准备资源...”
                .setMessage(R.string.description)
                .setView(progressBar)
                .setCancelable(false) // 防止解压时用户点击返回键取消
                .create()

            dialog.show()

            // 2. 开启协程/后台线程处理 IO
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    assets.open("pvzge_web-master.zip").use { inputStream ->
                        ZipInputStream(inputStream).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val file = File(filesDir, entry.name)
                                if (entry.isDirectory) {
                                    file.mkdirs()
                                } else {
                                    file.parentFile?.mkdirs()
                                    file.outputStream().use { zis.copyTo(it) }
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }

                    // 写入版本号
                    prefs.edit().putInt("extracted_version", currentVersion).apply()

                    // 3. 回到主线程关闭对话框并加载游戏
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        setupWebview() // 将你原来的 WebView 初始化逻辑封装成此函数
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                    }
                }
            }
        }



        // 1. 准备路径
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

        if (prefs.getInt("extracted_version", 0) != currentVersion) {
            checkAndExtractAssets(currentVersion)
        } else {
            setupWebview()
        }
    }

    override fun onDestroy() {
        // 页面若仍处于全屏，通知 WebView 退出，避免其内部状态残留
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        super.onDestroy()
    }

    // 在 Activity 中处理选择结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            FILE_CHOOSER_RESULT_CODE -> {
                val result = if (data == null || resultCode != RESULT_OK) null else arrayOf(data.data!!)
                filePathCallback?.onReceiveValue(result as Array<Uri>?)
                filePathCallback = null
            }
            EXPORT_SAVE_RESULT_CODE -> {
                val bytes = pendingExport
                pendingExport = null
                // 用户取消保存时 data 为 null，直接丢弃暂存内容
                val uri = data?.data
                if (bytes != null && uri != null) saveExportTo(uri, bytes)
            }
        }
    }

    private fun saveExportTo(uri: Uri, bytes: ByteArray) {
        GlobalScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("openOutputStream returned null")
            }.isSuccess
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@GameActivity,
                    if (ok) R.string.export_done else R.string.export_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 保存对话框默认名的兜底：游戏通常已通过 Tauri dialog.save 给出文件名（见 [gameBridgeHookJs]），
     * 取不到时才退回 contentDisposition 的建议名，再取不到才按类型和时间生成。
     */
    private fun suggestFileName(contentDisposition: String?, mimeType: String): String {
        contentDisposition
            ?.let { Regex("""filename\*?=(?:UTF-8''|utf-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val time = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "gardendless_$time.${mimeToExtension(mimeType)}"
    }

    private fun mimeToExtension(mimeType: String): String = when (val type = mimeType.substringBefore(';').trim()) {
        "application/json" -> "json"
        "text/plain" -> "txt"
        "application/octet-stream" -> "bin"
        else -> type.substringAfter('/').takeIf { it.all(Char::isLetterOrDigit) } ?: "bin"
    }

    private fun mimeFromExtension(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it != fileName } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    private fun setupFullScreen() {
        // 隐藏 ActionBar (如果在 Manifest 中没设主题，这里是双保险)
        supportActionBar?.hide()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) 及以上
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // 旧版本兼容写法
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        // 保持屏幕常亮，适合游戏场景
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * 注入页面的钩子。
     *
     * 游戏跑在 Tauri polyfill 上，有两件事在 WebView 里会丢失：
     * - 全屏：polyfill 把 `plugin:window|set_fullscreen` 实现为空函数，且 WebView 对非 video
     *   元素不回调 onShowCustomView；
     * - 导出文件名：polyfill 用 `prompt()` 询问文件名，而 WebView 不响应 prompt 恒返回 null，
     *   游戏拿不到名字，导出时只能退化为默认名。
     *
     * 这里包装相关通道，把网页的意图转发给原生处理。
     */
    private val gameBridgeHookJs = """
(function() {
    if (window.__gdHooked) return;
    window.__gdHooked = true;

    var bridge = window.GardendlessBridge;

    // 游戏启动时（约 2s）会按自己内部状态同步一次 setFullscreen(false)，
    // 那会把我们从 SharedPreferences 恢复的全屏状态冲掉。
    // 故在启动保护期内忽略这条自动同步的 false；true 一律放行，不影响用户主动操作。
    var bootGuard = true;
    setTimeout(function() { bootGuard = false; }, 6000);

    var setFullscreen = function(v) {
        if (bootGuard && !v) { bootGuard = false; return; }
        bootGuard = false;
        try { bridge.setFullscreen(!!v); } catch (e) { /* 桥不可用时静默降级 */ }
    };

    var setExportName = function(name) {
        if (!name) return;
        try { bridge.setExportName(String(name)); } catch (e) { /* 忽略 */ }
    };

    // 下载命名：游戏用 <a download="名字" href="data:..."> + click() 触发导出，
    // 文件名只存在于 a.download 上，不会传给原生，故在此捕获
    var isDataHref = function(el) {
        return (el && el.getAttribute && (el.getAttribute('href') || '').indexOf('data:') === 0);
    };
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        if (this.download && isDataHref(this)) setExportName(this.download);
        return origClick.apply(this, arguments);
    };
    document.addEventListener('click', function(e) {
        var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
        if (a && isDataHref(a)) setExportName(a.getAttribute('download'));
    }, true);

    // 标准 Fullscreen API（游戏若改用此路径也能覆盖）
    var ep = Element.prototype;
    var req = ep.requestFullscreen || ep.webkitRequestFullscreen || ep.webkitRequestFullScreen;
    if (req) {
        ep.requestFullscreen = function() { setFullscreen(true); return req.apply(this, arguments); };
    }
    var dp = Document.prototype;
    var exit = dp.exitFullscreen || dp.webkitExitFullscreen || dp.webkitCancelFullScreen;
    if (exit) {
        dp.exitFullscreen = function() { setFullscreen(false); return exit.apply(this, arguments); };
    }

    // Tauri invoke：游戏实际走这条
    var patchTauri = function() {
        var ti = window.__TAURI_INTERNALS__;
        if (!ti || !ti.invoke || ti.__gdHooked) return false;
        ti.__gdHooked = true;
        var orig = ti.invoke;
        ti.invoke = function(cmd, args) {
            if (cmd === 'plugin:window|set_fullscreen') {
                setFullscreen(!!(args && args.value));
                return Promise.resolve(null);
            }
            if (cmd === 'plugin:dialog|save') {
                // 游戏请求一个保存文件名。原生记下它，随后用它作为保存对话框的默认名，
                // 与游戏自身的命名规则（存档、键位配置等各不相同）保持一致。
                var raw = args && (args.defaultPath || (args.options && args.options.defaultPath));
                var name = raw ? String(raw).split(/[\\/]/).pop() : '';
                if (name) {
                    setExportName(name);
                    return Promise.resolve(name);
                }
            }
            return orig.apply(this, arguments);
        };
        return true;
    };

    // polyfill 在 head 中同步执行，可能晚于本脚本，短暂轮询等待
    if (!patchTauri()) {
        var tries = 0;
        var timer = setInterval(function() {
            if (patchTauri() || ++tries > 100) clearInterval(timer);
        }, 50);
    }
})();
    """.trimIndent()

    /**
     * 切换 WebView 满屏 / 比例适配，并持久化该状态供下次启动沿用。
     */
    private fun setWebviewFullscreen(enabled: Boolean) {
        aspectContainer.fullscreen = enabled
        prefs.edit().putBoolean(PREF_FULLSCREEN, enabled).apply()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hint) // 需在 strings.xml 定义
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> finish() }
            .setNegativeButton(R.string.no, null)
            .show()
    }
}

