package com.fct.gardendless

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

class MouseGameWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : WebView(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var lastScrollY = 0f
    private var touchStartCenterX = 0f
    private var touchStartCenterY = 0f
    private var hasMovedEnough = false
    private val moveThreshold = 20f
    private var maxTouches = 0
    private var isTouching = false

    // 获取屏幕密度，用于将物理像素转换为CSS像素
    private val density = context.resources.displayMetrics.density

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.source == InputDevice.SOURCE_MOUSE) {
            return super.dispatchTouchEvent(event)
        }

        val action = event.actionMasked
        val pointerCount = event.pointerCount
        if (pointerCount > maxTouches) maxTouches = pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (pointerCount == 1) {
                    isTouching = true
                    val dx = event.x
                    val dy = event.y

                    // 1. [Move] 保持原样：立即瞬移光标（Native）

                    // 2. [Down] 修改为：延迟执行 JS 按下
                    if (isTouching && maxTouches == 1) {
                        // JS 左键按下 (button 0)
                        injectJsMouseEvent(dx, dy, "mousedown", 0)
                        isDragging = true
                    }
                    return super.dispatchTouchEvent(event)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    isDragging = false // 取消左键拖拽
                    hasMovedEnough = false
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    touchStartCenterX = cx
                    touchStartCenterY = cy
                    lastScrollY = cy

                    // [Move] 保持原样：双指按下瞬间的 move (Native)
                    injectMouseEventAt(cx, cy, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // Move 逻辑完全保持不变，使用 Native 事件以保证流畅度
                if (pointerCount == 1 && isDragging) {
                    injectMouseEventAt(event.x, event.y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                } else if (pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    val deltaTotal = abs(cy - touchStartCenterY)

                    if (hasMovedEnough || deltaTotal > moveThreshold) {
                        hasMovedEnough = true
                        val scrollDelta = cy - lastScrollY
                        // Scroll 保持原样 (Native)
                        injectScrollEventAt(touchStartCenterX, touchStartCenterY, scrollDelta * 2)
                        lastScrollY = cy
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (action == MotionEvent.ACTION_UP) {
                    isTouching = false
                    mainHandler.removeCallbacksAndMessages(null)

                    if (maxTouches == 1 && isDragging) {
                        // [Up] 修改为：JS 左键抬起
                        injectJsMouseEvent(event.x, event.y, "mouseup", 0)
                        maxTouches = 0
                        isDragging = false
                        hasMovedEnough = false
                        return super.dispatchTouchEvent(event)
                    }
                    else if (maxTouches == 2 && !hasMovedEnough) {
                        // 右键点击逻辑
                        injectRightClickAt(touchStartCenterX, touchStartCenterY)
                    }

                    maxTouches = 0
                    isDragging = false
                    hasMovedEnough = false
                }
            }
        }
        return true
    }

    /**
     * 新增：通过 EvaluateJavascript 注入鼠标点击事件
     * @param x 物理像素X
     * @param y 物理像素Y
     * @param type 事件类型 "mousedown" 或 "mouseup"
     * @param button 按键代码：0=左键, 2=右键
     */
    private fun injectJsMouseEvent(x: Float, y: Float, type: String, button: Int) {
        // 将物理坐标转换为 CSS 坐标
        val cssX = x / density
        val cssY = y / density

        // 构建 JS 脚本
        val js = """
            (function() {
                var element = document.getElementById("GameCanvas");
                var ev = new MouseEvent('$type', {
                    view: window,
                    bubbles: true,
                    cancelable: true,
                    screenX: $x,
                    screenY: $y,
                    clientX: $cssX,
                    clientY: $cssY,
                    button: $button
                });
                element.dispatchEvent(ev);
            })();
        """.trimIndent()

        this.evaluateJavascript(js, null)
    }

    // 保持不变：用于 Move
    private fun injectMouseEventAt(x: Float, y: Float, action: Int, buttonState: Int) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), action,
            1, props, coords, 0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // 保持不变：用于 Scroll
    private fun injectScrollEventAt(x: Float, y: Float, delta: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, delta / 15f)
        })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), MotionEvent.ACTION_SCROLL,
            1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    // 修改：右键点击逻辑，Move 用 Native，点击用 JS
    private fun injectRightClickAt(x: Float, y: Float) {
        // 1. Move 到位 (Native)
        //injectMouseEventAt(x, y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
        injectJsMouseEvent(x, y, "mousedown", 2)
        injectJsMouseEvent(x, y, "mouseup", 2)
    }
}