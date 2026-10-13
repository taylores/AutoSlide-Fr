package com.ltx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 坐标拾取器：以全屏悬浮窗显示可拖动的“半透明中心点 + 十字准心”，并附带底部操作提示与三按钮。
 *
 * 坐标对齐要点：无障碍手势 dispatchGesture 使用“真实屏幕坐标（含状态栏/导航栏）”。
 * 浮窗在部分机型上处于应用区坐标系（不含状态栏），二者相差一个状态栏高度。
 * 本类通过对比浮窗实际高度与真实屏幕高度，自动判定并补偿状态栏高度，
 * 使拾取坐标（真实屏幕百分比）与执行时的 dispatchGesture 完全一致。
 *
 * 底部按钮框：由系统 gravity 在可见区域内居中（底部）；触摸框内任意位置（含按钮上）皆可拖动整框
 * （纯增量跟随手指，不做绝对坐标换算），松手时若未发生拖动则视为点击命中按钮（手动 performClick）。
 */
object CoordinatePicker {
    private var windowManager: WindowManager? = null
    private var crossView: PickView? = null
    private var crossParams: WindowManager.LayoutParams? = null
    private var barView: View? = null

    fun start(
        context: Context,
        initXPct: Float = 50f,
        initYPct: Float = 50f,
        onPicked: (xPct: Float, yPct: Float) -> Unit,
        onCancel: () -> Unit
    ) {
        stop(context)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val density = metrics.density
        val radiusPx = 14f * density

        // 1) 准心层（全屏、透明、默认不可触摸，便于穿透操作桌面；尽量铺满真实屏幕）
        val pickView = PickView(context, screenW, screenH, initXPct, initYPct)
        val cp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        crossParams = cp
        wm.addView(pickView, cp)
        crossView = pickView

        // 2) 底部操作栏：按钮行 + 一行提示（半透明扁平化，可拖拽）
        val bar = DragBar(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            background = roundedBg(0x80545995.toInt(), 18f * density)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val pickBtn = makeFlatButton(context, "拾取坐标", 0xCC6C7BD6.toInt(), radiusPx, density)
        val okBtn = makeFlatButton(context, "确认", 0xCC43A047.toInt(), radiusPx, density)
        val cancelBtn = makeFlatButton(context, "取消", 0xCCEF5350.toInt(), radiusPx, density)
        row.addView(pickBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = (10 * density).toInt()
        })
        row.addView(okBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = (10 * density).toInt()
        })
        row.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val hint = TextView(context).apply {
            text = "①点击「拾取坐标」切换状态 ②拖动准心拾取坐标 ③点击「确认」保存"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
            setPadding(0, (6 * density).toInt(), 0, 0)
            // 防止提示过长导致整框超出屏幕
            maxWidth = screenW - (28 * density).toInt()
        }
        bar.addView(row)
        bar.addView(hint)

        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (screenH * 0.02).toInt()
        }
        wm.addView(bar, bp)
        barView = bar

        // 底部框拖拽：按下时事件先给按钮（保证可点击），移动超过阈值后由 DragBar 拦截转为拖动整框；
        // 松手时若未发生拖动则视为点击命中按钮（按钮自身正常触发 click）。
        var dStartX = 0f
        var dStartY = 0f
        var dOrgX = 0
        var dOrgY = 0
        var dMoved = false
        var pressedBtn: Button? = null
        val buttons = listOf(pickBtn, okBtn, cancelBtn)

        fun buttonAt(rawX: Float, rawY: Float): Button? {
            for (b in buttons) {
                val loc = IntArray(2)
                b.getLocationOnScreen(loc)
                if (rawX >= loc[0] && rawX <= loc[0] + b.width &&
                    rawY >= loc[1] && rawY <= loc[1] + b.height
                ) return b
            }
            return null
        }

        // 按下初始化（含按钮上的按下：由 DragBar 在 onInterceptTouchEvent 的 DOWN 阶段回调）
        bar.onDown = { e ->
            dStartX = e.rawX
            dStartY = e.rawY
            dMoved = false
            pressedBtn = buttonAt(e.rawX, e.rawY)
            dOrgX = bp.x
            dOrgY = bp.y
        }
        // 移动是否超过拖动阈值（超过则 DragBar 拦截，转为拖动）
        bar.shouldStartDrag = { e ->
            abs(e.rawX - dStartX) > 6 || abs(e.rawY - dStartY) > 6
        }

        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!dMoved && (abs(e.rawX - dStartX) > 6 || abs(e.rawY - dStartY) > 6)) {
                        dMoved = true
                    }
                    if (dMoved) {
                        // CENTER_HORIZONTAL：x 正向右移；BOTTOM：y 正向上移（故纵向取负增量）
                        bp.x = dOrgX + (e.rawX - dStartX).toInt()
                        bp.y = dOrgY - (e.rawY - dStartY).toInt()
                        wm.updateViewLayout(bar, bp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dMoved) pressedBtn?.performClick()
                    pressedBtn = null
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedBtn = null
                    true
                }
                else -> true
            }
        }

        // 拾取状态切换：再次点击“拾取坐标”退出拾取（恢复桌面操作），如此反复
        var picking = false
        pickBtn.setOnClickListener {
            if (crossParams == null || crossView == null) return@setOnClickListener
            picking = !picking
            pickView.setPicking(picking)
            crossParams!!.flags = if (picking) {
                crossParams!!.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                crossParams!!.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            wm.updateViewLayout(crossView, crossParams)
            pickBtn.text = if (picking) "退出拾取" else "拾取坐标"
        }
        okBtn.setOnClickListener {
            val xPct = pickView.xPct
            val yPct = pickView.yPct
            stop(context)
            onPicked(xPct, yPct)
        }
        cancelBtn.setOnClickListener {
            stop(context)
            onCancel()
        }
    }

    fun stop(context: Context) {
        windowManager?.let { wm ->
            crossView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            barView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        crossView = null
        barView = null
        crossParams = null
        windowManager = null
    }

    private fun statusBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    private fun roundedBg(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            shape = GradientDrawable.RECTANGLE
        }

    private fun makeFlatButton(
        context: Context,
        text: String,
        color: Int,
        radiusPx: Float,
        density: Float
    ): Button = Button(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding((14 * density).toInt(), (2 * density).toInt(), (14 * density).toInt(), (2 * density).toInt())
        minimumHeight = 0
        background = roundedBg(color, radiusPx)
        elevation = 0f
        stateListAnimator = null
    }

    /**
     * 可拖动的底部操作栏容器：
     * - 按下时事件正常分发给子按钮（保证按钮可点击）；
     * - 移动超过阈值后 onInterceptTouchEvent 返回 true 抢占事件（子按钮收到 CANCEL），
     *   后续 MOVE/UP 交给父容器的 onTouchListener 完成整框拖动/点击判定。
     */
    private class DragBar(context: Context) : LinearLayout(context) {
        /** 按下初始化回调（onInterceptTouchEvent 的 DOWN 阶段触发，含按钮上的按下） */
        var onDown: ((MotionEvent) -> Unit)? = null

        /** MOVE 阶段询问是否应转为拖动（返回 true 则拦截） */
        var shouldStartDrag: ((MotionEvent) -> Boolean)? = null

        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    onDown?.invoke(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && shouldStartDrag?.invoke(ev) == true) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }
    }

    private class PickView(
        context: Context,
        private val realW: Int,
        private val realH: Int,
        initXPct: Float,
        initYPct: Float
    ) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        // 存为“真实屏幕坐标”
        private var cx = (initXPct / 100f * realW).coerceIn(0f, realW.toFloat())
        private var cy = (initYPct / 100f * realH).coerceIn(0f, realH.toFloat())

        // 浮窗是否铺满状态栏：若未铺满，视图坐标系比真实坐标少一个状态栏高度（仅影响顶部偏移）
        private var viewTopOffset = 0
        private var offsetResolved = false

        private var picking = false

        val xPct: Float get() = (cx / realW * 100f).coerceIn(0f, 100f)
        val yPct: Float get() = (cy / realH * 100f).coerceIn(0f, 100f)

        init {
            setPicking(false)
        }

        private fun resolveOffset() {
            if (offsetResolved) return
            offsetResolved = true
            val sb = statusBarHeight(context)
            val gap = realH - height
            viewTopOffset = if (gap <= 4) 0 else sb
        }

        fun setPicking(picking: Boolean) {
            this.picking = picking
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            resolveOffset()
            val activeColor = if (picking) Color.RED else Color.BLUE
            val viewX = cx
            val viewY = cy - viewTopOffset

            // 十字准心（贯穿全屏，颜色随状态）
            linePaint.color = activeColor
            linePaint.alpha = 255
            canvas.drawLine(0f, viewY, realW.toFloat(), viewY, linePaint)
            canvas.drawLine(viewX, 0f, viewX, realH.toFloat(), linePaint)
            canvas.drawLine(viewX - 12f, viewY, viewX + 12f, viewY, linePaint)
            canvas.drawLine(viewX, viewY - 12f, viewX, viewY + 12f, linePaint)

            // 半透明中心点
            dotPaint.color = activeColor
            dotPaint.alpha = 70
            canvas.drawCircle(viewX, viewY, 34f, dotPaint)

            // 坐标文字：白色描边 + 蓝/红填充
            val label = "X: %.1f%%  Y: %.1f%%".format(xPct, yPct)
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = 5f
            textPaint.color = Color.WHITE
            canvas.drawText(label, viewX, viewY - 46f, textPaint)
            textPaint.style = Paint.Style.FILL
            textPaint.color = activeColor
            canvas.drawText(label, viewX, viewY - 46f, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            resolveOffset()
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    cx = event.x.coerceIn(0f, realW.toFloat())
                    cy = (event.y + viewTopOffset).coerceIn(0f, realH.toFloat())
                    invalidate()
                }
            }
            return true
        }
    }
}
