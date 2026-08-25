package com.example.bot

import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DetectionResult(
    val rect: RectF,
    val label: String,
    val confidence: Float,
    val isFruit: Boolean = label.equals("Fruit", ignoreCase = true),
    val isBomb: Boolean = label.equals("Bomb", ignoreCase = true)
) {
    val centerX: Float get() = rect.centerX()
    val centerY: Float get() = rect.centerY()
    val width: Float get() = rect.width()
    val height: Float get() = rect.height()
}

data class BotStatistics(
    val fps: Int = 0,
    val totalFramesProcessed: Long = 0L,
    val totalSlices: Int = 0,
    val bombsAvoided: Int = 0,
    val lastInferenceTimeMs: Long = 0L,
    val isRunning: Boolean = false,
    val isModelLoaded: Boolean = false,
    val modelParamInfo: String = ""
)

object BotManager {
    private val _stats = MutableStateFlow(BotStatistics())
    val stats: StateFlow<BotStatistics> = _stats.asStateFlow()

    private val _isBotActive = MutableStateFlow(false)
    val isBotActive: StateFlow<Boolean> = _isBotActive.asStateFlow()

    // Configurable parameters
    var confidenceThreshold: Float = 0.45f
    var swipeDurationMs: Long = 35L
    var sliceLengthPx: Float = 220f
    var sliceAngleDegrees: Float = 45f
    var minTimeBetweenSlicesMs: Long = 40L
    var targetFps: Int = 30

    fun updateRunningState(running: Boolean) {
        _isBotActive.value = running
        _stats.value = _stats.value.copy(isRunning = running)
    }

    fun updateModelStatus(loaded: Boolean, info: String) {
        _stats.value = _stats.value.copy(isModelLoaded = loaded, modelParamInfo = info)
    }

    fun recordFrame(fps: Int, inferenceTimeMs: Long) {
        val current = _stats.value
        _stats.value = current.copy(
            fps = fps,
            totalFramesProcessed = current.totalFramesProcessed + 1,
            lastInferenceTimeMs = inferenceTimeMs
        )
    }

    fun recordSlice() {
        val current = _stats.value
        _stats.value = current.copy(totalSlices = current.totalSlices + 1)
    }

    fun recordBombAvoided() {
        val current = _stats.value
        _stats.value = current.copy(bombsAvoided = current.bombsAvoided + 1)
    }

    fun resetStats() {
        _stats.value = BotStatistics(
            isModelLoaded = _stats.value.isModelLoaded,
            modelParamInfo = _stats.value.modelParamInfo,
            isRunning = _isBotActive.value
        )
    }
}
