package com.example.mapasssist

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/** Bounded pinch-and-pan viewer used for expanded territory maps. */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val matrixValue = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var hasDragged = false
    private var scaling = false
    private var doubleTapTriggered = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cancelMotion()
                scaling = true
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                parent?.requestDisallowInterceptTouchEvent(true)
                return drawable != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // The focal point also moves when both fingers travel together.
                // Apply that movement before scaling so a pinch can pan naturally.
                val focusDx = detector.focusX - lastFocusX
                val focusDy = detector.focusY - lastFocusY
                translateX += focusDx
                translateY += focusDy
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                if (abs(focusDx) > 0.5f || abs(focusDy) > 0.5f) hasDragged = true
                val wantedZoom = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                if (wantedZoom == zoom) applyBoundsAndMatrix()
                else scaleAroundFocus(wantedZoom, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
            }
        }
    )

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (drawable == null) return false
                doubleTapTriggered = true
                animateZoom(if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM, event.x, event.y)
                return true
            }

            override fun onFling(
                downEvent: MotionEvent?,
                upEvent: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!hasDragged || zoom <= 1f || drawable == null) return false
                fling(velocityX, velocityY)
                return true
            }
        }
    )

    private val displayScale get() = baseScale * zoom

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetZoom()
    }

    private fun resetZoom() {
        val image = drawable ?: return
        if (width == 0 || height == 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        baseScale = min(width.toFloat() / image.intrinsicWidth, height.toFloat() / image.intrinsicHeight)
        zoom = 1f
        translateX = (width - image.intrinsicWidth * baseScale) / 2f
        translateY = (height - image.intrinsicHeight * baseScale) / 2f
        applyBoundsAndMatrix()
    }

    private fun scaleAroundFocus(targetZoom: Float, focusX: Float, focusY: Float) {
        val oldScale = displayScale
        zoom = targetZoom.coerceIn(1f, MAX_ZOOM)
        val newScale = displayScale
        translateX = focusX - (focusX - translateX) * newScale / oldScale
        translateY = focusY - (focusY - translateY) * newScale / oldScale
        applyBoundsAndMatrix()
    }

    private fun animateZoom(targetZoom: Float, focusX: Float, focusY: Float) {
        cancelMotion()
        val startZoom = zoom
        val startScale = displayScale
        val startX = translateX
        val startY = translateY
        val imageXAtFocus = (focusX - startX) / startScale
        val imageYAtFocus = (focusY - startY) / startScale
        zoomAnimator = ValueAnimator.ofFloat(startZoom, targetZoom.coerceIn(1f, MAX_ZOOM)).apply {
            duration = ZOOM_ANIMATION_MS
            addUpdateListener { animator ->
                zoom = animator.animatedValue as Float
                val scale = displayScale
                translateX = focusX - imageXAtFocus * scale
                translateY = focusY - imageYAtFocus * scale
                applyBoundsAndMatrix()
            }
            start()
        }
    }

    private fun fling(velocityX: Float, velocityY: Float) {
        val image = drawable ?: return
        val scale = displayScale
        val imageWidth = image.intrinsicWidth * scale
        val imageHeight = image.intrinsicHeight * scale
        val minX = if (imageWidth > width) (width - imageWidth).toInt() else translateX.toInt()
        val maxX = if (imageWidth > width) 0 else translateX.toInt()
        val minY = if (imageHeight > height) (height - imageHeight).toInt() else translateY.toInt()
        val maxY = if (imageHeight > height) 0 else translateY.toInt()
        scroller.fling(translateX.toInt(), translateY.toInt(), velocityX.toInt(), velocityY.toInt(), minX, maxX, minY, maxY)
        postInvalidateOnAnimation()
    }

    private fun cancelMotion() {
        if (!scroller.isFinished) scroller.forceFinished(true)
        zoomAnimator?.cancel()
        zoomAnimator = null
    }

    private fun applyBoundsAndMatrix() {
        val image = drawable ?: return
        val scale = displayScale
        val imageWidth = image.intrinsicWidth * scale
        val imageHeight = image.intrinsicHeight * scale
        translateX = if (imageWidth <= width) (width - imageWidth) / 2f else translateX.coerceIn(width - imageWidth, 0f)
        translateY = if (imageHeight <= height) (height - imageHeight) / 2f else translateY.coerceIn(height - imageHeight, 0f)
        matrixValue.setValues(floatArrayOf(scale, 0f, translateX, 0f, scale, translateY, 0f, 0f, 1f))
        imageMatrix = matrixValue
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // GestureDetector receives this first and identifies a double tap here.
                // Do not cancel the animation that double tap has just started.
                if (!doubleTapTriggered) cancelMotion()
                doubleTapTriggered = false
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                hasDragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0 && event.pointerCount == 1 && !scaling) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    val dx = x - lastX
                    val dy = y - lastY
                    if (hasDragged || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        hasDragged = true
                        translateX += dx
                        translateY += dy
                        applyBoundsAndMatrix()
                    }
                    lastX = x
                    lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                if (event.getPointerId(liftedIndex) == activePointerId) {
                    val replacement = if (liftedIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(replacement)
                    lastX = event.getX(replacement)
                    lastY = event.getY(replacement)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!hasDragged && !scaling) performClick()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            translateX = scroller.currX.toFloat()
            translateY = scroller.currY.toFloat()
            applyBoundsAndMatrix()
            postInvalidateOnAnimation()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val ZOOM_ANIMATION_MS = 240L
    }
}
