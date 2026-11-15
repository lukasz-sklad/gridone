package one.lmk.gridcraft

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Main Android activity hosting GridCraft (`eframe` + `wgpu` on top of `GameActivity`).
 * Bridges IME composing sessions, safe display/IME insets, SAF document pickers,
 * mouse/trackpad wheel & hover events, clipboard, and Cast / Presentation mirroring.
 */
class GridCraftActivity : GameActivity() {
    companion object {
        private const val TAG = "GridCraftActivity"
        private const val SAF_STAGING_DIR = "saf_staging"
        private const val MAX_STAGED_BYTES = 128L * 1024L * 1024L

        init {
            System.loadLibrary("gridcraft_android")
        }

        @JvmStatic
        external fun nativeOnInsetsTestPing(bytes: ByteArray)
    }

    private external fun nativeOnNightModeChanged(night: Boolean)
    private external fun nativeOnSafeInsetsChanged(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imeBottom: Int,
    )

    private external fun nativeOnImeBatch(
        seq: Long,
        opKinds: IntArray,
        intArgs: IntArray,
        texts: Array<String>,
    )

    private external fun nativeOnImeKey(
        pressed: Boolean,
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        repeat: Int,
    )

    private external fun nativeOnImeDismissed()
    private external fun nativeOnTouchPointerType(isMouse: Boolean)
    private external fun nativeOnWheelEvent(dx: Float, dy: Float, ctrl: Boolean, shift: Boolean)
    private external fun nativeOnMouseHover(xPx: Float, yPx: Float)
    private external fun nativeOnFilePicked(
        uri: String,
        stagedPath: String,
        displayName: String,
        writable: Boolean,
    )

    private external fun nativeOnFileSavePicked(
        uri: String,
        stagedPath: String,
        displayName: String,
    )

    private external fun nativeOnFileCancelled()
    private external fun nativeOnPendingOpen(
        uri: String,
        stagedPath: String,
        displayName: String,
        writable: Boolean,
    )

    private external fun nativeOnPresentationDisplayChanged(
        active: Boolean,
        widthPx: Int,
        heightPx: Int,
    )

    private data class PendingFrame(
        val rgba: ByteArray,
        val width: Int,
        val height: Int,
        val backdropArgb: Int,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GridCraftIo").apply { isDaemon = true }
    }
    private val imeSession = ImeEditSession()
    private var imeProxyView: ImeProxyView? = null
    private var imeSingleLine = false
    private var lastReportedImeVisible = false
    private var lastReportedIsMouse: Boolean? = null

    private lateinit var openPickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var savePickerLauncher: ActivityResultLauncher<String>
    private lateinit var castController: CastPlatformController
    private var displayManager: DisplayManager? = null
    private var activeExternalDisplay: Display? = null
    private var externalPresentation: GridExternalPresentation? = null

    private val pendingPresentationFrame = AtomicReference<PendingFrame?>(null)
    private val presentationDrainScheduled = AtomicBoolean(false)
    private var reusableBitmap: Bitmap? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshExternalDisplay()
        override fun onDisplayRemoved(displayId: Int) = refreshExternalDisplay()
        override fun onDisplayChanged(displayId: Int) = refreshExternalDisplay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupLaunchers()
        castController = PlatformCastFactory.create(this) {
            notifyDisplayBridgeState()
        }
        super.onCreate(savedInstanceState)

