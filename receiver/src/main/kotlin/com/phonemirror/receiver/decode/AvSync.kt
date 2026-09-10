package com.phonemirror.receiver.decode

sealed class RenderDecision {
    data object Now : RenderDecision()
    data class At(val ns: Long) : RenderDecision()
    data object Drop : RenderDecision()
}

interface AvSync {
    fun scheduleRender(ptsUs: Long): RenderDecision
}

class NowAvSync : AvSync {
    override fun scheduleRender(ptsUs: Long): RenderDecision = RenderDecision.Now
}