package com.phonemirror.receiver.decode

sealed class RenderDecision {
    data object Now : RenderDecision()
    data class At(val ns: Long) : RenderDecision()
    data object Drop : RenderDecision()
}

interface AvSync {
    fun scheduleRender(ptsUs: Long): RenderDecision = scheduleRender(ptsUs, false)
    fun scheduleRender(ptsUs: Long, isKeyframe: Boolean): RenderDecision = scheduleRender(ptsUs)
}

class NowAvSync : AvSync {
    override fun scheduleRender(ptsUs: Long, isKeyframe: Boolean): RenderDecision = RenderDecision.Now
}