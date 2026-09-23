package com.naomi.hoshimusic.playback.route

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import com.naomi.hoshimusic.playback.OutputSinkCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class AudioRouteState(
    val name: String = "Speaker perangkat",
    val description: String = "Output Android standar",
    val type: RouteType = RouteType.SPEAKER,
    val isUsb: Boolean = false,
    val isExternal: Boolean = false,
    val sinkCapability: OutputSinkCapability = OutputSinkCapability()
)

enum class RouteType { SPEAKER, HEADPHONES, BLUETOOTH, HDMI, USB, WATCH, CAST }

class AudioRouteMonitor(private val context: Context) : AutoCloseable {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var bluetoothA2dp: BluetoothA2dp? = null
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val mutableState = MutableStateFlow(resolveRoute())
    val state = mutableState.asStateFlow()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = proxy as BluetoothA2dp
                refresh()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = null
                refresh()
            }
        }
    }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        refresh()
    }

    private fun refresh() {
        mutableState.value = resolveRoute()
    }

    private fun resolveRoute(): AudioRouteState {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        
        val preferred = outputs.firstOrNull { it.type in USB_TYPES }
            ?: outputs.firstOrNull { it.type in WIRED_TYPES }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_HDMI }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        
        if (preferred == null) return AudioRouteState()
        
        val product = preferred.productName?.toString()?.trim().orEmpty()
        
        return when (preferred.type) {
            in USB_TYPES -> {
                val deviceName = product.ifBlank { "External USB Device" }
                val (rate, bit) = resolveActualCapability(preferred, 192000, 24)
                val cap = OutputSinkCapability(deviceName, "USB DAC", rate, bit, false)
                AudioRouteState(deviceName, "High-Fidelity USB Audio", RouteType.USB, isUsb = true, isExternal = true, cap)
            }
            in WIRED_TYPES -> {
                val deviceName = product.ifBlank { "Wired Headphones" }
                val (rate, bit) = resolveActualCapability(preferred, 192000, 24)
                val cap = OutputSinkCapability(deviceName, "Analog", rate, bit, false)
                AudioRouteState(deviceName, "Analog High-Res Output", RouteType.HEADPHONES, isExternal = true, sinkCapability = cap)
            }
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                val deviceName = product.ifBlank { getBluetoothDeviceName("Bluetooth Audio") }
                val codec = getBluetoothCodecInfo()
                val cap = evaluateBluetoothCapability(deviceName, codec)
                val desc = if (preferred.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) "Wireless Call Profile (SCO)" 
                           else "Wireless Music Profile ($codec)"
                AudioRouteState(deviceName, desc, RouteType.BLUETOOTH, isExternal = true, sinkCapability = cap)
            }
            AudioDeviceInfo.TYPE_HDMI -> {
                val deviceName = product.ifBlank { "HDMI Output" }
                val (rate, bit) = resolveActualCapability(preferred, 192000, 24)
                val cap = OutputSinkCapability(deviceName, "HDMI", rate, bit, false)
                AudioRouteState(deviceName, "Digital Audio Interface", RouteType.HDMI, isExternal = true, sinkCapability = cap)
            }
            else -> {
                val deviceName = "Internal Speaker"
                val (rate, bit) = resolveActualCapability(preferred, 48000, 16)
                val cap = OutputSinkCapability(deviceName, "Internal PCM", rate, bit, false)
                AudioRouteState(deviceName, "Built-in Dual Speaker", RouteType.SPEAKER, sinkCapability = cap)
            }
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun getBluetoothDeviceName(fallback: String): String {
        if (!hasBluetoothConnectPermission()) return "Bluetooth Audio"
        return try {
            val device = bluetoothA2dp?.connectedDevices?.firstOrNull() ?: return fallback
            device.name ?: fallback
        } catch (e: SecurityException) {
            fallback
        }
    }

    private fun resolveActualCapability(
        device: AudioDeviceInfo,
        fallbackSampleRate: Int,
        fallbackBitDepth: Int
    ): Pair<Int, Int> {
        val rates = device.sampleRates
        val bestSampleRate = if (rates.isNotEmpty()) rates.maxOrNull() ?: fallbackSampleRate else fallbackSampleRate
        
        val encodings = device.encodings
        val bestBitDepth = when {
            encodings.contains(android.media.AudioFormat.ENCODING_PCM_32BIT) -> 32
            encodings.contains(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED) -> 24
            encodings.contains(android.media.AudioFormat.ENCODING_PCM_16BIT) -> 16
            else -> fallbackBitDepth
        }
        return bestSampleRate to bestBitDepth
    }

    @SuppressLint("MissingPermission")
    private fun getBluetoothCodecInfo(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val a2dp = bluetoothA2dp ?: return "SBC/AAC"
            if (!hasBluetoothConnectPermission()) return "SBC/AAC"

            return try {
                val device = a2dp.connectedDevices.firstOrNull() ?: return "SBC/AAC"
                runCatching {
                    val status = a2dp.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java)
                        .invoke(a2dp, device)
                    val config = status?.javaClass?.getMethod("getCodecConfig")?.invoke(status)
                    val type = config?.javaClass?.getMethod("getCodecType")?.invoke(config) as? Int
                    when (type) {
                        0 -> "SBC"
                        1 -> "AAC"
                        2 -> "aptX"
                        3 -> "aptX HD"
                        4 -> "LDAC"
                        else -> "Bluetooth"
                    }
                }.getOrDefault("SBC/AAC")
            } catch (e: SecurityException) {
                "SBC/AAC"
            }
        }
        return "SBC/AAC"
    }

    private fun evaluateBluetoothCapability(
        deviceName: String,
        codec: String
    ): OutputSinkCapability {
        val (rate, bit) = when (codec) {
            "LDAC" -> 96000 to 24
            "aptX HD" -> 48000 to 24
            else -> 44100 to 16
        }
        val downsampled = codec !in setOf("LDAC", "aptX HD")
        return OutputSinkCapability(deviceName, codec, rate, bit, downsampled)
    }

    override fun close() {
        audioManager.unregisterAudioDeviceCallback(callback)
        try {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
        } catch (e: SecurityException) {
        }
    }

    private companion object {
        val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
        val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )
    }
}
