package org.openndimonitor.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager
import android.media.Image
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Surface
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import android.media.projection.MediaProjectionManager

private const val BG = 0xFF101820.toInt()
private const val PANEL = 0xFF1A2732.toInt()
private const val PANEL_FOCUS = 0xFF29445A.toInt()
private const val WHITE = 0xFFF4F7FA.toInt()
private const val SOFT = 0xFF9AABB8.toInt()
private const val BLUE = 0xFF56A9F5.toInt()
private const val GREEN = 0xFF56D6A6.toInt()
private const val DISCONNECTED_RED = 0xFFE55252.toInt()
private const val DEFAULT_WEB_URL = "https://screen.oneverse.top"
private const val LEGACY_DEFAULT_WEB_URL = "https://songverse.one/screen"
private const val MAX_NDI_FRAME_BYTES = 64 * 1024 * 1024
private const val MAX_NDI_FRAME_DIMENSION = 8192

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var cameraPreviewView: ImageView? = null
    private var previewCameraDevice: CameraDevice? = null
    private var previewCameraSession: CameraCaptureSession? = null
    private var openedPreviewCameraId: String? = null
    private var openingPreviewCameraId: String? = null
    private var waitingForPreviewCameraClose = false
    private var previewImageReader: ImageReader? = null
    private var previewConversionThread: HandlerThread? = null
    private var previewConversionHandler: Handler? = null
    private var desiredPreviewCameraId: String? = null
    private var pendingPreviewCameraId: String? = null
    private var lastLocalPreviewFrameNanos = 0L
    private var cameraPreviewPlaceholder: TextView? = null
    private var cameraStartButton: Button? = null
    private var contributeFocusTarget: View? = null
    private val cameraPreviewPoll = object : Runnable {
        override fun run() {
            val preview = cameraPreviewView ?: return
            val bitmap = ContributeService.latestCameraPreview
            if (bitmap != null && preview.tag !== bitmap) {
                (preview.tag as? Bitmap)?.takeIf { it !== bitmap }?.recycle()
                preview.setImageBitmap(bitmap)
                preview.tag = bitmap
                preview.contentDescription = "Live camera preview"
                cameraPreviewPlaceholder?.visibility = View.GONE
            }
            if (currentMode == Mode.CONTRIBUTE && ContributeService.isRunning) main.postDelayed(this, 200)
            else if (currentMode == Mode.CONTRIBUTE) renderContribute()
        }
    }
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private lateinit var nsdManager: NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var root: FrameLayout? = null
    @Volatile private var currentMode = Mode.HOME
    @Volatile private var videoView: NdiVideoView? = null
    private var webView: WebView? = null
    private var urlEntry: EditText? = null
    @Volatile private var latestSources: List<NdiSource> = emptyList()
    private var lastSourceSignature = ""
    @Volatile private var activeSource: NdiSource? = null
    @Volatile private var sourceConnected = false
    @Volatile private var frameBuffer = ByteBuffer.allocateDirect(1920 * 1080 * 4)
    @Volatile private var ndiReady = false
    private var discoveryTask: ScheduledFuture<*>? = null
    private var frameTask: ScheduledFuture<*>? = null
    private var connectionGeneration = 0
    private var hybridRequested = false
    private var hybridViewport = NdiViewport()
    private var ndiContainer: FrameLayout? = null
    private var ndiSourceTitle: TextView? = null

    private enum class Mode { HOME, SETTINGS, SOURCES, WEB, NDI, HYBRID, CONTRIBUTE }

    private data class NdiViewport(val x: Double = 0.0, val y: Double = 0.0, val width: Double = 100.0, val height: Double = 100.0)
    private var includeScreenAudio = false
    private var includeCameraAudio = false
    private var pendingCaptureMode: String? = null

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 410
        private const val REQUEST_CAPTURE_PERMISSIONS = 411
        private const val REQUEST_CAMERA_PREVIEW_PERMISSION = 412
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setKeepScreenAwake(true)
        enterImmersiveMode()
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        when (startupMode()) {
            "web" -> renderWeb(configuredUrl())
            "ndi" -> renderSources()
            "hybrid" -> renderSources(hybrid = true)
            else -> renderHome()
        }
    }

    private fun setKeepScreenAwake(keepAwake: Boolean) {
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.keepScreenOn = keepAwake
    }

    private fun enterImmersiveMode() {
        val decor = window.decorView
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        setKeepScreenAwake(true)
        if (currentMode == Mode.CONTRIBUTE) {
            val cameraIsSending = ContributeService.isRunning && ContributeService.activeMode == ContributeService.MODE_CAMERA
            ContributeService.cameraPreviewRequested = cameraIsSending
            if (!cameraIsSending) getPreferences(MODE_PRIVATE).getString("contribute_camera_id", null)?.let { requestCameraPreview(it) }
            if (ContributeService.isRunning) main.post(cameraPreviewPoll)
        }
        keepMulticastDiscoveryAwake()
        worker.execute {
            ndiReady = runCatching { NdiNative.initialize() }.getOrDefault(false)
            if (ndiReady) {
                val restoreSource = activeSource
                    ?: if (currentMode in setOf(Mode.NDI, Mode.HYBRID, Mode.SOURCES) && autoReconnectLastSource()) lastSource() else null
                val openPreview = currentMode in setOf(Mode.NDI, Mode.HYBRID) ||
                    (currentMode == Mode.SOURCES && startupMode() in setOf("ndi", "hybrid"))
                main.post {
                    if (restoreSource != null && currentMode in setOf(Mode.NDI, Mode.HYBRID, Mode.SOURCES)) {
                        connect(restoreSource, openPreview, hybrid = hybridRequested || startupMode() == "hybrid")
                    }
                    else if (currentMode == Mode.SOURCES) renderSources()
                }
                discoveryTask?.cancel(false)
                discoveryTask = worker.scheduleAtFixedRate({
                    if (!ndiReady) return@scheduleAtFixedRate
                    val found = runCatching { NdiNative.findSources(0) }.getOrDefault(emptyArray())
                    val sources = found.mapNotNull { row ->
                        if (row.size < 2 || row[0].isBlank()) null else NdiSource(row[0], row[1])
                    }
                    val signature = sources.joinToString("|") { "${it.name}\u0000${it.address}" }
                    if (signature != lastSourceSignature) {
                        lastSourceSignature = signature
                        latestSources = sources
                        main.post { if (currentMode == Mode.SOURCES) renderSources() }
                    }
                }, 0, 900, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun keepMulticastDiscoveryAwake() {
        runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("OpenNdiMonitorDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun startFramePolling() {
        if (frameTask != null && frameTask?.isCancelled == false) return
        var lastConnectionCheck = 0L
        var lastReconnectAttempt = SystemClock.elapsedRealtime()
        frameTask = worker.scheduleAtFixedRate({
            if (!ndiReady || currentMode !in setOf(Mode.NDI, Mode.HYBRID, Mode.SOURCES) || activeSource == null) return@scheduleAtFixedRate
            val now = SystemClock.elapsedRealtime()
            if (now - lastConnectionCheck >= 1000L) {
                lastConnectionCheck = now
                val connections = runCatching { NdiNative.connectionCount() }.getOrDefault(-1)
                val wasConnected = sourceConnected
                sourceConnected = connections > 0
                main.post { videoView?.setConnectionCount(connections) }
                if (wasConnected != sourceConnected) main.post { if (currentMode == Mode.SOURCES) renderSources() }
                if (connections == 0 && now - lastReconnectAttempt >= 5000L) {
                    lastReconnectAttempt = now
                    val remembered = activeSource ?: lastSource()
                    val source = remembered?.let { saved ->
                        latestSources.firstOrNull { it.name == saved.name } ?: saved
                    }
                    if (source != null) runCatching { NdiNative.connect(source.name, source.address) }
                }
            }
            val info = runCatching { NdiNative.capture(frameBuffer, 0) }.getOrNull() ?: return@scheduleAtFixedRate
            val width = info.getOrNull(0) ?: return@scheduleAtFixedRate
            val height = info.getOrNull(1) ?: return@scheduleAtFixedRate
            val needed = info.getOrNull(2) ?: return@scheduleAtFixedRate
            val expected = width.toLong() * height.toLong() * 4L
            if (width <= 0 || height <= 0 || width > MAX_NDI_FRAME_DIMENSION || height > MAX_NDI_FRAME_DIMENSION ||
                needed <= 0 || needed > MAX_NDI_FRAME_BYTES || needed.toLong() < expected) return@scheduleAtFixedRate
            if (needed > frameBuffer.capacity()) {
                frameBuffer = ByteBuffer.allocateDirect(needed)
                val retriedInfo = runCatching { NdiNative.capture(frameBuffer, 0) }.getOrNull() ?: return@scheduleAtFixedRate
                if (retriedInfo.getOrNull(0) != width || retriedInfo.getOrNull(1) != height || retriedInfo.getOrNull(2) != needed) return@scheduleAtFixedRate
            }
            frameBuffer.position(0)
            val fourcc = info.getOrNull(3) ?: return@scheduleAtFixedRate
            val rateNumerator = info.getOrNull(4) ?: 0
            val rateDenominator = info.getOrNull(5) ?: 0
            videoView?.setFrame(frameBuffer, width, height, fourcc, rateNumerator, rateDenominator)
        }, 0, maxOf(1L, 1000L / previewFrameRate()), TimeUnit.MILLISECONDS)
    }

    private fun baseScreen(title: String, subtitle: String? = null): LinearLayout {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(52), dp(32), dp(52), dp(30))
            setBackgroundColor(BG)
        }
        val top = row()
        top.addView(label("ONEVERSE", 15, BLUE, true).apply { letterSpacing = 0.12f })
        top.addView(label("  /  ", 14, SOFT))
        top.addView(label(title.uppercase(), 15, WHITE, true).apply { letterSpacing = 0.12f })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        screen.addView(top, LinearLayout.LayoutParams(-1, dp(34)))
        screen.addView(View(this), LinearLayout.LayoutParams(1, dp(28)))
        if (subtitle != null) {
            screen.addView(label(subtitle, 28, WHITE, true))
            screen.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        }
        return screen
    }

    private fun renderHome() {
        stopNdiPlaybackIfActive()
        currentMode = Mode.HOME
        hybridRequested = false
        hybridViewport = NdiViewport()
        ndiContainer = null
        ndiSourceTitle = null
        webView = null
        videoView = null
        val screen = baseScreen("HOME", "Choose what to display")
        val content = FrameLayout(this)
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val firstRow = row()
        firstRow.addView(actionCard("WEB VIEW", "Open the configured URL", "W", BLUE) { renderWeb(configuredUrl()) }, weightedCard(150))
        firstRow.addView(View(this), LinearLayout.LayoutParams(dp(18), 1))
        firstRow.addView(actionCard("NDI SOURCE", "Find and monitor a feed", "N", GREEN) {
            hybridRequested = false
            renderSources()
        }, weightedCard(150))
        val secondRow = row()
        secondRow.addView(actionCard("HYBRID VIEW", "NDI background with web overlay", "H", BLUE) {
            renderSources(hybrid = true)
        }, weightedCard(150))
        secondRow.addView(View(this), LinearLayout.LayoutParams(dp(18), 1))
        secondRow.addView(actionCard("CONTRIBUTE", "Share this screen or camera", "+", BLUE) { renderContribute() }, weightedCard(150))
        choices.addView(firstRow, LinearLayout.LayoutParams(-1, 0, 1f))
        choices.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        choices.addView(secondRow, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(choices, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(58) })
        val settings = TextView(this).apply {
            text = "⚙"
            contentDescription = "Settings"
            textSize = 24f
            setTextColor(SOFT)
            gravity = Gravity.CENTER
            background = rounded(PANEL, dp(14))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) PANEL_FOCUS else PANEL, dp(14), if (focused) BLUE else null)
                setTextColor(if (focused) WHITE else SOFT)
            }
            setOnClickListener { renderSettings() }
        }
        content.addView(settings, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END))
        screen.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        install(screen)
        firstRow.getChildAt(0)?.requestFocus()
    }

    private fun renderContribute() {
        stopNdiPlaybackIfActive()
        currentMode = Mode.CONTRIBUTE
        videoView = null
        val preferences = getPreferences(MODE_PRIVATE)
        includeScreenAudio = preferences.getBoolean("contribute_screen_audio", false)
        includeCameraAudio = preferences.getBoolean("contribute_camera_audio", false)
        val screen = baseScreen("CONTRIBUTE", "Share this device as an NDI source")
        cameraPreviewView = null
        cameraPreviewPlaceholder = null
        cameraStartButton = null
        contributeFocusTarget = null
        main.removeCallbacks(cameraPreviewPoll)
        val cameraActive = ContributeService.isRunning && ContributeService.activeMode == ContributeService.MODE_CAMERA
        ContributeService.cameraPreviewRequested = cameraActive

        val nameRow = row()
        nameRow.addView(label("NDI SOURCE NAME", 13, SOFT, true), LinearLayout.LayoutParams(dp(170), -2))
        val sourceName = EditText(this).apply {
            setSingleLine(true)
            setText(configuredContributeSourceName())
            textSize = 16f
            isEnabled = !ContributeService.isRunning
            setTextColor(WHITE)
            setHintTextColor(SOFT)
            hint = "Oneverse Screen"
            background = rounded(PANEL, dp(12))
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        nameRow.addView(sourceName, LinearLayout.LayoutParams(0, dp(48), 1f))
        screen.addView(nameRow, LinearLayout.LayoutParams(-1, dp(48)))
        screen.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))

        val cameras = availableCameras()
        val showCameraCard = cameras.isNotEmpty() || cameraActive
        val cards = row().apply { gravity = Gravity.TOP }
        if (showCameraCard) {
            cards.addView(cameraShareCard(sourceName, cameras, cameraActive), LinearLayout.LayoutParams(0, dp(390), 1.1f))
            cards.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))
        }
        cards.addView(screenShareCard(sourceName), LinearLayout.LayoutParams(0, dp(390), 1f))
        screen.addView(cards, LinearLayout.LayoutParams(-1, dp(390)))
        screen.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        val footer = label("NDI® is a registered trademark of Vizrt NDI AB.  https://ndi.video", 12, SOFT).apply {
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            linksClickable = true
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(52), dp(32), dp(52), dp(14))
            setBackgroundColor(BG)
            addView(ScrollView(this@MainActivity).apply {
            isFillViewport = true
            addView(screen, FrameLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, dp(30)))
        }
        install(page)
        val selectedCameraId = preferences.getString("contribute_camera_id", null)
        if (cameras.any { it.id == selectedCameraId } && !cameraActive) requestCameraPreview(selectedCameraId!!)
        if (ContributeService.isRunning) main.post(cameraPreviewPoll)
        main.post { contributeFocusTarget?.requestFocus() }
    }

    private fun cameraShareCard(sourceName: EditText, cameras: List<CameraChoice>, active: Boolean): LinearLayout {
        val card = shareCard()
        val selectedId = getPreferences(MODE_PRIVATE).getString("contribute_camera_id", null)
        val previewAvailable = !active && !selectedId.isNullOrBlank() &&
            checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        card.addView(shareCardHeader("CAMERA", ContributeService.MODE_CAMERA, previewAvailable), LinearLayout.LayoutParams(-1, dp(34)))

        val previewFrame = FrameLayout(this).apply {
            setBackground(rounded(0xFF070A0D.toInt(), dp(12)))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Camera live preview"
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val placeholder = label(
            when {
                cameras.isEmpty() -> "No camera detected"
                active -> "Opening camera preview…"
                selectedId.isNullOrBlank() -> "Choose a camera to see its live view"
                else -> "Opening ${cameras.firstOrNull { it.id == selectedId }?.label ?: "camera"} preview…"
            }, 15, SOFT
        ).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        preview.visibility = View.VISIBLE
        previewFrame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        val latest = if (active) ContributeService.latestCameraPreview else null
        if (latest != null) { preview.setImageBitmap(latest); placeholder.visibility = View.GONE }
        else if (!active && !selectedId.isNullOrBlank() && checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            placeholder.text = "Opening camera preview…"
        }
        cameraPreviewView = preview
        cameraPreviewPlaceholder = placeholder
        card.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val cameraItems = listOf(CameraChoice("", if (cameras.isEmpty()) "No camera available" else "Choose a camera…")) + cameras
        val selector = Spinner(this).apply {
            background = rounded(0xFF253543.toInt(), dp(10), 0xFF3D5364.toInt())
            clipToOutline = true
            setPadding(dp(10), dp(2), dp(10), dp(2))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, cameraItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setPopupBackgroundDrawable(rounded(0xFF253543.toInt(), dp(10), 0xFF3D5364.toInt()))
            val selectedIndex = cameras.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.plus(1) ?: 0
            setSelection(selectedIndex)
            isEnabled = cameras.isNotEmpty() && !ContributeService.isRunning
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    cameraItems.getOrNull(position)?.let { choice ->
                        if (choice.id.isBlank()) {
                            getPreferences(MODE_PRIVATE).edit().remove("contribute_camera_id").apply()
                            closeCameraPreview(clearDesired = true)
                            clearLocalCameraPreview("Choose a camera to see its live view")
                            cameraPreviewPlaceholder?.text = "Choose a camera to see its live view"
                            cameraStartButton?.isEnabled = false
                            cameraStartButton?.alpha = 0.45f
                        } else {
                            getPreferences(MODE_PRIVATE).edit().putString("contribute_camera_id", choice.id).apply()
                            clearLocalCameraPreview("Opening ${choice.label} preview…")
                            cameraStartButton?.isEnabled = !ContributeService.isRunning
                            cameraStartButton?.alpha = if (ContributeService.isRunning) 0.45f else 1f
                            if (!ContributeService.isRunning || ContributeService.activeMode != ContributeService.MODE_CAMERA) {
                                requestCameraPreview(choice.id)
                            }
                        }
                    }
                }
            }
        }
        card.addView(selector, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        card.addView(audioToggle("Include audio", includeCameraAudio, ContributeService.MODE_CAMERA), LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
        card.addView(shareActionRow(
            "SHARE CAMERA", "Stop camera sharing", ContributeService.MODE_CAMERA, sourceName,
            canStart = cameras.any { it.id == selectedId }, start = { beginCameraCapture() }
        ), LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        return card
    }

    private fun requestCameraPreview(cameraId: String) {
        if (cameraId.isBlank() || ContributeService.isRunning) return
        desiredPreviewCameraId = cameraId
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (pendingPreviewCameraId == cameraId) return
            pendingPreviewCameraId = cameraId
            cameraPreviewPlaceholder?.text = "Allow camera access to preview"
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA_PREVIEW_PERMISSION)
            return
        }
        if (currentMode != Mode.CONTRIBUTE) return
        if (openedPreviewCameraId == cameraId && previewCameraSession != null) return
        if (openingPreviewCameraId == cameraId) return
        val cameraWasOpen = previewCameraDevice != null || openingPreviewCameraId != null || waitingForPreviewCameraClose
        if (cameraWasOpen) clearLocalCameraPreview("Switching camera…")
        waitingForPreviewCameraClose = cameraWasOpen
        closeCameraPreview(clearDesired = false)
        desiredPreviewCameraId = cameraId
        if (!cameraWasOpen) openCameraPreview(cameraId)
    }

    private fun clearLocalCameraPreview(message: String) {
        (cameraPreviewView?.tag as? Bitmap)?.let { bitmap ->
            cameraPreviewView?.setImageDrawable(null)
            cameraPreviewView?.tag = null
            bitmap.recycle()
        }
        cameraPreviewPlaceholder?.text = message
        cameraPreviewPlaceholder?.visibility = View.VISIBLE
    }

    private fun openCameraPreview(cameraId: String) {
        if (desiredPreviewCameraId != cameraId || currentMode != Mode.CONTRIBUTE || ContributeService.isRunning || waitingForPreviewCameraClose) return
        if (openingPreviewCameraId == cameraId || openedPreviewCameraId == cameraId) return
        openingPreviewCameraId = cameraId
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        val sizes = characteristics?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val size = sizes.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        previewImageReader = reader
        previewConversionThread = HandlerThread("OneversePreviewConvert").apply { start() }
        previewConversionHandler = Handler(previewConversionThread!!.looper)
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastLocalPreviewFrameNanos < 200_000_000L) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastLocalPreviewFrameNanos = now
            val bitmap = try { imageToPreviewBitmap(image) } finally { image.close() }
            main.post {
                if (desiredPreviewCameraId == cameraId && currentMode == Mode.CONTRIBUTE) {
                    val view = cameraPreviewView
                    if (view != null) {
                        (view.tag as? Bitmap)?.takeIf { it !== bitmap }?.recycle()
                        view.setImageBitmap(bitmap)
                        view.tag = bitmap
                        cameraPreviewPlaceholder?.visibility = View.GONE
                    } else bitmap.recycle()
                } else bitmap.recycle()
            }
        }, previewConversionHandler)
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (desiredPreviewCameraId != cameraId || currentMode != Mode.CONTRIBUTE) {
                        waitingForPreviewCameraClose = true
                        device.close()
                        return
                    }
                    if (openingPreviewCameraId == cameraId) openingPreviewCameraId = null
                    previewCameraDevice = device
                    openedPreviewCameraId = cameraId
                    runCatching {
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (previewCameraDevice !== device || desiredPreviewCameraId != cameraId) {
                                    session.close()
                                    return
                                }
                                previewCameraSession = session
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                }.build()
                                runCatching { session.setRepeatingRequest(request, null, main) }
                                    .onFailure { cameraPreviewPlaceholder?.text = "Camera preview unavailable" }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                session.close()
                                cameraPreviewPlaceholder?.text = "Camera preview unavailable"
                            }
                        }, main)
                    }.onFailure { cameraPreviewPlaceholder?.text = "Camera preview unavailable" }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (previewCameraDevice === device) {
                        closeCameraPreview(clearDesired = false)
                        clearLocalCameraPreview("Camera disconnected")
                    }
                    cameraPreviewPlaceholder?.text = "Camera disconnected"
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (openingPreviewCameraId == cameraId) openingPreviewCameraId = null
                    if (previewCameraDevice === device) {
                        closeCameraPreview(clearDesired = false)
                        clearLocalCameraPreview("Camera preview unavailable")
                    }
                    cameraPreviewPlaceholder?.text = "Camera preview unavailable"
                }
                override fun onClosed(device: CameraDevice) {
                    if (!waitingForPreviewCameraClose) return
                    waitingForPreviewCameraClose = false
                    val nextCameraId = desiredPreviewCameraId ?: return
                    if (currentMode == Mode.CONTRIBUTE && !ContributeService.isRunning) {
                        main.post { openCameraPreview(nextCameraId) }
                    }
                }
            }, main)
        } catch (_: SecurityException) {
            openingPreviewCameraId = null
            waitingForPreviewCameraClose = false
            cameraPreviewPlaceholder?.text = "Allow camera access to preview"
        } catch (_: android.hardware.camera2.CameraAccessException) {
            openingPreviewCameraId = null
            waitingForPreviewCameraClose = false
            cameraPreviewPlaceholder?.text = "Camera preview unavailable"
        }
    }

    private fun closeCameraPreview(clearDesired: Boolean) {
        if (clearDesired) desiredPreviewCameraId = null
        if (clearDesired) waitingForPreviewCameraClose = false
        runCatching { previewCameraSession?.stopRepeating() }
        runCatching { previewCameraSession?.close() }
        previewCameraSession = null
        runCatching { previewCameraDevice?.close() }
        previewCameraDevice = null
        openedPreviewCameraId = null
        openingPreviewCameraId = null
        previewImageReader?.setOnImageAvailableListener(null, null)
        runCatching { previewImageReader?.close() }
        previewImageReader = null
        previewConversionThread?.quitSafely()
        previewConversionThread = null
        previewConversionHandler = null
    }

    private fun imageToPreviewBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val targetWidth = minOf(width, 640)
        val targetHeight = (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1)
        val pixels = IntArray(targetWidth * targetHeight)
        val (yPlane, uPlane, vPlane) = image.planes
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        for (py in 0 until targetHeight) {
            val sy = py * height / targetHeight
            for (px in 0 until targetWidth) {
                val sx = px * width / targetWidth
                val y = (yBuffer.get(sy * yPlane.rowStride + sx * yPlane.pixelStride).toInt() and 0xFF) - 16
                val uvx = sx / 2
                val uvy = sy / 2
                val u = (uBuffer.get(uvy * uPlane.rowStride + uvx * uPlane.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvy * vPlane.rowStride + uvx * vPlane.pixelStride).toInt() and 0xFF) - 128
                val luminance = maxOf(0, y) * 1192
                val red = (luminance + 1634 * v).coerceIn(0, 262143) shr 10
                val green = (luminance - 833 * v - 400 * u).coerceIn(0, 262143) shr 10
                val blue = (luminance + 2066 * u).coerceIn(0, 262143) shr 10
                pixels[py * targetWidth + px] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }

    private fun screenShareCard(sourceName: EditText): LinearLayout {
        val card = shareCard()
        card.addView(shareCardHeader("SCREEN", ContributeService.MODE_SCREEN), LinearLayout.LayoutParams(-1, dp(34)))
        val screenPreview = FrameLayout(this).apply {
            setBackgroundColor(0xFF111C23.toInt())
            background = rounded(0xFF111C23.toInt(), dp(12))
        }
        val displaySymbol = label("▣", 46, 0xFF78909E.toInt(), true).apply { gravity = Gravity.CENTER }
        screenPreview.addView(displaySymbol, FrameLayout.LayoutParams(-1, -1))
        card.addView(screenPreview, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        val screenInfo = row().apply {
            addView(label("Entire device screen", 14, WHITE, true))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(label("Android capture", 12, SOFT))
        }
        card.addView(screenInfo, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        card.addView(audioToggle("Include audio", includeScreenAudio, ContributeService.MODE_SCREEN), LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
        card.addView(shareActionRow(
            "SHARE SCREEN", "Stop screen sharing", ContributeService.MODE_SCREEN, sourceName,
            start = { beginScreenCapture() }
        ), LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        return card
    }

    private fun shareCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(PANEL, dp(15))
    }

    private fun shareCardHeader(title: String, mode: String, previewAvailable: Boolean = false): LinearLayout {
        val active = ContributeService.isRunning && ContributeService.activeMode == mode
        return row().apply {
            addView(label(title, 16, WHITE, true))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            if (active || previewAvailable) {
                val dot = TextView(this@MainActivity).apply {
                    text = "●"
                    textSize = 14f
                    setTextColor(if (active) 0xFFFF4B4B.toInt() else BLUE)
                    if (active) startAnimation(AlphaAnimation(1f, 0.2f).apply {
                        duration = 650
                        repeatMode = AlphaAnimation.REVERSE
                        repeatCount = AlphaAnimation.INFINITE
                    })
                }
                addView(dot)
            }
            val status = label(
                when { active -> "SHARING"; previewAvailable -> "PREVIEW"; else -> "OFF" },
                12, if (active) 0xFFFF777D.toInt() else if (previewAvailable) BLUE else SOFT, true
            )
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        }
    }

    private fun audioToggle(title: String, checked: Boolean, mode: String): CheckBox = CheckBox(this).apply {
        background = rounded(0xFF253543.toInt(), dp(10), 0xFF3D5364.toInt())
        setPadding(dp(10), 0, dp(10), 0)
        text = title
        textSize = 14f
        setTextColor(WHITE)
        buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
        isChecked = checked
        isEnabled = !ContributeService.isRunning &&
            (mode != ContributeService.MODE_SCREEN || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        setOnCheckedChangeListener { _, enabled ->
            if (mode == ContributeService.MODE_CAMERA) {
                includeCameraAudio = enabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("contribute_camera_audio", enabled).apply()
            } else {
                includeScreenAudio = enabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("contribute_screen_audio", enabled).apply()
            }
        }
    }

    private fun shareActionRow(
        title: String,
        stopDescription: String,
        mode: String,
        sourceName: EditText,
        canStart: Boolean = true,
        start: () -> Unit
    ): LinearLayout {
        val active = ContributeService.isRunning && ContributeService.activeMode == mode
        val row = row()
        val share = actionButton(if (active) "SHARING" else title) {
            if (!ContributeService.isRunning) {
                saveContributionName(sourceName.text.toString())
                start()
            }
        }.apply {
            isEnabled = !ContributeService.isRunning && canStart
            alpha = if (!canStart || (ContributeService.isRunning && !active)) 0.45f else 1f
            background = rounded(if (active) PANEL_FOCUS else PANEL, dp(10), if (active) BLUE else null)
        }
        if (mode == ContributeService.MODE_CAMERA) cameraStartButton = share
        row.addView(share, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        val stop = actionButton("■") {
            if (active) {
                stopContributeService()
                main.postDelayed({ if (currentMode == Mode.CONTRIBUTE) renderContribute() }, 400)
            }
        }.apply {
            isEnabled = active
            contentDescription = stopDescription
            textSize = 19f
            alpha = if (active) 1f else 0.3f
            background = rounded(if (active) 0xFF382328.toInt() else PANEL, dp(10), if (active) 0xFF704047.toInt() else null)
        }
        if (active) contributeFocusTarget = stop
        else if (!ContributeService.isRunning && contributeFocusTarget == null && share.isEnabled) contributeFocusTarget = share
        row.addView(stop, LinearLayout.LayoutParams(dp(48), -1))
        return row
    }

    private fun saveContributionName(raw: String): String {
        val name = raw.trim().ifBlank { "Oneverse Screen" }.take(100)
        getPreferences(MODE_PRIVATE).edit().putString("contribute_source_name", name).apply()
        return name
    }

    private fun availableCameras(): List<CameraChoice> = runCatching {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.map { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            val position = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> ""
            }
            CameraChoice(id, listOf(position, "Camera $id").filter { it.isNotBlank() }.joinToString(" · "), facing)
        }.sortedByDescending { it.facing == CameraCharacteristics.LENS_FACING_BACK }
    }.getOrDefault(emptyList())

    private fun beginScreenCapture() {
        if (includeScreenAudio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingCaptureMode = ContributeService.MODE_SCREEN
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CAPTURE_PERMISSIONS)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    private fun beginCameraCapture() {
        val selectedCameraId = getPreferences(MODE_PRIVATE).getString("contribute_camera_id", null)
        if (selectedCameraId.isNullOrBlank() || availableCameras().none { it.id == selectedCameraId }) {
            showToastMessage(if (availableCameras().isEmpty()) "No camera is available on this device" else "Choose a camera first")
            return
        }
        val needed = mutableListOf(android.Manifest.permission.CAMERA)
        if (includeCameraAudio) needed.add(android.Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingCaptureMode = ContributeService.MODE_CAMERA
            requestPermissions(missing.toTypedArray(), REQUEST_CAPTURE_PERMISSIONS)
            return
        }
        startContributeService(ContributeService.MODE_CAMERA)
    }

    private fun startContributeService(mode: String, projectionResultCode: Int? = null, projectionData: Intent? = null) {
        if (mode == ContributeService.MODE_CAMERA) {
            closeCameraPreview(clearDesired = true)
            ContributeService.cameraPreviewRequested = true
            cameraPreviewView?.visibility = View.VISIBLE
            cameraPreviewPlaceholder?.text = "Starting camera share…"
            cameraPreviewPlaceholder?.visibility = View.VISIBLE
        }
        val intent = Intent(this, ContributeService::class.java)
            .setAction(ContributeService.ACTION_START)
            .putExtra(ContributeService.EXTRA_MODE, mode)
            .putExtra(ContributeService.EXTRA_NAME, configuredContributeSourceName())
            .putExtra(ContributeService.EXTRA_AUDIO, if (mode == ContributeService.MODE_CAMERA) includeCameraAudio else includeScreenAudio)
            .putExtra(ContributeService.EXTRA_CAMERA_ID, getPreferences(MODE_PRIVATE).getString("contribute_camera_id", null))
        if (projectionResultCode != null && projectionData != null) {
            intent.putExtra(ContributeService.EXTRA_RESULT_CODE, projectionResultCode)
            intent.putExtra(ContributeService.EXTRA_PROJECTION_DATA, projectionData)
        }
        val launchService = Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            main.postDelayed({ if (currentMode == Mode.CONTRIBUTE) renderContribute() }, 700)
        }
        if (mode == ContributeService.MODE_CAMERA) main.postDelayed(launchService, 250) else launchService.run()
    }

    private fun stopContributeService() {
        stopService(Intent(this, ContributeService::class.java))
    }

    @Deprecated("Deprecated by Android in favor of Activity Result APIs; kept for API 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) startContributeService(ContributeService.MODE_SCREEN, resultCode, data)
            else showToastMessage("Screen sharing was not approved")
        }
    }

    @Deprecated("Deprecated by Android in favor of Activity Result APIs; kept for API 23 compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PREVIEW_PERMISSION) {
            val cameraId = pendingPreviewCameraId.also { pendingPreviewCameraId = null } ?: return
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestCameraPreview(cameraId)
            } else {
                cameraPreviewPlaceholder?.text = "Camera permission is needed for preview"
            }
            return
        }
        if (requestCode != REQUEST_CAPTURE_PERMISSIONS) return
        val mode = pendingCaptureMode.also { pendingCaptureMode = null } ?: return
        if (grantResults.isEmpty() || grantResults.any { it != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            showToastMessage("Permission is required for this capture option")
            return
        }
        if (mode == ContributeService.MODE_CAMERA) beginCameraCapture() else beginScreenCapture()
    }

    private fun configuredUrl(): String {
        val prefs = getPreferences(MODE_PRIVATE)
        val custom = prefs.getString("custom_url", null)
            ?: prefs.getString("startup_url", null)?.takeUnless { it == DEFAULT_WEB_URL || it == LEGACY_DEFAULT_WEB_URL }
        return custom ?: DEFAULT_WEB_URL
    }

    private fun configuredContributeSourceName(): String = getPreferences(MODE_PRIVATE)
        .getString("contribute_source_name", null)
        ?.takeUnless { it.isBlank() || it == "Songverse Screen" }
        ?: "Oneverse Screen"

    private fun customUrlForEditing(): String {
        val prefs = getPreferences(MODE_PRIVATE)
        return prefs.getString("custom_url", null)
            ?: prefs.getString("startup_url", null)?.takeUnless { it == DEFAULT_WEB_URL || it == LEGACY_DEFAULT_WEB_URL }
            ?: ""
    }

    private fun startupMode(): String {
        val prefs = getPreferences(MODE_PRIVATE)
        return prefs.getString("startup_mode", null)
            ?: if (prefs.getBoolean("open_url_on_start", true)) "web" else "dashboard"
    }

    private fun showSourceName() = getPreferences(MODE_PRIVATE).getBoolean("show_source_name", false)
    private fun showSourceDetails() = getPreferences(MODE_PRIVATE).getBoolean("show_source_details", false)
    private fun autoReconnectLastSource() = getPreferences(MODE_PRIVATE).getBoolean("auto_reconnect_ndi", true)
    private fun lastSource(): NdiSource? {
        val prefs = getPreferences(MODE_PRIVATE)
        val name = prefs.getString("last_ndi_source_name", null)?.takeIf { it.isNotBlank() } ?: return null
        val address = prefs.getString("last_ndi_source_address", null)?.takeIf { it.isNotBlank() } ?: return null
        return NdiSource(name, address)
    }

    private fun sourceAliasKey(source: NdiSource): String = "ndi_alias_${source.name.length}:${source.name}${source.address}"

    private fun sourceAlias(source: NdiSource): String = getPreferences(MODE_PRIVATE)
        .getString(sourceAliasKey(source), "")
        .orEmpty()
        .trim()

    private fun setSourceAlias(source: NdiSource, alias: String) {
        val editor = getPreferences(MODE_PRIVATE).edit()
        if (alias.isBlank()) editor.remove(sourceAliasKey(source))
        else editor.putString(sourceAliasKey(source), alias.trim())
        editor.apply()
    }
    private fun configuredPreviewFrameRate(): Int {
        val prefs = getPreferences(MODE_PRIVATE)
        val savedRate = prefs.getInt("ndi_preview_fps", 0)
        val customized = prefs.getBoolean("ndi_preview_rate_customized", false)
        return if (customized || savedRate != 30) savedRate else 0
    }
    private fun previewFrameRate() = configuredPreviewFrameRate().takeIf { it > 0 }?.coerceIn(15, 120) ?: deviceRefreshRate()

    @Suppress("DEPRECATION")
    private fun deviceRefreshRate(): Int {
        val reported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.refreshRate
        } else {
            windowManager.defaultDisplay.refreshRate
        }
        return reported?.takeIf { it.isFinite() && it > 0f }?.roundToInt()?.coerceIn(15, 120) ?: 60
    }

    private fun normalizeUrl(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val url = if (raw.contains("://")) raw else "https://$raw"
        val separator = url.indexOf("://")
        if (separator <= 0) return null
        val normalized = url.substring(0, separator).lowercase() + url.substring(separator)
        val parsed = runCatching { android.net.Uri.parse(normalized) }.getOrNull() ?: return null
        return normalized.takeIf { parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank() }
    }

    private fun webOrigin(input: String): String? {
        val uri = runCatching { android.net.Uri.parse(input) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null
        val port = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
        return "$scheme://$host:$port"
    }

    private fun renderSettings() {
        stopNdiPlaybackIfActive()
        currentMode = Mode.SETTINGS
        val screen = baseScreen("SETTINGS", "Startup destination")
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(options)
        }
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val destinations = listOf("Dashboard", "Web", "NDI", "Hybrid (NDI + Web)")
        val startupMode = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                destinations
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(when (this@MainActivity.startupMode()) { "web" -> 1; "ndi" -> 2; "hybrid" -> 3; else -> 0 })
        }
        options.addView(label("Open by default", 16, SOFT))
        options.addView(startupMode, LinearLayout.LayoutParams(-1, dp(56)))
        options.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        options.addView(label("Custom web URL (optional)", 16, SOFT))
        val field = EditText(this).apply {
            setSingleLine(true)
            textSize = 21f
            setTextColor(WHITE)
            setHintTextColor(SOFT)
            hint = "https://example.com"
            setText(customUrlForEditing())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(PANEL, dp(12))
        }
        urlEntry = field
        val urlStatus = label("Leave blank to use the default Oneverse page", 13, SOFT)
        var recentUrls = recentWebUrls()
        var recentPicker: Spinner? = null
        var selectedRecentUrl: String? = null
        var removeRecentButton: Button? = null
        fun refreshRecentUrlPicker() {
            recentUrls = recentWebUrls()
            selectedRecentUrl = null
            removeRecentButton?.apply { isEnabled = false; alpha = 0.45f }
            recentPicker?.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("Recent URLs…") + recentUrls
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val saved = saveCustomUrl(field.text.toString(), urlStatus)
                if (saved) refreshRecentUrlPicker()
                saved
            } else false
        }
        val clearUrl = TextView(this).apply {
            text = "×"
            contentDescription = "Clear custom URL"
            textSize = 26f
            setTextColor(SOFT)
            gravity = Gravity.CENTER
            background = rounded(PANEL, dp(12))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) PANEL_FOCUS else PANEL, dp(12), if (focused) BLUE else null)
                setTextColor(if (focused) WHITE else SOFT)
            }
            setOnClickListener {
                field.setText("")
                getPreferences(MODE_PRIVATE).edit().remove("custom_url").apply()
                field.error = null
                urlStatus.text = "Custom URL cleared. The default Oneverse page will be used."
                urlStatus.setTextColor(GREEN)
                field.requestFocus()
            }
        }
        val saveUrl = TextView(this).apply {
            text = "↓"
            contentDescription = "Save custom URL"
            textSize = 23f
            setTextColor(SOFT)
            gravity = Gravity.CENTER
            background = rounded(PANEL, dp(12))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) PANEL_FOCUS else PANEL, dp(12), if (focused) BLUE else null)
                setTextColor(if (focused) WHITE else SOFT)
            }
            setOnClickListener {
                if (saveCustomUrl(field.text.toString(), urlStatus)) refreshRecentUrlPicker()
            }
        }
        val urlRow = row()
        urlRow.addView(field, LinearLayout.LayoutParams(0, dp(58), 1f))
        urlRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        urlRow.addView(clearUrl, LinearLayout.LayoutParams(dp(52), dp(56)))
        urlRow.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        urlRow.addView(saveUrl, LinearLayout.LayoutParams(dp(52), dp(56)))
        options.addView(urlRow, LinearLayout.LayoutParams(-1, dp(58)))
        options.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        options.addView(urlStatus)
        val recentMenu = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("Recent URLs…") + recentUrls
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) return
                    val selectedUrl = recentUrls.getOrNull(position - 1) ?: return
                    selectedRecentUrl = selectedUrl
                    removeRecentButton?.apply { isEnabled = true; alpha = 1f }
                    field.setText(selectedUrl)
                    field.setSelection(field.text.length)
                    urlStatus.text = "Selected a recent URL. Save it or open the page to use it."
                    urlStatus.setTextColor(SOFT)
                }
            }
        }
        recentPicker = recentMenu
        val recentRow = row()
        recentRow.addView(recentMenu, LinearLayout.LayoutParams(0, dp(48), 1f))
        recentRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        val removeRecent = actionButton("REMOVE") {
            val urlToRemove = selectedRecentUrl ?: return@actionButton
            forgetRecentUrl(urlToRemove)
            if (field.text.toString() == urlToRemove) {
                field.setText("")
                field.error = null
            }
            refreshRecentUrlPicker()
            urlStatus.text = "Removed from recent URLs."
            urlStatus.setTextColor(GREEN)
        }.apply { isEnabled = false; alpha = 0.45f }
        removeRecentButton = removeRecent
        recentRow.addView(removeRecent, LinearLayout.LayoutParams(dp(112), dp(48)))
        options.addView(recentRow, LinearLayout.LayoutParams(-1, dp(48)))
        options.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        options.addView(label("Web page zoom", 16, SOFT))
        val webZoomValues = listOf(50, 67, 75, 90, 100, 110, 125, 150, 175, 200)
        val webZoom = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, webZoomValues.map { "$it%" }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(webZoomValues.indexOf(configuredWebZoom()).coerceAtLeast(0))
        }
        options.addView(webZoom, LinearLayout.LayoutParams(-1, dp(48)))
        options.addView(label("Adjusts the page scale on the TV. You can change it here at any time.", 12, SOFT))
        val hybridOpacityValues = listOf(35, 50, 65, 80, 100)
        val hybridOpacity = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                hybridOpacityValues.map { if (it == 100) "100% (opaque)" else "$it%" }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(hybridOpacityValues.indexOf(configuredHybridOpacity()).coerceAtLeast(0))
        }
        options.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        options.addView(label("Hybrid web overlay opacity", 16, SOFT))
        options.addView(hybridOpacity, LinearLayout.LayoutParams(-1, dp(48)))
        options.addView(label("Lower opacity lets more of the NDI background show through.", 12, SOFT))
        options.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        options.addView(label("Advanced debug options", 18, WHITE, true))
        val showSourceName = android.widget.CheckBox(this).apply {
            text = "Show source name / web address banner"
            textSize = 16f
            setTextColor(WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            isChecked = this@MainActivity.showSourceName()
        }
        options.addView(showSourceName, LinearLayout.LayoutParams(-1, dp(50)))
        val showSourceDetails = android.widget.CheckBox(this).apply {
            text = "Show source details"
            textSize = 16f
            setTextColor(WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            isChecked = this@MainActivity.showSourceDetails()
        }
        options.addView(showSourceDetails, LinearLayout.LayoutParams(-1, dp(50)))
        options.addView(label("Details include frame size, decoded pixel format and source/display frame rate. The source codec is not exposed by this receiver API.", 12, SOFT))
        val autoReconnect = android.widget.CheckBox(this).apply {
            text = "Reconnect to the last NDI source at startup"
            textSize = 16f
            setTextColor(WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            isChecked = autoReconnectLastSource()
        }
        options.addView(autoReconnect, LinearLayout.LayoutParams(-1, dp(50)))
        options.addView(label("Used when NDI is selected as the startup destination.", 12, SOFT))
        options.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        options.addView(label("NDI source labels", 16, SOFT))
        options.addView(label("Assign labels such as Main camera, Secondary, Computer, Presentation, or Stage. Choose any names that fit your setup; Hybrid View pages can select a source by its label.", 12, SOFT))
        val aliasList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshAliasList() {
            aliasList.removeAllViews()
            if (latestSources.isEmpty()) {
                aliasList.addView(label("No NDI sources discovered yet. Open source selection and rescan first.", 13, SOFT))
            } else {
                latestSources.forEach { source ->
                    val aliasField = EditText(this@MainActivity).apply {
                        setSingleLine(true)
                        textSize = 16f
                        setTextColor(WHITE)
                        setHintTextColor(SOFT)
                        hint = "Label (e.g. Main camera)"
                        setText(sourceAlias(source))
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        background = rounded(PANEL, dp(10))
                    }
                    val sourceRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = rounded(PANEL, dp(12))
                    }
                    sourceRow.addView(label(source.name, 14, WHITE, true))
                    if (source.address.isNotBlank()) sourceRow.addView(label(source.address, 11, SOFT))
                    val aliasRow = row()
                    aliasRow.addView(aliasField, LinearLayout.LayoutParams(0, dp(48), 1f))
                    aliasRow.addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(8), 1))
                    aliasRow.addView(actionButton("SAVE") {
                        val alias = aliasField.text.toString().trim()
                        val duplicate = alias.isNotEmpty() && latestSources.any { other ->
                            other != source && sourceAlias(other).equals(alias, ignoreCase = true)
                        }
                        if (duplicate) {
                            aliasField.error = "Each source label must be unique"
                        } else {
                            setSourceAlias(source, alias)
                            aliasField.error = null
                            showToastMessage(if (alias.isEmpty()) "Source label cleared" else "Source label saved")
                        }
                    }, LinearLayout.LayoutParams(dp(84), dp(48)))
                    sourceRow.addView(aliasRow)
                    aliasList.addView(sourceRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }
        }
        refreshAliasList()
        options.addView(aliasList, LinearLayout.LayoutParams(-1, -2))
        val refreshRateValues = listOf(0, 15, 24, 30, 60, 90, 120)
        val refreshRates = refreshRateValues.map { if (it == 0) "Auto (device refresh)" else "$it fps" }
        val refreshRate = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                refreshRates
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(refreshRateValues.indexOf(configuredPreviewFrameRate()).coerceAtLeast(0))
        }
        options.addView(label("NDI preview refresh limit", 15, SOFT))
        options.addView(refreshRate, LinearLayout.LayoutParams(-1, dp(48)))
        options.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        val actions = row()
        actions.addView(actionButton("SAVE SETTINGS") {
            val rawUrl = field.text.toString().trim()
            val normalized = if (rawUrl.isEmpty()) null else normalizeUrl(rawUrl)
            if (rawUrl.isNotEmpty() && normalized == null) {
                field.error = "Enter a valid HTTP or HTTPS address"
            } else {
                val editor = getPreferences(MODE_PRIVATE).edit()
                    .putString("startup_mode", when (startupMode.selectedItemPosition) { 1 -> "web"; 2 -> "ndi"; 3 -> "hybrid"; else -> "dashboard" })
                    .putBoolean("show_source_name", showSourceName.isChecked)
                    .putBoolean("show_source_details", showSourceDetails.isChecked)
                    .putBoolean("auto_reconnect_ndi", autoReconnect.isChecked)
                    .putInt("ndi_preview_fps", refreshRateValues.getOrElse(refreshRate.selectedItemPosition) { 0 })
                    .putBoolean("ndi_preview_rate_customized", true)
                    .putInt("web_zoom", webZoomValues.getOrElse(webZoom.selectedItemPosition) { 100 })
                    .putInt("hybrid_overlay_opacity", hybridOpacityValues.getOrElse(hybridOpacity.selectedItemPosition) { 80 })
                    .remove("startup_url")
                    .remove("open_url_on_start")
                    .remove("show_debug_overlays")
                if (normalized == null) editor.remove("custom_url") else editor.putString("custom_url", normalized)
                editor.apply()
                normalized?.let(::rememberWebUrl)
                showToastMessage("Settings saved")
                renderHome()
            }
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(actionButton("OPEN PAGE NOW") {
            val rawUrl = field.text.toString().trim()
            val url = if (rawUrl.isEmpty()) DEFAULT_WEB_URL else normalizeUrl(rawUrl)
            if (url == null) {
                field.error = "Enter a valid HTTP or HTTPS address"
                urlStatus.text = "Enter a valid HTTP or HTTPS address before opening the page."
                urlStatus.setTextColor(DISCONNECTED_RED)
            } else {
                if (rawUrl.isEmpty()) getPreferences(MODE_PRIVATE).edit().remove("custom_url").apply()
                else saveCustomUrl(rawUrl, urlStatus)
                getPreferences(MODE_PRIVATE).edit().putInt("web_zoom", webZoomValues.getOrElse(webZoom.selectedItemPosition) { 100 }).apply()
                renderWeb(url)
            }
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        screen.addView(actions, LinearLayout.LayoutParams(-1, dp(58)))
        screen.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        install(screen)
        startupMode.requestFocus()
    }

    private fun configuredWebZoom(): Int = getPreferences(MODE_PRIVATE).getInt("web_zoom", 100).coerceIn(50, 200)
    private fun configuredHybridOpacity(): Int = getPreferences(MODE_PRIVATE).getInt("hybrid_overlay_opacity", 80).coerceIn(35, 100)

    private fun recentWebUrls(): List<String> = getPreferences(MODE_PRIVATE)
        .getString("custom_url_history", "")
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(8)
        .toList()

    private fun rememberWebUrl(url: String) {
        val history = (listOf(url) + recentWebUrls()).distinct().take(8).joinToString("\n")
        getPreferences(MODE_PRIVATE).edit().putString("custom_url_history", history).apply()
    }

    private fun forgetRecentUrl(url: String) {
        val history = recentWebUrls().filterNot { it == url }.joinToString("\n")
        val preferences = getPreferences(MODE_PRIVATE)
        val editor = preferences.edit().putString("custom_url_history", history)
        if (preferences.getString("custom_url", null) == url) editor.remove("custom_url")
        editor.apply()
    }

    private fun saveCustomUrl(input: String, status: TextView): Boolean {
        val raw = input.trim()
        if (raw.isEmpty()) {
            getPreferences(MODE_PRIVATE).edit().remove("custom_url").apply()
            urlEntry?.error = null
            status.text = "Custom URL cleared. The default Oneverse page will be used."
            status.setTextColor(GREEN)
            return true
        }
        val normalized = normalizeUrl(raw)
        if (normalized == null) {
            urlEntry?.error = "Enter a valid HTTP or HTTPS address"
            status.text = "Enter a valid address, such as https://example.com or http://example.com"
            status.setTextColor(DISCONNECTED_RED)
            return false
        }
        getPreferences(MODE_PRIVATE).edit().putString("custom_url", normalized).apply()
        rememberWebUrl(normalized)
        urlEntry?.error = null
        status.text = "URL saved. Press SAVE SETTINGS to apply the startup destination."
        status.setTextColor(GREEN)
        return true
    }

    private fun renderWeb(url: String) {
        stopNdiPlaybackIfActive()
        currentMode = Mode.WEB
        videoView = null
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val browser = WebView(this).apply {
            keepScreenOn = true
            setInitialScale(configuredWebZoom())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            loadUrl(url)
        }
        webView = browser
        container.addView(browser, FrameLayout.LayoutParams(-1, -1))
        val osd = TextView(this).apply {
            text = "BACK  •  $url"
            textSize = 13f
            setTextColor(WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(0xC9101820.toInt(), dp(18))
            isFocusable = true
            setOnClickListener { renderHome() }
        }
        if (showSourceName()) {
            container.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(28), dp(24), dp(28), dp(24))
            })
        }
        install(container)
        if (showSourceName()) osd.requestFocus() else browser.requestFocus()
    }

    private fun renderSources(hybrid: Boolean? = null) {
        hybrid?.let { hybridRequested = it }
        val keepReceiver = activeSource != null && currentMode in setOf(Mode.NDI, Mode.HYBRID, Mode.SOURCES)
        if (keepReceiver) videoView = null else stopNdiPlaybackIfActive()
        runCatching { webView?.stopLoading() }
        webView = null
        ndiContainer = null
        ndiSourceTitle = null
        currentMode = Mode.SOURCES
        val screen = baseScreen(
            if (hybridRequested) "HYBRID VIEW" else "NDI SOURCES",
            if (hybridRequested) "Choose the NDI background for the web overlay" else "Select a source to display"
        )
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(list) }
        val actions = row()
        actions.addView(actionButton("RESCAN") { lastSourceSignature = ""; renderSources() })
        actions.addView(View(this), LinearLayout.LayoutParams(dp(16), 1))
        actions.addView(actionButton("SETTINGS") { renderSettings() })
        actions.addView(View(this), LinearLayout.LayoutParams(dp(16), 1))
        actions.addView(actionButton("HOME") { renderHome() })
        screen.addView(actions, LinearLayout.LayoutParams(-1, dp(54)))
        screen.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val attribution = label("NDI® is a registered trademark of Vizrt NDI AB.  https://ndi.video", 12, SOFT).apply {
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            linksClickable = true
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        screen.addView(attribution)
        renderSourceItems(list)
        install(screen)
        list.getChildAt(0)?.requestFocus()
        if (keepReceiver) startFramePolling()
    }

    private fun renderSourceItems(list: LinearLayout) {
        list.removeAllViews()
        if (!ndiReady) {
            list.addView(label("NDI runtime could not start. Check the NDI SDK path and device architecture.", 18, SOFT))
            return
        }
        if (latestSources.isEmpty()) {
            list.addView(label("Searching the local network…", 20, SOFT))
            return
        }
        val rememberedSource = lastSource()
        latestSources.forEach { source ->
            val wasLastActive = rememberedSource?.name == source.name
            val isCurrentlyActive = sourceConnected && activeSource?.name == source.name
            val state = when {
                isCurrentlyActive -> "ACTIVE"
                wasLastActive -> "PREVIOUS"
                else -> ""
            }
            val accent = if (isCurrentlyActive) GREEN else if (wasLastActive) BLUE else GREEN
            val item = row().apply {
                setPadding(dp(14), 0, dp(8), 0)
                background = rounded(PANEL, dp(14))
                isFocusable = true
                isClickable = true
                contentDescription = "${source.name}${if (state.isNotEmpty()) ", $state" else ""}"
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(if (focused) PANEL_FOCUS else PANEL, dp(14), if (focused) accent else null)
                }
                setOnClickListener { connect(source, hybrid = hybridRequested) }
            }
            item.addView(label(state, 12, accent, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                setSingleLine(true)
            }, LinearLayout.LayoutParams(dp(104), -1))
            item.addView(label(source.name, 18, WHITE, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                setSingleLine(true)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            val disconnect = TextView(this).apply {
                text = "×"
                contentDescription = "Disconnect ${source.name}"
                textSize = 28f
                setTextColor(SOFT)
                gravity = Gravity.CENTER
                background = rounded(PANEL, dp(12))
                isFocusable = isCurrentlyActive
                isClickable = isCurrentlyActive
                visibility = if (isCurrentlyActive) View.VISIBLE else View.INVISIBLE
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(if (focused) PANEL_FOCUS else PANEL, dp(12), if (focused) DISCONNECTED_RED else null)
                    setTextColor(if (focused) WHITE else SOFT)
                }
                setOnClickListener { disconnectActiveSource() }
            }
            item.addView(disconnect, LinearLayout.LayoutParams(dp(48), dp(48)))
            list.addView(item, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(8) })
        }
    }

    private fun disconnectActiveSource() {
        connectionGeneration++
        frameTask?.cancel(false)
        frameTask = null
        activeSource = null
        sourceConnected = false
        videoView = null
        worker.execute { if (ndiReady) runCatching { NdiNative.disconnect() } }
        renderSources()
    }

    private fun connect(source: NdiSource, openPreview: Boolean = true, hybrid: Boolean = hybridRequested) {
        val requestGeneration = ++connectionGeneration
        getPreferences(MODE_PRIVATE).edit()
            .putString("last_ndi_source_name", source.name)
            .putString("last_ndi_source_address", source.address)
            .apply()
        worker.execute {
            val connected = runCatching { NdiNative.connect(source.name, source.address) }.getOrDefault(false)
            main.post {
                if (requestGeneration != connectionGeneration || currentMode !in setOf(Mode.SOURCES, Mode.NDI, Mode.HYBRID)) return@post
                if (connected) {
                    activeSource = source
                    sourceConnected = false
                    if (openPreview && hybrid && currentMode == Mode.HYBRID && videoView != null) {
                        ndiSourceTitle?.text = "NDI  •  ${source.name}"
                    } else if (openPreview) renderNdi(source, hybrid)
                    else {
                        if (currentMode == Mode.SOURCES) renderSources()
                        startFramePolling()
                    }
                } else {
                    activeSource = null
                    sourceConnected = false
                    renderSources()
                    showToastMessage("Could not connect to ${source.name}")
                }
            }
        }
    }

    private fun renderNdi(source: NdiSource, hybrid: Boolean = false) {
        currentMode = if (hybrid) Mode.HYBRID else Mode.NDI
        webView = null
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val showName = showSourceName()
        val display = NdiVideoView(this, showSourceDetails())
        videoView = display
        container.addView(display, FrameLayout.LayoutParams(-1, -1))
        if (hybrid) {
            val hybridUrl = configuredUrl()
            val trustedOrigin = webOrigin(hybridUrl)
            val browser = WebView(this).apply {
                keepScreenOn = true
                setInitialScale(configuredWebZoom())
                setBackgroundColor(Color.TRANSPARENT)
                alpha = configuredHybridOpacity() / 100f
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (request == null || !request.isForMainFrame) return false
                        return trustedOrigin == null || webOrigin(request.url.toString()) != trustedOrigin
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (view == null || url == null) return
                        if (webOrigin(url) == trustedOrigin) {
                            val bridge = HybridPageBridge()
                            view.addJavascriptInterface(bridge, "OneverseHybrid")
                            view.addJavascriptInterface(bridge, "SongverseHybrid") // Backward compatibility for existing pages.
                        } else {
                            view.removeJavascriptInterface("OneverseHybrid")
                            view.removeJavascriptInterface("SongverseHybrid")
                        }
                    }
                }
                if (trustedOrigin != null) {
                    val bridge = HybridPageBridge()
                    addJavascriptInterface(bridge, "OneverseHybrid")
                    addJavascriptInterface(bridge, "SongverseHybrid") // Backward compatibility for existing pages.
                }
                loadUrl(hybridUrl)
            }
            webView = browser
            container.addView(browser, FrameLayout.LayoutParams(-1, -1))
            ndiContainer = container
        }
        val title = TextView(this).apply {
            text = "NDI  •  ${source.name}"
            textSize = 15f
            setTextColor(WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(0xC9101820.toInt(), dp(18))
            isFocusable = true
            setOnClickListener { renderSources() }
        }
        ndiSourceTitle = title
        if (showName) {
            container.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(28), dp(24), dp(28), dp(24))
            })
        }
        install(container)
        if (hybrid) applyNdiViewport() else {
            ndiContainer = null
            hybridViewport = NdiViewport()
        }
        if (hybrid) webView?.requestFocus() else if (showName) title.requestFocus() else display.requestFocus()
        startFramePolling()
    }

    private fun applyNdiViewport() {
        val parent = ndiContainer ?: return
        val display = videoView ?: return
        val viewport = hybridViewport
        parent.post {
            if (currentMode != Mode.HYBRID || parent.width <= 0 || parent.height <= 0) return@post
            val left = (parent.width * viewport.x / 100.0).roundToInt().coerceIn(0, parent.width - 1)
            val top = (parent.height * viewport.y / 100.0).roundToInt().coerceIn(0, parent.height - 1)
            val width = (parent.width * viewport.width / 100.0).roundToInt().coerceAtLeast(1).coerceAtMost(parent.width - left)
            val height = (parent.height * viewport.height / 100.0).roundToInt().coerceAtLeast(1).coerceAtMost(parent.height - top)
            display.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = left
                topMargin = top
            }
        }
    }

    private inner class HybridPageBridge {
        @JavascriptInterface
        fun getAvailableNdiSources(): String {
            val result = org.json.JSONArray()
            latestSources.forEach { source ->
                result.put(org.json.JSONObject()
                    .put("name", source.name)
                    .put("address", source.address)
                    .put("label", sourceAlias(source)))
            }
            return result.toString()
        }

        @JavascriptInterface
        fun setNdiViewport(x: Double, y: Double, width: Double, height: Double): Boolean {
            if (currentMode != Mode.HYBRID || !x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() ||
                x < 0.0 || y < 0.0 || width <= 0.0 || height <= 0.0 || x + width > 100.0 || y + height > 100.0) return false
            val requested = NdiViewport(x, y, width, height)
            main.post {
                if (currentMode == Mode.HYBRID) {
                    hybridViewport = requested
                    applyNdiViewport()
                }
            }
            return true
        }

        @JavascriptInterface
        fun resetNdiViewport(): Boolean {
            if (currentMode != Mode.HYBRID) return false
            main.post {
                if (currentMode == Mode.HYBRID) {
                    hybridViewport = NdiViewport()
                    applyNdiViewport()
                }
            }
            return true
        }

        @JavascriptInterface
        fun selectNdiSource(name: String?, address: String?): Boolean {
            val sourceName = name?.takeIf { it.isNotBlank() } ?: return false
            val sourceAddress = address ?: return false
            if (currentMode != Mode.HYBRID) return false
            val source = latestSources.firstOrNull { it.name == sourceName && it.address == sourceAddress } ?: return false
            main.post {
                if (currentMode == Mode.HYBRID) {
                    val stillAvailable = latestSources.firstOrNull { it.name == source.name && it.address == source.address }
                    if (stillAvailable != null) connect(stillAvailable, hybrid = true)
                }
            }
            return true
        }

        @JavascriptInterface
        fun selectNdiSourceByLabel(label: String?): Boolean {
            val requestedLabel = label?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            if (currentMode != Mode.HYBRID) return false
            val matches = latestSources.filter { sourceAlias(it).equals(requestedLabel, ignoreCase = true) }
            if (matches.size != 1) return false
            val source = matches.single()
            main.post {
                if (currentMode == Mode.HYBRID) {
                    val stillAvailable = latestSources.firstOrNull { it.name == source.name && it.address == source.address }
                    if (stillAvailable != null && sourceAlias(stillAvailable).equals(requestedLabel, ignoreCase = true)) {
                        connect(stillAvailable, hybrid = true)
                    }
                }
            }
            return true
        }
    }

    private fun stopNdiPlaybackIfActive() {
        connectionGeneration++
        frameTask?.cancel(false)
        frameTask = null
        activeSource = null
        sourceConnected = false
        if (ndiReady && !worker.isShutdown) worker.execute { runCatching { NdiNative.disconnect() } }
    }

    private fun install(view: View) {
        main.removeCallbacks(cameraPreviewPoll)
        if (currentMode != Mode.CONTRIBUTE) {
            cameraPreviewView = null
            cameraPreviewPlaceholder = null
            ContributeService.cameraPreviewRequested = false
            closeCameraPreview(clearDesired = true)
        }
        root = FrameLayout(this).apply { setBackgroundColor(BG); addView(view, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
    }

    private fun actionCard(title: String, detail: String, mark: String, accent: Int, action: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(20), dp(26), dp(20))
            background = rounded(PANEL, dp(18))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { view, focused -> view.background = rounded(if (focused) PANEL_FOCUS else PANEL, dp(18), if (focused) accent else null) }
            setOnClickListener { action() }
        }
        card.addView(label(mark, 23, accent, true))
        card.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        card.addView(label(title, 21, WHITE, true))
        card.addView(View(this), LinearLayout.LayoutParams(1, dp(5)))
        card.addView(label(detail, 14, SOFT))
        return card
    }

    private fun actionButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 14f
        setTextColor(WHITE)
        isAllCaps = false
        background = rounded(0xFF253543.toInt(), dp(12), 0xFF3D5364.toInt())
        isFocusable = true
        setOnFocusChangeListener { view, focused -> view.background = rounded(if (focused) PANEL_FOCUS else 0xFF253543.toInt(), dp(12), if (focused) BLUE else 0xFF3D5364.toInt()) }
        setOnClickListener { action() }
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weightedCard(height: Int = 220) = LinearLayout.LayoutParams(0, dp(height), 1f)
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        stroke?.let { setStroke(dp(2), it) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showToastMessage(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when (currentMode) {
                Mode.HOME -> return true
                Mode.SETTINGS, Mode.SOURCES, Mode.CONTRIBUTE -> renderHome()
                Mode.WEB -> { webView?.stopLoading(); renderHome() }
                Mode.NDI, Mode.HYBRID -> renderSources()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && currentMode in setOf(Mode.WEB, Mode.NDI, Mode.HYBRID)) {
            renderHome()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        setKeepScreenAwake(false)
        ContributeService.cameraPreviewRequested = false
        main.removeCallbacks(cameraPreviewPoll)
        closeCameraPreview(clearDesired = false)
        runCatching { webView?.stopLoading() }
        worker.execute {
            discoveryTask?.cancel(false)
            discoveryTask = null
            frameTask?.cancel(false)
            frameTask = null
            if (ndiReady && !ContributeService.isRunning) {
                runCatching { NdiNative.shutdown() }
                ndiReady = false
            }
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    override fun onDestroy() {
        ContributeService.cameraPreviewRequested = false
        main.removeCallbacks(cameraPreviewPoll)
        worker.shutdownNow()
        closeCameraPreview(clearDesired = true)
        (cameraPreviewView?.tag as? Bitmap)?.recycle()
        webView?.destroy()
        super.onDestroy()
    }
}

private data class NdiSource(val name: String, val address: String)
private data class CameraChoice(val id: String, val label: String, val facing: Int? = null) {
    override fun toString(): String = label
}

internal object NdiNative {
    init {
        System.loadLibrary("ndi")
        System.loadLibrary("openndi")
    }
    external fun initialize(): Boolean
    external fun findSources(timeoutMs: Int): Array<Array<String>>
    external fun connect(name: String, address: String): Boolean
    external fun connectionCount(): Int
    external fun startSender(name: String): Boolean
    external fun sendVideo(pixels: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): Boolean
    external fun sendYuvVideo(
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        v: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int
    ): Boolean
    external fun sendAudio(samples: ByteBuffer, sampleRate: Int, channels: Int, sampleCount: Int): Boolean
    external fun stopSender()
    external fun disconnect()
    external fun capture(target: ByteBuffer, timeoutMs: Int): IntArray?
    external fun shutdown()
}

private class NdiVideoView(context: android.content.Context, private val showSourceDetails: Boolean) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val diagnosticBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6101820.toInt() }
    private val diagnosticText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val frameLock = Any()
    private var frame: Bitmap? = null
    private var decodedFormat = "Waiting for NDI video…"
    private var sourceFrameRate = "—"
    private var measuredFrameRate = 0f
    private var rateWindowStart = 0L
    private var framesInRateWindow = 0
    private var isDisconnected = false

    fun setConnectionCount(count: Int) {
        synchronized(frameLock) {
            isDisconnected = count == 0
        }
        postInvalidateOnAnimation()
    }

    fun setFrame(source: ByteBuffer, width: Int, height: Int, fourcc: Int, rateNumerator: Int, rateDenominator: Int) {
        val requiredBytes = width.toLong() * height.toLong() * 4L
        if (width <= 0 || height <= 0 || width > MAX_NDI_FRAME_DIMENSION || height > MAX_NDI_FRAME_DIMENSION ||
            requiredBytes > source.capacity() || requiredBytes > MAX_NDI_FRAME_BYTES) return
        synchronized(frameLock) {
            if (frame?.width != width || frame?.height != height) {
                frame?.recycle()
                frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val pixels = source.duplicate()
            pixels.position(0)
            runCatching { frame?.copyPixelsFromBuffer(pixels) }.onFailure {
                frame?.recycle()
                frame = null
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (rateWindowStart == 0L) rateWindowStart = now
            framesInRateWindow++
            val elapsed = now - rateWindowStart
            if (elapsed >= 1000L) {
                measuredFrameRate = framesInRateWindow * 1000f / elapsed
                framesInRateWindow = 0
                rateWindowStart = now
            }
            decodedFormat = "$width × $height • ${fourccText(fourcc)}"
            sourceFrameRate = if (rateNumerator > 0 && rateDenominator > 0) "%.2f fps".format(rateNumerator.toFloat() / rateDenominator) else "—"
            isDisconnected = false
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        synchronized(frameLock) {
            val current = frame
            if (current != null) {
                val scale = minOf(width.toFloat() / current.width, height.toFloat() / current.height)
                val w = current.width * scale
                val h = current.height * scale
                val dest = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                canvas.drawBitmap(current, null, dest, paint)
            }
            if (isDisconnected) drawDisconnectedIndicator(canvas)
            if (showSourceDetails && current != null) {
                val density = resources.displayMetrics.density
                val firstLine = "Decoded: $decodedFormat"
                val secondLine = "Source: $sourceFrameRate  •  Display: %.1f fps".format(measuredFrameRate)
                val padding = 12f * density
                val lineHeight = 20f * density
                diagnosticText.textSize = 14f * density
                val boxWidth = maxOf(diagnosticText.measureText(firstLine), diagnosticText.measureText(secondLine)) + padding * 2
                val boxHeight = lineHeight * 2 + padding * 2
                val left = 20f * density
                val top = height - boxHeight - 20f * density
                val box = RectF(left, top, left + boxWidth, top + boxHeight)
                canvas.drawRoundRect(box, 10f * density, 10f * density, diagnosticBackground)
                canvas.drawText(firstLine, left + padding, top + padding + lineHeight, diagnosticText)
                canvas.drawText(secondLine, left + padding, top + padding + lineHeight * 2, diagnosticText)
            }
        }
    }

    private fun drawDisconnectedIndicator(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val radius = 5f * density
        val margin = 18f * density
        val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISCONNECTED_RED }
        canvas.drawCircle(width - margin - radius, margin + radius, radius, indicatorPaint)
    }
}

private fun fourccText(value: Int): String = (0..3)
    .map { ((value ushr (it * 8)) and 0xFF).toChar() }
    .joinToString("")


