package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.bot.BotManager
import com.example.bot.DetectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin

/**
 * FruitBotService: Accessibility Service & Fast Touch Gesture Engine.
 *
 * Implements high-speed slice gestures via AccessibilityService.dispatchGesture().
 * Executes diagonal and directional swipes across detected fruit targets.
 * Completely ignores bombs.
 */
class FruitBotService : AccessibilityService() {

    companion object {
        private const val TAG = "FruitBotService"

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        @Volatile
        var instance: FruitBotService? = null
            private set

        private val lastGestureTime = AtomicLong(0L)
        private val isSwiping = AtomicBoolean(false)

        /**
         * Dispatches a swipe gesture on screen from (startX, startY) to (endX, endY).
         */
        fun performSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long = BotManager.swipeDurationMs,
            onComplete: (() -> Unit)? = null
        ): Boolean {
            val service = instance ?: return false
            return service.dispatchSwipeInternal(startX, startY, endX, endY, durationMs, onComplete)
        }

        /**
         * Slices a detected fruit target by calculating a fast diagonal stroke across its center.
         */
        fun sliceFruit(
            fruit: DetectionResult,
            allDetections: List<DetectionResult> = emptyList(),
            sliceLength: Float = BotManager.sliceLengthPx,
            angleDegrees: Float = BotManager.sliceAngleDegrees
        ): Boolean {
            val service = instance ?: return false

            // Completely ignore bomb detections
            if (fruit.isBomb) {
                Log.d(TAG, "Ignoring Bomb at (${fruit.centerX}, ${fruit.centerY})")
                return false
            }

            // Safety check: Avoid slicing if a bomb is within close proximity of the fruit center
            val bombDangerRadius = 160f
            for (detection in allDetections) {
                if (detection.isBomb) {
                    val dx = detection.centerX - fruit.centerX
                    val dy = detection.centerY - fruit.centerY
                    val distSq = dx * dx + dy * dy
                    if (distSq < bombDangerRadius * bombDangerRadius) {
                        Log.d(TAG, "Bomb proximity alert near fruit! Skipping swipe to avoid bomb.")
                        BotManager.recordBombAvoided()
                        return false
                    }
                }
            }

            val rad = Math.toRadians(angleDegrees.toDouble())
            val halfLen = sliceLength / 2f
            val dx = (halfLen * cos(rad)).toFloat()
            val dy = (halfLen * sin(rad)).toFloat()

            val startX = fruit.centerX - dx
            val startY = fruit.centerY - dy
            val endX = fruit.centerX + dx
            val endY = fruit.centerY + dy

            val dispatched = service.dispatchSwipeInternal(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = BotManager.swipeDurationMs,
                onComplete = {
                    BotManager.recordSlice()
                }
            )

            return dispatched
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "FruitBotService connected and ready to dispatch gestures.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events can be monitored here if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "FruitBotService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        _isServiceConnected.value = false
        Log.i(TAG, "FruitBotService destroyed.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        _isServiceConnected.value = false
        return super.onUnbind(intent)
    }

    /**
     * Internal implementation of dispatchGesture with rate-limiting to maintain optimal frame rate.
     */
    private fun dispatchSwipeInternal(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        onComplete: (() -> Unit)?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGestureTime.get() < BotManager.minTimeBetweenSlicesMs) {
            // Drop swipe to avoid touch event backlog
            return false
        }

        lastGestureTime.set(currentTime)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(15L))
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)
        val gesture = gestureBuilder.build()

        isSwiping.set(true)

        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isSwiping.set(false)
                    onComplete?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isSwiping.set(false)
                }
            },
            null
        )
    }
}
