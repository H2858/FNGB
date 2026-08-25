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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.bot.BotManager
import com.example.bot.YoloV8NcnnDetector

class ScreenCaptureService : Service(), ImageReader.OnImageAvailableListener {

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

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var detector: YoloV8NcnnDetector? = null

    private var scaleFactor = 1f

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
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        scaleFactor = if (metrics.widthPixels > 720) 720f / metrics.widthPixels else 1f
        val screenWidth = (metrics.widthPixels * scaleFactor).toInt()
        val screenHeight = (metrics.heightPixels * scaleFactor).toInt()

        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener(this, null)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "FruitNinjaBotDisplay",
                screenWidth,
                screenHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            BotManager.updateRunningState(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture: ${e.message}")
            stopSelf()
        }
    }

    override fun onImageAvailable(reader: ImageReader?) {
        val image = reader?.acquireLatestImage() ?: return

        if (!BotManager.isBotActive.value) {
            image.close()
            return
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cleanBitmap = if (bitmap.width != image.width) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }

            val detections = detector?.detect(cleanBitmap) ?: emptyList()

            val inverseScale = if (scaleFactor > 0f) 1f / scaleFactor else 1f
            for (detection in detections) {
                if (detection.isFruit && !detection.isBomb) {
                    val scaledFruit = detection.copy(
                        rect = android.graphics.RectF(
                            detection.rect.left * inverseScale,
                            detection.rect.top * inverseScale,
                            detection.rect.right * inverseScale,
                            detection.rect.bottom * inverseScale
                        )
                    )
                    FruitBotService.sliceFruit(
                        fruit = scaledFruit,
                        allDetections = detections,
                        sliceLength = BotManager.sliceLengthPx,
                        angleDegrees = BotManager.sliceAngleDegrees
                    )
                }
            }

            if (cleanBitmap != bitmap) cleanBitmap.recycle()
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error in frame processing: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopScreenCapture() {
        BotManager.updateRunningState(false)
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
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fruit Ninja Bot Active")
            .setContentText("Auto-detecting and slicing fruits in real-time")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopScreenCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
