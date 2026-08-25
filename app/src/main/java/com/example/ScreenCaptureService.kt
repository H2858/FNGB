package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.bot.BotManager
import com.example.bot.YoloV8NcnnDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCaptureService: MediaProjection Frame Capturer & Real-time Bot Pipeline.
 *
 * Captures screen frames at 30-60 FPS using MediaProjection + ImageReader.
 * Passes frames to YoloV8NcnnDetector.
 * If class 'Fruit' is detected -> triggers FruitBotService.sliceFruit().
 * If class 'Bomb' is detected -> completely ignores and avoids.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "fruit_ninja_bot_capture_channel"

        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var projectionData: Intent? = null
        var projectionResultCode: Int = 0

        fun startService(context: Context, resultCode: Int, data: Intent) {
            projectionResultCode = resultCode
            projectionData = data
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var detector: YoloV8NcnnDetector? = null

    private val isCapturing = AtomicBoolean(false)
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensityDpi = 320

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        detector = YoloV8NcnnDetector(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, projectionResultCode)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: projectionData

                if (resultData != null && resultCode != 0) {
                    startForeground(NOTIFICATION_ID, buildForegroundNotification())
                    startScreenCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "Cannot start capture: Missing MediaProjection permission data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopScreenCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        if (isCapturing.get()) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // Scale down capture resolution slightly for maximum AI frame rate (e.g. max 720p)
        val scale = if (metrics.widthPixels > 720) 720f / metrics.widthPixels else 1f
        screenWidth = (metrics.widthPixels * scale).toInt()
        screenHeight = (metrics.heightPixels * scale).toInt()
        screenDensityDpi = metrics.densityDpi

        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection instance")
                stopSelf()
                return
            }

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "FruitNinjaBotDisplay",
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            isCapturing.set(true)
            BotManager.updateRunningState(true)
            Log.i(TAG, "Screen capture initialized: ${screenWidth}x${screenHeight} @ ${screenDensityDpi}dpi")

            startProcessingLoop(scale)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaProjection: ${e.message}", e)
            stopSelf()
        }
    }

    private fun startProcessingLoop(scale: Float) {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            val inverseScale = if (scale > 0f) 1f / scale else 1f

            while (isActive && isCapturing.get()) {
                val loopStartTime = System.currentTimeMillis()

                try {
                    val image: Image? = imageReader?.acquireLatestImage()
                    if (image != null) {
                        processFrame(image, inverseScale)
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame capture error: ${e.message}")
                }

                // Maintain target frame rate
                val elapsed = System.currentTimeMillis() - loopStartTime
                val targetDelay = (1000L / BotManager.targetFps) - elapsed
                if (targetDelay > 0) {
                    delay(targetDelay)
                }
            }
        }
    }

    private fun processFrame(image: Image, inverseScale: Float) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmapHeight = image.height

        val rawBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)

        val cleanBitmap = if (rawBitmap.width != image.width) {
            Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
        } else {
            rawBitmap
        }

        // Run YOLOv8 NCNN Detection
        val detections = detector?.detect(cleanBitmap) ?: emptyList()

        if (detections.isNotEmpty() && BotManager.isBotActive.value) {
            for (detection in detections) {
                if (detection.isFruit && !detection.isBomb) {
                    // Scale bounding box back to real device screen coordinates
                    val scaledFruit = detection.copy(
                        rect = android.graphics.RectF(
                            detection.rect.left * inverseScale,
                            detection.rect.top * inverseScale,
                            detection.rect.right * inverseScale,
                            detection.rect.bottom * inverseScale
                        )
                    )

                    // Execute fast swipe gesture on fruit
                    FruitBotService.sliceFruit(
                        fruit = scaledFruit,
                        allDetections = detections,
                        sliceLength = BotManager.sliceLengthPx,
                        angleDegrees = BotManager.sliceAngleDegrees
                    )
                } else if (detection.isBomb) {
                    // Explicitly ignore bombs
                    Log.d(TAG, "Bomb detected: Ignoring swipe at (${detection.centerX * inverseScale}, ${detection.centerY * inverseScale})")
                }
            }
        }

        if (cleanBitmap != rawBitmap) {
            cleanBitmap.recycle()
        }
        rawBitmap.recycle()
    }

    private fun stopScreenCapture() {
        isCapturing.set(false)
        BotManager.updateRunningState(false)
        captureJob?.cancel()
        captureJob = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fruit Ninja Bot Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen capture and target detection running in real-time."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fruit Ninja Bot Active")
            .setContentText("Auto-detecting and slicing fruits in real-time (Bombs ignored)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Bot", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopScreenCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
