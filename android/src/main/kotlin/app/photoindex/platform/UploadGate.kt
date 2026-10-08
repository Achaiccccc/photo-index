package app.photoindex.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import app.photoindex.core.FakeProviderLedger
import app.photoindex.core.uploadAllowedByConditions
import java.io.File

/**
 * T11 用手动开关代替真的拔电和断网。
 * 默认两个都开，这样「仅 Wi-Fi」「仅充电」仍允许上传。
 * 关掉其中一个，已封口的批会停在上传前。
 */
class UploadGate(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var onWifi: Boolean
        get() = preferences.getBoolean(KEY_WIFI, true)
        set(value) {
            preferences.edit().putBoolean(KEY_WIFI, value).commit()
        }

    var charging: Boolean
        get() = preferences.getBoolean(KEY_CHARGING, true)
        set(value) {
            preferences.edit().putBoolean(KEY_CHARGING, value).commit()
        }

    fun allows(wifiOnly: Boolean, chargingOnly: Boolean): Boolean =
        uploadAllowedByConditions(wifiOnly, chargingOnly, onWifi, charging)

    private companion object {
        const val PREFS = "upload-gate"
        const val KEY_WIFI = "wifi"
        const val KEY_CHARGING = "charging"
    }
}

fun systemLinkStatus(context: Context): String {
    val network = if (context.isOnWifi()) "Wi-Fi" else "移动网络或其他"
    val power = if (context.isCharging()) "充电中" else "未充电"
    return "系统当前是$network，$power。上传由下面两个测试开关决定。"
}

fun fakeLedgerFile(context: Context): File =
    File(context.applicationContext.filesDir, "fake-batch-ledger.txt")

class FileFakeProviderLedger(
    private val file: File,
) : FakeProviderLedger {
    override fun read(): String = if (file.isFile) file.readText() else ""

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        if (file.exists() && !file.delete()) {
            file.writeText(text)
            temporary.delete()
            return
        }
        if (!temporary.renameTo(file)) {
            file.writeText(text)
            temporary.delete()
        }
    }
}

private fun Context.isOnWifi(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

private fun Context.isCharging(): Boolean {
    val manager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return manager.isCharging
}
