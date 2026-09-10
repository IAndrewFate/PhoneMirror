package com.phonemirror.sender.capture

import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface

interface VirtualDisplayHandle {
    fun resize(width: Int, height: Int, densityDpi: Int)
    fun setSurface(surface: Surface?)
    fun release()
}

class AndroidVirtualDisplayHandle(private val virtualDisplay: VirtualDisplay) : VirtualDisplayHandle {
    override fun resize(width: Int, height: Int, densityDpi: Int) {
        virtualDisplay.resize(width, height, densityDpi)
    }

    override fun setSurface(surface: Surface?) {
        virtualDisplay.surface = surface
    }

    override fun release() {
        virtualDisplay.release()
    }
}

interface ProjectionAdapter {
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        surface: Surface?,
        callback: VirtualDisplay.Callback?,
        handler: Handler?
    ): VirtualDisplayHandle?
}

class AndroidProjectionAdapter(private val projection: MediaProjection) : ProjectionAdapter {
    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        surface: Surface?,
        callback: VirtualDisplay.Callback?,
        handler: Handler?
    ): VirtualDisplayHandle? {
        val vd = projection.createVirtualDisplay(name, width, height, dpi, flags, surface, callback, handler) ?: return null
        return AndroidVirtualDisplayHandle(vd)
    }
}