package fi.mitsukanny.phevbridge

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Two-level in-app log, shared by [Rc4TestActivity] and [PhevCallScreeningService] -- a
 * call-triggered run reads as a handful of SUMMARY lines ("Call received", "Starting
 * heat...", "Completed"), with the full Wi-Fi/RC4 handshake only in DETAIL.
 *
 * - [SUMMARY]: what a user/operator cares about -- call received, command started
 *   with its settings, completed or failed. This is the level to show by default.
 * - [DETAIL]: the full step-by-step trace -- Wi-Fi network requested/joined, socket
 *   connecting, RC4 handshake steps, etc. For diagnosing a failure.
 *
 * Every entry also goes to Logcat (SUMMARY at INFO, DETAIL at DEBUG) so `adb logcat`
 * keeps working as usual; the in-memory buffer exists so the log is visible on-device
 * without adb. Neither survives the app crashing or being killed, though -- that's
 * exactly when the log matters most (diagnosing a failed real-car test), so once
 * [attachFileLogging] is called every entry is also appended, durably, to a plain text
 * file the app can be pulled from afterward even if the process died right after.
 */
object BridgeLog {
    enum class Level { SUMMARY, DETAIL }

    data class Entry(val timestampMs: Long, val level: Level, val message: String)

    private const val TAG = "PhevBridge"
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<(Entry) -> Unit>()
    private val fileLock = Any()
    private var logFile: File? = null
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Call once, as early as possible (e.g. Activity.onCreate), so even startup-time
     * DETAIL lines are captured. Also installs an uncaught-exception hook that writes
     * the crash (message + full stack trace) as a SUMMARY line before the app dies,
     * so a crash during a real car test leaves a diagnosable file behind -- then
     * re-throws to whatever handler was previously installed so normal Android crash
     * behavior (the "app has stopped" dialog, crash reporting, etc.) is unaffected.
     *
     * The file lives in the app's external-files directory (no permission needed on
     * API 29+): pull it with
     * `adb pull /sdcard/Android/data/fi.mitsukanny.phevbridge/files/bridge-log.txt`.
     */
    fun attachFileLogging(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "bridge-log.txt")
        detail("File logging attached: ${logFile?.absolutePath}")

        if (previousUncaughtHandler == null) {
            previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                append(Level.SUMMARY, "FATAL on thread ${thread.name}: ${throwable.message}\n$trace")
                previousUncaughtHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun summary(message: String) = append(Level.SUMMARY, message)
    fun detail(message: String) = append(Level.DETAIL, message)

    private fun append(level: Level, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        synchronized(entries) {
            entries.addLast(entry)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        when (level) {
            Level.SUMMARY -> Log.i(TAG, message)
            Level.DETAIL -> Log.d(TAG, message)
        }
        writeToFile(entry)
        synchronized(listeners) { listeners.toList() }.forEach { it(entry) }
    }

    /**
     * Opens, writes, flushes and closes the file for every single entry rather than
     * holding a writer open -- log volume here is low (a handful of lines per test
     * run), and this way a line is durably on disk before the call returns, which
     * matters when the very next thing that can happen is the process dying.
     */
    private fun writeToFile(entry: Entry) {
        val file = logFile ?: return
        synchronized(fileLock) {
            try {
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    file.writeText("[log truncated at ${timeFormat.format(System.currentTimeMillis())}]\n")
                }
                FileOutputStream(file, true).use { it.write((format(entry) + "\n").toByteArray(Charsets.UTF_8)) }
            } catch (ignored: Exception) {
                // Logging must never be the reason the app crashes.
            }
        }
    }

    /** Snapshot of everything logged so far, oldest first. */
    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    /** Called on the thread that logged the entry -- hop to the UI thread yourself if needed. */
    fun addListener(listener: (Entry) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: (Entry) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    fun format(entry: Entry): String {
        val tag = if (entry.level == Level.DETAIL) "detail" else "info"
        return "${timeFormat.format(entry.timestampMs)} [$tag] ${entry.message}"
    }
}
