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

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 游戏画面容器（黑底，兼作黑边）。
 *
 * 负责把唯一的子 View（WebView）约束在 16:10 ~ 17:9 的比例区间内，区间外留黑边；
 * [fullscreen] 为 true 时子 View 直接铺满整个容器。
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** true：子 View 铺满容器；false：按 16:10 ~ 17:9 约束 */
    var fullscreen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // 防空保护：确保有子 View 才进行自定义测量
        val child = getChildAt(0) ?: return

        val screenWidth = measuredWidth
        val screenHeight = measuredHeight
        var targetWidth = screenWidth
        var targetHeight = screenHeight

        if (!fullscreen) {
            when {
                screenWidth * MAX_ASPECT_H > screenHeight * MAX_ASPECT_W -> {
                    // 1. 屏幕【太宽】了：超过 17:9（例如 20:9、21:9 手机）
                    // 以高度为基准，宽度卡死在 17:9，左右留黑边
                    targetWidth = screenHeight * MAX_ASPECT_W / MAX_ASPECT_H
                }
                screenWidth * MIN_ASPECT_H < screenHeight * MIN_ASPECT_W -> {
                    // 2. 屏幕【太方/太高】了：窄于 16:10（例如 4:3、7:5 平板）
                    // 以宽度为基准，高度卡死在 16:10，上下留黑边
                    targetHeight = screenWidth * MIN_ASPECT_H / MIN_ASPECT_W
                }
                // 3. 比例在 16:10 ~ 17:9 之间：全屏铺满
            }
        }

        // 强制指定子 View (WebView) 的精确测量尺寸
        child.measure(
            MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
        )
    }

    private companion object {
        // 允许的最宽比例 17:9
        const val MAX_ASPECT_W = 171
        const val MAX_ASPECT_H = 90
        // 允许的最窄比例 16:10
        const val MIN_ASPECT_W = 160
        const val MIN_ASPECT_H = 100
    }
}
