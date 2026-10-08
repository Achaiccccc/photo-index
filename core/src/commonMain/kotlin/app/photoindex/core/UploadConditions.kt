package app.photoindex.core

/**
 * 仅 Wi-Fi、仅充电不满足时，打包仍可进行，上传要等条件满足。
 * 测试用 [ManualUploadConditions] 切换，不必真的拔电。
 */
fun uploadAllowedByConditions(
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    onWifi: Boolean,
    charging: Boolean,
): Boolean {
    if (wifiOnly && !onWifi) return false
    if (chargingOnly && !charging) return false
    return true
}

/** 两个开关都可以改。状态机每次上传前重新读。 */
class ManualUploadConditions(
    var onWifi: Boolean = true,
    var charging: Boolean = true,
) {
    fun allows(wifiOnly: Boolean, chargingOnly: Boolean): Boolean =
        uploadAllowedByConditions(wifiOnly, chargingOnly, onWifi, charging)
}
