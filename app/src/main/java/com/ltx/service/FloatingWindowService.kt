package com.ltx.service

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ltx.ACTION_APPLY_FLOATING_SCHEME
import com.ltx.ACTION_FLOATING_SCHEME_CHANGED
import com.ltx.DIRECTION_DOWN
import com.ltx.DIRECTION_LEFT
import com.ltx.DIRECTION_RIGHT
import com.ltx.DIRECTION_UP
import com.ltx.DIRECTION_UP_DOWN
import com.ltx.EXTRA_FLOATING_EDIT_MODE
import com.ltx.EXTRA_FLOATING_SCHEME_ID
import com.ltx.FloatingButtonType
import com.ltx.DEFAULT_FLOATING_TRANSPARENCY
import com.ltx.KEY_FLOATING_TRANSPARENCY
import com.ltx.KEY_FLOATING_POSITION
import com.ltx.DEFAULT_FLOATING_POSITION
import com.ltx.ACTION_FLOATING_POSITION_CHANGED
import com.ltx.KEY_FLOATING_LAST_X
import com.ltx.KEY_FLOATING_LAST_Y
import com.ltx.POS_TOP_LEFT
import com.ltx.POS_TOP_CENTER
import com.ltx.POS_TOP_RIGHT
import com.ltx.POS_CENTER_LEFT
import com.ltx.POS_ORIGINAL
import com.ltx.POS_CENTER_RIGHT
import com.ltx.POS_BOTTOM_LEFT
import com.ltx.POS_BOTTOM_CENTER
import com.ltx.POS_BOTTOM_RIGHT
import com.ltx.ACTION_FLOATING_APPEARANCE_CHANGED
import com.ltx.DEFAULT_FLOATING_BTN_SIZE
import com.ltx.DEFAULT_FLOATING_CORNER
import com.ltx.DEFAULT_FLOATING_SHAPE
import com.ltx.FloatingPalette
import com.ltx.KEY_FLOATING_BTN_SIZE
import com.ltx.KEY_FLOATING_CORNER
import com.ltx.KEY_FLOATING_CUSTOM_COLOR
import com.ltx.KEY_FLOATING_SHAPE
import com.ltx.KEY_FLOATING_THEME
import com.ltx.SHAPE_CIRCLE
import com.ltx.SHAPE_SQUARE
import com.ltx.THEME_CUSTOM
import com.ltx.resolveFloatingPalette
import com.ltx.LayoutScheme
import com.ltx.MainActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ltx.PREFS_NAME
import com.ltx.R
import com.ltx.SchemeStore
import com.ltx.SlideEvent
import com.ltx.SlideEventHub
import com.ltx.clearCustomTrajectory
import com.ltx.getSlideConfig
import com.ltx.getTrajectoryKey
import com.ltx.hasCustomTrajectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮窗服务（按钮面板按布局方案动态构建，支持编辑模式）
 *
 * @author tianxing
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var rootView: View
    private lateinit var themedContext: Context
    private lateinit var controlPanel: LinearLayout
    private lateinit var expandButton: View
    private lateinit var editBar: View
    private lateinit var editDoneButton: View
    private lateinit var tbCountdownText: TextView
    private lateinit var currentScheme: LayoutScheme
    private var editMode = false
    private val buttonViews = mutableMapOf<FloatingButtonType, ImageButton>()
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private val density by lazy { resources.displayMetrics.density }
    private var currentShape = DEFAULT_FLOATING_SHAPE
    private var currentSizePx = DEFAULT_FLOATING_BTN_SIZE
    private var currentCornerPx = DEFAULT_FLOATING_CORNER
    private var cellPx = DEFAULT_FLOATING_BTN_SIZE + 6
    private lateinit var palette: FloatingPalette
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var recordOverlayView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /* 自动后退倒计时（收纳小方块右下角显示，剩余秒数） */
    private val tbCountdownHandler = Handler(Looper.getMainLooper())
    private var tbCountdownRemaining = 0
    private val tbCountdownRunnable = object : Runnable {
        override fun run() {
            if (tbCountdownRemaining > 0) {
                tbCountdownRemaining--
                updateTbCountdownText()
                if (tbCountdownRemaining > 0) {
                    tbCountdownHandler.postDelayed(this, 1000L)
                } else {
                    hideTbCountdown()
                }
            }
        }
    }

    /* 应用布局方案广播 */
    private val applyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(EXTRA_FLOATING_SCHEME_ID) ?: return
            if (!::rootView.isInitialized) return
            currentScheme = SchemeStore.getScheme(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), id)
            if (editMode) exitEditMode()
            buildControlPanel()
        }
    }

    /* 外观（大小/形状/配色）变更广播：实时重建面板与迷你 FAB */
    private val appearanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::rootView.isInitialized) return
            applyAppearance()
            buildControlPanel()
        }
    }

    /* 默认出现位置变更广播：实时重定位悬浮框 */
    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::rootView.isInitialized) return
            rootView.post { placeInitialPosition() }
        }
    }

    /* 绑定服务 */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::rootView.isInitialized) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val id = intent?.getStringExtra(EXTRA_FLOATING_SCHEME_ID)
            if (id != null) {
                currentScheme = SchemeStore.getScheme(prefs, id)
            }
            if (intent?.getBooleanExtra(EXTRA_FLOATING_EDIT_MODE, false) == true) {
                enterEditMode()
            } else {
                exitEditMode()
                buildControlPanel()
            }
        }
        return START_STICKY
    }

    /* 创建服务根视图并添加到窗口管理器 */
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        AutoSlideTileService.requestUpdate(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        rootView = createRootView()
        themedContext = rootView.context
        controlPanel = rootView.findViewById(R.id.control_panel)
        expandButton = rootView.findViewById(R.id.floating_expand_container)
        editBar = rootView.findViewById(R.id.editBar)
        editDoneButton = rootView.findViewById(R.id.editDoneButton)
        tbCountdownText = rootView.findViewById(R.id.floating_tb_countdown_text)
        layoutParams = createLayoutParams()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentScheme = SchemeStore.getScheme(prefs, SchemeStore.loadSelectedId(prefs))
        editMode = false
        applyAppearance()
        updatePanelTransparency()
        setupDragging()
        setupExpandButton()
        editDoneButton.setOnClickListener { exitEditModeAndSave() }
        buildControlPanel()
        if (editMode) enterEditMode()
        // 添加悬浮窗到窗口管理器
        try {
            windowManager.addView(rootView, layoutParams)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(
                "FloatingWindowService",
                "Failed to add floating window: overlay permission missing",
                e
            )
            isServiceRunning = false
            AutoSlideTileService.requestUpdate(this)
            stopSelf()
            return
        }
        // 视图添加后据配置放置初始位置（需测量宽高）
        rootView.post { placeInitialPosition() }
        // 监听自动滑动服务事件
        serviceScope.launch {
            SlideEventHub.eventFlow.collect { event ->
                if (!::rootView.isInitialized) return@collect
                when (event) {
                    is SlideEvent.ForceStop -> {
                        hideTbCountdown()
                        expand(stopSlide = false)
                    }
                    is SlideEvent.CustomTrajectoryCleared -> updateDirectionButtonIndicators()
                    is SlideEvent.FloatingTransparencyChanged -> updatePanelTransparency(event.transparency)
                }
            }
        }
        ContextCompat.registerReceiver(
            this, applyReceiver, IntentFilter(ACTION_APPLY_FLOATING_SCHEME),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, appearanceReceiver, IntentFilter(ACTION_FLOATING_APPEARANCE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, positionReceiver, IntentFilter(ACTION_FLOATING_POSITION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /* 服务销毁时移除悬浮窗 */
    override fun onDestroy() {
        isServiceRunning = false
        AutoSlideTileService.requestUpdate(this)
        serviceScope.cancel()
        hideTbCountdown()
        removeRecordView()
        // 持久化当前位置，供“原位置”选项使用
        if (::layoutParams.isInitialized) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putInt(KEY_FLOATING_LAST_X, layoutParams.x)
                putInt(KEY_FLOATING_LAST_Y, layoutParams.y)
            }
        }
        runCatching { unregisterReceiver(applyReceiver) }
        runCatching { unregisterReceiver(appearanceReceiver) }
        runCatching { unregisterReceiver(positionReceiver) }
        super.onDestroy()
        runCatching { windowManager.removeView(rootView) }
    }

    /**
     * 配置改变时更新悬浮窗位置
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::rootView.isInitialized && ::layoutParams.isInitialized) {
            rootView.post {
                if (::rootView.isInitialized && ::layoutParams.isInitialized) {
                    updateClampedPosition()
                }
            }
        }
    }

    /**
     * 创建悬浮窗根视图
     */
    @SuppressLint("InflateParams")
    private fun createRootView(): View {
        val themedContext: Context = ContextThemeWrapper(this, R.style.Theme_AutoSlide)
        return LayoutInflater.from(themedContext).inflate(R.layout.floating_window, null)
    }

    /**
     * 构造悬浮窗布局参数
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /* 设置拖拽事件处理 */
    private fun setupDragging() {
        val draggableRoot = rootView as? DraggableLinearLayout ?: return
        draggableRoot.setOnDragListener(object : DraggableLinearLayout.OnDragListener {
            override fun onDragDown(rawX: Float, rawY: Float) {
                initialX = layoutParams.x.toFloat()
                initialY = layoutParams.y.toFloat()
                initialTouchX = rawX
                initialTouchY = rawY
            }

            override fun onDragMove(rawX: Float, rawY: Float) {
                val deltaX = rawX - initialTouchX
                val deltaY = rawY - initialTouchY
                val targetX = (initialX + deltaX).toInt()
                val targetY = (initialY + deltaY).toInt()
                updateClampedPosition(targetX, targetY)
            }
        })
    }

    private fun setupExpandButton() {
        expandButton.setOnClickListener { expand() }
    }

    /**
     * 根据当前方案动态构建按钮面板
     */
    private fun buildControlPanel() {
        buttonViews.clear()
        controlPanel.removeAllViews()
        val cols = currentScheme.columns.coerceAtLeast(1)
        val rows = (currentScheme.maxRow() + 1).coerceAtLeast(1)
        for (r in 0 until rows) {
            val rowL = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (c in 0 until cols) {
                val placement = currentScheme.placements.firstOrNull { it.row == r && it.col == c }
                val frame = FrameLayout(themedContext).apply {
                    layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
                }
                if (placement != null) {
                    val btn = createFloatingButton(placement.type)
                    if (editMode) enableEditDrag(btn, placement.type) else bindButtonActions(btn, placement.type)
                    frame.addView(btn)
                    buttonViews[placement.type] = btn
                }
                rowL.addView(frame)
            }
            controlPanel.addView(rowL)
        }
        updateDirectionButtonIndicators()
    }

    /* 创建一个悬浮框按钮（按外观配置生成形状/大小/配色） */
    private fun createFloatingButton(type: FloatingButtonType): ImageButton {
        val (bgColor, iconColor, iconRes) = when (type) {
            FloatingButtonType.SETTING -> Triple(palette.btnSettingsBg, palette.btnSettingsIcon, R.drawable.ic_floating_settings)
            FloatingButtonType.CLOSE -> Triple(palette.btnCloseBg, palette.btnCloseIcon, R.drawable.ic_floating_close)
            else -> Triple(palette.btnBg, palette.btnIcon, iconFor(type))
        }
        return ImageButton(themedContext).apply {
            layoutParams = FrameLayout.LayoutParams(currentSizePx, currentSizePx).apply { gravity = Gravity.CENTER }
            background = GradientDrawable().apply {
                shape = if (currentShape == SHAPE_CIRCLE) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                setColor(bgColor)
                if (currentShape == SHAPE_SQUARE) cornerRadius = currentCornerPx.toFloat()
            }
            val iconPx = (currentSizePx * 0.55f).toInt().coerceAtLeast(1)
            val iconDrawable = ContextCompat.getDrawable(themedContext, iconRes)!!
            iconDrawable.setBounds(0, 0, iconPx, iconPx)
            setImageDrawable(iconDrawable)
            imageTintList = ColorStateList.valueOf(iconColor)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = themedContext.getString(type.labelRes)
            elevation = 6f
        }
    }

    private fun iconFor(type: FloatingButtonType): Int = when (type) {
        FloatingButtonType.UP -> R.drawable.ic_floating_arrow_up
        FloatingButtonType.DOWN -> R.drawable.ic_floating_arrow_down
        FloatingButtonType.LEFT -> R.drawable.ic_floating_arrow_left
        FloatingButtonType.RIGHT -> R.drawable.ic_floating_arrow_right
        FloatingButtonType.UPDOWN -> R.drawable.ic_floating_arrow_up_down
        FloatingButtonType.SETTING -> R.drawable.ic_floating_settings
        FloatingButtonType.CLOSE -> R.drawable.ic_floating_close
    }

    /* 正常模式：绑定按钮点击/长按逻辑 */
    private fun bindButtonActions(btn: ImageButton, type: FloatingButtonType) {
        when (type) {
            FloatingButtonType.SETTING -> btn.setOnClickListener {
                returnToMainActivity()
                AutoSlideService.getInstance()?.stopSlide()
                stopSelf()
            }
            FloatingButtonType.CLOSE -> btn.setOnClickListener {
                AutoSlideService.getInstance()?.stopSlide()
                stopSelf()
            }
            else -> {
                val direction = type.key
                btn.setOnClickListener {
                    val svc = AutoSlideService.getInstance() ?: return@setOnClickListener
                    svc.setDirection(direction)
                    startSlide()
                }
                btn.setOnLongClickListener {
                    if (type == FloatingButtonType.UPDOWN) return@setOnLongClickListener true
                    if (hasCustomTrajectory(direction)) showTrajectoryManageDialog(direction)
                    else startRecordingTrajectory(direction)
                    true
                }
            }
        }
    }

    /* 编辑模式：拖动按钮到目标格子（与同格按钮交换） */
    private fun enableEditDrag(btn: ImageButton, type: FloatingButtonType) {
        btn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = ev.rawX
                    dragStartRawY = ev.rawY
                    btn.translationX = 0f
                    btn.translationY = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    btn.translationX = ev.rawX - dragStartRawX
                    btn.translationY = ev.rawY - dragStartRawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btn.translationX = 0f
                    btn.translationY = 0f
                    val cols = currentScheme.columns.coerceAtLeast(1)
                    val rows = (currentScheme.maxRow() + 1).coerceAtLeast(1)
                    if (controlPanel.width > 0 && controlPanel.height > 0) {
                        val loc = IntArray(2)
                        controlPanel.getLocationOnScreen(loc)
                        val cellW = controlPanel.width.toFloat() / cols
                        val cellH = controlPanel.height.toFloat() / rows
                        val tc = ((ev.rawX - loc[0]) / cellW).toInt().coerceIn(0, cols - 1)
                        val tr = ((ev.rawY - loc[1]) / cellH).toInt().coerceIn(0, rows - 1)
                        
                        // movePlacement(type, tr, tc)   //会崩溃。开始进行编辑时，一拖动某一个按钮放开时，就闪退，拖动放置失败。

                        // 延后到本次触摸事件结束后再重建面板，避免移除正在被触摸的按钮引发崩溃。崩溃最可能是拖动中同步 removeAllViews() 把正在被触摸的按钮移除，触发 Android 触摸派发 NPE（经典 mid-gesture view removal 崩溃(移除视图崩溃)），用 post 延后重建即可规避，等本次触摸事件结束后再重建面板，放置生效且不崩溃。。先读取 colors.xml 以精确插入。
                        val targetRow = tr
                        val targetCol = tc
                        btn.post { movePlacement(type, targetRow, targetCol) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /* 移动按钮到目标格子，并与目标处已有按钮交换位置 */
    private fun movePlacement(type: FloatingButtonType, tr: Int, tc: Int) {
        val self = currentScheme.placementOf(type) ?: return
        if (self.row == tr && self.col == tc) {
            buildControlPanel()
            return
        }
        val target = currentScheme.placements.firstOrNull { it.row == tr && it.col == tc }
        val newPlacements = currentScheme.placements.map { p ->
            when {
                p.type == type -> p.copy(row = tr, col = tc)
                target != null && p.type == target.type -> p.copy(row = self.row, col = self.col)
                else -> p
            }
        }
        currentScheme = currentScheme.copy(placements = newPlacements)
        buildControlPanel()
    }

    private fun enterEditMode() {
        editMode = true
        (rootView as DraggableLinearLayout).dragEnabled = false
        editBar.visibility = View.VISIBLE
        setExpanded(true, false)
        buildControlPanel()
    }

    private fun exitEditMode() {
        editMode = false
        (rootView as DraggableLinearLayout).dragEnabled = true
        editBar.visibility = View.GONE
        buildControlPanel()
    }

    /* 退出编辑模式并保存方案（内置方案另存为自定义） */
    private fun exitEditModeAndSave() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val customs = SchemeStore.loadCustomSchemes(prefs).toMutableList()
        val saved = if (currentScheme.builtIn) {
            val clone = currentScheme.copy(
                id = "custom_${System.currentTimeMillis()}",
                name = currentScheme.name + " " + getString(R.string.scheme_copy_suffix),
                builtIn = false
            )
            customs.add(clone)
            clone
        } else {
            val idx = customs.indexOfFirst { it.id == currentScheme.id }
            if (idx >= 0) customs[idx] = currentScheme else customs.add(currentScheme)
            currentScheme
        }
        SchemeStore.saveCustomSchemes(prefs, customs)
        SchemeStore.saveSelectedId(prefs, saved.id)
        sendBroadcast(Intent(ACTION_FLOATING_SCHEME_CHANGED))
        editMode = false
        (rootView as DraggableLinearLayout).dragEnabled = true
        editBar.visibility = View.GONE
        currentScheme = saved
        buildControlPanel()
        Toast.makeText(this, R.string.scheme_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新悬浮窗位置并限制在屏幕可视范围内
     */
    private fun updateClampedPosition(
        targetX: Int = layoutParams.x, targetY: Int = layoutParams.y
    ) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val viewWidth = rootView.width
        val viewHeight = rootView.height
        layoutParams.x = targetX.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
        layoutParams.y = targetY.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    /**
     * 依据配置项把悬浮框放置到指定初始位置
     */
    private fun placeInitialPosition() {
        if (!::rootView.isInitialized || !::layoutParams.isInitialized) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pos = prefs.getInt(KEY_FLOATING_POSITION, DEFAULT_FLOATING_POSITION)
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val w = rootView.width
        val h = rootView.height
        var x = 0
        var y = 0
        when (pos) {
            POS_TOP_LEFT -> { x = 0; y = 0 }
            POS_TOP_CENTER -> { x = (screenW - w) / 2; y = 0 }
            POS_TOP_RIGHT -> { x = screenW - w; y = 0 }
            POS_CENTER_LEFT -> { x = 0; y = (screenH - h) / 2 }
            POS_CENTER_RIGHT -> { x = screenW - w; y = (screenH - h) / 2 }
            POS_BOTTOM_LEFT -> { x = 0; y = screenH - h }
            POS_BOTTOM_CENTER -> { x = (screenW - w) / 2; y = screenH - h }
            POS_BOTTOM_RIGHT -> { x = screenW - w; y = screenH - h }
            POS_ORIGINAL -> {
                val lx = prefs.getInt(KEY_FLOATING_LAST_X, -1)
                val ly = prefs.getInt(KEY_FLOATING_LAST_Y, -1)
                if (lx >= 0 && ly >= 0) { x = lx; y = ly } else { x = 0; y = 0 }
            }
        }
        updateClampedPosition(x, y)
    }

    /**
     * 显示轨迹管理对话框
     */
    private fun showTrajectoryManageDialog(direction: String) {
        val items = arrayOf(getString(R.string.record), getString(R.string.reset))
        val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_AutoSlide))
            .setTitle(getTrajectoryManageTitle(direction)).setItems(items) { _, which ->
                when (items[which]) {
                    getString(R.string.record) -> startRecordingTrajectory(direction)
                    getString(R.string.reset) -> clearCustomTrajectory(direction)
                }
            }.setNegativeButton(R.string.cancel, null)
        showSystemAlertDialog(builder)
    }

    /**
     * 开始录制轨迹
     */
    private fun startRecordingTrajectory(direction: String) {
        AutoSlideService.getInstance()?.stopSlide()
        minimize()
        val instructionText = getRecordDirectionInstruction(direction)
        val recordView =
            TrajectoryRecordView(this, instructionText = instructionText, onTrajectoryRecorded = { points ->
                removeRecordView()
                expand()
                val detected = detectTrajectoryDirection(points)
                if (detected != direction && detected.isNotEmpty()) {
                    showDirectionMismatchDialog(points, direction, detected)
                } else {
                    saveTrajectory(points, direction)
                    updateDirectionButtonIndicators()
                    Toast.makeText(this, R.string.trajectory_saved, Toast.LENGTH_SHORT).show()
                }
            }, onCancel = {
                removeRecordView()
                expand()
            })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(recordView, params)
            recordOverlayView = recordView
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "Failed to add record view", e)
        }
    }

    /* 移除录制视图 */
    private fun removeRecordView() {
        val recordView = recordOverlayView ?: return
        runCatching { windowManager.removeView(recordView) }
        recordOverlayView = null
    }

    /**
     * 保存轨迹
     */
    private fun saveTrajectory(points: List<PointF>, direction: String) {
        if (points.isEmpty()) return
        val sb = StringBuilder()
        points.forEach { point ->
            sb.append("${point.x},${point.y};")
        }
        val key = getTrajectoryKey(direction) ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(key, sb.toString())
        }
    }

    /**
     * 获取方向显示名称
     */
    private fun getDirectionDisplayName(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.desc_slide_up)
        DIRECTION_DOWN -> getString(R.string.desc_slide_down)
        DIRECTION_LEFT -> getString(R.string.desc_slide_left)
        DIRECTION_RIGHT -> getString(R.string.desc_slide_right)
        DIRECTION_UP_DOWN -> getString(R.string.desc_slide_up_down)
        else -> direction
    }

    /**
     * 获取轨迹管理标题
     */
    private fun getTrajectoryManageTitle(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.trajectory_title_up)
        DIRECTION_DOWN -> getString(R.string.trajectory_title_down)
        DIRECTION_LEFT -> getString(R.string.trajectory_title_left)
        DIRECTION_RIGHT -> getString(R.string.trajectory_title_right)
        else -> direction
    }

    /**
     * 获取录制时的方向提示文本
     */
    private fun getRecordDirectionInstruction(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.record_direction_up_explicit)
        DIRECTION_DOWN -> getString(R.string.record_direction_down_explicit)
        DIRECTION_LEFT -> getString(R.string.record_direction_left_explicit)
        DIRECTION_RIGHT -> getString(R.string.record_direction_right_explicit)
        else -> direction
    }

    /**
     * 根据轨迹首尾点位移检测主导方向
     */
    private fun detectTrajectoryDirection(points: List<PointF>): String {
        if (points.size < 2) return ""
        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        return when {
            abs(dx) > abs(dy) -> if (dx > 0) DIRECTION_LEFT else DIRECTION_RIGHT
            else -> if (dy > 0) DIRECTION_UP else DIRECTION_DOWN
        }
    }

    /**
     * 当录制轨迹方向与所选方向不一致时弹出确认对话框
     */
    private fun showDirectionMismatchDialog(
        points: List<PointF>, selectedDirection: String, detectedDirection: String
    ) {
        val selectedName = getDirectionDisplayName(selectedDirection)
        val detectedName = getDirectionDisplayName(detectedDirection)
        val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_AutoSlide))
            .setTitle(R.string.trajectory_mismatch_title)
            .setMessage(getString(R.string.trajectory_mismatch_message, detectedName, selectedName))
            .setPositiveButton(R.string.save_anyway) { _, _ ->
                saveTrajectory(points, selectedDirection)
                updateDirectionButtonIndicators()
                Toast.makeText(this, R.string.trajectory_saved, Toast.LENGTH_SHORT).show()
            }.setNeutralButton(R.string.record_again) { _, _ ->
                startRecordingTrajectory(selectedDirection)
            }.setNegativeButton(R.string.cancel, null)
        showSystemAlertDialog(builder)
    }

    /* 读取配置并应用外观（形状/大小/圆角/配色），同步到面板与迷你 FAB */
    private fun applyAppearance() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentShape = prefs.getInt(KEY_FLOATING_SHAPE, DEFAULT_FLOATING_SHAPE).coerceIn(SHAPE_CIRCLE, SHAPE_SQUARE)
        val sizeDp = prefs.getInt(KEY_FLOATING_BTN_SIZE, DEFAULT_FLOATING_BTN_SIZE).coerceIn(36, 72)
        val cornerDp = prefs.getInt(KEY_FLOATING_CORNER, DEFAULT_FLOATING_CORNER).coerceIn(0, sizeDp / 2)
        currentSizePx = (sizeDp * density).toInt()
        currentCornerPx = (cornerDp * density).toInt()
        cellPx = currentSizePx + (6 * density).toInt()
        palette = resolveFloatingPalette(prefs)
        applyStaticAppearance()
    }

    /* 应用与按钮尺寸无关的静态外观：面板底色 + 迷你 FAB 配色 */
    private fun applyStaticAppearance() {
        controlPanel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(palette.panelBg)
        }
        updatePanelTransparency()
        val fab = rootView.findViewById<FloatingActionButton>(R.id.floating_expand_button)
        fab.backgroundTintList = ColorStateList.valueOf(palette.btnBg)
        fab.imageTintList = ColorStateList.valueOf(palette.btnIcon)
    }

    /* 更新方向按钮视觉标记 */
    private fun updateDirectionButtonIndicators() {
        buttonViews.forEach { (type, btn) ->
            if (type == FloatingButtonType.SETTING || type == FloatingButtonType.CLOSE) return@forEach
            val has = hasCustomTrajectory(type.key)
            val bgColor = if (has) palette.btnActive else palette.btnBg
            val iconColor = if (has) palette.btnActiveIcon else palette.btnIcon
            btn.background = GradientDrawable().apply {
                shape = if (currentShape == SHAPE_CIRCLE) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                setColor(bgColor)
                if (currentShape == SHAPE_SQUARE) cornerRadius = currentCornerPx.toFloat()
            }
            btn.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    /**
     * 设置悬浮窗展开与最小化状态
     */
    private fun setExpanded(isExpanded: Boolean, stopSlide: Boolean = true) {
        if (isExpanded) {
            controlPanel.visibility = View.VISIBLE
            expandButton.visibility = View.GONE
        } else {
            controlPanel.visibility = View.GONE
            expandButton.visibility = View.VISIBLE
        }
        windowManager.updateViewLayout(rootView, layoutParams)
        if (isExpanded && stopSlide) {
            AutoSlideService.getInstance()?.stopSlide()
        }
    }

    /* 最小化悬浮窗 */
    private fun minimize() {
        setExpanded(false)
    }

    /**
     * 展开悬浮窗并停止当前自动滑动
     */
    private fun expand(stopSlide: Boolean = true) {
        hideTbCountdown()
        setExpanded(true, stopSlide)
    }

    /* 启动自动滑动服务 */
    private fun startSlide() {
        val config = getSlideConfig()
        if (config.tbAutoBack) {
            startTbCountdown(config.tbBackTime)
        } else {
            hideTbCountdown()
        }
        minimize()
        AutoSlideService.getInstance()?.startSlideWithConfig(config)
    }

    /* 启动自动后退倒计时（收纳小方块右下角显示剩余秒数） */
    private fun startTbCountdown(seconds: Int) {
        tbCountdownHandler.removeCallbacks(tbCountdownRunnable)
        tbCountdownRemaining = seconds.coerceAtLeast(1)
        updateTbCountdownText()
        tbCountdownHandler.postDelayed(tbCountdownRunnable, 1000L)
    }

    /* 刷新倒计时文本 */
    private fun updateTbCountdownText() {
        tbCountdownText.text = "${tbCountdownRemaining}s"
        tbCountdownText.visibility = View.VISIBLE
    }

    /* 隐藏并清除自动后退倒计时 */
    private fun hideTbCountdown() {
        tbCountdownHandler.removeCallbacks(tbCountdownRunnable)
        tbCountdownRemaining = 0
        tbCountdownText.visibility = View.GONE
    }

    /* 返回主界面 */
    private fun returnToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    /**
     * 更新悬浮窗底板透明度
     */
    private fun updatePanelTransparency(transparencyValue: Int? = null) {
        val transparency = transparencyValue ?: getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_FLOATING_TRANSPARENCY, DEFAULT_FLOATING_TRANSPARENCY)
            .coerceIn(0, 100)
        val alpha = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
        controlPanel.background?.mutate()?.alpha = alpha
    }

    /**
     * 显示系统级对话框
     */
    private fun showSystemAlertDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    companion object {
        private var isServiceRunning = false

        /**
         * 获取悬浮窗服务运行状态
         */
        @JvmStatic
        fun isRunning(): Boolean = isServiceRunning
    }
}
