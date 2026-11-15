package one.lmk.gridcraft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Display
import androidx.appcompat.app.AlertDialog

/**
 * FOSS flavor Cast controller: relies strictly on offline Android system external displays
 * (`Presentation` via HDMI / DisplayPort / Miracast) and opens system Cast settings.
 */
object PlatformCastFactory {
    fun create(
        context: Context,
        onCastStateChanged: () -> Unit,
    ): CastPlatformController = FossCastController(context.applicationContext, onCastStateChanged)
}

private class FossCastController(
    private val appContext: Context,
    private val onCastStateChanged: () -> Unit,
) : CastPlatformController {
    override val isCastConnected: Boolean = false
    override val connectedDeviceName: String? = null

    override fun onStart() {}

    override fun onStop() {}

    override fun onDestroy() {}

    override fun openCastChooser(activity: Activity, activeExternalDisplay: Display?) {
        val statusMsg = if (activeExternalDisplay != null) {
            val mode = activeExternalDisplay.mode
            activity.getString(
                R.string.cast_status_display,
                mode?.physicalWidth ?: 0,
                mode?.physicalHeight ?: 0,
            )
        } else {
            activity.getString(R.string.cast_status_idle)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.cast_menu_title)
            .setMessage(statusMsg)
            .setPositiveButton(R.string.cast_system_settings) { _, _ ->
                openSystemCastSettings(activity)
            }
            .setNegativeButton(R.string.close, null)
            .show()
        onCastStateChanged()
    }

    override fun updateFrame(pngBytes: ByteArray, width: Int, height: Int) {}

    override fun clearFrame() {}

    private fun openSystemCastSettings(activity: Activity) {
        val intents = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(appContext.packageManager) != null) {
                    activity.startActivity(intent)
                    return
                }
            } catch (_: RuntimeException) {
            }
        }
    }
}
