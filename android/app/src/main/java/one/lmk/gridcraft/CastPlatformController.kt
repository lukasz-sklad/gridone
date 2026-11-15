package one.lmk.gridcraft

import android.app.Activity
import android.view.Display

/** Flavor-specific controller for Cast / external display routing (`foss` vs `play`). */
interface CastPlatformController {
    val isCastConnected: Boolean
    val connectedDeviceName: String?

    fun onStart()
    fun onStop()
    fun onDestroy()
    fun openCastChooser(activity: Activity, activeExternalDisplay: Display?)
    fun updateFrame(pngBytes: ByteArray, width: Int, height: Int)
    fun clearFrame()
}
