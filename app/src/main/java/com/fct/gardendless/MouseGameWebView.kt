package com.fct.gardendless

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

/**
 * 把触摸手势转换为鼠标事件，供游戏使用。
 *
 * 事件注入分两条路径：
 * - 移动与滚轮使用 native MotionEvent（SOURCE_MOUSE），以保证流畅度；
 * - 左键按下/抬起与右键点击使用 JS MouseEvent，直接派发到 GameCanvas。
 *
 * 手势映射：
 * - 单指拖动：左键按下 → 移动 → 抬起；
 * - 双指滑动：以两指中点作为光标位置，纵向位移映射为滚轮；
 * - 双指轻点（位移未超过 moveThreshold）：右键点击。
 */
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

                    if (isTouching && maxTouches == 1) {
                        // 左键按下（JS 事件，button 0）
                        injectJsMouseEvent(dx, dy, "mousedown", 0)
                        isDragging = true
                    }
                    return super.dispatchTouchEvent(event)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    // 进入双指手势，取消左键拖拽
                    isDragging = false
                    hasMovedEnough = false
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    touchStartCenterX = cx
                    touchStartCenterY = cy
                    lastScrollY = cy

                    // 双指按下瞬间，先在两指中点补发一次 native 移动
                    injectMouseEventAt(cx, cy, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 移动统一使用 native 事件，以保证流畅度
                if (pointerCount == 1 && isDragging) {
                    injectMouseEventAt(event.x, event.y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                } else if (pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    val deltaTotal = abs(cy - touchStartCenterY)

                    if (hasMovedEnough || deltaTotal > moveThreshold) {
                        hasMovedEnough = true
                        val scrollDelta = cy - lastScrollY
                        // 滚轮使用 native 事件
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
                        // 左键抬起（JS 事件）
                        injectJsMouseEvent(event.x, event.y, "mouseup", 0)
                        maxTouches = 0
                        isDragging = false
                        hasMovedEnough = false
                        return super.dispatchTouchEvent(event)
                    }
                    else if (maxTouches == 2 && !hasMovedEnough) {
                        // 双指轻点：右键点击
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
     * 向页面注入 JS MouseEvent，并直接派发到 GameCanvas。
     *
     * @param x 物理像素 X 坐标
     * @param y 物理像素 Y 坐标
     * @param type 事件类型，"mousedown" 或 "mouseup"
     * @param button 按键：0=左键，2=右键
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

    /** 注入 native 鼠标事件，用于移动 */
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

    /** 注入 native 滚轮事件，用于双指滑动 */
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

    /** 右键点击：只注入 JS 的按下与抬起 */
    private fun injectRightClickAt(x: Float, y: Float) {
        // 备选方案：先以 native 事件把光标移到目标位置（当前未启用）
        // injectMouseEventAt(x, y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
        injectJsMouseEvent(x, y, "mousedown", 2)
        injectJsMouseEvent(x, y, "mouseup", 2)
    }
}