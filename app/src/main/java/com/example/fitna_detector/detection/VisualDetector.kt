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

    private val classLabels = arrayOf("drawings", "hentai", "neutral", "porn", "sexy")

    // Preallocated buffers for high-speed zero-GC inference
    private val targetSize = 224
    private val floatBufferSize = 3 * targetSize * targetSize * 4
    private val reusableByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(floatBufferSize).apply {
        order(ByteOrder.nativeOrder())
    }
    private val reusableFloatBuffer: FloatBuffer = reusableByteBuffer.asFloatBuffer()
    private val reusablePixels = IntArray(targetSize * targetSize)

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
    @Suppress("UseKtx")
    fun analyzeFrame(bitmap: Bitmap, sensitivity: SensitivityLevel): VisualDetectionResult {
        val scaledBitmap = if (bitmap.width == targetSize && bitmap.height == targetSize) {
            bitmap
        } else {
            val minDim = minOf(bitmap.width, bitmap.height)
            val cropY = if (bitmap.height > bitmap.width) (bitmap.height - minDim) / 3 else 0
            val cropped = Bitmap.createBitmap(bitmap, 0, cropY, minDim, minDim)
            val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
            if (cropped != bitmap) {
                cropped.recycle()
            }
            scaled
        }

        // 1. Check if the frame is dominated by our own red shield overlay
        val isOverlay = isShieldOverlayPresent(scaledBitmap)
        if (isOverlay) {
            return VisualDetectionResult(
                isProhibited = false,
                prohibitedScore = 0f,
                dominantCategory = "shield_overlay",
                probabilities = mapOf("neutral" to 1.0f)
            )
        }

        // 2. Calculate Intimacy & Skin Exposure Heuristic (RGB + YCbCr)
        val skinRatio = computeSkinRatio(scaledBitmap)

        // 3. Run ONNX Model Inference if loaded
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

                val pornProb = probMap["porn"] ?: 0f
                val hentaiProb = probMap["hentai"] ?: 0f
                val sexyProb = probMap["sexy"] ?: 0f

                val drawingsProb = probMap["drawings"] ?: 0f
                // Hentai (animated porn) only applies if the content is actually an animated cartoon/drawing.
                // Real-life human videos (vlogs, tech, cooking, news, lectures) are never polluted by hentai background noise.
                val isCartoon = drawingsProb > 0.40f
                val hentaiEffective = if (isCartoon && hentaiProb > 0.25f) {
                    hentaiProb * 0.80f
                } else {
                    0f
                }

                // Prohibited score: sensitive to provocative clothing, romantic intimacy, swimwear, adult content
                var score = pornProb + hentaiEffective + (sexyProb * 1.15f)

                // Intimacy / swimwear boost: ONLY when BOTH sexy/porn is elevated AND high exposed skin (swimwear, romantic intimacy)
                if ((sexyProb > 0.30f || pornProb > 0.15f) && skinRatio > 0.20f) {
                    score += (skinRatio * 0.35f)
                }

                prohibitedScore = score.coerceIn(0f, 1f)
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
            // 2 consecutive safe frames clears the shield
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

    @Synchronized
    private fun createInputTensor(bitmap: Bitmap): OnnxTensor {
        reusableFloatBuffer.clear()
        bitmap.getPixels(reusablePixels, 0, targetSize, 0, 0, targetSize, targetSize)

        // CHW Format with raw [0.0f, 1.0f] floats
        // Note: The ONNX model includes built-in /transforms/Sub and /transforms/Div for ImageNet normalization
        val total = targetSize * targetSize

        // Red channel
        for (i in 0 until total) {
            val r = ((reusablePixels[i] shr 16) and 0xFF) / 255.0f
            reusableFloatBuffer.put(r)
        }
        // Green channel
        for (i in 0 until total) {
            val g = ((reusablePixels[i] shr 8) and 0xFF) / 255.0f
            reusableFloatBuffer.put(g)
        }
        // Blue channel
        for (i in 0 until total) {
            val b = (reusablePixels[i] and 0xFF) / 255.0f
            reusableFloatBuffer.put(b)
        }
        reusableFloatBuffer.rewind()

        // Rank 3 tensor: (3, 224, 224)
        return OnnxTensor.createTensor(
            ortEnv,
            reusableFloatBuffer,
            longArrayOf(3, targetSize.toLong(), targetSize.toLong())
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
     * Detects if the current frame is predominantly our own crimson red shield overlay.
     * Prevents the detector from getting trapped in an infinite loop analyzing its own overlay.
     */
    private fun isShieldOverlayPresent(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var overlayPixelCount = 0
        val step = 8 // Fast subsampling
        var sampled = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            // Deep crimson red overlay: R around 195, G and B around 15
            if (r > 130 && g < 45 && b < 45) {
                overlayPixelCount++
            }
            sampled++
            i += step
        }

        // If more than 30% of pixels match synthetic crimson red, it is our overlay
        return sampled > 0 && (overlayPixelCount.toFloat() / sampled) > 0.30f
    }

    /**
     * Analyzes skin tone exposure ratio using combined RGB and YCbCr color spaces.
     * Rejects synthetic red overlays, warm UI themes, and non-skin colors.
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

            // 1. Strict RGB skin boundary check (rejects synthetic red where g<35, b<20 or r-g > 95)
            val isRgbSkin = r > 80 && g > 35 && b > 20 &&
                    r > g && r > b &&
                    (r - g) in 15..95 &&
                    (r - b) > 15

            if (isRgbSkin) {
                // 2. Standard human skin color bounding box in YCbCr space
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
                val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b

                if (y in 60f..250f && cb in 80f..130f && cr in 135f..175f) {
                    skinPixelCount++
                }
            }
            sampled++
            i += step
        }

        return if (sampled > 0) skinPixelCount.toFloat() / sampled else 0f
    }

    private fun fallbackHeuristicResult(skinRatio: Float, sensitivity: SensitivityLevel): VisualDetectionResult {
        val threshold = if (sensitivity == SensitivityLevel.STRICT) 0.35f else 0.45f
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
        } catch (_: Exception) {}
    }
}
