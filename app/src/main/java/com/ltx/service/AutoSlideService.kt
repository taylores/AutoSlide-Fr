package com.ltx.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject
import android.graphics.PointF
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ltx.DEFAULT_MIN_PAUSE_TIME
import com.ltx.DEFAULT_PAUSE_TIME
import com.ltx.DEFAULT_SPEED
import com.ltx.DEFAULT_TB_AUTO_BACK
import com.ltx.DEFAULT_TB_BACK_TIME
import com.ltx.DEFAULT_TB_SOUND
import com.ltx.DEFAULT_TB_VIBRATE
import com.ltx.R
import com.ltx.DIRECTION_DOWN
import com.ltx.DIRECTION_LEFT
import com.ltx.DIRECTION_RIGHT
import com.ltx.DIRECTION_UP
import com.ltx.DEFAULT_MAX_PAUSE_TIME
import com.ltx.DIRECTION_UP_DOWN
import com.ltx.MACRO_PAUSE_FIXED
import com.ltx.MACRO_PAUSE_RANDOM
import com.ltx.PAUSE_MODE_FIXED
import com.ltx.PAUSE_MODE_NONE
import com.ltx.PAUSE_MODE_RANDOM
import com.ltx.SlideConfig
import com.ltx.SlideEvent
import com.ltx.SlideEventHub
import com.ltx.clearCustomTrajectory
import com.ltx.getCustomTrajectory
import com.ltx.ACTION_RUN_MACRO
import com.ltx.ACTION_STOP_MACRO
import com.ltx.EXTRA_MACRO_PLAN_JSON
import com.ltx.MACRO_STARTUP_DELAY_MS
import com.ltx.MacroActionType
import com.ltx.MacroPlan
import com.ltx.MacroStep
import java.lang.ref.WeakReference
import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ltx.DEFAULT_TB_SOUND
import com.ltx.DEFAULT_TB_VIBRATE
import com.ltx.DEFAULT_MACRO_TEXT_HINT
import com.ltx.DEFAULT_MACRO_STEP_SOUND
import com.ltx.DEFAULT_MACRO_TASK_SOUND
import com.ltx.DEFAULT_MACRO_VIBRATE
import com.ltx.getSlideConfig
import com.ltx.DEFAULT_MACRO_VIBRATE_START
import com.ltx.DEFAULT_MACRO_VIBRATE_STEP
import com.ltx.DEFAULT_MACRO_VIBRATE_STEP_DONE
import com.ltx.DEFAULT_MACRO_VIBRATE_DONE
import com.ltx.DEFAULT_MACRO_STEP_SOUND_START
import com.ltx.DEFAULT_MACRO_STEP_SOUND_STEP
import com.ltx.DEFAULT_MACRO_STEP_SOUND_DONE

/**
 * 自动滑动无障碍服务
 *
 * @author tianxing
 */
@SuppressLint("AccessibilityPolicy")
class AutoSlideService : AccessibilityService() {

    private val secureRandom = SecureRandom().asKotlinRandom()
    private val handler = Handler(Looper.getMainLooper())
    private var runGeneration = 0
    private var isScreenOffReceiverRegistered = false
    private var speed = DEFAULT_SPEED
    private var pauseMode = PAUSE_MODE_NONE
    private var pauseTime = DEFAULT_PAUSE_TIME
    private var minPauseTime = DEFAULT_MIN_PAUSE_TIME
    private var maxPauseTime = DEFAULT_MAX_PAUSE_TIME
    private var currentDirection = DIRECTION_LEFT
    private var upDownToggle = false
    private var isRunning = false
    private var isGestureActive = false
    private var tbAutoBack = DEFAULT_TB_AUTO_BACK
    private var tbBackTime = DEFAULT_TB_BACK_TIME
    private var tbSound = DEFAULT_TB_SOUND
    private var tbVibrate = DEFAULT_TB_VIBRATE
    // 任务方案执行提示开关
    private var macroTextHint = DEFAULT_MACRO_TEXT_HINT
    private var macroTaskSound = DEFAULT_MACRO_TASK_SOUND
    private var macroVibrateStart = DEFAULT_MACRO_VIBRATE_START
    private var macroVibrateStep = DEFAULT_MACRO_VIBRATE_STEP
    private var macroVibrateStepDone = DEFAULT_MACRO_VIBRATE_STEP_DONE
    private var macroVibrateDone = DEFAULT_MACRO_VIBRATE_DONE
    private var macroStepSoundStart = DEFAULT_MACRO_STEP_SOUND_START
    private var macroStepSoundStep = DEFAULT_MACRO_STEP_SOUND_STEP
    private var macroStepSoundDone = DEFAULT_MACRO_STEP_SOUND_DONE

    /* 自动滑动主循环 */
    private val slideRunnable = Runnable { runSlide() }

    /* 自动后退任务（附加功能，独立于滑动模式）：到点播放提示音并执行返回后停止滑动 */
    private val tbAutoBackRunnable = Runnable {
        if (!isRunning) {
            return@Runnable
        }
        if (tbSound) playAlertSound()
        if (tbVibrate) vibrateDevice()
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        forceStop()
    }

