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
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val stopping = AtomicBoolean(false)
    private val startClaimed = AtomicBoolean(false)
    private val senderStartupLock = Any()
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
        activeMode = mode
        val sourceName = intent.getStringExtra(EXTRA_NAME)?.trim().orEmpty().ifBlank { "Oneverse Screen" }
        val includeAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val requestedCameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
        startCaptureForeground(mode, includeAudio)
        isRunning = true
        captureThread = HandlerThread("OneverseCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        Thread {
            val started = synchronized(senderStartupLock) {
                if (stopping.get()) return@synchronized false
                val initialized = runCatching { NdiNative.initialize() && NdiNative.startSender(sourceName) }.getOrDefault(false)
                if (!initialized || stopping.get()) {
                    runCatching { NdiNative.stopSender() }
                    false
                } else {
                    runCatching {
                        when (mode) {
                            MODE_CAMERA -> startCamera(includeAudio, requestedCameraId)
                            else -> startScreenCapture(intent, includeAudio)
                        }
                    }.onFailure { stopSelf() }
                    true
                }
            }
            if (!started) stopSelf()
        }.start()
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
            image.use { frame ->
                val now = System.nanoTime()
                if (now - lastVideoFrameNanos < 33_000_000L) return@use
                lastVideoFrameNanos = now
                val plane = frame.planes.firstOrNull() ?: return@use
                val pixels = plane.buffer.slice()
                synchronized(senderLock) {
                    if (!stopping.get()) NdiNative.sendVideo(pixels, frame.width, frame.height, plane.rowStride, plane.pixelStride)
                }
            }
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
            image.use { frame ->
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
                                frame.width, frame.height
                            )
                        }
                    }
                    val previewTime = System.nanoTime()
                    if (cameraPreviewRequested && previewTime - lastPreviewFrameNanos >= 200_000_000L) {
                        lastPreviewFrameNanos = previewTime
                        latestCameraPreview = createCameraPreview(frame)
                    }
                }
            }
        }, captureHandler)
        if (includeAudio) startAudioCapture(playback = false)
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopping.get()) { device.close(); return }
                camera = device
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
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        stopSelf()
                    }
                }, captureHandler)
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

    private fun createCameraPreview(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val targetWidth = minOf(width, 640)
        val targetHeight = (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1)
        val pixels = IntArray(targetWidth * targetHeight)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
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
                val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                val perChannel = count / channels
                val floats = bytes.asFloatBuffer()
                floats.clear()
                for (i in 0 until perChannel) floats.put(samples[i * channels] / 32768f)
                if (channels > 1) {
                    for (i in 0 until perChannel) floats.put(samples[i * channels + 1] / 32768f)
                }
                bytes.position(0)
                synchronized(senderLock) {
                    if (!stopping.get()) NdiNative.sendAudio(bytes, rate, channels, perChannel)
                }
            }
        }.apply { name = "OneverseAudioCapture"; start() }
    }

    override fun onDestroy() {
        stopping.set(true)
        synchronized(senderStartupLock) {
            cameraPreviewRequested = false
            latestCameraPreview = null
            imageReader?.setOnImageAvailableListener(null, null)
            runCatching { cameraSession?.stopRepeating() }
            runCatching { cameraSession?.close() }
            runCatching { camera?.close() }
            runCatching { virtualDisplay?.release() }
            runCatching { projection?.stop() }
            runCatching { imageReader?.close() }
            audioRecord?.let { runCatching { it.stop() }; runCatching { it.release() } }
            captureThread?.quitSafely()
            synchronized(senderLock) { runCatching { NdiNative.stopSender() } }
        }
        isRunning = false
        activeMode = null
        super.onDestroy()
    }
}

