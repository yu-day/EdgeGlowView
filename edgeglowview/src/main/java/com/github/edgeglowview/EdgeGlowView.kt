package com.yuday.up.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
  * Full-screen edge breathing glow + two-way carousel comet effect View
  *
  * Usage: Place at the top level of the root layout, match_parent, clickable=false (set by default)
  *
  * Interfaces:
  * startGlow() Start the animation
  * stopGlow() Stop and hide
  * glowColor = 0xFF... Dynamically change the color (blue during connection, green on success, red on failure)
  * dualComet = true/false Toggle the dual glow points
  * startFromBottom = true The glow points start from the bottom and converge at the top
 */
class EdgeGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var glowColor: Int = Color.parseColor("#378ADD")
        set(value) {
            field = value
            // The cometShader is only non-empty after onSizeChanged; setting it beforehand has no side effects.
            rebuildShader()
            invalidate()
        }

    var cornerRadius: Float = 0f
    var dualComet: Boolean = true

    /** true = Starts from the bottom and converges at the top; false = Starts from the top and converges at the bottom */
    var startFromBottom: Boolean = false

    private var isAnimating = false
    private var anchorOffset = 0f   // Distance value of the starting point on the path (midpoint of top or bottom)

    // ---- Breathing Halo ----
    private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintMid   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rectOuter = RectF()
    private val rectMid   = RectF()
    private val rectInner = RectF()

    private var breathProgress = 0f
    private var breathAnimator: ValueAnimator? = null

    // ---- Comet ----
    private val paintComet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style    = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val borderPath        = Path()
    private val pathMeasure       = PathMeasure()
    private var borderPathLength  = 0f

    private val cometPath          = Path()
    private val cometSecondSegment = Path()  // Reuse to avoid allocating per frame

    private var cometProgress = 0f
    private var cometAnimator: ValueAnimator? = null

    /** The proportion of each comet's length to the total path circumference */
    private val cometLengthRatio = 0.18f

    // Shader reuse
    private var cometShader: LinearGradient? = null
    private val shaderMatrix = Matrix()

    // Used for sampling path location (reused to avoid allocation every time getPosTan is called).
    private val headPos = FloatArray(2)
    private val tailPos = FloatArray(2)

    init {
        isClickable = false
        isFocusable = false
    }

    // -------------------------------------------------------------------------
    // Public Interface
    // -------------------------------------------------------------------------

    fun startGlow() {
        if (isAnimating) return
        isAnimating = true
        visibility = VISIBLE

        rebuildShader()

        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration     = 2200L
            repeatCount  = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                breathProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        cometAnimator?.cancel()
        // ofFloat(0f, 0.5f)：The light spots travel half a circle and then meet on the opposite side. After restarting, start again from the starting point.
        cometAnimator = ValueAnimator.ofFloat(0f, 0.5f).apply {
            duration     = 1800L       // In half a lap, the two objects visually converge once every 1.8 seconds.
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                cometProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopGlow() {
        isAnimating = false
        breathAnimator?.cancel()
        cometAnimator?.cancel()
        breathAnimator = null
        cometAnimator  = null
        visibility = GONE
    }

    // -------------------------------------------------------------------------
    // Size calculation
    // -------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        if (cornerRadius == 0f) {
            cornerRadius = minOf(w, h) * 0.05f
        }

        val padding    = 4f
        val outerStroke = 8f
        val midStroke   = 5f
        val innerStroke = 2.5f

        rectOuter.set(
            padding + outerStroke / 2f,
            padding + outerStroke / 2f,
            w - padding - outerStroke / 2f,
            h - padding - outerStroke / 2f
        )
        rectMid.set(
            rectOuter.left   + outerStroke / 2f + midStroke / 2f,
            rectOuter.top    + outerStroke / 2f + midStroke / 2f,
            rectOuter.right  - outerStroke / 2f - midStroke / 2f,
            rectOuter.bottom - outerStroke / 2f - midStroke / 2f
        )
        rectInner.set(
            rectMid.left   + midStroke / 2f + innerStroke / 2f,
            rectMid.top    + midStroke / 2f + innerStroke / 2f,
            rectMid.right  - midStroke / 2f - innerStroke / 2f,
            rectMid.bottom - midStroke / 2f - innerStroke / 2f
        )

        paintOuter.strokeWidth = outerStroke
        paintMid.strokeWidth   = midStroke
        paintInner.strokeWidth = innerStroke
        paintComet.strokeWidth = innerStroke + 3.5f

        val cr = maxOf(0f, cornerRadius - 10f)
        borderPath.reset()
        borderPath.addRoundRect(rectInner, cr, cr, Path.Direction.CW)
        pathMeasure.setPath(borderPath, true)
        borderPathLength = pathMeasure.length

        anchorOffset = if (startFromBottom) {
            findEdgeMidOffset(w, h, bottom = true)
        } else {
            findEdgeMidOffset(w, h, bottom = false)
        }

        rebuildShader()
    }

	/**
	* Sample the path and find the path distance value closest to the top/bottom horizontal midpoint
	* bottom=false → top midpoint; bottom=true → bottom midpoint
	*/
    private fun findEdgeMidOffset(viewWidth: Int, viewHeight: Int, bottom: Boolean): Float {
        if (borderPathLength <= 0f) return 0f

        val targetX = viewWidth / 2f
        // Top: smaller y is better; Bottom: larger y is better
        var bestDist = 0f
        var bestScore = Float.MAX_VALUE
        val pos = FloatArray(2)

        val sampleCount = 200
        repeat(sampleCount) { i ->
            val d = borderPathLength * i / sampleCount
            pathMeasure.getPosTan(d, pos, null)
            val score = if (bottom) {
                Math.abs(pos[0] - targetX) + (viewHeight - pos[1])   // The larger y is, the lower the score
            } else {
                Math.abs(pos[0] - targetX) + pos[1]                  // The smaller y is, the lower the score
            }
            if (score < bestScore) {
                bestScore = score
                bestDist  = d
            }
        }
        return bestDist
    }

    // -------------------------------------------------------------------------
    // draw
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAnimating || borderPathLength <= 0f) return

        drawBreathGlow(canvas)
        drawComet(canvas, cometProgress, reverse = false)
        if (dualComet) {
            drawComet(canvas, cometProgress, reverse = true)
        }
    }

    private fun drawBreathGlow(canvas: Canvas) {
        val eased = ((Math.sin(breathProgress * Math.PI - Math.PI / 2) + 1) / 2).toFloat()

        paintOuter.color      = applyAlpha(glowColor, lerp(20,  70,  eased))
        paintMid.color        = applyAlpha(glowColor, lerp(50,  140, eased))
        paintInner.color      = applyAlpha(glowColor, lerp(100, 180, eased))
        paintOuter.strokeWidth = 8f + lerp(0, 4, eased)

        canvas.drawRoundRect(rectOuter, cornerRadius,                cornerRadius,                paintOuter)
        canvas.drawRoundRect(rectMid,   maxOf(0f, cornerRadius - 6f),  maxOf(0f, cornerRadius - 6f),  paintMid)
        canvas.drawRoundRect(rectInner, maxOf(0f, cornerRadius - 10f), maxOf(0f, cornerRadius - 10f), paintInner)
    }

    private fun drawComet(canvas: Canvas, progress: Float, reverse: Boolean) {
        val cometLength = borderPathLength * cometLengthRatio

        // Starting from anchorOffset, increment forward / decrement backward.
        val traveled = progress * borderPathLength
        val rawHead = if (reverse) anchorOffset - traveled else anchorOffset + traveled

        // Normalize to [0, borderPathLength)
        fun norm(v: Float) = ((v % borderPathLength) + borderPathLength) % borderPathLength

        val headDist = norm(rawHead)
        // Tail: The head moves backward in the direction it came from. (CometLength)
        val tailDist = if (reverse) norm(headDist + cometLength) else norm(headDist - cometLength)

        // ---- Extract path fragments ----
        cometPath.reset()

		// Determine if the path's 0/max boundary has been crossed
		// If not crossed, the distance between headDist and tailDist ≈ cometLength; if crossed, the distance ≈ borderPathLength - cometLength
        val distBetween = Math.abs(headDist - tailDist)
        val isWrapped   = distBetween > borderPathLength * (1f - cometLengthRatio * 0.9f)

        val segStart = minOf(headDist, tailDist)
        val segEnd   = maxOf(headDist, tailDist)

        if (!isWrapped) {
            pathMeasure.getSegment(segStart, segEnd, cometPath, true)
        } else {
            // Crossing boundaries: Take [segEnd, pathLength] + [0, segStart]
            pathMeasure.getSegment(segEnd, borderPathLength, cometPath, true)
            cometSecondSegment.reset()
            pathMeasure.getSegment(0f, segStart, cometSecondSegment, true)
            cometPath.addPath(cometSecondSegment)
        }

        // ---- Calculate the gradient direction: from the tail (transparent) to the head (highlighted). ----
        pathMeasure.getPosTan(headDist, headPos, null)
        pathMeasure.getPosTan(tailDist, tailPos, null)

        val dx  = headPos[0] - tailPos[0]
        val dy  = headPos[1] - tailPos[1]
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (len < 1f) return   // The beginning and end almost overlap, skip this frame.

        // Map the unit gradient shader to the comet's physical region using Matrix.
        shaderMatrix.reset()
        shaderMatrix.setScale(len, 1f)
        shaderMatrix.postRotate(
            Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat(),
            0f, 0f
        )
        shaderMatrix.postTranslate(tailPos[0], tailPos[1])
        cometShader?.setLocalMatrix(shaderMatrix)

        canvas.drawPath(cometPath, paintComet)
    }

    // -------------------------------------------------------------------------
    // Tools and methods
    // -------------------------------------------------------------------------

    private fun rebuildShader() {
        // Basic Shader: Horizontally from transparent to white, length = 1, subsequent stretching and rotation are achieved using Matrix.
        cometShader = LinearGradient(
            0f, 0f, 1f, 0f,
            intArrayOf(applyAlpha(glowColor, 0), Color.WHITE),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paintComet.shader = cometShader
    }

    /** Replaces the alpha value of color with the specified value (bitwise operation, no overhead of calling Color.argb) */
    private fun applyAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun lerp(start: Int, end: Int, t: Float): Int =
        (start + (end - start) * t).toInt()

    private fun lerp(start: Int, end: Int, t: Float, dummy: Unit = Unit): Float =
        start + (end - start) * t

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopGlow()
    }
}
