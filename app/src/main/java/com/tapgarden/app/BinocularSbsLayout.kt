package com.tapgarden.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Side-by-side binocular compositor (ported from TapLinkX3 / TapInsight).
 *
 * The first child is treated as a single logical viewport, measured to half the
 * physical width, then drawn twice (left eye + right eye) so RayNeo X3 Pro shows
 * one logical scene in both lenses. Touch/generic-motion events arriving on the
 * right half are remapped onto the logical child.
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cursorView = CursorView(context)
    private var webView: WebView? = null
    var logicalClickHandler: ((Float, Float) -> Boolean)? = null
    var edgePanHandler: ((Int, Int) -> Unit)? = null
    var edgePanStopHandler: (() -> Unit)? = null
    var menuActionHandler: ((String) -> Unit)? = null
    private var cursorX = 320f
    private var cursorY = 240f
    private var activeSide = Side.NONE
    private var lastInputX = 0f
    private var lastInputY = 0f
    private var downInputX = 0f
    private var downInputY = 0f
    private var downCursorX = 0f
    private var downCursorY = 0f
    private var leftVolumeStartY = 0f
    private var leftVolumeStart = 0
    private var draggingPage = false
    private var pageDragDownTime = 0L
    private var pageDragX = 0f
    private var pageDragY = 0f
    private var currentInputUsesMirroredCoordinates = false
    private var edgeScrollDx = 0
    private var edgeScrollDy = 0
    private var edgeScrollActive = false
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            val target = webView
            if (!edgeScrollActive || target == null) return
            if (edgeScrollDx != 0 || edgeScrollDy != 0) {
                edgePanHandler?.invoke(edgeScrollDx, edgeScrollDy)
                postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
            } else {
                stopEdgeScroll()
            }
        }
    }

    private enum class Side { NONE, LEFT_VOLUME, RIGHT_CURSOR }

    init {
        clipChildren = false
        clipToPadding = false
        addView(cursorView)
    }

    fun attachWebView(view: WebView) {
        webView = view
        if (view.parent !== this) addView(view, 0)
        cursorView.bringToFront()
        post {
            val logicalWidth = logicalViewportWidth(width).coerceAtLeast(1)
            cursorX = logicalWidth * 0.5f
            cursorY = height * 0.5f
            updateCursor()
        }
    }

    fun setWebViewTarget(view: WebView) {
        webView = view
        cursorView.bringToFront()
        post {
            val logicalWidth = logicalViewportWidth(width).coerceAtLeast(1)
            cursorX = logicalWidth * 0.5f
            cursorY = height * 0.5f
            updateCursor()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return
        val drawTime = drawingTime
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
    }

    private fun drawLogicalChildren(canvas: Canvas, drawTime: Long) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) drawChild(canvas, child, drawTime)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)
        return handleGlassesInput(ev, logicalWidth)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchGenericMotionEvent(event)
        val isMouseLike = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (!isMouseLike) return super.dispatchGenericMotionEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                activeSide = Side.RIGHT_CURSOR
                cursorView.visibility = View.VISIBLE
                updateCursor()
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                    .takeIf { it != 0f } ?: 0f
                val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    .takeIf { it != 0f } ?: 0f
                if (dx != 0f || dy != 0f) {
                    moveCursor(dx, dy, logicalWidth)
                    return true
                }
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                clickAtCursor(event.eventTime)
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_CANCEL -> {
                activeSide = Side.NONE
                draggingPage = false
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleGlassesInput(event: MotionEvent, logicalWidth: Int): Boolean {
        val rawX = event.getX(0)
        val rawY = event.getY(0)
        val localX =
            if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) {
                rawX - logicalWidth
            } else {
                rawX
            }
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_BUTTON_PRESS -> {
                activeSide = classifyInputSide(event, logicalWidth)
                currentInputUsesMirroredCoordinates = isUnknownMirroredCoordinateEvent(event, logicalWidth)
                val startX =
                    if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) {
                        rawX - logicalWidth
                    } else {
                        rawX
                    }
                lastInputX = startX
                lastInputY = rawY
                downInputX = startX
                downInputY = rawY
                downCursorX = cursorX
                downCursorY = cursorY
                if (activeSide == Side.LEFT_VOLUME) {
                    leftVolumeStartY = rawY
                    leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                cursorView.visibility = View.VISIBLE
                updateCursor()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (activeSide == Side.LEFT_VOLUME) {
                    adjustVolume(rawY)
                    return true
                }
                if (activeSide == Side.NONE) {
                    activeSide = classifyInputSide(event, logicalWidth)
                    if (activeSide == Side.LEFT_VOLUME) {
                        leftVolumeStartY = rawY
                        leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        adjustVolume(rawY)
                        return true
                    }
                }
                val dx = localX - lastInputX
                val dy = rawY - lastInputY
                if (abs(dx) < 0.35f && abs(dy) < 0.35f) return true
                moveCursor(dx, dy, logicalWidth)
                lastInputX = localX
                lastInputY = rawY
                updateEdgeScroll(logicalWidth)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (activeSide == Side.LEFT_VOLUME) {
                    activeSide = Side.NONE
                    return true
                }
                val moved = abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop
                if (draggingPage) {
                    sendPagePointer(MotionEvent.ACTION_UP, event.eventTime, pageDragDownTime)
                } else if (!moved && action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_HOVER_EXIT) {
                    if (logicalClickHandler?.invoke(cursorX, cursorY) != true) {
                        clickAtCursor(event.eventTime)
                    }
                }
                currentInputUsesMirroredCoordinates = false
                draggingPage = false
                stopEdgeScroll()
                activeSide = Side.NONE
                return true
            }
        }
        return true
    }

    private fun classifyInputSide(event: MotionEvent, logicalWidth: Int): Side {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()

        return when {
            // Matches TapInsight/TapBrowser: cyttsp6_mt is the left-arm volume pad.
            name.contains("cyttsp6", ignoreCase = true) -> Side.LEFT_VOLUME
            // Matches TapInsight/TapBrowser: cyttsp5_mt is the right-arm temple pad.
            name.contains("cyttsp5", ignoreCase = true) -> Side.RIGHT_CURSOR
            event.isFromSource(InputDevice.SOURCE_MOUSE) -> Side.RIGHT_CURSOR
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE -> Side.RIGHT_CURSOR
            else -> if (event.getX(0) < logicalWidth) Side.LEFT_VOLUME else Side.RIGHT_CURSOR
        }
    }

    private fun isUnknownMirroredCoordinateEvent(event: MotionEvent, logicalWidth: Int): Boolean {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        if (name.contains("cyttsp5", ignoreCase = true) ||
            name.contains("cyttsp6", ignoreCase = true)) {
            return false
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            return false
        }
        return event.getX(0) >= logicalWidth
    }

    private fun adjustVolume(rawY: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = ((leftVolumeStartY - rawY) / 34f).toInt()
        val next = (leftVolumeStart + steps).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    }

    private fun moveCursor(dx: Float, dy: Float, logicalWidth: Int) {
        val sensitivity = 0.86f
        cursorX = (cursorX + dx * sensitivity).coerceIn(1f, logicalWidth - 1f)
        cursorY = (cursorY + dy * sensitivity).coerceIn(1f, height - 1f)
        updateCursor()
    }

    private fun updateCursor() {
        cursorView.cursorX = cursorX
        cursorView.cursorY = cursorY
        cursorView.invalidate()
        invalidate()
    }

    private fun updateEdgeScroll(logicalWidth: Int) {
        val edge = EDGE_SCROLL_BAND_PX
        val maxX = logicalWidth.toFloat()
        val maxY = height.toFloat()
        val leftStrength = ((edge - cursorX) / edge).coerceIn(0f, 1f)
        val rightStrength = ((cursorX - (maxX - edge)) / edge).coerceIn(0f, 1f)
        val upStrength = ((edge - cursorY) / edge).coerceIn(0f, 1f)
        val downStrength = ((cursorY - (maxY - edge)) / edge).coerceIn(0f, 1f)

        edgeScrollDx = ((rightStrength - leftStrength) * EDGE_SCROLL_MAX_STEP_PX).toInt()
        edgeScrollDy = ((downStrength - upStrength) * EDGE_SCROLL_MAX_STEP_PX).toInt()

        if (edgeScrollDx == 0 && edgeScrollDy == 0) {
            stopEdgeScroll()
            return
        }
        if (!edgeScrollActive) {
            edgeScrollActive = true
            removeCallbacks(edgeScrollRunnable)
            post(edgeScrollRunnable)
        }
    }

    private fun stopEdgeScroll() {
        if (edgeScrollActive) {
            edgePanStopHandler?.invoke()
        }
        edgeScrollActive = false
        edgeScrollDx = 0
        edgeScrollDy = 0
        removeCallbacks(edgeScrollRunnable)
    }

    private fun clickAtCursor(eventTime: Long) {
        val downTime = eventTime
        sendPagePointer(MotionEvent.ACTION_DOWN, eventTime, downTime)
        postDelayed({ sendPagePointer(MotionEvent.ACTION_UP, eventTime + 48L, downTime) }, 48L)
    }

    private fun maybeDragPage(event: MotionEvent, logicalWidth: Int, rawDx: Float, rawDy: Float) {
        val edge = 28f
        val nearEdge = cursorX <= edge || cursorX >= logicalWidth - edge || cursorY <= edge || cursorY >= height - edge
        val moving = abs(cursorX - downCursorX) > touchSlop ||
            abs(cursorY - downCursorY) > touchSlop ||
            abs(rawDx) > 0.5f ||
            abs(rawDy) > 0.5f
        if (!nearEdge || !moving) return
        if (!draggingPage) {
            draggingPage = true
            pageDragDownTime = event.eventTime
            pageDragX = cursorX.coerceIn(2f, logicalWidth - 2f)
            pageDragY = cursorY.coerceIn(2f, height - 2f)
            sendPagePointer(MotionEvent.ACTION_DOWN, event.eventTime, pageDragDownTime)
        }
        pageDragX = (pageDragX + rawDx * 0.95f).coerceIn(2f, logicalWidth - 2f)
        pageDragY = (pageDragY + rawDy * 0.95f).coerceIn(2f, height - 2f)
        sendPagePointer(MotionEvent.ACTION_MOVE, event.eventTime, pageDragDownTime)
    }

    private fun sendPagePointer(action: Int, eventTime: Long, downTime: Long) {
        val target = webView ?: return
        val ev = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            if (draggingPage) pageDragX else cursorX,
            if (draggingPage) pageDragY else cursorY,
            0
        )
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    companion object {
        private const val EDGE_SCROLL_BAND_PX = 44f
        private const val EDGE_SCROLL_MAX_STEP_PX = 22f
        private const val EDGE_SCROLL_INTERVAL_MS = 33L
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate() // both halves must redraw when logical content changes
    }

    private fun logicalViewportWidth(totalWidth: Int): Int = (totalWidth / 2).coerceAtLeast(0)

    private class CursorView(context: Context) : View(context) {
        var cursorX = 320f
        var cursorY = 240f
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.BLACK
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.WHITE
        }

        init {
            visibility = VISIBLE
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(cursorX, cursorY, 7f, outer)
            canvas.drawCircle(cursorX, cursorY, 7f, inner)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, outer)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, outer)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, inner)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, inner)
        }
    }

}
