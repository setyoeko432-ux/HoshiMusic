package com.naomi.hoshimusic.playback.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object DspTelemetryBridge {
    private val _telemetry = MutableStateFlow(DspTelemetry())
    val telemetry = _telemetry.asStateFlow()

    private val _vocalState = MutableStateFlow<VocalTelemetryState?>(null)
    val vocalState = _vocalState.asStateFlow()

    private val subscriberCount = AtomicInteger(0)
    val hasSubscribers: Boolean get() = subscriberCount.get() > 0

    fun incrementSubscribers() = subscriberCount.incrementAndGet()
    fun decrementSubscribers() = subscriberCount.decrementAndGet()

    fun update(data: DspTelemetry, vocal: VocalTelemetryState? = null) {
        _telemetry.value = data
        if (vocal != null) _vocalState.value = vocal
    }
}
