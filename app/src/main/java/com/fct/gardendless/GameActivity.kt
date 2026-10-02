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
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject

class GameActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var aspectContainer: AspectRatioFrameLayout

    private val prefs by lazy { getSharedPreferences("app_data", MODE_PRIVATE) }

    /**
     * gp-next 数据目录，对应 Tauri 的 AppData 根。
     * 游戏会把 plugin:path|resolve_directory 的返回值拼上 `/gp-next`，
     * 因此数据实际位于 filesDir/gp-next，与 GameDocumentsProvider 的 gpnext 根指向同一目录。
     */
    private val gpNextDir: File by lazy { File(filesDir, GP_NEXT_DIR_NAME).apply { mkdirs() } }

    /**
     * 注入页面的 Tauri shim（assets/gdnext-shim.js）。
     * 前置的 window.__gdForceJsModding 供 shim 决定是否自动启用 JS Modding，见 [FORCE_JS_MODDING]。
     */
    private val gpNextShimJs: String by lazy {
        val js = runCatching {
            assets.open(SHIM_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { e ->
            Log.e(TAG, "无法读取 $SHIM_ASSET，gp-next 的数据包与 JS 模组将不可用", e)
            ""
        }
        if (js.isEmpty()) js else "window.__gdForceJsModding=$FORCE_JS_MODDING;\n$js"
    }

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
        const val TAG = "Gardendless"
        const val SHIM_ASSET = "gdnext-shim.js"
        const val GP_NEXT_DIR_NAME = "gp-next"

        /**
         * 是否在启动时自动启用 gp-next 的 JS Modding 开关。
         *
         * 该开关默认关闭，且游戏「实验性」页中的对应开关处于锁定状态（pointer-events:none，
         * 回调会把 true 还原为 false），唯一入口是控制台的 window.gpNext.mods.enableJsModding()。
         * 移植版没有可用的控制台，因此由 gdnext-shim.js 代为调用一次。
         *
         * 置为 false 可恢复原行为（需手动从 devtools 开启）。
         */
        const val FORCE_JS_MODDING = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setupFullScreen()

        /** 装配 WebView、AssetLoader、JS 桥与各回调，并加载游戏入口页 */
        fun setupWebview() {
            webView = MouseGameWebView(this)

            // 黑底容器，负责 16:10 ~ 17:9 的比例适配与全屏切换
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

            // AssetLoader 把 https://appassets.androidplatform.net/ 映射到本地目录。
            //
            // /gp-next/ 供 shim 读取 gp-next 数据：shim 中的 plugin:fs|read_text_file 与 read_file
            // 通过 fetch 该虚拟路径获取流式的原始字节，无需经 JS 桥传送 base64。
            // gpNextDir 的懒初始化会先确保目录存在，否则该路径下的请求全部 404。
            //
            // 注册顺序不可调整：WebViewAssetLoader 按注册顺序遍历 handler，返回第一个非 null 的响应；
            // 而 "/" 能匹配任意路径，且 InternalStoragePathHandler 在文件不存在时返回的是 404 空响应
            // （并非 null），因此 "/" 若排在前面会拦截 /gp-next/ 的请求，表现为到 docs 目录下查找
            // gp-next/... 并输出 "Error opening the requested path"。
            // 依据：androidx.webkit 1.15.0 的 WebViewAssetLoader.shouldInterceptRequest 与
            // Builder.addPathHandler。
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler(
                    "/$GP_NEXT_DIR_NAME/",
                    InternalStoragePathHandler(this, gpNextDir)
                )
                .addPathHandler(
                    "/",
                    InternalStoragePathHandler(this, File(filesDir, "pvzge_web-master/docs"))
                )
                .build()

            webView.settings.apply {
                javaScriptEnabled = true
                // 游戏与 gp-next 的设置都依赖 localStorage
                domStorageEnabled = true
                // 资源统一经 AssetLoader 提供，文件与 content 访问保持关闭
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
            }

            // 网页到原生的桥。游戏运行在 Tauri polyfill 之上，其中两件事在 WebView 里会失效：
            // 全屏被实现为空函数；保存文件名使用 prompt 而 WebView 不响应。
            // 因此由 JS 层截获意图后经此桥转发。其余方法面向 gp-next 数据目录的读写，
            // 由 assets/gdnext-shim.js 调用。
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun setFullscreen(value: Boolean) {
                    webView.post { setWebviewFullscreen(value) }
                }

                @JavascriptInterface
                fun setExportName(name: String) {
                    pendingExportName = name
                }

                // ── gp-next 数据目录（Tauri plugin:path / plugin:fs 的落地实现）──

                /** plugin:path|resolve_directory(14)：返回 AppData 根，游戏会自行拼上 /gp-next */
                @JavascriptInterface
                fun appDataRoot(): String = filesDir.absolutePath

                /** plugin:fs|read_dir：返回 [{name, isFile, isDirectory, isSymlink}] 形式的 JSON */
                @JavascriptInterface
                fun fsReadDir(rel: String): String = gpNextReadDir(rel)

                /**
                 * plugin:fs|stat 与 plugin:fs|lstat。
                 * dist-js 会把返回值直接映射为 FileInfo（首个字段即 isFile），
                 * 因此路径不存在时返回 null 交由调用方转成 reject，其余字段不可缺失。
                 */
                @JavascriptInterface
                fun fsStat(rel: String): String? = gpNextStatJson(rel)

                /** plugin:fs|rename（新版 dist-js 新增的命令） */
                @JavascriptInterface
                fun fsRename(oldRel: String, newRel: String): Boolean = gpNextRename(oldRel, newRel)

                /** plugin:fs|write_file（新版 dist-js 新增的命令），内容以 base64 传入 */
                @JavascriptInterface
                fun fsWriteBytes(rel: String, base64: String): Boolean = gpNextWriteBytes(rel, base64)

                /** plugin:fs|exists */
                @JavascriptInterface
                fun fsExists(rel: String): Boolean = gpNextFile(rel).exists()

                /** plugin:fs|mkdir */
                @JavascriptInterface
                fun fsMkdir(rel: String): Boolean = gpNextMkdir(rel)

                /** plugin:fs|write_text_file */
                @JavascriptInterface
                fun fsWriteText(rel: String, text: String): Boolean = gpNextWriteText(rel, text)

                /** plugin:fs|remove */
                @JavascriptInterface
                fun fsRemove(rel: String): Boolean = gpNextRemove(rel)

                /** plugin:opener|open_path：以系统文件管理器打开 gp-next 数据目录 */
                @JavascriptInterface
                fun openDataFolder() {
                    webView.post { openGpNextFolder() }
                }
            }, "GardendlessBridge")

            webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (!url.startsWith("data:")) return@setDownloadListener

                // 解析 Data URI（形如 data:application/json;base64,XXXXX）
                val parts = url.split(",")
                if (parts.size < 2) return@setDownloadListener
                pendingExport = Uri.decode(parts.subList(1, parts.size).joinToString(",")).toByteArray()

                // 交由系统保存对话框决定位置与文件名
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
                /** 内部资源留在 WebView 中加载，外部链接交给系统浏览器 */
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    if (url.startsWith("https://appassets.androidplatform.net/")) {
                        return false
                    }

                    try {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        startActivity(intent)
                        // 已由原生处理，阻止 WebView 继续加载
                        return true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return false
                    }
                }
                /**
                 * 注入 gp-next shim，并在游戏启动后屏蔽 GameCanvas 上的触摸事件
                 * （触摸手势统一由 MouseGameWebView 转换为鼠标事件处理）。
                 * 页面每次加载都会重新注入，shim 内部以 __gdHooked 保证幂等。
                 */
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (gpNextShimJs.isNotEmpty()) view?.evaluateJavascript(gpNextShimJs, null)
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
                    // 延迟到 GameCanvas 创建完成后再挂载监听
                    view?.postDelayed({
                        view.evaluateJavascript(js, null)
                    }, 8000)
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    // 交由 AssetLoader 解析（游戏资源与 /gp-next/ 虚拟路径）
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

                        // 主类型放宽为通配符，使选择器能显示更多文件
                        type = "*/*"

                        // 显式补充类型：各系统对 .json5 的识别结果不一致
                        val mimeTypes = arrayOf(
                            "application/json",
                            "application/octet-stream", // 部分系统识别为二进制
                            "text/plain"                // 部分系统识别为纯文本
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

            // 加载入口页：https://appassets.androidplatform.net/ 已映射到游戏 docs 目录
            webView.loadUrl("https://appassets.androidplatform.net/index.html")

            setupBackNavigation()
        }

        /** 首次安装或版本号变化时，把 assets 中的游戏包解压到 filesDir */
        fun checkAndExtractAssets(currentVersion: Int) {
            val progressBar = ProgressBar(this).apply {
                // 解压总时长不可预知，使用不确定模式
                isIndeterminate = true
                setPadding(50, 50, 50, 50)
            }

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unzipping)
                .setMessage(R.string.description)
                .setView(progressBar)
                // 解压期间不可取消，避免留下不完整的目录
                .setCancelable(false)
                .create()

            dialog.show()

            // 解压在 IO 线程执行，完成后再回主线程启动 WebView
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

                    // 记录已解压版本，后续启动可直接跳过解压
                    prefs.edit().putInt("extracted_version", currentVersion).apply()

                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        setupWebview()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                    }
                }
            }
        }

        // 游戏包版本变化（含首次安装）时重新解压
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

    /** 接收文件选择与导出保存两处 startActivityForResult 的结果 */
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
     * 保存对话框默认名的兜底：游戏用 `<a download="名字" href="data:...">` 触发导出时，
     * 文件名会被 assets/gdnext-shim.js 截获并经桥转发过来（setExportName），
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

    /** 沉浸式全屏：隐藏状态栏与导航栏、允许刘海区域，并保持屏幕常亮 */
    private fun setupFullScreen() {
        // 兜底隐藏 ActionBar
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

    // ── gp-next 数据目录 I/O ──
    // 以下方法的调用方是 assets/gdnext-shim.js：它把游戏发出的 plugin:fs|* 命令路由到这里。
    // 传入的路径为相对 AppData 的路径，例如 gp-next\packs\Foo\pack.json。

    /**
     * 把 gp-next 相对路径解析为 filesDir 下的真实文件。
     * 越界或非法输入一律收敛到数据根目录，避免逃逸到 filesDir 的其他位置。
     */
    private fun gpNextFile(rawPath: String): File {
        val root = gpNextDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val filesPrefix = filesDir.absolutePath.replace('\\', '/').trimEnd('/') + '/'
        val normalized = rawPath.replace('\\', '/').trimStart('/')
        // 游戏可能传入已拼接 base path 的绝对路径，先还原为相对形式
        val relative =
            if (normalized.startsWith(filesPrefix)) normalized.removePrefix(filesPrefix) else normalized
        val candidate = File(filesDir, relative).canonicalFile
        return if (candidate.path == root.path || candidate.path.startsWith(rootPrefix)) candidate else root
    }

    /**
     * 返回 [{name, isFile, isDirectory, isSymlink}] 形式的 JSON；目录不存在时返回空数组。
     */
    private fun gpNextReadDir(rawPath: String): String {
        val entries = JSONArray()
        gpNextFile(rawPath).listFiles()?.forEach { file ->
            entries.put(JSONObject().apply {
                put("name", file.name)
                put("isFile", file.isFile)
                put("isDirectory", file.isDirectory)
                // 内部存储不涉及符号链接，固定为 false；需要真实判断时见 gpNextStatJson
                put("isSymlink", false)
            })
        }
        return entries.toString()
    }

    /** mkdirs() 在目录已存在时返回 false，因此以 isDirectory 判断最终结果 */
    private fun gpNextMkdir(rawPath: String): Boolean {
        val dir = gpNextFile(rawPath)
        dir.mkdirs()
        return dir.isDirectory
    }

    /** 写入文本，父目录不存在时自动创建 */
    private fun gpNextWriteText(rawPath: String, text: String): Boolean = runCatching {
        val file = gpNextFile(rawPath)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }.isSuccess

    private fun gpNextRemove(rawPath: String): Boolean {
        val file = gpNextFile(rawPath)
        // 仅用于清理 __gpn_edits，禁止删除数据根
        if (file.canonicalFile == gpNextDir.canonicalFile) return false
        return file.deleteRecursively()
    }

    /**
     * 生成 stat / lstat 的结果，字段与 @tauri-apps/plugin-fs 的 FileInfo 一致。
     * dist-js 会逐字段读取，其中 mtime/atime 必须是毫秒时间戳或 null，且字段不可缺失。
     * 使用 Os.lstat 而非 File，以便识别符号链接——新版包快照会拒绝含符号链接的包。
     * 路径不存在时返回 null，由调用方转换为 reject，与 Tauri 的 stat 失败语义一致。
     */
    private fun gpNextStatJson(rawPath: String): String? = runCatching {
        val file = gpNextFile(rawPath)
        val st = Os.lstat(file.absolutePath)
        val type = st.st_mode and OsConstants.S_IFMT
        JSONObject().apply {
            put("isFile", type == OsConstants.S_IFREG)
            put("isDirectory", type == OsConstants.S_IFDIR)
            put("isSymlink", type == OsConstants.S_IFLNK)
            put("size", st.st_size)
            // Os.lstat 返回秒，而 FileInfo 使用毫秒
            put("mtime", st.st_mtime * 1000L)
            put("atime", st.st_atime * 1000L)
            // Android 无法获取 birthtime，此处以 ctime 近似
            put("birthtime", st.st_ctime * 1000L)
            put("readonly", !file.canWrite())
            put("fileAttributes", 0)
            put("dev", st.st_dev)
            put("ino", st.st_ino)
            put("mode", st.st_mode)
            put("nlink", st.st_nlink)
            put("uid", st.st_uid)
            put("gid", st.st_gid)
            put("rdev", st.st_rdev)
            put("blksize", st.st_blksize)
            put("blocks", st.st_blocks)
        }.toString()
    }.getOrNull()

    /** 重命名；源文件不存在，或任一参数指向数据根时返回 false */
    private fun gpNextRename(oldRawPath: String, newRawPath: String): Boolean {
        val src = gpNextFile(oldRawPath)
        if (!src.exists()) return false
        val dst = gpNextFile(newRawPath)
        if (src.canonicalFile == gpNextDir.canonicalFile || dst.canonicalFile == gpNextDir.canonicalFile) {
            return false
        }
        dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }

    /** 写入二进制内容（以 base64 传入），父目录不存在时自动创建 */
    private fun gpNextWriteBytes(rawPath: String, base64: String): Boolean = runCatching {
        val file = gpNextFile(rawPath)
        file.parentFile?.mkdirs()
        file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
    }.isSuccess

    /**
     * 以系统文件管理器打开 gp-next 数据目录（即 DocumentsProvider 的 gpnext 根）。
     * 对应游戏 patcher 页的「打开补丁文件夹」。
     */
    private fun openGpNextFolder() {
        val authority = "$packageName.documents"
        val rootUri = DocumentsContract.buildRootUri(authority, GameDocumentsProvider.GP_NEXT_ROOT_ID)
        val initialUri =
            DocumentsContract.buildDocumentUri(authority, "${GameDocumentsProvider.GP_NEXT_ROOT_ID}:")
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法打开 gp-next 数据目录", e)
            Toast.makeText(this, R.string.documents_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

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

