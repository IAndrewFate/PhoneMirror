package com.phonemirror.sender.ui

import android.app.Activity
import android.view.WindowManager

interface WindowController {
    fun setKeepScreenOn(enable: Boolean)
    fun setDimScreen(dim: Boolean)
    val isKeepScreenOn: Boolean
    val isDimmed: Boolean
}

class FakeWindowController : WindowController {
    override var isKeepScreenOn: Boolean = false
        private set
    override var isDimmed: Boolean = false
        private set

    var setKeepScreenOnCalls: Int = 0
        private set
    var setDimScreenCalls: Int = 0
        private set

    override fun setKeepScreenOn(enable: Boolean) {
        isKeepScreenOn = enable
        setKeepScreenOnCalls++
    }

    override fun setDimScreen(dim: Boolean) {
        isDimmed = dim
        setDimScreenCalls++
    }
}

class ActivityWindowController(private val activity: Activity) : WindowController {
    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var _isKeepScreenOn = false
    private var _isDimmed = false

    override val isKeepScreenOn: Boolean get() = _isKeepScreenOn
    override val isDimmed: Boolean get() = _isDimmed

    override fun setKeepScreenOn(enable: Boolean) {
        _isKeepScreenOn = enable
        if (enable) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun setDimScreen(dim: Boolean) {
        _isDimmed = dim
        val lp = activity.window.attributes
        if (dim) {
            if (originalBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                originalBrightness = lp.screenBrightness
            }
            lp.screenBrightness = 0.01f
        } else {
            lp.screenBrightness = originalBrightness
            originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        activity.window.attributes = lp
    }
}
