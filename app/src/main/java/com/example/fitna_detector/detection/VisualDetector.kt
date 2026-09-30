package com.example.fitna_detector.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.fitna_detector.model.SensitivityLevel
import com.example.fitna_detector.model.VisualDetectionResult
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * On-device AI classifier for detecting Islamically prohibited visual content:
 * - Explicit nudity / adult content (porn / hentai)
 * - Suggestive, provocative attire, swimwear, lingerie, and romantic/intimate couple scenes (sexy)
 *
 * Uses MobileNetV4 NSFW Classifier via ONNX Runtime Mobile, augmented with an intimacy
 * skin-exposure heuristic for catching romantic YouTube couple scenes and music videos.
 */
class VisualDetector(private val context: Context) {

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var ortSession: OrtSession? = null

    // ImageNet normalization constants
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    private val classLabels = arrayOf("drawings", "hentai", "neutral", "porn", "sexy")

    // Hysteresis counters to prevent overlay flickering
    private var consecutiveSafeFrames = 0
    private var isCurrentlyFlagged = false

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetManager = context.assets
            val modelStream: InputStream = assetManager.open("mobilenetv4_nsfw.onnx")
            val modelBytes = modelStream.readBytes()
            modelStream.close()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            ortSession = ortEnv.createSession(modelBytes, sessionOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            ortSession = null
        }
    }

    /**
     * Analyzes a screen capture frame and returns whether it contains prohibited visual content.
     */
    fun analyzeFrame(bitmap: Bitmap, sensitivity: SensitivityLevel): VisualDetectionResult {
        val targetSize = 224
        val scaledBitmap = if (bitmap.width == targetSize && bitmap.height == targetSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }

        // 1. Calculate Intimacy & Skin Exposure Heuristic
        val skinRatio = computeSkinRatio(scaledBitmap)

        // 2. Run ONNX Model Inference if loaded
        val session = ortSession
        val probabilities: Map<String, Float>
        val prohibitedScore: Float
        val dominantCategory: String

        if (session != null) {
            val inputTensor = createInputTensor(scaledBitmap)
            try {
                val results = session.run(mapOf("input" to inputTensor))
                @Suppress("UNCHECKED_CAST")
                val outputTensor = results[0].value as Array<FloatArray>
                val rawLogits = outputTensor[0]
                val softmaxProbs = softmax(rawLogits)

                val probMap = mutableMapOf<String, Float>()
                for (i in classLabels.indices) {
                    probMap[classLabels[i]] = softmaxProbs[i]
                }
                probabilities = probMap

                // Prohibited classes: porn, hentai, and sexy (suggestive/romantic/revealing)
                val pornProb = probMap["porn"] ?: 0f
                val hentaiProb = probMap["hentai"] ?: 0f
                val sexyProb = probMap["sexy"] ?: 0f
                val neutralProb = probMap["neutral"] ?: 0f

                // Combined score boosted by skin/intimacy ratio when suggestive cues exist
                var combinedScore = pornProb + hentaiProb + sexyProb

                // Intimacy boost: If central skin exposure is prominent (e.g. romantic couple close-up,
                // shirtless, or revealing attire) and sexy probability is elevated (> 0.20), boost score
                if (skinRatio > 0.28f && sexyProb > 0.20f) {
                    combinedScore += (skinRatio * 0.35f)
                }

                prohibitedScore = combinedScore.coerceIn(0f, 1f)
                dominantCategory = probMap.maxByOrNull { it.value }?.key ?: "neutral"
                results.close()
            } catch (e: Exception) {
                e.printStackTrace()
                return fallbackHeuristicResult(skinRatio, sensitivity)
            } finally {
                inputTensor.close()
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
            }
        } else {
            // Fallback when ONNX session is unavailable
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            return fallbackHeuristicResult(skinRatio, sensitivity)
        }

        // Apply hysteresis debouncing
        val threshold = sensitivity.threshold
        val isFrameProhibited = prohibitedScore >= threshold

        if (isFrameProhibited) {
            isCurrentlyFlagged = true
            consecutiveSafeFrames = 0
        } else {
            consecutiveSafeFrames++
            // Require 2 consecutive safe frames to clear the shield
            if (consecutiveSafeFrames >= 2) {
                isCurrentlyFlagged = false
            }
        }

        return VisualDetectionResult(
            isProhibited = isCurrentlyFlagged,
            prohibitedScore = prohibitedScore,
            dominantCategory = dominantCategory,
            probabilities = probabilities
        )
    }

    private fun createInputTensor(bitmap: Bitmap): OnnxTensor {
        val width = 224
        val height = 224
        val floatBufferSize = 3 * width * height * 4
        val byteBuffer = ByteBuffer.allocateDirect(floatBufferSize).apply {
            order(ByteOrder.nativeOrder())
        }
        val floatBuffer = byteBuffer.asFloatBuffer()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // CHW Format: First all Red, then all Green, then all Blue
        // Red channel
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16 and 0xFF) / 255.0f
            floatBuffer.put((r - mean[0]) / std[0])
        }
        // Green channel
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val g = (pixel shr 8 and 0xFF) / 255.0f
            floatBuffer.put((g - mean[1]) / std[1])
        }
        // Blue channel
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val b = (pixel and 0xFF) / 255.0f
            floatBuffer.put((b - mean[2]) / std[2])
        }
        floatBuffer.rewind()

        return OnnxTensor.createTensor(
            ortEnv,
            floatBuffer,
            longArrayOf(1, 3, height.toLong(), width.toLong())
        )
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) {
            if (v > max) max = v
        }
        var sum = 0f
        val expValues = FloatArray(logits.size)
        for (i in logits.indices) {
            val e = exp((logits[i] - max).toDouble()).toFloat()
            expValues[i] = e
            sum += e
        }
        for (i in expValues.indices) {
            expValues[i] /= sum
        }
        return expValues
    }

    /**
     * Analyzes skin tone exposure ratio in YCbCr color space.
     * Detects high exposed body areas, intimate romantic scenes, and revealing clothing.
     */
    private fun computeSkinRatio(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var skinPixelCount = 0
        val step = 4 // Subsample every 4th pixel for speed

        var sampled = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            // Convert RGB to YCbCr
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b

            // Standard human skin color bounding box in YCbCr space
            if (y > 60 && cb in 80f..133f && cr in 133f..178f) {
                skinPixelCount++
            }
            sampled++
            i += step
        }

        return if (sampled > 0) skinPixelCount.toFloat() / sampled else 0f
    }

    private fun fallbackHeuristicResult(skinRatio: Float, sensitivity: SensitivityLevel): VisualDetectionResult {
        val threshold = if (sensitivity == SensitivityLevel.STRICT) 0.32f else 0.42f
        val isFlagged = skinRatio >= threshold
        return VisualDetectionResult(
            isProhibited = isFlagged,
            prohibitedScore = skinRatio,
            dominantCategory = if (isFlagged) "suggestive_heuristic" else "neutral",
            probabilities = mapOf("skin_ratio" to skinRatio)
        )
    }

    fun close() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (ignored: Exception) {}
    }
}