        configureEdgeToEdgeWindow()
        installImeProxyView()
        installPointerHooks()
        installWindowInsetsListener()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)

        nativeOnNightModeChanged(jniIsNightMode())
        refreshExternalDisplay()
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        castController.onStart()
        refreshExternalDisplay()
    }

    override fun onStop() {
        castController.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        dismissExternalPresentation()
        castController.onDestroy()
        reusableBitmap?.recycle()
        reusableBitmap = null
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        nativeOnNightModeChanged(isNightUiConfig(newConfig))
        mSurfaceView?.let { ViewCompat.requestApplyInsets(it) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 0) {
            val isMouse = ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
            if (lastReportedIsMouse != isMouse) {
                lastReportedIsMouse = isMouse
                nativeOnTouchPointerType(isMouse)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupLaunchers() {
        openPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                nativeOnFileCancelled()
                return@registerForActivityResult
            }
            backgroundExecutor.execute {
                stageUriAndReport(uri, forPendingOpen = false)
            }
        }

        savePickerLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri == null) {
                nativeOnFileCancelled()
                return@registerForActivityResult
            }
            backgroundExecutor.execute {
                val displayName = resolveDisplayName(uri)
                val stagingFile = allocateStagingFile(displayName)
                mainHandler.post {
                    nativeOnFileSavePicked(uri.toString(), stagingFile.absolutePath, displayName)
                }
            }
        }
    }

    private fun configureEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            window.attributes = lp
        }
        jniSetSystemBarsDark(jniIsNightMode())
    }

    private fun installImeProxyView() {
        val root = findViewById<ViewGroup>(contentViewId) ?: return
        val proxy = ImeProxyView(this)
        val lp = FrameLayout.LayoutParams(1, 1)
        root.addView(proxy, lp)
        imeProxyView = proxy
    }

    private fun installPointerHooks() {
        val surface = mSurfaceView ?: return
        surface.setOnGenericMotionListener { _, event ->
            handleGenericMotion(event)
        }
        surface.setOnHoverListener { _, event ->
            handleHoverMotion(event)
        }
    }

    private fun handleGenericMotion(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_POINTER) == 0) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 48f
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 48f
                val ctrl = (event.metaState and KeyEvent.META_CTRL_ON) != 0
                val shift = (event.metaState and KeyEvent.META_SHIFT_ON) != 0
                if (h != 0f || v != 0f) {
                    nativeOnWheelEvent(h, v, ctrl, shift)
                }
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_ENTER -> {
                return handleHoverMotion(event)
            }
        }
        return false
    }

    private fun handleHoverMotion(event: MotionEvent): Boolean {
        if (event.pointerCount > 0) {
            val isMouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
            if (lastReportedIsMouse != isMouse) {
                lastReportedIsMouse = isMouse
                nativeOnTouchPointerType(isMouse)
            }
        }
        nativeOnMouseHover(event.x, event.y)
        return false
    }

    private fun installWindowInsetsListener() {
        val target = mSurfaceView ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(target) { _, windowInsets ->
            val sys = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())

            if (lastReportedImeVisible && !imeVisible) {
                nativeOnImeDismissed()
            }
            lastReportedImeVisible = imeVisible

            val computed = InsetsCalculator.compute(
                sysLeft = sys.left,
                sysTop = sys.top,
                sysRight = sys.right,
                sysBottom = sys.bottom,
                cutoutLeft = cutout.left,
                cutoutTop = cutout.top,
                cutoutRight = cutout.right,
                cutoutBottom = cutout.bottom,
                imeBottom = ime.bottom,
                imeVisible = imeVisible,
            )
            nativeOnSafeInsetsChanged(
                computed.left,
                computed.top,
                computed.right,
                computed.bottom,
                computed.imeBottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(target)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_EDIT) return
        val uri = intent.data ?: return
        backgroundExecutor.execute {
            stageUriAndReport(uri, forPendingOpen = true)
        }
    }

    private fun stageUriAndReport(uri: Uri, forPendingOpen: Boolean) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
            }

            val displayName = resolveDisplayName(uri)
            val stagedFile = allocateStagingFile(displayName)
            try {
                copyUriBounded(uri, stagedFile)
            } catch (err: Exception) {
                runCatching { stagedFile.delete() }
                throw err
            }
            val writable = isUriWritable(uri)

            mainHandler.post {
                if (forPendingOpen) {
                    nativeOnPendingOpen(uri.toString(), stagedFile.absolutePath, displayName, writable)
                } else {
                    nativeOnFilePicked(uri.toString(), stagedFile.absolutePath, displayName, writable)
                }
            }
        } catch (err: Exception) {
            Log.w(TAG, "Failed staging URI $uri: ${err.message}")
            if (!forPendingOpen) {
                mainHandler.post { nativeOnFileCancelled() }
            }
        }
    }

    private fun copyUriBounded(uri: Uri, dest: File) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI input stream")
        input.use { stream ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n.toLong()
                    if (total > MAX_STAGED_BYTES) {
                        throw IllegalStateException("File exceeds maximum staged limit ($MAX_STAGED_BYTES bytes)")
                    }
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }
    }

    private fun allocateStagingFile(displayName: String): File {
        val dir = File(cacheDir, SAF_STAGING_DIR).apply { mkdirs() }
        pruneOldStagingFiles(dir)
        return AndroidSecurityPolicy.allocateSafeStagingFile(dir, displayName)
    }

    private fun pruneOldStagingFiles(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= 16) return
        files.sortedBy { it.lastModified() }
            .take(files.size - 16)
            .forEach { runCatching { it.delete() } }
    }

    private fun sanitizeFilename(raw: String): String =
        AndroidSecurityPolicy.sanitizeFilename(raw)

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) {
                            return sanitizeFilename(name)
                        }
                    }
                }
            }
        }
        return sanitizeFilename(uri.lastPathSegment ?: "workbook.xlsx")
    }

    private fun isUriWritable(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return false
            return File(path).canWrite()
        }
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val perm = checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (perm != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            if (DocumentsContract.isDocumentUri(this, uri)) {
                runCatching {
                    contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val flags = c.getInt(0)
                            return (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                        }
                    }
                }
            }
            return true
        }
        return false
    }

    private fun isNightUiConfig(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun refreshExternalDisplay() {
        val mgr = displayManager ?: return
        val presDisplays = mgr.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val candidates = presDisplays.map { d ->
            val mode = d.mode
            CastDisplayHelper.DisplayCandidate(
                displayId = d.displayId,
                isValid = d.isValid,
                isPresentation = (d.flags and Display.FLAG_PRESENTATION) != 0,
                widthPx = mode?.physicalWidth ?: 0,
                heightPx = mode?.physicalHeight ?: 0,
            )
        }
        val best = CastDisplayHelper.selectBest(candidates)
        val chosen = best?.let { b -> presDisplays.firstOrNull { it.displayId == b.displayId } }
        if (chosen?.displayId != activeExternalDisplay?.displayId) {
            dismissExternalPresentation()
            activeExternalDisplay = chosen
            if (chosen != null) {
                showExternalPresentation(chosen)
            }
        }
        notifyDisplayBridgeState()
    }

    private fun showExternalPresentation(display: Display) {
        try {
            val pres = GridExternalPresentation(this, display)
            pres.show()
            externalPresentation = pres
        } catch (err: Exception) {
            Log.w(TAG, "Failed creating external Presentation: ${err.message}")
            externalPresentation = null
        }
    }

    private fun dismissExternalPresentation() {
        runCatching { externalPresentation?.dismiss() }
        externalPresentation = null
    }

    private fun notifyDisplayBridgeState() {
        val disp = activeExternalDisplay
        if (disp != null && disp.isValid) {
            val mode = disp.mode
            val (w, h) = CastDisplayHelper.clampDimensions(
                mode?.physicalWidth ?: 1280,
                mode?.physicalHeight ?: 720,
            )
            nativeOnPresentationDisplayChanged(true, w, h)
        } else if (castController.isCastConnected) {
            nativeOnPresentationDisplayChanged(true, 1280, 720)
        } else {
            nativeOnPresentationDisplayChanged(false, 0, 0)
        }
    }

    private fun schedulePresentationDrain() {
        if (presentationDrainScheduled.compareAndSet(false, true)) {
            mainHandler.post { drainPendingPresentationFrame() }
        }
    }

    private fun drainPendingPresentationFrame() {
        presentationDrainScheduled.set(false)
        val frame = pendingPresentationFrame.getAndSet(null) ?: return
        if (frame.width <= 0 || frame.height <= 0 || frame.rgba.size < frame.width * frame.height * 4) {
            return
        }
        val bmp = obtainBitmap(frame.width, frame.height)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba))

        externalPresentation?.updateFrame(bmp, frame.backdropArgb)

        if (castController.isCastConnected) {
            val width = frame.width
            val height = frame.height
            val pngBytes = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                out.toByteArray()
            }
            backgroundExecutor.execute {
                castController.updateFrame(pngBytes, width, height)
            }
        }
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val current = reusableBitmap
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableBitmap = it
        }
    }

    private fun isPlayBuildFlavor(): Boolean =
        try {
            Class.forName("com.google.android.gms.cast.framework.CastContext")
            packageName == "one.lmk.gridone"
        } catch (_: ClassNotFoundException) {
            false
        }

    // ---- Methods invoked from Rust via JNI (ActivityBridge) ----

    @Suppress("unused")
    fun jniGetFilesDir(): String? = filesDir?.absolutePath

    @Suppress("unused")
    fun jniGetLocaleTag(): String = Locale.getDefault().toLanguageTag()

    @Suppress("unused")
    fun jniIsNightMode(): Boolean = isNightUiConfig(resources.configuration)

    @Suppress("unused")
    fun jniIsPlayFlavor(): Boolean = isPlayBuildFlavor()

    @Suppress("unused")
    fun jniLaunchOpenPicker() {
        mainHandler.post {
            val mimes = arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
                "application/vnd.ms-excel.sheet.macroEnabled.12",
                "application/vnd.ms-excel.template.macroEnabled.12",
                "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
                "application/vnd.ms-excel",
                "application/vnd.oasis.opendocument.spreadsheet",
                "text/csv",
                "text/tab-separated-values",
                "text/comma-separated-values",
                "application/octet-stream",
                "*/*",
            )
            openPickerLauncher.launch(mimes)
        }
    }

    @Suppress("unused")
    fun jniLaunchSavePicker(suggestedName: String) {
        mainHandler.post {
            savePickerLauncher.launch(sanitizeFilename(suggestedName))
        }
    }

    @Suppress("unused")
    fun jniWriteUriBytes(uri: String, bytes: ByteArray): Boolean {
        return try {
            val parsed = Uri.parse(uri)
            val out = try {
                contentResolver.openOutputStream(parsed, "wt")
            } catch (_: IllegalArgumentException) {
                contentResolver.openOutputStream(parsed, "w")
            } ?: return false
            out.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            true
        } catch (err: Exception) {
            Log.w(TAG, "Failed writing bytes to $uri: ${err.message}")
            false
        }
    }

    @Suppress("unused")
    fun jniShowIme(singleLine: Boolean) {
        mainHandler.post {
            imeSingleLine = singleLine
            val proxy = imeProxyView ?: return@post
            proxy.isFocusable = true
            proxy.isFocusableInTouchMode = true
            proxy.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.restartInput(proxy)
            imm?.showSoftInput(proxy, InputMethodManager.SHOW_IMPLICIT)
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.ime())
        }
    }

    @Suppress("unused")
    fun jniHideIme() {
        mainHandler.post {
            val proxy = imeProxyView ?: return@post
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(proxy.windowToken, 0)
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            proxy.clearFocus()
            proxy.isFocusable = false
            proxy.isFocusableInTouchMode = false
        }
    }

    @Suppress("unused")
    fun jniSyncImeText(text: String, anchorU16: Int, focusU16: Int, ackSeq: Long) {
        mainHandler.post {
            val result = imeSession.syncFromEngine(text, anchorU16, focusU16, ackSeq)
            if (result.changed) {
                val proxy = imeProxyView ?: return@post
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.updateSelection(
                    proxy,
                    result.selStart,
                    result.selEnd,
                    result.composingStart,
                    result.composingEnd,
                )
            }
        }
    }

    @Suppress("unused")
    fun jniSetClipboardText(text: String) {
        mainHandler.post {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("GridCraft", text))
            }
        }
    }

    @Suppress("unused")
    fun jniGetClipboardText(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) null else clip.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("unused")
    fun jniOpenUrl(url: String) {
        if (!AndroidSecurityPolicy.isAllowedExternalUrl(url)) return
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return
        mainHandler.post {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, parsed))
            }
        }
    }

    @Suppress("unused")
    fun jniOpenCastMenu() {
        mainHandler.post {
            castController.openCastChooser(this, activeExternalDisplay)
        }
    }

    @Suppress("unused")
    fun jniPresentRgbaFrame(rgba: ByteArray, width: Int, height: Int, backdropArgb: Int) {
        pendingPresentationFrame.set(PendingFrame(rgba, width, height, backdropArgb))
        schedulePresentationDrain()
    }

    @Suppress("unused")
    fun jniClearPresentationFrame() {
        pendingPresentationFrame.set(null)
        mainHandler.post {
            externalPresentation?.clearFrame()
            castController.clearFrame()
        }
    }

    @Suppress("unused")
    fun jniSetSystemBarsDark(darkUi: Boolean) {
        mainHandler.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkUi
                isAppearanceLightNavigationBars = !darkUi
            }
        }
    }

    private fun dispatchImeBatch(batch: ImeEditSession.Batch?) {
        if (batch == null) return
        nativeOnImeBatch(batch.seq, batch.opKinds, batch.intArgs, batch.texts)
    }

    private inner class ImeProxyView(context: Context) : View(context) {
        private var inputConnection: GridInputConnection? = null

        init {
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                (if (imeSingleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                (if (imeSingleLine) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NONE)
            outAttrs.initialSelStart = imeSession.selectionStart
            outAttrs.initialSelEnd = imeSession.selectionEnd
            val conn = GridInputConnection(this)
            inputConnection = conn
            return conn
        }

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && lastReportedImeVisible) {
                nativeOnImeDismissed()
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }

    private inner class GridInputConnection(targetView: View) : BaseInputConnection(targetView, true) {
        private val editableBridge: Editable by lazy {
            Editable.Factory.getInstance().newEditable(imeSession.text)
        }

        override fun getEditable(): Editable {
            val cur = imeSession.text
            if (editableBridge.toString() != cur) {
                editableBridge.replace(0, editableBridge.length, cur)
            }
            return editableBridge
        }

        override fun beginBatchEdit(): Boolean {
            imeSession.beginBatchEdit()
            return true
        }

        override fun endBatchEdit(): Boolean {
            dispatchImeBatch(imeSession.endBatchEdit())
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            imeSession.setComposingRegion(start, end)
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            dispatchImeBatch(imeSession.setComposingText(text, newCursorPosition))
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            dispatchImeBatch(imeSession.commitText(text, newCursorPosition, imeSingleLine))
            return true
        }

        override fun finishComposingText(): Boolean {
            dispatchImeBatch(imeSession.finishComposingText())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            dispatchImeBatch(imeSession.deleteSurroundingText(beforeLength, afterLength))
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            dispatchImeBatch(imeSession.deleteSurroundingTextInCodePoints(beforeLength, afterLength))
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            dispatchImeBatch(imeSession.setSelection(start, end))
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val txt = imeSession.text
            val start = imeSession.selectionStart.coerceIn(0, txt.length)
            return txt.substring((start - n.coerceAtLeast(0)).coerceAtLeast(0), start)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            val txt = imeSession.text
            val end = imeSession.selectionEnd.coerceIn(0, txt.length)
            return txt.substring(end, (end + n.coerceAtLeast(0)).coerceAtMost(txt.length))
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            val txt = imeSession.text
            val s = imeSession.selectionStart.coerceIn(0, txt.length)
            val e = imeSession.selectionEnd.coerceIn(s, txt.length)
            return if (s == e) null else txt.substring(s, e)
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            when (editorAction) {
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEND -> dispatchImeBatch(imeSession.tapKey(ImeEditSession.KEYCODE_ENTER))
                EditorInfo.IME_ACTION_NEXT -> dispatchImeBatch(imeSession.tapKey(ImeEditSession.KEYCODE_TAB))
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val pressed = event.action == KeyEvent.ACTION_DOWN
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                nativeOnImeKey(
                    pressed = pressed,
                    keyCode = event.keyCode,
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    repeat = event.repeatCount,
                )
                return true
            }
            return super.sendKeyEvent(event)
        }
    }

    private class GridExternalPresentation(
        outerContext: Context,
        display: Display,
    ) : android.app.Presentation(outerContext, display) {
        private lateinit var imageView: ImageView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            imageView = ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF18181B.toInt())
            }
            setContentView(imageView)
        }

        fun updateFrame(bitmap: Bitmap, backdropArgb: Int) {
            if (!::imageView.isInitialized) return
            imageView.setBackgroundColor(backdropArgb)
            imageView.setImageBitmap(bitmap)
        }

        fun clearFrame() {
            if (!::imageView.isInitialized) return
            imageView.setImageDrawable(null)
        }
    }
}
