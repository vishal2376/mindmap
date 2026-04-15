package com.mindmap.plugin

import com.intellij.openapi.application.PathManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Dedicated debug logger for the Mindmap plugin.
 *
 * Writes to a separate file (mindmap-debug.log) in the IDE log directory,
 * so plugin logs are not mixed with the thousands of IDE log lines.
 * Only active when the user enables "Debug Logging" in the Mindmap settings panel.
 */
object DebugLog {

    @Volatile var enabled = false

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val logFile: File by lazy {
        File(PathManager.getLogPath(), "mindmap-debug.log")
    }

    val logFilePath: String get() = logFile.absolutePath

    fun log(msg: String) {
        if (!enabled) return
        try {
            val timestamp = LocalDateTime.now().format(formatter)
            logFile.appendText("[$timestamp] $msg\n")
        } catch (_: Exception) { }
    }

    fun clear() {
        try {
            if (logFile.exists()) logFile.writeText("")
        } catch (_: Exception) { }
    }
}
