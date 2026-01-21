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
            webView = WebView(this)
            setContentView(webView)

            // 3. 配置 AssetLoader (关键步骤)
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler(
                    "/game/",
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

    let maxTouches = 0;
    let isDragging = false;
    let lastWheelY = 0;
    let touchStartCenter = null;
    let delayTime = 24;

    // --- 新增：用于区分滚动和右键的变量 ---
    let hasMovedEnough = false;
    const moveThreshold = 10; // 移动超过10像素才触发滚动

    function emitMouseEvent(type, coords, button = 0) {
        const mouseEvent = new MouseEvent(type, {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: coords.clientX,
            clientY: coords.clientY,
            button: button,
            buttons: button === 0 ? 1 : (button === 2 ? 2 : 0)
        });
        target.dispatchEvent(mouseEvent);
    }

    function emitWheelEvent(coords, deltaY) {
        target.dispatchEvent(new WheelEvent("wheel", {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: coords.clientX,
            clientY: coords.clientY,
            deltaY: deltaY,
            deltaMode: 0
        }));
    }

    function getCenter(t1, t2) {
        return {
            clientX: (t1.clientX + (t2?.clientX || t1.clientX)) / 2,
            clientY: (t1.clientY + (t2?.clientY || t1.clientY)) / 2
        };
    }

    document.addEventListener("touchstart", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();
        const count = e.touches.length;
        if (count > maxTouches) maxTouches = count;

        if (count === 1) {
            emitMouseEvent("mousemove", e.touches[0], 0);
            setTimeout(() => {
                emitMouseEvent("mousedown", e.touches[0], 0);
            }, delayTime)
            isDragging = true;
        } else if (count === 2) {
            touchStartCenter = getCenter(e.touches[0], e.touches[1]);
            lastWheelY = touchStartCenter.clientY;
            hasMovedEnough = false; // 重置移动判定

            emitMouseEvent("mousemove", touchStartCenter, 0);
            if (isDragging) {
                isDragging = false;
            }
        }
    }, { capture: true, passive: false });

    document.addEventListener("touchmove", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();

        if (e.touches.length === 1 && isDragging) {
            setTimeout(() => {
                emitMouseEvent("mousemove", e.touches[0], 0);
            }, delayTime)
        } else if (e.touches.length === 2) {
            const currentCenter = getCenter(e.touches[0], e.touches[1]);
            const deltaYTotal = Math.abs(currentCenter.clientY - touchStartCenter.clientY);

            // 只有当移动距离超过阈值，才触发滚轮
            if (hasMovedEnough || deltaYTotal > moveThreshold) {
                hasMovedEnough = true;
                const deltaY = lastWheelY - currentCenter.clientY;
                emitWheelEvent(touchStartCenter, deltaY * 2);
                lastWheelY = currentCenter.clientY;
            }
        }
    }, { capture: true, passive: false });

    document.addEventListener("touchend", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();

        if (maxTouches === 1) {
            setTimeout(() => {
                emitMouseEvent("mouseup", e.changedTouches[0], 0);
            }, delayTime);
        }
        // 只有当没有触发过滚动（hasMovedEnough 为 false）时，才触发右键
        else if (maxTouches === 2 && e.touches.length === 0 && !hasMovedEnough) {
            const finalCenter = touchStartCenter || getCenter(e.changedTouches[0], e.changedTouches[1]);
            emitMouseEvent("mousemove", finalCenter, 0);
            emitMouseEvent("mousedown", finalCenter, 2);
            emitMouseEvent("mouseup", finalCenter, 2);
        }

        if (e.touches.length === 0) {
            maxTouches = 0;
            isDragging = false;
            touchStartCenter = null;
            hasMovedEnough = false; // 重置状态
        }
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
            // 映射关系：https://appassets.androidplatform.net/game/ -> gameDir/
            webView.loadUrl("https://appassets.androidplatform.net/game/index.html")

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

