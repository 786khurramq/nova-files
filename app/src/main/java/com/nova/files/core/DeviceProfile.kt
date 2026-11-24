package com.nova.files.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/** Capabilities used to adapt the UI and features per device (S24 Ultra vs Shield vs MINIX). */
data class DeviceProfile(
    val isTv: Boolean,
    val hasTouch: Boolean,
    val manufacturer: String,
    val model: String,
    val sdk: Int,
) {
    val typeTag: String get() = if (isTv) "tv" else "phone"
    val defaultName: String get() = when {
        model.contains("SHIELD", true) -> "NVIDIA Shield"
        manufacturer.contains("MINIX", true) -> "MINIX ${model.removePrefix("MINIX").trim()}".trim()
        manufacturer.equals("samsung", true) -> modelFriendly()
        else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
    }

    private fun modelFriendly(): String = when {
        model.startsWith("SM-S928") -> "Galaxy S24 Ultra"
        model.startsWith("SM-S92") -> "Galaxy S24"
        else -> "Samsung $model"
    }

    companion object {
        fun detect(ctx: Context): DeviceProfile {
            val pm = ctx.packageManager
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            val tv = ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature("android.software.leanback_only")
            val touch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) && !tv
            return DeviceProfile(tv, touch, Build.MANUFACTURER ?: "", Build.MODEL ?: "", Build.VERSION.SDK_INT)
        }
    }
}
