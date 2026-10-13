package com.ltx.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.ltx.R

/**
 * 屏幕文字检查悬浮按钮服务（业务场景：调试/采集元素坐标）：
 * 在目标 App（如淘宝）之上显示一个“TVT”悬浮按钮，
 * 轻点即抓取当前前台窗口的文字节点树，供用户查看页面结构与坐标，用于配置“点击文字”等动作；
 * 目标 App 始终为前台活动窗口，本服务不会把它切到后台。
 * 拖动按钮可移动位置（避免遮挡页面元素）；长按 2 秒以上才移除浮窗并停止本服务（2 秒内拖动不算关闭，防止误触）。
 */
class InspectFloatingService : Service() {

    // 悬浮按钮控件实例，销毁时需据此从窗口管理器移除
    private var btn: Button? = null

    // 系统窗口管理器，负责悬浮窗的添加/移除/更新位置
    private var wm: WindowManager? = null

    // 悬浮窗布局参数：尺寸、类型、初始位置等，拖动时实时修改其 x/y
    private var params: WindowManager.LayoutParams? = null

    // 主线程消息队列，用于“长按 2 秒关闭”的计时
    private val handler = Handler(Looper.getMainLooper())

    // 按下时的触点坐标（屏幕绝对坐标），作为拖动位移计算的基准
    private var downX = 0f
    private var downY = 0f

    // 按下时悬浮窗的 x/y（窗口坐标），拖动时在此基础上叠加位移
    private var startX = 0
    private var startY = 0

    // 本次触摸是否已判定为“拖动”（移动超过 8px），用于区分“点击”与“拖动”
    private var dragging = false

    // 长按计时任务：按下 2 秒未松手则关闭悬浮按钮（长按关闭）
    private var closeRunnable: Runnable? = null

    companion object {
        // 标记当前悬浮按钮是否正在显示，供磁贴点击时切换显示/隐藏（重复启动前先判断，避免叠加多个按钮）
        var isRunning = false
    }

    // 本服务不需要绑定（非 bound service），返回 null
    override fun onBind(intent: Intent?) = null

    /**
     * 服务创建：构建“TVT”悬浮按钮并上屏。
     * 初始位置：左上角锚定，距左边缘 4dp、距顶部 120dp（可拖动调整）。
     */
    override fun onCreate() {
        super.onCreate()
        // 置为运行中，供外部判断悬浮按钮当前是否已显示
        isRunning = true
        // 获取系统窗口管理器，后续悬浮窗操作都依赖它
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 屏幕密度，用于把 dp 换算成真实像素，保证不同分辨率下按钮大小一致
        val density = resources.displayMetrics.density
        // 构建悬浮按钮本体
        val button = Button(this).apply {
            text = "TVT"  //text = "查"   // 查找检测和显示页面节点树信息的悬浮按钮
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())      // 白色文字
            setBackgroundColor(0xCC1565C0.toInt()) // 半透明蓝底，避免完全遮挡页面内容
            setPadding(14, 18, 14, 18)
        }
        btn = button
        // 按钮宽高：56dp 正方形（点击目标足够大，不易误触）
        val size = (56 * density).toInt()
        // 悬浮窗参数
        params = WindowManager.LayoutParams(
            size,
            size,
            // 悬浮窗类型：Android 8.0+ 需 TYPE_APPLICATION_OVERLAY（依赖“显示在其他应用上层”权限）
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢占焦点、不弹输入法：目标 App 的键盘/返回键不受影响，让桌面能正常接收按键与触摸（按钮点击通过 OnTouchListener 自己处理）；
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            //窗口支持半透明
            PixelFormat.TRANSLUCENT
        ).apply {
            // 锚定左上角，配合 x/y 决定初始位置
            gravity = Gravity.TOP or Gravity.START
            x = (4 * density).toInt()     // 距左边缘 4dp（贴边）
            y = (120 * density).toInt()   // 距顶部 120dp（靠上）
        }
        // 把触摸事件交给 handleTouch 统一处理（拖动/轻点抓取/长按关闭）
        button.setOnTouchListener { v, event -> handleTouch(v, event) }
        // 上屏；权限缺失等异常时仅打印堆栈，避免服务崩溃
        try {
            wm?.addView(button, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 悬浮按钮触摸事件处理：
     * - 轻点（未拖动）：抓取当前页面文字节点树并弹窗展示；
     * - 拖动：移动悬浮按钮位置（位移以按下点为基准实时跟随手指）；
     * - 长按 2 秒不松手：关闭并移除悬浮按钮。
     *
     * @param v 被触摸的悬浮按钮
     * @param event 触摸事件
     * @return 始终消费事件（true），防止事件穿透到下层 App
     */
    private fun handleTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录按下基点，用于计算拖动位移
                downX = event.rawX
                downY = event.rawY
                // 记录按下时窗口位置，拖动时在此基础上叠加
                startX = params?.x ?: 0
                startY = params?.y ?: 0
                dragging = false
                // 启动“长按 2 秒关闭”计时；若期间拖动或松手则取消（见 MOVE/UP）
                closeRunnable = Runnable { removeAndStop() }
                handler.postDelayed(closeRunnable!!, 2000)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 手指相对按下点的位移
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                // 移动超过阈值即判定为“拖动”，取消关闭计时（避免“按住拖走”被误判为长按关闭）
                if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                    dragging = true
                    handler.removeCallbacks(closeRunnable!!)
                }
                if (dragging) {
                    // 拖动中：窗口新位置 = 按下时位置 + 位移，实时刷新悬浮窗
                    params?.let {
                        it.x = (startX + dx).toInt()
                        it.y = (startY + dy).toInt()
                        wm?.updateViewLayout(v, it)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 无论长按是否到时，松手即取消关闭计时
                handler.removeCallbacks(closeRunnable!!)
                // 未发生拖动（轻点）则抓取节点树
                if (!dragging) capture()
                return true
            }
        }
        return false
    }

    /**
     * 抓取并展示当前前台页面的文字节点树：
     * 依赖 AutoSlide 无障碍服务读取节点，供用户查看页面文字与坐标，辅助配置“点击文字”动作。
     * 无障碍服务未开启时给出提示；抓取异常时展示异常信息，均以对话框呈现。
     */
    private fun capture() {
        val svc = AutoSlideService.getInstance()
        val text = if (svc == null) {
            "无障碍服务未运行，请在系统设置中重新开启 AutoSlide 无障碍权限"
        } else {
            runCatching { svc.collectScreenText() }.getOrElse { "抓取异常：${it.message}" }
        }
        InspectDialog.show(applicationContext, text)
    }

    /**
     * 移除悬浮按钮并停止本服务（长按 2 秒触发）。
     * 移除窗口时包 runCatching，防止视图已不存在时抛异常。
     */
    private fun removeAndStop() {
        handler.removeCallbacks(closeRunnable ?: return)
        btn?.let { runCatching { wm?.removeView(it) } }
        btn = null
        stopSelf()
    }

    /**
     * 服务销毁：清理运行标记、挂起的长按计时与悬浮窗，防止窗口泄漏（不 removeView 会导致按钮残留在屏幕上）。
     */
    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        btn?.let { runCatching { wm?.removeView(it) } }
        btn = null
        super.onDestroy()
    }
}
