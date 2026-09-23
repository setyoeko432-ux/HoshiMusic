package com.naomi.hoshimusic.playback.dsp

import com.naomi.hoshimusic.data.PeqProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object DspPreviewBridge {
    private val _previewPeq = MutableStateFlow<PeqProfile?>(null)
    val previewPeq = _previewPeq.asStateFlow()

    private val _previewParams = MutableStateFlow<FloatArray?>(null)
    val previewParams = _previewParams.asStateFlow()

    fun setPreviewPeq(profile: PeqProfile?) {
        _previewPeq.value = profile
    }

    fun setPreviewParams(params: FloatArray?) {
        _previewParams.value = params
    }
}
