package com.antigulag.vpn

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private var isInitialized = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        appContext = context.applicationContext

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logEntry = """
                    ========================================
                    CRASH: $time
                    Thread: ${thread.name} (id: ${thread.id})
                    Error: ${throwable.localizedMessage}
                    Stacktrace:
                    $stackTrace
                    ========================================
                    
                """.trimIndent()

                writeLog(logEntry)

            } catch (e: Exception) {
                Log.e("CrashLogger", "Не удалось записать лог сбоя", e)
            }

            defaultHandler?.uncaughtException(thread, throwable) ?: run {
                Process.killProcess(Process.myUid())
                System.exit(10)
            }
        }
    }

    // Для логирования пойманных исключений в try-catch
    fun log(message: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        writeLog("[$time] $message\n")
    }

    private fun writeLog(text: String) {
        val ctx = appContext ?: return
        val targetDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val logFile = File(targetDir, "crash_log.txt")
        logFile.appendText(text)
    }
}