    /* 执行一次自动滑动 */
    private fun runSlide() {
        if (!isRunning) {
            return
        }
        // 上下组合模式：每次滑动在"上/下"之间交替（首次为向下）
        val effectiveDirection = if (currentDirection == DIRECTION_UP_DOWN) {
            upDownToggle = !upDownToggle
            if (upDownToggle) DIRECTION_DOWN else DIRECTION_UP
        } else {
            currentDirection
        }
        // 计算手势持续时间
        val gestureDurationMillis = calculateGestureDurationMillis()
        // 执行滑动
        performSlideByDirection(gestureDurationMillis, effectiveDirection)
    }

    /* 息屏时强制停止滑动 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || !isRunning) {
                return
            }
            forceStop()
        }
    }

    /* ===================== 任务方案（组合动作宏） ===================== */

    private val macroScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var macroJob: Job? = null
    private var macroRunId = 0
    private var macroRunning = false

    /* 运行/停止任务方案的广播 */
    private val macroReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RUN_MACRO -> {
                    val json = intent.getStringExtra(EXTRA_MACRO_PLAN_JSON) ?: return
                    runCatching { runMacro(MacroPlan.fromJson(JSONObject(json))) }
                }
                ACTION_STOP_MACRO -> stopMacro()
            }
        }
    }

    /* 运行任务方案：重复 repeatCount 次，每次按序执行 steps */

    fun runMacro(plan: MacroPlan) {
        if (plan.steps.isEmpty()) return
        val myId = ++macroRunId
        macroJob?.cancel()
        macroRunning = true
        // 每次启动都刷新配置，确保“任务提示”等开关为最新值
        applyConfig(applicationContext.getSlideConfig())
        SlideEventHub.sendEvent(SlideEvent.MacroStarted(plan.name))
        macroJob = macroScope.launch {
            // 启动前延迟：给用户时间切换到目标 App（如抖音），否则手势会打到桌面，看起来像没反应
            showOverlayToast(
                "开始执行「${plan.name}」：${plan.steps.count { it.enabled }}步 × ${plan.repeatCount}次，请切到目标 App",
                3000
            )
            if (macroVibrateStart) macroVibrate(0)
            if (macroStepSoundStart) playSound(R.raw.open_dede)
            delay(MACRO_STARTUP_DELAY_MS)
            if (myId != macroRunId) return@launch
            try {
                repeat(plan.repeatCount.coerceAtLeast(1)) {
                    if (myId != macroRunId) return@launch
                    for (step in plan.steps) {
                        if (myId != macroRunId) return@launch
                        executeStep(step, myId)
                    }
                }
                if (myId == macroRunId) {
                    showOverlayToast("方案「${plan.name}」执行完成", 2000)
                    if (macroVibrateDone) macroVibrate(2)
                    if (macroStepSoundDone) playSound(R.raw.open_dede)
                    if (macroTaskSound) playSound(R.raw.open_deng)
                }
            } finally {
                macroRunning = false
                withContext(Dispatchers.Main) { SlideEventHub.sendEvent(SlideEvent.MacroStopped) }
            }
        }
    }

    /* 停止任务方案 */
    fun stopMacro() {
        macroRunId++
        macroJob?.cancel()
        macroJob = null
        macroRunning = false
        SlideEventHub.sendEvent(SlideEvent.MacroStopped)
    }

    /* 以悬浮窗 overlay 显示提示（绕过 Android 11+ 后台 Toast 拦截，后台也能看到） */
    private var overlayTextView: TextView? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayTextView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
    }

    private fun showOverlayToast(text: String, durationMs: Long = 2200) {
        overlayHandler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                if (overlayTextView == null) {
                    overlayTextView = TextView(this@AutoSlideService).apply {
                        setBackgroundColor(0xCC000000.toInt())
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        val pad = (8 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                    }
                }
                overlayTextView!!.text = text
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = (90 * resources.displayMetrics.density).toInt()
                try {
                    wm.addView(overlayTextView, params)
                } catch (_: Exception) {
                    try {
                        wm.updateViewLayout(overlayTextView, params)
                    } catch (_: Exception) {
                    }
                }
                overlayHandler.removeCallbacks(hideOverlayRunnable)
                overlayHandler.postDelayed(hideOverlayRunnable, durationMs)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun executeStep(step: MacroStep, myId: Int) {
        if (myId != macroRunId) return
        if (!step.enabled) {
            showOverlayToast("跳过（未启用）：${step.summary()}", 1200)
            return
        }
        if (macroTextHint) showOverlayToast("执行步骤：${step.summary()}", 1500)
        if (macroVibrateStep) macroVibrate(1)
        // 步骤每步音效：每一步开始时播放一声提示音
        if (macroStepSoundStep) playSound(R.raw.open_dede)
        when (step.type) {
            MacroActionType.SLIDE -> performMacroSlide(step, myId)
            MacroActionType.BACK -> repeat(step.loopCount.coerceAtLeast(1)) { i ->
                if (myId != macroRunId) return@repeat
                withContext(Dispatchers.Main) {
                    if (myId == macroRunId) performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (i < step.loopCount.coerceAtLeast(1) - 1 && step.loopDelaySec > 0f) {
                    delay((step.loopDelaySec * 1000).toLong())
                }
            }
            MacroActionType.DELAY -> delay((step.delaySec.coerceAtLeast(0.1f) * 1000).toLong())
            MacroActionType.CLICK_TEXT -> clickByText(step, myId)
            MacroActionType.CLICK_POSITION -> clickByPosition(step, myId)
            MacroActionType.WAIT_TEXT -> waitForText(step, myId)
            MacroActionType.BRANCH -> executeBranch(step, myId)
            MacroActionType.BRANCH_IF -> executeBranchIf(step, myId)
            MacroActionType.DRAG -> performMacroDrag(step, myId)
        }
        // 步骤完成时震动一下（每一步结束的那一刻，与“步骤启动/每步”一样仅一声短震）
        if (macroVibrateStepDone) macroVibrate(1)
    }

    /* 条件分支（单情况）：先等待条件（出现/消失）满足，再循环 loopCount 次顺序执行子步骤；超时未命中则跳过 */
    private suspend fun executeBranch(step: MacroStep, myId: Int) {
        val condMet = pollTextCondition(step, myId)
        if (!condMet) {
            showOverlayToast("条件分支未命中「${step.waitText}」，跳过", 2000)
            return
        }
        repeat(step.loopCount.coerceAtLeast(1)) {
            if (myId != macroRunId) return
            for (sub in step.subSteps) executeStep(sub, myId)
        }
    }

    /* 条件分支（if/else）：先判断等待条件是否满足；命中执行 true 子步骤，否则执行 false 子步骤；循环 loopCount 次 */
    private suspend fun executeBranchIf(step: MacroStep, myId: Int) {
        val condMet = pollTextCondition(step, myId)
        val subs = if (condMet) step.subSteps else step.falseSubSteps
        repeat(step.loopCount.coerceAtLeast(1)) {
            if (myId != macroRunId) return
            for (sub in subs) executeStep(sub, myId)
        }
    }

    /* 按文字点击：命中白名单且所在行不含黑名单，点第一个可点击节点（可点多次） */
    private suspend fun clickByText(step: MacroStep, myId: Int) {
        if (myId != macroRunId || step.whitelist.isEmpty()) return
        var clickedAny = false
        var count = 0
        val max = step.maxClicks.coerceAtLeast(1)
        while (count < max) {
            if (myId != macroRunId) return
            val ct = withContext(Dispatchers.Main) { findClickTargetInWindows(step) }
            try {
                if (ct.click != null) {
                    val rect = Rect()
                    // 优先取“文字节点”自身包围盒作为点击坐标（更贴近用户看到的按钮位置）
                    (ct.text ?: ct.click).getBoundsInScreen(rect)
                    withContext(Dispatchers.Main) {
                        if (rect.width() > 0 && rect.height() > 0) {
                            dispatchTapAt(rect.centerX(), rect.centerY())
                        } else {
                            ct.click.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                    clickedAny = true
                    SlideEventHub.sendEvent(SlideEvent.MacroStep(step.whitelist.joinToString("/")))
                }
            } finally {
                ct.all.forEach { it.recycle() }
            }
            if (ct.click == null) break
            count++
            if (count < max) delay(400)
        }
        if (!clickedAny) {
            showOverlayToast(
                "未找到「${step.whitelist.joinToString("/")}」：该文字未暴露在无障碍节点中",
                3000
            )
        }
    }

    /* 真实屏幕尺寸（含状态栏/导航栏），用于坐标百分比换算，确保与无障碍手势的真实屏幕坐标系一致 */
    private val realMetrics: DisplayMetrics
        get() {
            val dm = DisplayMetrics()
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            return dm
        }

    /* 按坐标点击：posX/posY 为屏幕百分比，运行时按当前分辨率换算像素后派发点击手势（可点多次） */
    private suspend fun clickByPosition(step: MacroStep, myId: Int) {
        if (myId != macroRunId) return
        val metrics = realMetrics
        val x = (step.posX / 100f * metrics.widthPixels).toInt().coerceAtLeast(0)
        val y = (step.posY / 100f * metrics.heightPixels).toInt().coerceAtLeast(0)
        val max = step.maxClicks.coerceAtLeast(1)
        repeat(max) {
            if (myId != macroRunId) return
            withContext(Dispatchers.Main) { dispatchTapAt(x, y) }
            if (it < max - 1) delay(400)
        }
        SlideEventHub.sendEvent(SlideEvent.MacroStep("点击坐标(${"%.1f".format(step.posX)}%, ${"%.1f".format(step.posY)}%)"))
    }

    /* 点击目标结果：可点击祖先节点、命中的文字节点、整棵访问过的节点（用于回收） */
    private data class ClickTarget(
        val click: AccessibilityNodeInfo?,
        val text: AccessibilityNodeInfo?,
        val all: List<AccessibilityNodeInfo>
    )

    /* 遍历节点树找最佳点击目标；返回(可点击目标, 命中文字节点, 全部访问节点)用于回收 */
    private fun findClickTarget(root: AccessibilityNodeInfo?, step: MacroStep): ClickTarget {
        if (root == null) return ClickTarget(null, null, emptyList())
        val all = mutableListOf<AccessibilityNodeInfo>()
        var bestClick: AccessibilityNodeInfo? = null
        var bestText: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || bestClick != null) return
            all.add(node)
            val selfText = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            if (step.whitelist.any { selfText.contains(it) }) {
                // 找到命中文字的节点后，向上取最近的可点击祖先作为点击目标；
                // 若没有可点击祖先，则退回文字节点本身（用坐标点按）
                var clickable = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                val target = clickable ?: node
                val rowText = buildRowText(target, all)
                if (!step.blacklist.any { rowText.contains(it) }) {
                    bestClick = target
                    bestText = node
                }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)
        return ClickTarget(bestClick, bestText, all)
    }

    /* 跨窗口搜索点击目标：先查前台窗口，再遍历所有窗口（跳过自身包）。
       修复无障碍 overlay/悬浮窗压在最上层时，rootInActiveWindow 取到的是自身窗口、
       导致在目标 App 的树里搜不到“去完成”等问题 */
    private fun findClickTargetInWindows(step: MacroStep): ClickTarget {
        val ownPkg = packageName
        var cand = findClickTarget(rootInActiveWindow, step)
        if (cand.click != null) return cand
        cand.all.forEach { it.recycle() }
        for (w in windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == ownPkg) continue
            cand = findClickTarget(root, step)
            if (cand.click != null) return cand
            cand.all.forEach { it.recycle() }
        }
        return ClickTarget(null, null, emptyList())
    }

    /* 取点击目标所在行的文本（自身 + 父 + 同级兄弟），用于黑名单判断 */
    private fun buildRowText(node: AccessibilityNodeInfo, all: List<AccessibilityNodeInfo>): String {
        val sb = StringBuilder()
        sb.append(node.text).append(' ').append(node.contentDescription)
        val parent = node.parent
        if (parent != null) {
            sb.append(' ').append(parent.text).append(' ').append(parent.contentDescription)
            for (sib in all) {
                if (sib !== node && sib.parent === parent) {
                    sb.append(' ').append(sib.text).append(' ').append(sib.contentDescription)
                }
            }
        }
        return sb.toString()
    }

    /* 轮询文字出现/消失条件（跨窗口），返回是否在超时内满足（可取消）；
       等待文字支持“,”分隔的多个目标（任一命中即满足）；
       单个目标内以空格分隔的多个词表示“同一页面内需同时出现”（AND，可跨不同节点），
       如“浏览得奖励 20”；纯数字 token 按“独立数字”匹配，避免“2000+人付款”误判 */
    private suspend fun pollTextCondition(step: MacroStep, myId: Int): Boolean {
        if (myId != macroRunId) return false
        // 恒成立：无条件满足，直接执行 true 子步骤
        if (step.waitMode == "always") return true
        val targets = step.waitText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (targets.isEmpty()) return false
        val deadline = System.currentTimeMillis() + (step.timeoutSec.coerceAtLeast(0.1f) * 1000).toLong()
        val ownPkg = packageName
        while (myId == macroRunId && System.currentTimeMillis() < deadline) {
            val satisfied = withContext(Dispatchers.Main) {
                val roots = mutableListOf<AccessibilityNodeInfo?>().apply {
                    add(rootInActiveWindow)
                    for (w in windows) {
                        val r = w.root
                        if (r?.packageName?.toString() != ownPkg) add(r)
                    }
                }
                roots.any { root ->
                    if (root == null) return@any false
                    val texts = collectWindowTexts(root)
                    // 排除条件：页面出现排除文字即视为不满足条件（避免误判，如“2000+人付款”含“20”）
                    val excluded = step.excludeText.isNotBlank() &&
                        step.excludeText.split(Regex("\\s+")).filter { it.isNotBlank() }
                            .any { ex -> texts.any { t -> t.contains(ex) } }
                    targets.any { target ->
                        val toks = target.split(Regex("\\s+")).filter { it.isNotBlank() }
                        toks.all { tok -> texts.any { t -> textMatchesToken(t, tok) } }
                    } && !excluded
                }
            }
            val result = if (step.waitMode == "appear") satisfied else !satisfied
            if (result) return true
            delay(300)
        }
        return false
    }

    /* 收集整棵节点树的所有“文字/描述”（按节点），供组合(AND)匹配使用；遍历结束后回收节点 */
    private fun collectWindowTexts(node: AccessibilityNodeInfo?): List<String> {
        val out = mutableListOf<String>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = (n.text?.toString().orEmpty()) + " " + (n.contentDescription?.toString().orEmpty())
            if (t.isNotBlank()) out.add(t)
            for (i in 0 until n.childCount) visit(n.getChild(i))
            n.recycle()
        }
        visit(node)
        return out
    }

    /* 单个 token 与某节点文字的匹配：
       纯数字 token 必须是“独立数字”（前后不紧邻其它数字），避免“2000+人付款”里的“20”被误判；
       非数字 token 仍按子串匹配（如“浏览得奖励”） */
    private fun textMatchesToken(nodeText: String, token: String): Boolean {
        if (token.matches(Regex("^\\d+$"))) {
            return nodeText.contains(Regex("(?<!\\d)$token(?!\\d)"))
        }
        return nodeText.contains(token)
    }

    /* 条件等待文字出现/消失（轮询，可取消） */
    private suspend fun waitForText(step: MacroStep, myId: Int) {
        pollTextCondition(step, myId)
    }

    /* 调试：采集当前所有非本应用窗口的文字节点，供“屏幕文字检查”页使用（已加保护，避免异常弄崩服务） */
    fun collectScreenText(): String {
        return try {
            val sb = StringBuilder()
            val ownPkg = packageName
            val activePkg = rootInActiveWindow?.packageName?.toString()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                if (node == null) return
                val t = node.text?.toString().orEmpty().trim()
                val cd = node.contentDescription?.toString().orEmpty().trim()
                if (t.isNotEmpty() || cd.isNotEmpty()) {
                    val cls = node.className?.toString().orEmpty().substringAfterLast('.')
                    repeat(depth) { sb.append("  ") }
                    sb.append("[$cls] ")
                    if (t.isNotEmpty()) sb.append("text=\"$t\" ")
                    if (cd.isNotEmpty()) sb.append("desc=\"$cd\"")
                    sb.append("\n")
                }
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i)
                    visit(c, depth + 1)
                    c.recycle()
                }
            }
            val roots = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
            rootInActiveWindow?.let { if (it.packageName?.toString() != ownPkg && it.packageName?.toString() != SYS_UI) roots.add(it) }
            for (w in windows) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString() ?: continue
                if (pkg == ownPkg || pkg == SYS_UI) continue
                if (pkg == activePkg && roots.isNotEmpty()) continue
                roots.add(r)
            }
            for (r in roots) { visit(r, 0); r.recycle() }
            if (sb.isEmpty()) "(未检测到任何非本应用文字节点；请确认已开启无障碍服务、且目标 App 仍在最近任务中)" else sb.toString()
        } catch (e: Exception) {
            "(抓取出错：${e.message})"
        }
    }

    /* 在指定坐标派发一次点击手势（节点点击失败时的兜底） */
    private fun dispatchTapAt(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(builder.build(), null, null)
    }

    companion object {
        private const val MIN_GESTURE_DURATION_MS = 100L
        private const val MAX_GESTURE_DURATION_MS = 900L
        private const val NO_PAUSE_GAP_MS = 80L
        private const val SPEED_CURVE_FACTOR = 0.7
        private const val SYS_UI = "com.android.systemui"
        private var instanceRef: WeakReference<AutoSlideService>? = null

        /**
         * 获取服务单例实例
         *
         * @return 当前服务实例
         */
        @JvmStatic
        fun getInstance(): AutoSlideService? = instanceRef?.get()
    }

    /**
     * 设置滑动方向
     *
     * @param direction 目标方向字符串(up/down/left/right)
     */
    fun setDirection(direction: String) {
        currentDirection = when (direction) {
            DIRECTION_UP, DIRECTION_DOWN, DIRECTION_LEFT, DIRECTION_RIGHT, DIRECTION_UP_DOWN -> direction
            else -> DIRECTION_LEFT
        }
    }

    /**
     * 安排下一次滑动
     *
     * @param currentGen 当前运行代数
     */
    private fun scheduleNextSlide(currentGen: Int = runGeneration) {
        if (isRunning && currentGen == runGeneration) {
            handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
        }
    }

    /**
     * 更新滑动速度而不触发启动逻辑
     *
     * @param newSpeed 最新速度值
     */
    fun updateSpeed(newSpeed: Int) {
        speed = newSpeed.coerceIn(1, 100)
    }

    /**
     * 应用滑动配置
     *
     * @param config 滑动配置数据对象
     */
    private fun applyConfig(config: SlideConfig) {
        speed = config.speed.coerceIn(1, 100)
        pauseMode = config.pauseMode
        pauseTime = config.pauseTime.coerceAtLeast(1)
        minPauseTime = config.minPauseTime.coerceAtLeast(1)
        maxPauseTime = config.maxPauseTime.coerceAtLeast(1)
        tbAutoBack = config.tbAutoBack
        tbBackTime = config.tbBackTime.coerceAtLeast(1)
        tbSound = config.tbSound
        tbVibrate = config.tbVibrate
        macroTextHint = config.macroTextHint
        macroTaskSound = config.macroTaskSound
        macroVibrateStart = config.macroVibrateStart
        macroVibrateStep = config.macroVibrateStep
        macroVibrateStepDone = config.macroVibrateStepDone
        macroVibrateDone = config.macroVibrateDone
        macroStepSoundStart = config.macroStepSoundStart
        macroStepSoundStep = config.macroStepSoundStep
        macroStepSoundDone = config.macroStepSoundDone
    }

    /**
     * 更新停顿配置参数
     *
     * @param config 滑动配置数据对象
     */
    fun updatePauseConfig(config: SlideConfig) {
        applyConfig(config)
        if (!isRunning || isGestureActive) {
            return
        }
        // 移除当前滑动任务并重新调度新的停顿时间
        handler.removeCallbacks(slideRunnable)
        handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
        // 重新调度自动后退（运行时勾选/调整后退时间即时生效）
        scheduleAutoBack()
    }

    /**
     * 调度自动后退任务（独立于滑动模式，勾选"自动后退"即生效）
     *
     * @param currentGen 当前运行代数
     */
    private fun scheduleAutoBack(currentGen: Int = runGeneration) {
        handler.removeCallbacks(tbAutoBackRunnable)
        if (isRunning && currentGen == runGeneration && tbAutoBack) {
            handler.postDelayed(tbAutoBackRunnable, tbBackTime.coerceAtLeast(1) * 1000L)
        }
    }

   /**
    * 根据配置启动自动滑动
    *
    * @param config 滑动配置数据对象
    */
    fun startSlideWithConfig(config: SlideConfig) {
        applyConfig(config)
        startAutoSlide()
    }

    /* 服务连接完成后初始化屏幕参数并注册单例 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        // 请求按键过滤能力(用于音量键强制停止滑动)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        registerScreenOffReceiver()
        ContextCompat.registerReceiver(
            this, macroReceiver,
            IntentFilter(ACTION_RUN_MACRO).apply { addAction(ACTION_STOP_MACRO) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /* 服务销毁时停止滑动并释放单例 */
    override fun onDestroy() {
        unregisterScreenOffReceiver()
        runCatching { unregisterReceiver(macroReceiver) }
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        runCatching { overlayTextView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        macroScope.cancel()
        stopSlide()
        instanceRef = null
        super.onDestroy()
    }

    /* 停止自动滑动循环 */
    fun stopSlide() {
        if (!isRunning) {
            return
        }
        isRunning = false
        isGestureActive = false
        runGeneration++
        handler.removeCallbacks(slideRunnable)
        handler.removeCallbacks(tbAutoBackRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * 监听音量键(在滑动运行中按音量键强制停止)
     *
     * @param event 物理按键事件
     * @return 是否已处理按键事件
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 只在按键按下且滑动正在运行时处理
        if (event.action != KeyEvent.ACTION_DOWN || !isRunning) {
            return super.onKeyEvent(event)
        }
        // 判断是否为音量键
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey) {
            return super.onKeyEvent(event)
        }
        // 强制停止滑动并恢复悬浮窗面板
        forceStop()
        return true
    }

    /* 强制停止滑动并恢复悬浮窗面板 */
    private fun forceStop() {
        stopSlide()
        stopMacro()
        SlideEventHub.sendEvent(SlideEvent.ForceStop)
    }

    /* 播放指定资源音效（播放完成后自动释放） */
    private fun playSound(resId: Int) {
        runCatching {
            MediaPlayer.create(applicationContext, resId)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        }
    }

    /* 播放提示音（tb模式定时后退提醒，需勾选"音效提示"） */
    private fun playAlertSound() {
        playSound(R.raw.open_deng)
    }

    /* 震动提示（tb模式定时后退提醒，需勾选"震动提示"） */
    @SuppressLint("MissingPermission")
    private fun vibrateDevice() {
        runCatching {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
            }
        }
    }

    /* 宏运行反馈用的震动（不依赖悬浮窗权限，后台也能感知）：0=启动长震 1=每步一声短震 2=任务完成两声短震 */
    @SuppressLint("MissingPermission")
    private fun macroVibrate(kind: Int) {
        runCatching {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (kind) {
                    0 -> VibrationEffect.createOneShot(600L, VibrationEffect.DEFAULT_AMPLITUDE) // 启动
                    1 -> VibrationEffect.createOneShot(150L, VibrationEffect.DEFAULT_AMPLITUDE) // 每步/步骤完成
                    else -> VibrationEffect.createWaveform(longArrayOf(0, 150, 120, 150), -1)  // 任务完成：两声短震
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                when (kind) {
                    0 -> vibrator.vibrate(600L)                 // 启动
                    1 -> vibrator.vibrate(150L)                 // 每步/步骤完成
                    else -> vibrator.vibrate(longArrayOf(0, 150, 120, 150), -1) // 任务完成：两声短震
                }
            }
        }
    }

    /* 注册息屏广播 */
    private fun registerScreenOffReceiver() {
        if (isScreenOffReceiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isScreenOffReceiverRegistered = true
    }

    /* 注销息屏广播 */
    private fun unregisterScreenOffReceiver() {
        if (!isScreenOffReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(screenOffReceiver) }
        isScreenOffReceiverRegistered = false
    }

    /* 滑动起止坐标数据类 */
    private data class SlideCoordinates(
        val startX: Float, val startY: Float, val endX: Float, val endY: Float
    )

    /**
     * 根据滑动方向计算滑动起止坐标
     *
     * @param direction 滑动方向
     * @return 起止坐标
     */
    private fun getSlideCoordinates(direction: String): SlideCoordinates {
        val width = realMetrics.widthPixels
        val height = realMetrics.heightPixels
        val centerX = width / 2f
        val centerY = height / 2f
        return when (direction) {
            DIRECTION_UP -> SlideCoordinates(centerX, height * 0.2f, centerX, height * 0.8f)
            DIRECTION_DOWN -> SlideCoordinates(centerX, height * 0.8f, centerX, height * 0.2f)
            DIRECTION_RIGHT -> SlideCoordinates(width * 0.9f, centerY, width * 0.1f, centerY)
            DIRECTION_UP_DOWN -> SlideCoordinates(centerX, height * 0.2f, centerX, height * 0.8f)
            else -> SlideCoordinates(width * 0.1f, centerY, width * 0.9f, centerY)
        }
    }

    /* 启动自动滑动循环 */
    private fun startAutoSlide() {
        isRunning = true
        isGestureActive = false
        upDownToggle = false
        runGeneration++
        val currentGen = runGeneration
        handler.removeCallbacks(slideRunnable)
        // 安排自动后退（独立于滑动模式，从点击滑动时开始计时）
        scheduleAutoBack(currentGen)
        // 延迟300ms执行第一次滑动(等待悬浮窗完成最小化动画)(防止悬浮窗拦截手势)
        handler.postDelayed({
            if (currentGen == runGeneration && isRunning) {
                runSlide()
            }
        }, 300L)
    }

    /**
     * 按指定方向执行一次滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     * @param direction 本次滑动方向（上下组合模式下由调用方决定具体上/下）
     */
    private fun performSlideByDirection(durationMillis: Long, direction: String = currentDirection) {
        dispatchGestureAndContinue(buildSlideGesture(direction, durationMillis))
    }

    /* 构建一次滑动手势（默认或自定义轨迹），返回 GestureDescription 供派发或挂起等待 */
    private fun buildSlideGesture(direction: String, durationMillis: Long): GestureDescription {        val trajectoryStr = getCustomTrajectory(direction)
        val pointCount = trajectoryStr?.split(";")?.count { it.isNotBlank() } ?: 0
        return if (trajectoryStr != null && pointCount >= 2) {
            buildCustomGesture(trajectoryStr, durationMillis, direction)
        } else {
            val (sx, sy, ex, ey) = getSlideCoordinates(direction)
            buildDefaultGesture(sx, sy, ex, ey, durationMillis)
        }
    }

    /* 自定义轨迹不完整时的回退：用默认手势（按方向） */
    private fun fallbackGesture(direction: String, durationMillis: Long): GestureDescription {
        val (sx, sy, ex, ey) = getSlideCoordinates(direction)
        return buildDefaultGesture(sx, sy, ex, ey, durationMillis)
    }
    /* 派发手势并挂起，直到手势完成/被取消（供宏内循环使用） */
    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val cb = object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resumeWith(Result.success(true))
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            }
            val ok = dispatchGesture(gesture, cb, handler)
            if (!ok && cont.isActive) cont.resumeWith(Result.success(false))
        }

    /* 宏“滑动”步骤：在总时长内反复“快速滑动”（手指即离屏），按停顿模式间隔，可随时被打断 */
    private suspend fun performMacroSlide(step: MacroStep, myId: Int) {
        val totalMs = (step.durationSec.coerceAtLeast(0.1f) * 1000).toLong()
        val deadline = System.currentTimeMillis() + totalMs
        var upDownToggle = false
        while (myId == macroRunId && System.currentTimeMillis() < deadline) {
            if (myId != macroRunId) return
            val dir = if (step.direction == DIRECTION_UP_DOWN) {
                upDownToggle = !upDownToggle
                if (upDownToggle) DIRECTION_DOWN else DIRECTION_UP
            } else step.direction
            val gestureDur = calculateGestureDurationMillis()
            val gesture = withContext(Dispatchers.Main) { buildSlideGesture(dir, gestureDur) }
            withContext(Dispatchers.Main) {
                if (myId == macroRunId) dispatchGestureAwait(gesture)
            }
            if (myId != macroRunId) return
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(macroPauseMillis(step).coerceIn(0, remaining))
        }
    }

    /* 宏“拖拽”步骤：从起点坐标滑动到终点坐标（屏幕百分比换算为像素），按指定时长完成单次自然手势 */
    private suspend fun performMacroDrag(step: MacroStep, myId: Int) {
        if (myId != macroRunId) return
        val n = step.loopCount.coerceAtLeast(1)
        repeat(n) { i ->
            if (myId != macroRunId) return
            val width = realMetrics.widthPixels
            val height = realMetrics.heightPixels
            val sx = width * step.dragStartX / 100f
            val sy = height * step.dragStartY / 100f
            val ex = width * step.dragEndX / 100f
            val ey = height * step.dragEndY / 100f
            val duration = step.dragDurationMs.toLong().coerceAtLeast(100)
            val gesture = withContext(Dispatchers.Main) {
                buildDefaultGesture(sx, sy, ex, ey, duration)
            }
            withContext(Dispatchers.Main) {
                if (myId == macroRunId) dispatchGestureAwait(gesture)
            }
            if (i < n - 1 && step.loopDelaySec > 0f) delay((step.loopDelaySec * 1000).toLong())
        }
    }

    /* 宏滑动步骤的停顿毫秒数（借鉴偏好设置“停顿时间”三种模式） */
    private fun macroPauseMillis(step: MacroStep): Long = when (step.pauseMode) {
        MACRO_PAUSE_FIXED -> step.pauseTime.coerceAtLeast(0) * 1000L
        MACRO_PAUSE_RANDOM -> {
            val lo = step.minPauseTime.coerceAtLeast(0) * 1000L
            val hi = step.maxPauseTime.coerceAtLeast(0) * 1000L
            if (lo >= hi) lo else (minOf(lo, hi)..maxOf(lo, hi)).random(secureRandom)
        }
        else -> NO_PAUSE_GAP_MS
    }

    /**
     * 分发自定义手势
     *
     * @param trajectoryStr 轨迹字符串
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun buildCustomGesture(trajectoryStr: String, durationMillis: Long, direction: String): GestureDescription {
        // 按分号拆分轨迹字符串并过滤空项
        val pointsStr = trajectoryStr.split(";").filter { it.isNotBlank() }
        // 有效点不足两个时回退到默认手势（不在此清理轨迹/调度循环，避免影响宏内循环）
        if (pointsStr.size < 2) {
            return fallbackGesture(direction, durationMillis)
        }
        // 解析坐标数据
        val rawPoints = pointsStr.mapNotNull { pointStr ->
            val xyValues = pointStr.split(",")
            if (xyValues.size == 2) {
                val x = xyValues[0].toFloatOrNull() ?: return@mapNotNull null
                val y = xyValues[1].toFloatOrNull() ?: return@mapNotNull null
                PointF(x, y)
            } else null
        }
        // 解析后有效点不足两个时回退到默认手势
        if (rawPoints.size < 2) {
            return fallbackGesture(direction, durationMillis)
        }
        // 过滤间距过近的冗余噪点以防止曲线畸变
        val density = resources.displayMetrics.density
        val minDistSq = (3f * density) * (3f * density)
        val filteredPoints = mutableListOf<PointF>()
        filteredPoints.add(rawPoints.first())
        for (i in 1 until rawPoints.size) {
            val last = filteredPoints.last()
            val curr = rawPoints[i]
            val dx = curr.x - last.x
            val dy = curr.y - last.y
            if (i == rawPoints.size - 1 || dx * dx + dy * dy >= minDistSq) {
                filteredPoints.add(curr)
            }
        }
        // 过滤后不足两个点时保底保留首尾坐标
        if (filteredPoints.size < 2) {
            filteredPoints.clear()
            filteredPoints.add(rawPoints.first())
            filteredPoints.add(rawPoints.last())
        }
        // 计算整条轨迹统一的拟人化随机平移量
        val width = realMetrics.widthPixels
        val height = realMetrics.heightPixels
        val maxGlobalOffset = 3f * density
        val globalXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxGlobalOffset).toFloat()
        val globalYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxGlobalOffset).toFloat()
        // 应用平移量并确保坐标处于屏幕范围内
        val points = filteredPoints.map { pt ->
            val finalX = (pt.x + globalXOffset).coerceIn(0f, width.toFloat())
            val finalY = (pt.y + globalYOffset).coerceIn(0f, height.toFloat())
            PointF(finalX, finalY)
        }
        // 基于中点插值构建二次贝塞尔平滑路径
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
        } else {
            for (i in 1 until points.size - 1) {
                val midX = (points[i].x + points[i + 1].x) / 2f
                val midY = (points[i].y + points[i + 1].y) / 2f
                path.quadTo(points[i].x, points[i].y, midX, midY)
            }
            path.lineTo(points.last().x, points.last().y)
        }
        // 构建无障碍手势
        return GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMillis)
        ).build()
    }

    /**
     * 计算滑动手势持续时间
     *
     * @return 手势持续时间(毫秒)
     */
    private fun calculateGestureDurationMillis(): Long {
        val normalizedSpeed = speed.coerceIn(1, 100) / 100.0
        val curvedProgress = ln(1.0 + SPEED_CURVE_FACTOR * normalizedSpeed) / ln(1.0 + SPEED_CURVE_FACTOR)
        val durationRange = MAX_GESTURE_DURATION_MS - MIN_GESTURE_DURATION_MS
        return (MAX_GESTURE_DURATION_MS - durationRange * curvedProgress).roundToLong()
    }

    /**
     * 计算两次滑动之间的停顿时间
     *
     * @return 停顿时间(毫秒)
     */
    private fun calculatePauseDelayMillis(): Long = when (pauseMode) {
        PAUSE_MODE_FIXED -> pauseTime.coerceAtLeast(0) * 1000L
        PAUSE_MODE_RANDOM -> {
            val minMs = minPauseTime.coerceAtLeast(0) * 1000L
            val maxMs = maxPauseTime.coerceAtLeast(0) * 1000L
            val (lo, hi) = minOf(minMs, maxMs) to maxOf(minMs, maxMs)
            if (lo == hi) lo else (lo..hi).random(secureRandom)
        }

        else -> NO_PAUSE_GAP_MS
    }

    /**
     * 分发默认手势
     *
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun buildDefaultGesture(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long
    ): GestureDescription {
        // 获取当前设备的屏幕密度缩放比例
        val density = resources.displayMetrics.density
        val maxOffset = 10f * density
        // 计算起止坐标偏移量
        val startXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val startYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val endXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val endYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        // 计算实际起止坐标
        val actualStartX = startX + startXOffset
        val actualStartY = startY + startYOffset
        val actualEndX = endX + endXOffset
        val actualEndY = endY + endYOffset
        // 计算中点坐标
        val midX = (actualStartX + actualEndX) / 2
        val midY = (actualStartY + actualEndY) / 2
        // 计算控制点坐标偏移量
        val controlOffset = 15f * density
        // 计算控制点坐标
        val controlX = midX + ((secureRandom.nextDouble() * 2 - 1) * controlOffset).toFloat()
        val controlY = midY + ((secureRandom.nextDouble() * 2 - 1) * controlOffset).toFloat()
        // 构造贝塞尔曲线路径模拟真人滑动的自然微弯轨迹
        val path = Path().apply {
            moveTo(actualStartX, actualStartY)
            quadTo(controlX, controlY, actualEndX, actualEndY)
        }
        // 构建手势
        return GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMillis)
        ).build()
    }

    /**
     * 分发手势并在结束后安排下一次滑动
     *
     * @param gesture 待分发的手势
     */
    private fun dispatchGestureAndContinue(gesture: GestureDescription) {
        isGestureActive = true
        val currentGen = runGeneration
        val success = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isGestureActive = false
                scheduleNextSlide(currentGen)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                isGestureActive = false
                showOverlayToast("手势被系统取消（可能无手势权限/被 ROM 拦截）", 3000)
                scheduleNextSlide(currentGen)
            }
        }, handler)
        // 分发失败时手动复位并继续下一轮滑动
        if (!success) {
            isGestureActive = false
            showOverlayToast("手势派发失败（服务无手势权限或被系统拦截）", 3000)
            scheduleNextSlide(currentGen)
        }
    }
}
