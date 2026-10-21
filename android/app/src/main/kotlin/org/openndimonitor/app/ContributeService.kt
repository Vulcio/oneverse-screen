package org.openndimonitor.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ContributeService : Service() {
    private var captureHandler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val stopping = AtomicBoolean(false)
    private val startClaimed = AtomicBoolean(false)
    private var senderStarted = false
    private val senderLock = Any()
    private var lastVideoFrameNanos = 0L
    private var lastPreviewFrameNanos = 0L

    companion object {
        const val ACTION_START = "org.openndimonitor.app.action.START_CONTRIBUTE"
        const val ACTION_STOP = "org.openndimonitor.app.action.STOP_CONTRIBUTE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NAME = "source_name"
        const val EXTRA_AUDIO = "include_audio"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val MODE_SCREEN = "screen"
        const val MODE_CAMERA = "camera"
        @Volatile var isRunning = false
            private set
        @Volatile var activeMode: String? = null
            private set
        @Volatile var cameraPreviewRequested = false
        @Volatile var latestCameraPreview: Bitmap? = null
            private set
        @Volatile var lastError: String? = null
            private set
        // One queue orders startup, frame callbacks and teardown across Service instances.
        private val captureWorker by lazy { HandlerThread("OneverseCapture").apply { start() } }
        private val sharedCaptureHandler by lazy { Handler(captureWorker.looper) }
        private const val CHANNEL_ID = "ndi_contribute"
        private const val NOTIFICATION_ID = 73
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startClaimed.compareAndSet(false, true)) return START_NOT_STICKY
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCREEN
        if (mode !in setOf(MODE_SCREEN, MODE_CAMERA)) { stopSelf(); return START_NOT_STICKY }
        lastError = null
        activeMode = mode
        val sourceName = intent.getStringExtra(EXTRA_NAME)?.trim().orEmpty().ifBlank { "Oneverse Screen" }
        val includeAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val requestedCameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
        if (runCatching { startCaptureForeground(mode, includeAudio) }.isFailure) {
            failCapture("Android did not allow this sharing session")
            return START_NOT_STICKY
        }
        isRunning = true
        captureHandler = sharedCaptureHandler
        captureHandler!!.post {
            if (stopping.get()) return@post
            runCatching {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
                multicastLock = wifi?.createMulticastLock("OneverseContribution")?.apply { setReferenceCounted(false); acquire() }
            }
            runCatching {
                check(NdiNative.initialize() && NdiNative.startSender(sourceName)) { "NDI sender could not start" }
                senderStarted = true
                when (mode) {
                    MODE_CAMERA -> startCamera(includeAudio, requestedCameraId)
                    else -> startScreenCapture(intent, includeAudio)
                }
            }.onFailure { failCapture("Could not start sharing", it) }
        }
        return START_NOT_STICKY
    }

    private fun startCaptureForeground(mode: String, includeAudio: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "NDI contribution", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, ContributeService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Sending NDI source")
            .setContentText(if (mode == MODE_CAMERA) "Camera${if (includeAudio) " and microphone" else ""} is being shared" else "Screen${if (includeAudio) " and app audio" else ""} is being shared")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
        if (mode == MODE_CAMERA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    (if (includeAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (mode != MODE_CAMERA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenCapture(startIntent: Intent, includeAudio: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            stopSelf()
            return
        }
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            startIntent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            startIntent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        }
        if (data == null) {
            stopSelf()
            return
        }
        val resultCode = startIntent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val activeProjection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (activeProjection == null) {
            stopSelf()
            return
        }
        projection = activeProjection
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, captureHandler)

        val metrics = resources.displayMetrics
        val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2).let { it - it % 2 }
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2).let { it - it % 2 }
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ imageReader ->
            val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            runCatching { image.use { frame ->
                val now = System.nanoTime()
                if (now - lastVideoFrameNanos < 33_000_000L) return@use
                lastVideoFrameNanos = now
                val plane = frame.planes.firstOrNull() ?: return@use
                val pixels = plane.buffer.slice()
                synchronized(senderLock) {
                    if (!stopping.get()) NdiNative.sendVideo(pixels, frame.width, frame.height, plane.rowStride, plane.pixelStride)
                }
            } }.onFailure { if (!stopping.get()) failCapture("Video capture failed", it) }
        }, captureHandler)
        virtualDisplay = activeProjection.createVirtualDisplay(
            "Oneverse Screen NDI", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, captureHandler
        )
        if (includeAudio && Build.VERSION.SDK_INT >= 29) startAudioCapture(playback = true)
    }

    private fun startCamera(includeAudio: Boolean, requestedCameraId: String?) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = requestedCameraId?.takeIf { it in cameraManager.cameraIdList }
            ?: cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
        if (cameraId == null) {
            stopSelf()
            return
        }
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = chooseCameraSize(map) ?: run { stopSelf(); return }
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ imageReader ->
            val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            runCatching { image.use { frame ->
                val now = System.nanoTime()
                if (now - lastVideoFrameNanos < 33_000_000L) return@use
                lastVideoFrameNanos = now
                val planes = frame.planes
                if (planes.size == 3) {
                    val y = planes[0].buffer.slice()
                    val u = planes[1].buffer.slice()
                    val v = planes[2].buffer.slice()
                    synchronized(senderLock) {
                        if (!stopping.get()) {
                            NdiNative.sendYuvVideo(
                                y, planes[0].rowStride, planes[0].pixelStride,
                                u, planes[1].rowStride, planes[1].pixelStride,
                                v, planes[2].rowStride, planes[2].pixelStride,
                                frame.width, frame.height, cameraRotation(characteristics)
                            )
                        }
                    }
                    val previewTime = System.nanoTime()
                    if (cameraPreviewRequested && previewTime - lastPreviewFrameNanos >= 200_000_000L) {
                        lastPreviewFrameNanos = previewTime
                        latestCameraPreview = CameraFrames.preview(frame, cameraRotation(characteristics))
                    }
                }
            } }.onFailure { if (!stopping.get()) failCapture("Video capture failed", it) }
        }, captureHandler)
        if (includeAudio) startAudioCapture(playback = false)
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopping.get()) { device.close(); return }
                camera = device
                runCatching {
                val surface = reader.surface
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (stopping.get()) {
                            session.close()
                            device.close()
                            return
                        }
                        cameraSession = session
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }.build()
                        runCatching { session.setRepeatingRequest(request, null, captureHandler) }
                            .onFailure { failCapture("Camera could not start streaming", it) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        stopSelf()
                    }
                }, captureHandler)
                }.onFailure { failCapture("Camera session could not start", it) }
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); stopSelf() }
            override fun onError(device: CameraDevice, error: Int) { device.close(); stopSelf() }
        }, captureHandler)
    }

    private fun chooseCameraSize(map: StreamConfigurationMap?): Size? {
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        return sizes.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun cameraRotation(characteristics: android.hardware.camera2.CameraCharacteristics): Int {
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        return CameraFrames.rotation(characteristics, display?.rotation ?: Surface.ROTATION_0)
    }

    private fun startAudioCapture(playback: Boolean) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (playback && Build.VERSION.SDK_INT < 29) return
        val rate = 48000
        val channels = if (playback) 2 else 1
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(channelConfig).build()
        val minBytes = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return
        val builder = AudioRecord.Builder().setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, 1920 * channels * 2))
        if (playback) {
            val activeProjection = projection ?: return
            val config = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(config)
        } else {
            builder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        }
        val record = runCatching { builder.build() }.getOrNull() ?: return
        audioRecord = record
        if (runCatching { record.startRecording() }.isFailure) {
            record.release()
            audioRecord = null
            return
        }
        audioThread = Thread {
            val samples = ShortArray(480 * channels)
            val bytes = ByteBuffer.allocateDirect(480 * channels * 4)
            while (!stopping.get() && audioRecord === record) {
                val count = runCatching { record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING) }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
                if (count < 0) {
                    if (!stopping.get()) failCapture("Audio capture stopped unexpectedly")
                    break
                }
                if (count == 0) continue
                val perChannel = AudioSamples.writePlanar(samples, count, channels, bytes)
                synchronized(senderLock) {
                    if (!stopping.get()) NdiNative.sendAudio(bytes, rate, channels, perChannel)
                }
            }
        }.apply { name = "OneverseAudioCapture"; start() }
    }

    private fun failCapture(message: String, error: Throwable? = null) {
        lastError = if (error?.message.isNullOrBlank()) message else "$message: ${error?.message}"
        android.util.Log.e("OneverseCapture", message, error)
        stopSelf()
    }

    override fun onDestroy() {
        stopping.set(true)
        cameraPreviewRequested = false
        latestCameraPreview = null
        isRunning = false
        activeMode = null
        // The callback queue must finish reading any acquired Image before its reader closes.
        // Posting also keeps native sender teardown off the Android main thread.
        captureHandler?.post {
            runCatching { imageReader?.setOnImageAvailableListener(null, null) }
            runCatching { cameraSession?.stopRepeating() }
            runCatching { cameraSession?.close() }
            runCatching { camera?.close() }
            runCatching { virtualDisplay?.release() }
            runCatching { projection?.stop() }
            runCatching { imageReader?.close() }
            val record = audioRecord
            audioRecord = null
            runCatching { record?.stop() }
            runCatching { audioThread?.join(2000) }
            runCatching { record?.release() }
            synchronized(senderLock) { if (senderStarted) runCatching { NdiNative.stopSender() } }
            multicastLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
            multicastLock = null
        }
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }
}
