package com.naomi.hoshimusic.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic logger that writes to a file in the app's own internal storage.
 *
 * WHY THIS EXISTS
 *
 * On some OEM builds logd rejects writes coming from third-party app UIDs, so
 * everything the app sends through `android.util.Log` never reaches logcat.
 * Verified on vivo/iQOO V2322, Android 15 (SDK 35), `ro.build.type=user`:
 *
 *   adb shell setprop log.tag V                       -> property is written, still 0 lines
 *   adb shell run-as com.naomi.hoshimusic logcat -d    -> empty
 *   logcat -b main                                    -> system processes only
 *
 * The empty `run-as` result proves the block is on the WRITE side, not the read
 * side, so no logcat flag can work around it. Audio-path diagnostics therefore
 * cannot depend on logcat on these devices.
 *
 * This logger writes to `filesDir/hoshi_diag.log`, which needs no OEM
 * cooperation to read:
 *
 *   adb shell run-as com.naomi.hoshimusic cat files/hoshi_diag.log
 *
 * THREADING — READ THIS BEFORE ADDING A CALL SITE
 *
 * The call site never touches the disk. It formats a line and hands it to a
 * single background thread through a bounded queue; the writer blocks, the
 * caller does not. When the queue is full the entry is dropped and counted, so
 * logging degrades instead of stalling whichever thread logged.
 *
 * Never call this from `AudioProcessor.queueInput` or any other per-buffer
 * audio callback. Those run on the playback thread at buffer rate; doing work
 * there would trade a missing-log problem for a real audio dropout.
 */
object DiagLog {

    private const val FILE_NAME = "hoshi_diag.log"
    private const val MAX_BYTES = 512L * 1024L
    private const val MAX_BACKUPS = 2
    private const val QUEUE_CAPACITY = 512

    @Volatile
    private var dir: File? = null

    private val dropped = AtomicLong(0L)
    private val written = AtomicLong(0L)
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    private val stamp = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private val writer = Thread({ drainForever() }, "hoshi-diag").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    @Volatile
    private var started = false

    /** Call once from Application.onCreate. Safe to call more than once. */
    fun init(context: Context) {
        if (dir == null) {
            synchronized(this) {
                if (dir == null) dir = context.applicationContext.filesDir
            }
        }
        if (!started) {
            started = true
            writer.start()
            i("DiagLog", "ready; file=${dir?.absolutePath}")
        }
    }

    /**
     * Cermin ke logcat sebagai bonus di perangkat yang logcat-nya tidak diblokir.
     *
     * Dibatasi runCatching dengan sengaja: android.util.Log adalah stub yang
     * melempar RuntimeException("Stub!") di unit test JVM, dan pemanggilanya
     * berada di dalam catch block milik kode produksi. Logger diagnosis tidak
     * boleh pernah menjatuhkan aplikasi — hanya Alarm yang menulis.
     */
    private inline fun mirror(block: () -> Unit) {
        runCatching { block() }
    }

    fun d(tag: String, msg: String) {
        mirror { android.util.Log.d(tag, msg) }
        emit('D', tag, msg)
    }

    fun i(tag: String, msg: String) {
        mirror { android.util.Log.i(tag, msg) }
        emit('I', tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        mirror { if (t != null) android.util.Log.w(tag, msg, t) else android.util.Log.w(tag, msg) }
        emit('W', tag, if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        mirror { if (t != null) android.util.Log.e(tag, msg, t) else android.util.Log.e(tag, msg) }
        emit('E', tag, if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    // withInitial menjamin tidak pernah null, tapi ThreadLocal.get() tetap
    // bertipe nullable dari pandangan Kotlin.
    private fun now(): String = stamp.get()!!.format(Date(System.currentTimeMillis()))

    private fun emit(level: Char, tag: String, msg: String) {
        if (dir == null) return
        val line = buildString {
            append(now())
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(msg)
            append('\n')
        }
        if (queue.offer(line)) return

        // Antrean penuh.Buang, jangan tunggu — kalau caller ini audio thread,
        // menunggu berarti glitch.
        val n = dropped.incrementAndGet()
        if (n == 1L) {
            queue.offer("${now()} !/DiagLog: antrean penuh, entri mulai dibuang\n")
        }
    }

    private fun drainForever() {
        while (true) {
            val line = queue.take()
            val d = dir ?: continue
            try {
                val file = File(d, FILE_NAME)
                rotateIfNeeded(file)
                FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
                written.incrementAndGet()
            } catch (_: Throwable) {
                // Diagnostik tidak boleh menjatuhkan aplikasi.
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val parent = file.parentFile ?: return
        for (i in MAX_BACKUPS downTo 1) {
            val from = File(parent, if (i == 1) FILE_NAME else "$FILE_NAME.${i - 1}")
            if (!from.exists()) continue
            File(parent, "$FILE_NAME.$i").delete()
            from.renameTo(File(parent, "$FILE_NAME.$i"))
        }
    }

    /** Pembacaan dari adb: run-as com.naomi.hoshimusic cat files/hoshi_diag.log */
    fun stats(): String = "written=${written.get()} dropped=${dropped.get()} queued=${queue.size}"
}
