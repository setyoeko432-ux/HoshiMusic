package com.naomi.hoshimusic.playback.route

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.naomi.hoshimusic.playback.route.BtClarityPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge kecil agar UI bisa membaca status BT Clarity Guard tanpa menyentuh service langsung.
 */
object BtClarityBridge {
    private val _active = MutableStateFlow(false)

    /** Verdict AUTO dari monitor (codec-is-king). */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _a2dpPresent = MutableStateFlow(false)

    /** Ada sink A2DP terhubung? (dipakai mode FORCE) */
    val a2dpPresent: StateFlow<Boolean> = _a2dpPresent.asStateFlow()

    fun update(value: Boolean) {
        if (_active.value != value) {
            Log.i("HoshiDsp", "BtClarityBridge -> $value")
            _active.value = value
        }
    }

    fun updatePresence(value: Boolean) {
        if (_a2dpPresent.value != value) _a2dpPresent.value = value
    }
}

/**
 * BT Clarity Guard — deteksi otomatis speaker bluetooth generik berkualitas SBC.
 *
 * SBC merusak koherensi 200-500 Hz sehingga low-mid terasa 'muddy' di driver kecil.
 * Saat guard aktif, mesin DSP memaksa de-mud minimal x1.8 + boxiness -2.8 dB +
 * virtual bass dibatasi, apapun preset yang dipilih user.
 *
 * Strategi deteksi (semua dibungkus runCatching — tidak boleh crash):
 *  1. AudioDeviceCallback (tanpa permission) -> ada sink TYPE_BLUETOOTH_A2DP?
 *  2. Klasifikasi "speaker" via BluetoothClass (LOUDSPEAKER/PORTABLE/HIFI);
 *     fallback heuristik nama produk bila BLUETOOTH_CONNECT belum diizinkan.
 *  3. Cek codec lewat refleksi (BluetoothA2dp#getCodecStatus bukan API publik).
 *     Guard aktif bila codec SBC ATAU tidak diketahui, dan TIDAK aktif bila
 *     codec jelas AAC/aptX/LDAC.
 */
class BluetoothClarityMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var registered = false
    private var a2dpProxy: BluetoothA2dp? = null
    private var btAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ====== Public API ======

    fun register() {
        if (registered) return
        registered = true
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler) }
        btAdapter?.let { adapter ->
            runCatching {
                adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP)
            }.onFailure { Log.w(TAG, "Gagal ambil proxy A2DP", it) }
        }
        evaluate() // device mungkin sudah terhubung sebelum register
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        a2dpProxy?.let { proxy ->
            runCatching { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        }
        a2dpProxy = null
        emit(false)
    }

    /** Dipanggil ulang saat izin BLUETOOTH_CONNECT baru saja diberikan. */
    fun refresh() = evaluate()

    // ====== Deteksi ======

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = evaluate()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = evaluate()
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy as? BluetoothA2dp
                evaluate()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
                evaluate()
            }
        }
    }

    private fun evaluate() {
        runCatching {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val btSinks = outputs.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            
            Log.i(TAG, "[BTGuard] evaluate: btA2dpSinks=${btSinks.size} perm=${hasBtPermission()} proxy=${a2dpProxy != null}")
            BtClarityBridge.updatePresence(btSinks.isNotEmpty())
            
            // Periksa apakah output aktif sebenarnya adalah Bluetooth
            // (Sederhananya: jika ada BT sink dan tidak ada USB yang lebih diprioritaskan oleh sistem)
            val isUsbActive = outputs.any { it.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET) }
            val sink = btSinks.firstOrNull()
            
            if (sink == null || isUsbActive) {
                emit(false)
                return
            }
            
            val name = runCatching { sink.productName?.toString() }.getOrNull().orEmpty()
            Log.i(TAG, "[BTGuard] sink: name='$name'")

            val spk = classifySpeaker(sink)
            val sbc = isSbcOrUnknownCodec(sink)

            // Gunakan policy pure function
            val engage = BtClarityPolicy.shouldEngage(spk, sbc)
            Log.i(TAG, "[BTGuard] verdict: speakerLike=$spk sbcOrUnknown=$sbc -> engage=$engage")
            emit(engage)
        }.onFailure { Log.w(TAG, "Evaluasi BT gagal", it) }
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * true => terklasifikasi speaker; false => headphone/headset/buds.
     * Fallback nama produk dipakai bila BluetoothClass tak tersedia (izin belum diberikan).
     */
    private fun classifySpeaker(sink: AudioDeviceInfo): Boolean {
        val btDevice: BluetoothDevice? = if (hasBtPermission()) {
            // connectedDevices butuh BLUETOOTH_CONNECT — tangani SecurityException eksplisit.
            try {
                a2dpProxy?.connectedDevices?.let { devices -> pickDevice(devices, sink) }
            } catch (_: SecurityException) {
                null
            }
        } else null

        if (btDevice != null) {
            val cls: BluetoothClass? = try {
                btDevice.bluetoothClass
            } catch (_: SecurityException) {
                null
            }
            val devName = try {
                btDevice.name
            } catch (_: SecurityException) {
                "?"
            }
            Log.i(TAG, "[BTGuard] btDevice='$devName' class=0x${Integer.toHexString(cls?.deviceClass ?: -1)}")
            if (cls != null) {
                when (cls.deviceClass) {
                    BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                    BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                    BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> {
                        Log.i(TAG, "[BTGuard] class verdict -> SPEAKER")
                        return true
                    }

                    BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                    BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                    BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> {
                        Log.i(TAG, "[BTGuard] class verdict -> HEADPHONE-LIKE")
                        return false
                    }

                    else -> Log.i(TAG, "[BTGuard] class verdict -> UNCATEGORIZED, pakai heuristik nama")
                }
            }
        } else {
            Log.i(TAG, "[BTGuard] btDevice=NULL (proxy/izin belum siap) -> heuristik nama")
        }

        // Fallback: heuristik nama produk (tanpa perlu izin)
        val name = runCatching { sink.productName?.toString() }.getOrNull().orEmpty().lowercase()
        return !HEADPHONE_NAME_HINT.containsMatchIn(name)
    }

    /** true => codec SBC atau tidak dapat ditentukan; false => codec modern (AAC/aptX/LDAC). */
    private fun isSbcOrUnknownCodec(sink: AudioDeviceInfo): Boolean {
        val devices: List<BluetoothDevice>? = if (hasBtPermission()) {
            try {
                a2dpProxy?.connectedDevices
            } catch (e: SecurityException) {
                null
            }
        } else null
        
        val device = devices?.let { pickDevice(it, sink) }
            ?: return true // tanpa device/izin -> anggap unknown -> guard tetap aman

        // Perbaikan alur refleksi: getCodecStatus() -> getCodecConfig() -> getCodecType()
        val codecType = try {
            val getStatus = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java)
            val status = getStatus.invoke(a2dpProxy, device)
            
            val getConfig = status?.javaClass?.getMethod("getCodecConfig")
            val config = getConfig?.invoke(status)
            
            val getType = config?.javaClass?.getMethod("getCodecType")
            (getType?.invoke(config) as? Number)?.toInt()
        } catch (e: Exception) {
            Log.w(TAG, "[BTGuard] Gagal baca codec via refleksi: ${e.message}")
            null
        }

        if (codecType == null) {
            Log.i(TAG, "[BTGuard] Codec UNKNOWN, fallback ke guard ON")
            return true
        }

        Log.i(TAG, "[BTGuard] codecType=$codecType (sbcConst=$SBC_CONST)")

        val sbcConst = SBC_CONST ?: return true 
        return codecType == sbcConst || codecType == SBC_HDR_CONST
    }

    private fun pickDevice(
        devices: List<BluetoothDevice>,
        sink: AudioDeviceInfo,
    ): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= 28) {
            val addr = runCatching { sink.address }.getOrNull()
            if (!addr.isNullOrBlank()) {
                devices.firstOrNull { it.address == addr }?.let { return it }
            }
        }
        return devices.firstOrNull()
    }

    private fun emit(value: Boolean) {
        BtClarityBridge.update(value)
    }

    private companion object {
        const val TAG = "HoshiDsp"

        // Konstanta codec dibaca reflektif agar aman dikompilasi di semua SDK level.
        val SBC_CONST: Int? = runCatching {
            val f = Class.forName("android.bluetooth.BluetoothCodecConfig")
                .getField("SOURCE_CODEC_TYPE_SBC")
            f.getInt(null)
        }.getOrNull()

        val SBC_HDR_CONST: Int? = runCatching {
            val f = Class.forName("android.bluetooth.BluetoothCodecConfig")
                .getField("SOURCE_CODEC_TYPE_SBC_HDR")
            f.getInt(null)
        }.getOrNull()

        val HEADPHONE_NAME_HINT = Regex(
            "buds|headphone|headset|earphone|earbud|airpod|galaxy\\s*buds|wf[- ]|wh[- ]|xm[0-9]|iem|tws",
            RegexOption.IGNORE_CASE
        )
    }
}
