package com.js8call.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Custom view that displays a scrolling waterfall visualization of spectrum data.
 * Emulates the JS8Call waterfall display algorithm.
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var waterfallBitmap: Bitmap? = null
    private var waterfallCanvas: Canvas? = null
    private var tempBitmap: Bitmap? = null  // For double-buffering during scroll
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    // Waterfall parameters (matching JS8Call defaults)
    private var gain = 0        // Gain control (typically -50 to +50)
    private var zero = 0        // Zero offset control (typically 0-100)
    private var bpp = 1         // Bits per pixel (usually 1 for real-time)
    private var avg = 1         // Averaging factor

    // Drawing rate control (faster than JS8Call for smoother mobile display)
    private val drawHandler = Handler(Looper.getMainLooper())
    private val drawIntervalMs = 50L  // 20 FPS (JS8Call uses 100ms = 10 FPS)
    private var isDrawing = false
    private var pendingSpectrum: FloatArray? = null
    private val drawRunnable = object : Runnable {
        override fun run() {
            val spectrum = pendingSpectrum
            if (spectrum != null) {
                drawSpectrum(spectrum)
                pendingSpectrum = null
            }
            if (isDrawing) {
                drawHandler.postDelayed(this, drawIntervalMs)
            }
        }
    }

    // Color palette (256 colors, matching JS8Call style)
    private val colorPalette = IntArray(256)

    init {
        // Initialize color palette matching JS8Call
        initColorPalette()
    }

    private fun initColorPalette() {
        // JS8Call default palette: black -> blue -> cyan -> green -> yellow -> orange -> red
        for (i in 0 until 256) {
            val normalized = i / 255f

            colorPalette[i] = when {
                // Black -> Dark Blue (0.0 - 0.15)
                normalized < 0.15f -> {
                    val t = normalized / 0.15f
                    val r = 0
                    val g = 0
                    val b = (t * 128).toInt()
                    Color.rgb(r, g, b)
                }
                // Dark Blue -> Bright Blue (0.15 - 0.30)
                normalized < 0.30f -> {
                    val t = (normalized - 0.15f) / 0.15f
                    val r = 0
                    val g = 0
                    val b = (128 + t * 127).toInt()
                    Color.rgb(r, g, b)
                }
                // Bright Blue -> Cyan (0.30 - 0.45)
                normalized < 0.45f -> {
                    val t = (normalized - 0.30f) / 0.15f
                    val r = 0
                    val g = (t * 255).toInt()
                    val b = 255
                    Color.rgb(r, g, b)
                }
                // Cyan -> Green (0.45 - 0.60)
                normalized < 0.60f -> {
                    val t = (normalized - 0.45f) / 0.15f
                    val r = 0
                    val g = 255
                    val b = ((1 - t) * 255).toInt()
                    Color.rgb(r, g, b)
                }
                // Green -> Yellow (0.60 - 0.75)
                normalized < 0.75f -> {
                    val t = (normalized - 0.60f) / 0.15f
                    val r = (t * 255).toInt()
                    val g = 255
                    val b = 0
                    Color.rgb(r, g, b)
                }
                // Yellow -> Orange (0.75 - 0.85)
                normalized < 0.85f -> {
                    val t = (normalized - 0.75f) / 0.10f
                    val r = 255
                    val g = ((1 - t * 0.5) * 255).toInt()
                    val b = 0
                    Color.rgb(r, g, b)
                }
                // Orange -> Red (0.85 - 1.0)
                else -> {
                    val t = (normalized - 0.85f) / 0.15f
                    val r = 255
                    val g = ((1 - t) * 128).toInt()
                    val b = 0
                    Color.rgb(r, g, b)
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w > 0 && h > 0) {
            // Save the old bitmaps
            val oldWaterfall = waterfallBitmap
            val oldTemp = tempBitmap

            // Create new bitmaps for waterfall (double-buffered for scrolling)
            waterfallBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            tempBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            waterfallCanvas = Canvas(waterfallBitmap!!)

            // Fill with black background
            waterfallCanvas?.drawColor(Color.BLACK)

            // If we had an old bitmap, copy its content (scaled if size changed)
            if (oldWaterfall != null && !oldWaterfall.isRecycled) {
                val srcRect = Rect(0, 0, oldWaterfall.width, oldWaterfall.height)
                val dstRect = Rect(0, 0, w, h)
                waterfallCanvas?.drawBitmap(oldWaterfall, srcRect, dstRect, null)
                oldWaterfall.recycle()
            }

            // Clean up old temp bitmap
            oldTemp?.recycle()
        }
    }

    /**
     * Update the waterfall with new spectrum data.
     * Stores the data and schedules a draw at the fixed rate.
     *
     * @param bins Array of spectrum bin values (linear power or dB)
     * @param binHz Frequency width of each bin in Hz (not used in current implementation)
     * @param powerDb Average power in dB (not used in current implementation)
     */
    fun updateSpectrum(bins: FloatArray, binHz: Float, powerDb: Float) {
        if (bins.isEmpty()) return

        // Store the latest spectrum data (will be drawn at next timer tick)
        pendingSpectrum = bins.copyOf()

        // Start drawing timer if not already running
        if (!isDrawing) {
            isDrawing = true
            drawHandler.post(drawRunnable)
        }
    }

    /**
     * Actually draw the spectrum to the waterfall.
     * Called at fixed rate by the timer.
     */
    private fun drawSpectrum(bins: FloatArray) {
        val bitmap = waterfallBitmap ?: return
        val temp = tempBitmap ?: return
        val canvas = waterfallCanvas ?: return

        val width = bitmap.width
        val height = bitmap.height

        // Auto-detect usable spectrum range on first few frames
        if (drawCount < 10) {
            // Log raw spectrum sample on first frame
            if (drawCount == 0) {
                val sampleIndices = listOf(0, bins.size / 4, bins.size / 2, 3 * bins.size / 4, bins.size - 1)
                val samples = sampleIndices.map { "bins[$it]=${bins[it]}" }.joinToString(", ")
                android.util.Log.d("WaterfallView", "Raw spectrum sample: $samples")
                android.util.Log.d("WaterfallView", "Spectrum size: ${bins.size}, min=${bins.minOrNull()}, max=${bins.maxOrNull()}, avg=${bins.average()}")
            }

            // Check if left half is significantly different from right half
            val mid = bins.size / 2
            val leftHalf = bins.slice(0 until mid)
            val rightHalf = bins.slice(mid until bins.size)
            val leftAvg = leftHalf.average()
            val rightAvg = rightHalf.average()

            // If right half has much lower average, skip it (likely empty/noise)
            // Keep the half with actual signal data (higher average)
            if (rightAvg * 10 < leftAvg) {
                if (startBin == 0 && endBin == -1) {
                    android.util.Log.d("WaterfallView", "Right half is mostly noise: leftAvg=$leftAvg, rightAvg=$rightAvg. Using left half only.")
                    endBin = mid  // Only draw bins 0 to mid-1
                }
            } else if (leftAvg * 10 < rightAvg) {
                if (startBin == 0) {
                    android.util.Log.d("WaterfallView", "Left half is mostly noise: leftAvg=$leftAvg, rightAvg=$rightAvg. Using right half only.")
                    startBin = mid
                }
            }
            drawCount++
        }

        // Step 1: Copy current bitmap to temp, shifted down by 1 pixel
        val tempCanvas = Canvas(temp)
        tempCanvas.drawColor(Color.BLACK)  // Clear temp
        val srcRect = Rect(0, 0, width, height - 1)  // Copy all but bottom row
        val dstRect = Rect(0, 1, width, height)      // Paste starting at row 1
        tempCanvas.drawBitmap(bitmap, srcRect, dstRect, null)

        // Step 2: Copy temp back to main bitmap
        canvas.drawColor(Color.BLACK)  // Clear main
        canvas.drawBitmap(temp, 0f, 0f, null)

        // Step 3: Draw new spectrum line at the top (y=0)
        // Only draw from startBin to endBin (skip DC offset or bad data)
        val actualEndBin = if (endBin > 0) endBin else bins.size
        val usableBins = actualEndBin - startBin
        val pixelsPerBin = width.toFloat() / usableBins

        // Track statistics for this frame
        var minDb = Float.MAX_VALUE
        var maxDb = Float.MIN_VALUE
        var avgDb = 0f
        var colorCount = IntArray(256)

        for (i in startBin until actualEndBin) {
            val linearValue = bins[i]

            // Convert linear power to dB
            // Handle very small values to avoid log(0)
            val dbValue = if (linearValue > 1e-10f) {
                10f * kotlin.math.ln(linearValue) / kotlin.math.ln(10f)
            } else {
                -100f  // Noise floor
            }

            // Track statistics
            if (dbValue > maxDb) maxDb = dbValue
            if (dbValue < minDb) minDb = dbValue
            avgDb += dbValue

            // Map dB range to color index using auto-ranging
            val normalized = ((dbValue - autoDbMin) / (autoDbMax - autoDbMin)).coerceIn(0f, 1f)
            val colorIndex = (normalized * 254).toInt().coerceIn(0, 254)
            val color = colorPalette[colorIndex]

            colorCount[colorIndex]++

            // Draw pixel(s) for this bin at y=0 (top row)
            // Map bin index to screen x position
            paint.color = color
            val binIndex = i - startBin
            val x1 = (binIndex * pixelsPerBin).toInt()
            val x2 = ((binIndex + 1) * pixelsPerBin).toInt().coerceAtMost(width)

            if (x2 > x1) {
                canvas.drawRect(x1.toFloat(), 0f, x2.toFloat(), 1f, paint)
            }
        }

        avgDb /= usableBins

        // Update auto-ranging based on actual signal levels
        // Use smoothed min/max over history to avoid rapid changes
        dbMinHistory.add(minDb)
        dbMaxHistory.add(maxDb)
        if (dbMinHistory.size > historySize) dbMinHistory.removeAt(0)
        if (dbMaxHistory.size > historySize) dbMaxHistory.removeAt(0)

        // Calculate smoothed range (use 10th percentile for min, 90th percentile for max)
        val sortedMin = dbMinHistory.sorted()
        val sortedMax = dbMaxHistory.sorted()
        val smoothedMin = sortedMin[sortedMin.size / 10]  // 10th percentile
        val smoothedMax = sortedMax[(sortedMax.size * 9) / 10]  // 90th percentile

        // Update auto range with some headroom
        autoDbMin = (smoothedMin - 10f).coerceAtLeast(-100f)  // At least -100 dB
        autoDbMax = (smoothedMax + 10f).coerceAtMost(100f)    // At most +100 dB

        // Ensure minimum dynamic range of 30 dB
        if (autoDbMax - autoDbMin < 30f) {
            val center = (autoDbMin + autoDbMax) / 2f
            autoDbMin = center - 15f
            autoDbMax = center + 15f
        }

        // Log statistics every 30 frames (~1.5 seconds at 20 FPS)
        if (drawCount % 30 == 0) {
            // Find color distribution
            val significantColors = mutableListOf<Pair<Int, Int>>()
            for (i in colorCount.indices) {
                if (colorCount[i] > 10) {  // More than 10 bins with this color
                    significantColors.add(Pair(i, colorCount[i]))
                }
            }

            android.util.Log.d("WaterfallView",
                "Spectrum stats: dB range=[%.1f, %.1f], avg=%.1f dB, usableBins=%d/%d, range=[%d, %d), autoRange=[%.1f, %.1f]"
                .format(minDb, maxDb, avgDb, usableBins, bins.size, startBin, actualEndBin, autoDbMin, autoDbMax))

            if (significantColors.isNotEmpty()) {
                android.util.Log.d("WaterfallView",
                    "Color distribution: ${significantColors.take(5).joinToString { "(idx=${it.first}, cnt=${it.second})" }}")
            }
        }

        // Request redraw
        postInvalidate()
    }

    private var drawCount = 0
    private var startBin = 0  // First bin to display (skip DC offset if needed)
    private var endBin = -1   // Last bin to display (-1 means use all bins)

    // Auto-ranging for dB scale
    private var autoDbMin = -100f
    private var autoDbMax = 20f
    private val dbMinHistory = mutableListOf<Float>()
    private val dbMaxHistory = mutableListOf<Float>()
    private val historySize = 20  // Track last 20 frames for smoothing

    /**
     * Set waterfall gain control (matching JS8Call).
     * Typical range: -50 to +50
     */
    fun setGain(gain: Int) {
        this.gain = gain
    }

    /**
     * Set waterfall zero level control (matching JS8Call).
     * Typical range: 0 to 100
     */
    fun setZero(zero: Int) {
        this.zero = zero
    }

    /**
     * Clear the waterfall display.
     */
    fun clear() {
        // Stop drawing timer
        isDrawing = false
        drawHandler.removeCallbacks(drawRunnable)
        pendingSpectrum = null
        drawCount = 0  // Reset for next session
        startBin = 0   // Reset bin range
        endBin = -1    // Reset bin range

        // Reset auto-ranging
        autoDbMin = -100f
        autoDbMax = 20f
        dbMinHistory.clear()
        dbMaxHistory.clear()

        // Clear the display
        waterfallCanvas?.drawColor(Color.BLACK)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bitmap = waterfallBitmap ?: return

        // Draw the waterfall bitmap scaled to fill the view
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        // Stop drawing timer
        isDrawing = false
        drawHandler.removeCallbacks(drawRunnable)

        // Clean up resources
        waterfallBitmap?.recycle()
        waterfallBitmap = null
        tempBitmap?.recycle()
        tempBitmap = null
        waterfallCanvas = null
        pendingSpectrum = null
    }
}
