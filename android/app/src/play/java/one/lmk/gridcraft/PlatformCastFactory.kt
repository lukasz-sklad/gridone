package one.lmk.gridcraft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage

/**
 * Play flavor Cast controller combining offline Android Presentation displays with
 * Google Cast SDK local HTTP PNG mirroring.
 */
object PlatformCastFactory {
    fun create(
        context: Context,
        onCastStateChanged: () -> Unit,
    ): CastPlatformController = PlayCastController(context.applicationContext, onCastStateChanged)
}

private class PlayCastController(
    private val appContext: Context,
    private val onCastStateChanged: () -> Unit,
) : CastPlatformController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpServer = LocalCastHttpServer()
    private var castContext: CastContext? = null
    private var activeSession: CastSession? = null
    private var listenerRegistered = false
    private var lastSubmittedRev: Long = 0L
    private var lastSubmitMs: Long = 0L

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            activeSession = session
            httpServer.ensureStarted()
            lastSubmittedRev = 0L
            pushLatestFrameIfReady(force = true)
            onCastStateChanged()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            activeSession = null
            onCastStateChanged()
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            if (activeSession === session) {
                activeSession = null
            }
            httpServer.stop()
            onCastStateChanged()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            activeSession = session
            httpServer.ensureStarted()
            lastSubmittedRev = 0L
            pushLatestFrameIfReady(force = true)
            onCastStateChanged()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            activeSession = null
            onCastStateChanged()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            onCastStateChanged()
        }
    }

    override val isCastConnected: Boolean
        get() = activeSession?.isConnected == true

    override val connectedDeviceName: String?
        get() = activeSession?.castDevice?.friendlyName

    override fun onStart() {
        ensureCastContext { ctx ->
            val mgr = ctx.sessionManager
            if (!listenerRegistered) {
                mgr.addSessionManagerListener(sessionListener, CastSession::class.java)
                listenerRegistered = true
            }
            activeSession = mgr.currentCastSession
            if (activeSession?.isConnected == true) {
                httpServer.ensureStarted()
                pushLatestFrameIfReady(force = true)
            }
            onCastStateChanged()
        }
    }

    override fun onStop() {
        val ctx = castContext ?: return
        if (listenerRegistered) {
            ctx.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
            listenerRegistered = false
        }
    }

    override fun onDestroy() {
        onStop()
        httpServer.stop()
        activeSession = null
    }

    override fun openCastChooser(activity: Activity, activeExternalDisplay: Display?) {
        ensureCastContext { ctx ->
            showCastMenuDialog(activity, ctx, activeExternalDisplay)
        }
        if (castContext == null) {
            showCastMenuDialog(activity, null, activeExternalDisplay)
        }
    }

    override fun updateFrame(pngBytes: ByteArray, width: Int, height: Int) {
        if (!isCastConnected) return
        httpServer.ensureStarted()
        val rev = httpServer.updateFrame(pngBytes, width, height)
        if (rev != lastSubmittedRev) {
            pushLatestFrameIfReady(force = false)
        }
    }

    override fun clearFrame() {
        httpServer.clearFrame()
        lastSubmittedRev = 0L
    }

    private fun pushLatestFrameIfReady(force: Boolean) {
        val session = activeSession ?: return
        if (!session.isConnected) return
        val client = session.remoteMediaClient ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSubmitMs < 350L) return
        val url = httpServer.currentFrameUrl() ?: return
        val rev = url.substringAfterLast("frame_").substringBefore(".png").toLongOrNull() ?: 0L
        if (!force && rev == lastSubmittedRev) return
        lastSubmittedRev = rev
        lastSubmitMs = now

        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
            putString(MediaMetadata.KEY_TITLE, "GridO")
            addImage(WebImage(Uri.parse(url)))
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_NONE)
            .setContentType("image/png")
            .setMetadata(meta)
            .build()
        val loadData = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        mainHandler.post {
            try {
                client.load(loadData)
            } catch (_: Exception) {
            }
        }
    }

    private fun showCastMenuDialog(
        activity: Activity,
        ctx: CastContext?,
        activeExternalDisplay: Display?,
    ) {
        val statusMsg = buildString {
            val dev = connectedDeviceName
            if (!dev.isNullOrBlank()) {
                append(activity.getString(R.string.cast_status_connected, dev))
            } else if (activeExternalDisplay != null) {
                val mode = activeExternalDisplay.mode
                append(
                    activity.getString(
                        R.string.cast_status_display,
                        mode?.physicalWidth ?: 0,
                        mode?.physicalHeight ?: 0,
                    )
                )
            } else {
                append(activity.getString(R.string.cast_status_idle))
            }
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.cast_menu_title)
            .setMessage(statusMsg)
            .setNegativeButton(R.string.close, null)

        if (ctx != null) {
            if (isCastConnected) {
                builder.setPositiveButton(R.string.cast_disconnect) { _, _ ->
                    ctx.sessionManager.endCurrentSession(true)
                    onCastStateChanged()
                }
            } else {
                builder.setPositiveButton(R.string.cast_route_choose) { _, _ ->
                    val selector = ctx.mergedSelector ?: return@setPositiveButton
                    MediaRouteChooserDialog(activity).apply {
                        setRouteSelector(selector)
                        show()
                    }
                }
            }
            builder.setNeutralButton(R.string.cast_system_settings) { _, _ ->
                openSystemCastSettings(activity)
            }
        } else {
            builder.setPositiveButton(R.string.cast_system_settings) { _, _ ->
                openSystemCastSettings(activity)
            }
        }

        builder.show()
    }

    private fun ensureCastContext(onReady: (CastContext) -> Unit) {
        castContext?.let {
            onReady(it)
            return
        }
        try {
            val mainExec = ContextCompat.getMainExecutor(appContext)
            CastContext.getSharedInstance(appContext, mainExec)
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    onReady(ctx)
                }
        } catch (_: Exception) {
        }
    }

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
