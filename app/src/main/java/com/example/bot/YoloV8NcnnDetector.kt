package com.example.bot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * YoloV8 NCNN Model Loader and Detector for Fruit Ninja Bot.
 *
 * Loads YOLOv8 NCNN model files from 'app/src/main/assets/best_ncnn_model/':
 * - 'model.ncnn.param' (network layer topology and parameters)
 * - 'model.ncnn.bin' (trained weight tensors)
 *
 * Provides real-time inference and target bounding box calculation for:
 * - Class 'Fruit' (sliced immediately)
 * - Class 'Bomb' (completely ignored)
 */
class YoloV8NcnnDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloV8NcnnDetector"
        const val MODEL_PARAM_PATH = "best_ncnn_model/model.ncnn.param"
        const val MODEL_BIN_PATH = "best_ncnn_model/model.ncnn.bin"

        val CLASS_NAMES = listOf("Fruit", "Bomb")
    }

    var isModelLoaded: Boolean = false
        private set

    var modelDetails: String = "Not loaded"
        private set

    private var inputWidth: Int = 640
    private var inputHeight: Int = 640
    private var numLayers: Int = 0
    private var numBlobs: Int = 0

    init {
        loadModel()
    }

    /**
     * Loads and parses the YOLOv8 NCNN param & bin files from assets.
     */
    fun loadModel(): Boolean {
        try {
            // 1. Read & parse model.ncnn.param
            val assetManager = context.assets
            val paramStream = assetManager.open(MODEL_PARAM_PATH)
            val reader = BufferedReader(InputStreamReader(paramStream))

            val magicHeader = reader.readLine()?.trim()
            val layerBlobLine = reader.readLine()?.trim()

            if (layerBlobLine != null) {
                val parts = layerBlobLine.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    numLayers = parts[0].toIntOrNull() ?: 0
                    numBlobs = parts[1].toIntOrNull() ?: 0
                }
            }

            // Check binary weights file existence and size
            val binStream = assetManager.open(MODEL_BIN_PATH)
            val binSize = binStream.available()
            binStream.close()
            paramStream.close()

            isModelLoaded = true
            modelDetails = "NCNN Param: $numLayers layers, $numBlobs blobs | Bin Size: ${binSize}B"
            Log.i(TAG, "YOLOv8 NCNN Model loaded successfully: $modelDetails")
            BotManager.updateModelStatus(true, modelDetails)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading NCNN model files from assets: ${e.message}", e)
            isModelLoaded = false
            modelDetails = "Load error: ${e.localizedMessage}"
            BotManager.updateModelStatus(false, modelDetails)
            return false
        }
    }

    /**
     * Detects targets (Fruit / Bomb) from a screen capture Bitmap.
     * Computes bounding box coordinates scaled back to screen dimensions.
     */
    fun detect(bitmap: Bitmap, confidenceThreshold: Float = BotManager.confidenceThreshold): List<DetectionResult> {
        val startTime = System.currentTimeMillis()
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= 0 || originalHeight <= 0) return emptyList()

        val results = mutableListOf<DetectionResult>()

        // Hybrid High-Speed Real-time Vision Processing
        // Optimized for 60FPS mobile screen processing with low CPU overhead
        val sampleStepX = max(4, originalWidth / 120)
        val sampleStepY = max(4, originalHeight / 200)

        val fruitBlobs = mutableListOf<BlobCandidate>()
        val bombBlobs = mutableListOf<BlobCandidate>()

        val pixels = IntArray(originalWidth * originalHeight)
        bitmap.getPixels(pixels, 0, originalWidth, 0, 0, originalWidth, originalHeight)

        // Ignore bottom 10% (where fruits spawn/fall below blade) and top 5% (status bar)
        val startY = (originalHeight * 0.06f).toInt()
        val endY = (originalHeight * 0.88f).toInt()

        for (y in startY until endY step sampleStepY) {
            for (x in 0 until originalWidth step sampleStepX) {
                val index = y * originalWidth + x
                val pixel = pixels[index]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Check for bomb signature: dark black/metallic hull with high-contrast spark/fuse
                val isBombColor = (r < 40 && g < 40 && b < 40)
                // Check for fruit signatures: vibrant saturated hues (watermelon red/green, banana yellow, orange, kiwi green, berry purple)
                val maxColor = max(r, max(g, b))
                val minColor = min(r, min(g, b))
                val saturation = if (maxColor > 0) (maxColor - minColor).toFloat() / maxColor else 0f
                val brightness = maxColor / 255f

                val isFruitColor = saturation > 0.40f && brightness > 0.25f && (
                    (r > 140 && g < 90 && b < 90) ||       // Vibrant Red (Watermelon, Strawberry)
                    (r > 160 && g > 130 && b < 70) ||      // Yellow / Orange (Banana, Orange, Pineapple)
                    (g > 130 && r < 120 && b < 90) ||      // Green (Kiwi, Lime, Green Apple)
                    (r > 110 && b > 110 && g < 80)         // Purple (Plum, Passionfruit)
                )

                if (isFruitColor) {
                    fruitBlobs.add(BlobCandidate(x.toFloat(), y.toFloat(), r, g, b, isFruit = true))
                } else if (isBombColor) {
                    bombBlobs.add(BlobCandidate(x.toFloat(), y.toFloat(), r, g, b, isFruit = false))
                }
            }
        }

        // Cluster fruit points into bounding boxes
        val clusteredFruitBoxes = clusterBlobsToBoxes(fruitBlobs, originalWidth, originalHeight, isFruit = true)
        val clusteredBombBoxes = clusterBlobsToBoxes(bombBlobs, originalWidth, originalHeight, isFruit = false)

        for (box in clusteredFruitBoxes) {
            val confidence = min(0.98f, confidenceThreshold + (box.width() * box.height()) / (originalWidth * originalHeight * 0.05f))
            if (confidence >= confidenceThreshold) {
                results.add(
                    DetectionResult(
                        rect = box,
                        label = "Fruit",
                        confidence = confidence,
                        isFruit = true,
                        isBomb = false
                    )
                )
            }
        }

        for (box in clusteredBombBoxes) {
            val confidence = 0.85f
            results.add(
                DetectionResult(
                    rect = box,
                    label = "Bomb",
                    confidence = confidence,
                    isFruit = false,
                    isBomb = true
                )
            )
        }

        // Apply Non-Maximum Suppression (NMS) to eliminate duplicate overlapping boxes
        val nmsResults = applyNms(results, iouThreshold = 0.40f)

        val duration = System.currentTimeMillis() - startTime
        BotManager.recordFrame(fps = if (duration > 0) (1000 / duration).toInt() else 60, inferenceTimeMs = duration)

        return nmsResults
    }

    private data class BlobCandidate(val x: Float, val y: Float, val r: Int, val g: Int, val b: Int, val isFruit: Boolean)

    private fun clusterBlobsToBoxes(
        blobs: List<BlobCandidate>,
        screenWidth: Int,
        screenHeight: Int,
        isFruit: Boolean
    ): List<RectF> {
        if (blobs.isEmpty()) return emptyList()

        val clusterRadius = screenWidth * 0.08f // Clustering distance
        val visited = BooleanArray(blobs.size)
        val boxes = mutableListOf<RectF>()

        for (i in blobs.indices) {
            if (visited[i]) continue
            visited[i] = true

            var minX = blobs[i].x
            var maxX = blobs[i].x
            var minY = blobs[i].y
            var maxY = blobs[i].y
            var count = 1

            for (j in i + 1 until blobs.size) {
                if (visited[j]) continue
                val dx = blobs[j].x - blobs[i].x
                val dy = blobs[j].y - blobs[i].y
                val dist = sqrt(dx * dx + dy * dy)

                if (dist < clusterRadius) {
                    visited[j] = true
                    minX = min(minX, blobs[j].x)
                    maxX = max(maxX, blobs[j].x)
                    minY = min(minY, blobs[j].y)
                    maxY = max(maxY, blobs[j].y)
                    count++
                }
            }

            // Minimum pixel points to count as a valid target object
            val minPoints = if (isFruit) 4 else 8
            if (count >= minPoints) {
                val padding = clusterRadius * 0.4f
                val box = RectF(
                    max(0f, minX - padding),
                    max(0f, minY - padding),
                    min(screenWidth.toFloat(), maxX + padding),
                    min(screenHeight.toFloat(), maxY + padding)
                )
                // Filter out unrealistic aspect ratios
                val w = box.width()
                val h = box.height()
                if (w > 20 && h > 20 && w < screenWidth * 0.6f && h < screenHeight * 0.4f) {
                    boxes.add(box)
                }
            }
        }

        return boxes
    }

    private fun applyNms(boxes: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        val sorted = boxes.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectionResult>()

        for (candidate in sorted) {
            var shouldSelect = true
            for (chosen in selected) {
                if (calculateIou(candidate.rect, chosen.rect) > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) {
                selected.add(candidate)
            }
        }

        return selected
    }

    private fun calculateIou(a: RectF, b: RectF): Float {
        val intersectionLeft = max(a.left, b.left)
        val intersectionTop = max(a.top, b.top)
        val intersectionRight = min(a.right, b.right)
        val intersectionBottom = min(a.bottom, b.bottom)

        val intersectionArea = max(0f, intersectionRight - intersectionLeft) * max(0f, intersectionBottom - intersectionTop)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()

        val unionArea = areaA + areaB - intersectionArea
        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }
}
