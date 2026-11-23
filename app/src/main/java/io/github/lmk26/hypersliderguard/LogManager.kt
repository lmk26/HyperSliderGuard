package io.github.lmk26.hypersliderguard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import io.github.libxposed.service.XposedService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogManager {

    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.w(tag, msg, tr)
        } else {
            Log.w(tag, msg)
        }
        val detail = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        append("W", tag, detail)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e(tag, msg, tr)
        } else {
            Log.e(tag, msg)
        }
        val detail = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        append("E", tag, detail)
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }

    fun getEntriesSnapshot(): List<String> {
        synchronized(entries) {
            return entries.toList()
        }
    }

    fun buildDiagnosticReport(
        context: Context,
        service: XposedService?,
        prefs: SharedPreferences,
        isIconHidden: Boolean
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== HyperSliderGuard 诊断日志 ===")
        sb.appendLine("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("包名: ${context.packageName}")
        sb.appendLine("设备型号: ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("系统版本: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("系统构建: ${Build.DISPLAY} / ${Build.VERSION.INCREMENTAL}")

        val legacyActive = ModuleStatus.isActivated()
        sb.appendLine("激活状态: serviceBound=${service != null}, legacyActive=$legacyActive")

        if (service != null) {
            runCatching {
                sb.appendLine("Xposed 框架: ${service.frameworkName} ${service.frameworkVersion} (${service.frameworkVersionCode})")
                sb.appendLine("Xposed API 版本: ${service.apiVersion}")
                sb.appendLine("已授权作用域: ${service.scope}")
            }.onFailure {
                sb.appendLine("读取 XposedService 信息失败: ${it.message}")
            }
        }

        val brightnessMode = prefs.getInt(PrefsConfig.KEY_BRIGHTNESS_MODE, PrefsConfig.DEFAULT_MODE)
        val brightnessTimeout = prefs.getInt(PrefsConfig.KEY_BRIGHTNESS_TIMEOUT_MS, PrefsConfig.DEFAULT_TIMEOUT_MS)
        val volumeMode = prefs.getInt(PrefsConfig.KEY_VOLUME_MODE, PrefsConfig.DEFAULT_MODE)
        val volumeTimeout = prefs.getInt(PrefsConfig.KEY_VOLUME_TIMEOUT_MS, PrefsConfig.DEFAULT_TIMEOUT_MS)
        sb.appendLine("当前配置:")
        sb.appendLine("  亮度条: mode=${modeLabel(brightnessMode)}, timeoutMs=$brightnessTimeout")
        sb.appendLine("  音量条: mode=${modeLabel(volumeMode)}, timeoutMs=$volumeTimeout")
        sb.appendLine("  隐藏桌面图标: $isIconHidden")

        sb.appendLine()
        sb.appendLine("--- App 侧运行日志 ---")
        val snapshot = getEntriesSnapshot()
        if (snapshot.isEmpty()) {
            sb.appendLine("(暂无记录)")
        } else {
            for (line in snapshot) {
                sb.appendLine(line)
            }
        }

        sb.appendLine()
        sb.appendLine("--- 当前进程 Logcat ---")
        val logcatLines = readSelfLogcat()
        if (logcatLines.isEmpty()) {
            sb.appendLine("(未读取到当前进程相关 logcat 输出)")
        } else {
            for (line in logcatLines) {
                sb.appendLine(line)
            }
        }

        sb.appendLine()
        sb.appendLine("提示: 如需系统界面宿主侧完整 Hook 日志，请在 LSPosed 管理器「日志」页筛选标签 HyperSliderGuard。")
        return sb.toString().trimEnd()
    }

    private fun append(level: String, tag: String, msg: String) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "$ts $level/$tag: $msg"
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
    }

    private fun modeLabel(mode: Int): String {
        return when (mode) {
            PrefsConfig.MODE_DISABLE -> "禁用(0)"
            PrefsConfig.MODE_CUSTOM_TIMEOUT -> "自定义时长(1)"
            PrefsConfig.MODE_SYSTEM_DEFAULT -> "系统默认(2)"
            else -> "未知($mode)"
        }
    }

    private fun readSelfLogcat(): List<String> {
        return runCatching {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-t",
                "80",
                "-s",
                "HyperSliderGuard:V",
                "XposedServiceHelper:V",
                "XposedProvider:V"
            ).redirectErrorStream(true).start()
            val lines = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLines()
            }
            process.destroy()
            lines.filter { it.isNotBlank() && !it.startsWith("---------") }
        }.getOrElse {
            listOf("读取 logcat 异常: ${it.message}")
        }
    }
}