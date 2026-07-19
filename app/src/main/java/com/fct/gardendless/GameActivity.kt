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
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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

    private val FILE_CHOOSER_RESULT_CODE = 101

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setupFullScreen()

        fun setupWebview() {

// 2. 初始化 WebView
            webView = MouseGameWebView(this)

            // 创建一个黑色背景的容器，重写测量逻辑实现比例动态适配（最小16:10，最大17:9）
            val container = object : android.widget.FrameLayout(this) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)

                    // 防空保护：确保有子 View 才进行自定义测量
                    if (childCount == 0) return

                    val screenWidth = measuredWidth
                    val screenHeight = measuredHeight

                    var targetWidth = screenWidth
                    var targetHeight = screenHeight

                    if (screenWidth * 90 > screenHeight * 171) {
                        // 1. 屏幕【太宽】了：超过了 17:9 (例如 20:9, 21:9 手机)
                        // 此时以高度为基准，宽度强行卡死在 17:9，左右留黑边
                        targetWidth = screenHeight * 171 / 90
                    } else if (screenWidth * 100 < screenHeight * 160) {
                        // 2. 屏幕【太方/太高】了：窄于 16:10 (例如 4:3 或 7:5 平板)
                        // 此时以宽度为基准，高度强行卡死在 16:10，上下留黑边
                        targetHeight = screenWidth * 100 / 160
                    } else {
                        // 屏幕比例在 16:10 到 17:9 之间
                        // 全屏铺满
                        targetWidth = screenWidth
                        targetHeight = screenHeight
                    }

                    // 强制指定子 View (WebView) 的精确测量尺寸
                    getChildAt(0).measure(
                        MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
                    )
                }
            }

            // 设置背景为纯黑，充当黑边
            container.setBackgroundColor(android.graphics.Color.BLACK)

            // 设置 WebView 居中显示
            val layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            container.addView(webView, layoutParams)

            // 将容器设置为 Content View
            setContentView(container)

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

            webView.setDownloadListener { url, _, _, mimeType, _ ->
                if (url.startsWith("data:")) {
                    // 1. 解析 Data URI
                    // 数据格式通常为 data:application/json;base64,XXXXX
                    val parts = url.split(",")
                    if (parts.size < 2) return@setDownloadListener

                    // 2. 保存到临时目录
                    val cacheFile = File(cacheDir, "pp.json")
                    cacheFile.writeText(Uri.decode(parts.subList(1, parts.size).joinToString(",")))

                    // 3. 使用 FileProvider 生成可分享的 Uri
                    val contentUri = FileProvider.getUriForFile(
                        this, "${packageName}.fileprovider", cacheFile
                    )

                    // 4. 弹出系统“另存为”或分享菜单
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.export)))
                }
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
            }

            // 5. 加载入口文件
            // 映射关系：https://appassets.androidplatform.net/ -> gameDir/
            webView.loadUrl("https://appassets.androidplatform.net/index.html")

            setupBackNavigation()
        }

        fun checkAndExtractAssets(currentVersion: Int, sp: android.content.SharedPreferences) {
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
                    sp.edit().putInt("extracted_version", currentVersion).apply()

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
        val sp = getSharedPreferences("app_data", MODE_PRIVATE)
        val savedVersion = sp.getInt("extracted_version", 0)
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

        if (savedVersion != currentVersion) {
            checkAndExtractAssets(currentVersion, sp)
        } else {
            setupWebview()
        }
    }

    // 在 Activity 中处理选择结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            val result = if (data == null || resultCode != RESULT_OK) null else arrayOf(data.data!!)
            filePathCallback?.onReceiveValue(result as Array<Uri>?)
            filePathCallback = null
        }
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

