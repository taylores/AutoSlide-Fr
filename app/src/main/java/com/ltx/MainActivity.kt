package com.ltx

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ltx.databinding.ActivityMainBinding
import com.ltx.service.AutoSlideService
import com.ltx.service.FloatingWindowService
import com.ltx.MacroPlanActivity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.GridLayout
import android.widget.ImageButton
import com.ltx.ACTION_FLOATING_APPEARANCE_CHANGED
import com.ltx.DEFAULT_FLOATING_BTN_SIZE
import com.ltx.DEFAULT_FLOATING_CORNER
import com.ltx.DEFAULT_FLOATING_CUSTOM_COLOR
import com.ltx.DEFAULT_FLOATING_SHAPE
import com.ltx.DEFAULT_FLOATING_THEME
import com.ltx.KEY_FLOATING_BTN_SIZE
import com.ltx.KEY_FLOATING_CORNER
import com.ltx.KEY_FLOATING_CUSTOM_COLOR
import com.ltx.KEY_FLOATING_SHAPE
import com.ltx.KEY_FLOATING_THEME
import com.ltx.SHAPE_CIRCLE
import com.ltx.SHAPE_SQUARE
import com.ltx.THEME_CUSTOM
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import android.R as AndroidR
import com.google.android.material.R as MaterialR

/**
 * 应用主界面
 *
 * @author tianxing
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    /* 自动后退常用秒数按钮集合（预置 + 自定义），用于高亮当前选中项 */
    private val tbQuickButtons = mutableListOf<MaterialButton>()
    private val presetTbSeconds = setOf(16, 21, 31, 91)

    /* 悬浮框保存布局方案后刷新方案列表 */
    private val schemeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isFinishing && !isDestroyed) loadSchemesUI()
        }
    }

    /* 全局常量 */
    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 100
        // 无障碍授权方式选项常量
        private const val OPTION_MANUAL = 0
        private const val OPTION_SHIZUKU = 1
        private const val OPTION_ADB = 2
    }

    /**
     * Shizuku命令执行结果数据类
     *
     * @param exitCode 命令执行退出码
     * @param stdout 命令执行标准输出
     * @param stderr 命令执行标准错误输出
     */
    private data class ShizukuCommandResult(
        val exitCode: Int, val stdout: String, val stderr: String
    )

    /* Shizuku授权待执行的回调 */
    private var pendingShizukuOnGranted: (() -> Unit)? = null
    private var pendingShizukuOnFailed: (() -> Unit)? = null

    /* Shizuku权限请求监听器 */
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            pendingShizukuOnGranted?.invoke()
        } else {
            Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
            pendingShizukuOnFailed?.invoke()
        }
        pendingShizukuOnGranted = null
        pendingShizukuOnFailed = null
    }

    /* 无障碍服务权限开关监听器 */
    private val accessibilitySwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            onAccessibilityServicePermissionSwitchEnabled()
        } else {
            onAccessibilityServicePermissionSwitchDisabled()
        }
    }

    /* 悬浮窗权限开关监听器 */
    private val overlaySwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        updateOverlaySwitchState(!isChecked)
    }

    /**
     * 更新权限开关状态
     *
     * @param switch 目标开关
     * @param checked 开关状态
     * @param listener 监听器
     */
    private fun updateSwitchState(
        switch: CompoundButton, checked: Boolean, listener: CompoundButton.OnCheckedChangeListener
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        switch.jumpDrawablesToCurrentState()
        switch.setOnCheckedChangeListener(listener)
    }

    /**
     * 更新无障碍服务权限开关状态
     *
     * @param checked 开关状态
     */
    private fun updateAccessibilitySwitchState(checked: Boolean) {
        updateSwitchState(binding.accessibilityServicePermissionSwitch, checked, accessibilitySwitchListener)
    }

    /**
     * 更新悬浮窗权限开关状态
     *
     * @param checked 开关状态
     */
    private fun updateOverlaySwitchState(checked: Boolean) {
        updateSwitchState(binding.overlayPermissionSwitch, checked, overlaySwitchListener)
    }

    /**
     * 初始化Activity界面布局和事件绑定
     *
     * @param savedInstanceState 系统恢复状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 初始化SharedPreferences用于本地配置存储
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        restoreSettings()
        setupPauseControls()
        setupTbControls()
        setupSpeedControl()
        setupTransparencyControl()
        binding.accessibilityServicePermissionSwitch.setOnCheckedChangeListener(accessibilitySwitchListener)
        binding.overlayPermissionSwitch.setOnCheckedChangeListener(overlaySwitchListener)
        setupStartButton()
        setupUpdateButton()
        // 分类卡片展开/收纳（偏好设置默认展开，必要权限默认展开）
        setupCollapsible(binding.preferenceHeader, binding.preferenceParamsContainer, binding.preferenceChevron, expanded = true)
        setupCollapsible(binding.permissionHeader, binding.permissionParamsContainer, binding.permissionChevron, expanded = true)
        setupUiSettings()
        // 监听悬浮框保存布局方案后的刷新广播（overlay 不触发 onResume，需主动刷新）
        ContextCompat.registerReceiver(
            this, schemeChangedReceiver, IntentFilter(ACTION_FLOATING_SCHEME_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 注册Shizuku监听器
        binding.root.post {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    /* 活动恢复时检查⌈无障碍服务权限⌋并同步开关状态 */
    override fun onResume() {
        super.onResume()
        loadSchemesUI()
        // 使用协程异步检查权限并同步开关状态
        lifecycleScope.launch(ioDispatcher) {
            val hasWriteSecure = hasWriteSecureSettingsPermission()
            val isAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            // 有⌈写入安全设置权限⌋时直接开启⌈无障碍服务权限⌋
            if (hasWriteSecure && !isAccessibilityEnabled) {
                changeAccessibilityServicePermissionState(enable = true)
            }
            // 获取最终的权限状态
            val finalAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            val finalCanDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
            // 回到主线程同步开关状态并应用无动画过渡
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    updateAccessibilitySwitchState(finalAccessibilityEnabled)
                    updateOverlaySwitchState(finalCanDrawOverlays)
                }
            }
        }
        UpdateChecker.onHostResumed(this)
    }

    /* 活动销毁时移除Shizuku权限请求监听器 */
    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(schemeChangedReceiver) }
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    /* 恢复上次配置 */
    private fun restoreSettings() {
        val config = getSlideConfig()
        binding.speedSlider.value = config.speed.toFloat()
        binding.speedValueText.text = config.speed.toString()
        binding.speedSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        val transparency = preferences.getInt(KEY_FLOATING_TRANSPARENCY, DEFAULT_FLOATING_TRANSPARENCY)
        binding.transparencySlider.value = transparency.toFloat()
        binding.transparencyValueText.text = getString(R.string.transparency_percentage_format, transparency)
        binding.transparencySlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        when (config.pauseMode) {
            PAUSE_MODE_NONE -> binding.pauseModeToggleGroup.check(R.id.btnNoPause)
            PAUSE_MODE_FIXED -> binding.pauseModeToggleGroup.check(R.id.btnFixedPause)
            PAUSE_MODE_RANDOM -> binding.pauseModeToggleGroup.check(R.id.btnRandomPause)
        }
        // 动态调整固定停顿时间滑块的最大值
        binding.pauseTimeSlider.valueTo = maxOf(10, config.pauseTime).toFloat()
        binding.pauseTimeSlider.value = config.pauseTime.toFloat()
        // 动态调整随机停顿范围滑块的最大值并排序赋值
        binding.randomPauseTimeSlider.valueTo = maxOf(10, config.minPauseTime, config.maxPauseTime).toFloat()
        binding.randomPauseTimeSlider.values = listOf(config.minPauseTime.toFloat(), config.maxPauseTime.toFloat()).sorted()
        binding.pauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.randomPauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        // 恢复tb模式配置
        val tbAutoBack = preferences.getBoolean(KEY_TB_AUTO_BACK, DEFAULT_TB_AUTO_BACK)
        binding.tbAutoBackCheckbox.isChecked = tbAutoBack
        val tbBackTime = preferences.getInt(KEY_TB_BACK_TIME, DEFAULT_TB_BACK_TIME)
        binding.tbBackTimeSlider.valueTo = maxOf(60, tbBackTime).toFloat()
        binding.tbBackTimeSlider.value = tbBackTime.toFloat()
        binding.tbBackTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.tbBackTimeValueText.text = tbBackTime.toString()
        binding.tbBackTimeRow.isVisible = tbAutoBack
        binding.tbBackTimeSlider.isVisible = tbAutoBack
        binding.tbQuickSelectRow.isVisible = tbAutoBack
        binding.tbPromptRow.isVisible = tbAutoBack
        val tbSound = preferences.getBoolean(KEY_TB_SOUND, DEFAULT_TB_SOUND)
        val tbVibrate = preferences.getBoolean(KEY_TB_VIBRATE, DEFAULT_TB_VIBRATE)
        binding.tbSoundCheckbox.isChecked = tbSound
        binding.tbVibrateCheckbox.isChecked = tbVibrate
        updatePauseTimeVisibility(config.pauseMode)
    }

    /* 绑定停顿相关控件事件并持久化用户设置 */
    @SuppressLint("SetTextI18n")
    private fun setupPauseControls() {
        binding.pauseModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val pauseMode = when (checkedId) {
                    R.id.btnNoPause -> PAUSE_MODE_NONE
                    R.id.btnFixedPause -> PAUSE_MODE_FIXED
                    R.id.btnRandomPause -> PAUSE_MODE_RANDOM
                    else -> PAUSE_MODE_NONE
                }
                updatePauseTimeVisibility(pauseMode)
                preferences.edit { putInt(KEY_PAUSE_MODE, pauseMode) }
                // 更新停顿配置
                syncPauseConfigToService()
            }
        }
        // 停顿时间文本绑定点击事件
        binding.pauseTimeValueText.setOnClickListener {
            if (preferences.getInt(
                    KEY_PAUSE_MODE, PAUSE_MODE_NONE
                ) != PAUSE_MODE_FIXED
            ) return@setOnClickListener
            showCustomPauseTimeDialog()
        }
        // 绑定停顿时长滑块事件
        binding.pauseTimeSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.pauseTimeValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_PAUSE_TIME, progress) }
            }
        }
        // 绑定随机停顿时长范围滑块事件
        binding.randomPauseTimeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            val min = values[0].toInt()
            val max = values[1].toInt()
            binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            if (fromUser) {
                preferences.edit {
                    putInt(KEY_MIN_PAUSE_TIME, min)
                    putInt(KEY_MAX_PAUSE_TIME, max)
                }
            }
        }
        // 绑定停顿时长滑块触摸松开事件
        binding.pauseTimeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                syncPauseConfigToService()
            }
        })
        // 绑定随机停顿时长范围滑块触摸松开事件
        binding.randomPauseTimeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                syncPauseConfigToService()
            }
        })
    }

    /* 同步最新停顿配置到自动滑动服务 */
    private fun syncPauseConfigToService() {
        AutoSlideService.getInstance()?.updatePauseConfig(getSlideConfig())
    }

    /* 显示自定义停顿时间输入对话框 */
    @SuppressLint("SetTextI18n")
    private fun showCustomPauseTimeDialog() {
        val textInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.custom_pause_time)
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(binding.pauseTimeValueText.text)
            setSelection(text?.length ?: 0)
        }
        textInputLayout.addView(editText)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 3)
            addView(textInputLayout)
        }
        // 显示自定义停顿时间对话框
        AlertDialog.Builder(this).setTitle(R.string.custom_pause_time).setView(container)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                val value = editText.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    preferences.edit { putInt(KEY_PAUSE_TIME, value) }
                    binding.pauseTimeSlider.valueTo = maxOf(10, value).toFloat()
                    binding.pauseTimeSlider.value = value.toFloat()
                    binding.pauseTimeValueText.text = value.toString()
                    // 更新停顿配置
                    syncPauseConfigToService()
                } else {
                    Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /* 分类卡片展开/收纳：点击标题栏切换参数容器可见性并旋转箭头 */
    private fun setupCollapsible(header: View, params: View, chevron: ImageView, expanded: Boolean) {
        fun apply(expand: Boolean) {
            params.isVisible = expand
            chevron.animate().rotation(if (expand) 0f else -90f).setDuration(180).start()
        }
        apply(expanded)
        header.setOnClickListener { apply(!params.isVisible) }
    }

    /* 绑定tb模式（定时后退）控件事件并持久化用户设置 */
    private fun setupTbControls() {
        binding.tbAutoBackCheckbox.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit { putBoolean(KEY_TB_AUTO_BACK, isChecked) }
            binding.tbBackTimeRow.isVisible = isChecked
            binding.tbBackTimeSlider.isVisible = isChecked
            binding.tbQuickSelectRow.isVisible = isChecked
            binding.tbPromptRow.isVisible = isChecked
            syncPauseConfigToService()
        }
        // 常用后退时间快捷选择（预置 16秒 / 21秒 / 31秒 / 91秒 + 用户自定义）
        registerTbQuickButton(binding.tbQuick16Button, 16)
        registerTbQuickButton(binding.tbQuick21Button, 21)
        registerTbQuickButton(binding.tbQuick31Button, 31)
        registerTbQuickButton(binding.tbQuick91Button, 91)
        // 添加自定义常用秒数入口（短按新增 / 长按批量管理删除）
        binding.tbQuickAddButton.setOnClickListener { showAddCustomSecondDialog() }
        binding.tbQuickAddButton.setOnLongClickListener {
            showManageCustomSecondsDialog()
            true
        }
        // 恢复已保存的自定义秒数按钮（插在“+”之前，即 91秒 之后）
        preferences.getString(KEY_TB_CUSTOM_SECONDS, "").orEmpty()
            .split(",").mapNotNull { it.toIntOrNull() }.filter { it > 0 && it !in presetTbSeconds }
            .distinct().forEach { addCustomTbSecond(it, persist = false) }
        // 高亮当前选中的秒数按钮
        updateTbQuickHighlight(binding.tbBackTimeSlider.value.toInt())
        // 音效提示 / 震动提示 勾选（仅影响自动后退触发时的提示方式）
        binding.tbSoundCheckbox.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit { putBoolean(KEY_TB_SOUND, isChecked) }
            syncPauseConfigToService()
        }
        binding.tbVibrateCheckbox.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit { putBoolean(KEY_TB_VIBRATE, isChecked) }
            syncPauseConfigToService()
        }
        binding.tbBackTimeValueText.setOnClickListener {
            if (!binding.tbAutoBackCheckbox.isChecked) return@setOnClickListener
            showCustomTbBackTimeDialog()
        }
        binding.tbBackTimeSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.tbBackTimeValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_TB_BACK_TIME, progress) }
                updateTbQuickHighlight(progress)
            }
        }
        binding.tbBackTimeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                syncPauseConfigToService()
            }
        })
    }

    /* 设置自动后退时间（快捷按钮 / 自定义统一入口） */
    private fun setTbBackTime(seconds: Int) {
        val slider = binding.tbBackTimeSlider
        // 容错：点击的秒数若超出滑动条当前最大值，先扩容 valueTo，避免 value > valueTo 抛异常闪退
        if (slider.valueTo < seconds) {
            slider.valueTo = maxOf(60f, seconds.toFloat())
        }
        slider.value = seconds.toFloat()
        binding.tbBackTimeValueText.text = seconds.toString()
        preferences.edit { putInt(KEY_TB_BACK_TIME, seconds) }
        updateTbQuickHighlight(seconds)
        syncPauseConfigToService()
    }

    /* 注册一个自动后退常用秒数按钮（绑定点击与标记；仅自定义按钮支持长按删除） */
    private fun registerTbQuickButton(button: MaterialButton, seconds: Int) {
        button.tag = seconds
        button.setOnClickListener { setTbBackTime(seconds) }
        if (seconds !in presetTbSeconds) {
            button.setOnLongClickListener {
                removeCustomTbSecond(seconds)
                true
            }
        }
        tbQuickButtons.add(button)
    }

    /* 创建一个自动后退常用秒数按钮（与预置按钮同款样式） */
    @SuppressLint("SetTextI18n")
    private fun createTbQuickButton(seconds: Int): MaterialButton {
        val ref = binding.tbQuick16Button
        val density = resources.displayMetrics.density
        val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.text = "${seconds}s"
        btn.minWidth = 0
        btn.minimumWidth = 0
        btn.isAllCaps = false
        val hPad = (8 * density).toInt()
        btn.setPadding(hPad, ref.paddingTop, hPad, ref.paddingBottom)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = (8 * density).toInt()
        btn.layoutParams = lp
        btn.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.toggle_btn_bg)
        )
        btn.setTextColor(ContextCompat.getColor(this, R.color.toggle_btn_text))
        return btn
    }

    /* 新增一个自定义常用秒数按钮（插在“+”之前，即 91秒 之后） */
    private fun addCustomTbSecond(seconds: Int, persist: Boolean = true) {
        if (tbQuickButtons.any { it.tag == seconds }) return
        val btn = createTbQuickButton(seconds)
        registerTbQuickButton(btn, seconds)
        binding.tbQuickButtonsRow.addView(btn, binding.tbQuickButtonsRow.childCount - 1)
        if (persist) updateTbCustomSecondsPref()
    }

    /* 持久化自定义常用秒数列表 */
    private fun updateTbCustomSecondsPref() {
        val customs = tbQuickButtons.mapNotNull { it.tag as? Int }
            .filter { it !in presetTbSeconds }.distinct().sorted()
        preferences.edit { putString(KEY_TB_CUSTOM_SECONDS, customs.joinToString(",")) }
    }

    /* 删除一个自定义常用秒数按钮（预置按钮不可删）；silent 用于批量删除时只弹一次总提示 */
    private fun removeCustomTbSecond(seconds: Int, silent: Boolean = false) {
        val idx = tbQuickButtons.indexOfFirst { it.tag == seconds }
        if (idx < 0) return
        binding.tbQuickButtonsRow.removeView(tbQuickButtons[idx])
        tbQuickButtons.removeAt(idx)
        updateTbCustomSecondsPref()
        updateTbQuickHighlight(binding.tbBackTimeSlider.value.toInt())
        if (!silent) {
            Toast.makeText(this, getString(R.string.tb_custom_second_deleted, "${seconds}s"), Toast.LENGTH_SHORT).show()
        }
    }

    /* 批量管理自定义常用秒数：多选后删除（长按“+”按钮进入） */
    @SuppressLint("SetTextI18n")
    private fun showManageCustomSecondsDialog() {
        val customs = tbQuickButtons.mapNotNull { it.tag as? Int }
            .filter { it !in presetTbSeconds }.distinct().sorted()
        if (customs.isEmpty()) {
            Toast.makeText(this, R.string.tb_no_custom_second, Toast.LENGTH_SHORT).show()
            return
        }
        val items = customs.map { "${it}s" }.toTypedArray()
        val checked = BooleanArray(customs.size)
        val selected = mutableSetOf<Int>()
        var dialogRef: AlertDialog? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tb_manage_custom_second)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                if (isChecked) selected.add(customs[which]) else selected.remove(customs[which])
            }
            .setNeutralButton(R.string.tb_select_all) { _, _ ->
                val lv = dialogRef?.listView
                for (i in customs.indices) {
                    checked[i] = true
                    selected.add(customs[i])
                    lv?.setItemChecked(i, true)
                }
            }
            .setPositiveButton(R.string.tb_delete_selected) { _, _ ->
                if (selected.isNotEmpty()) {
                    val n = selected.size
                    selected.toList().forEach { removeCustomTbSecond(it, silent = true) }
                    Toast.makeText(this, getString(R.string.tb_custom_second_deleted_multi, n), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialogRef = dialog
        dialog.show()
    }

    /* 高亮当前选中的秒数按钮，其余恢复默认底色 */
    private fun updateTbQuickHighlight(seconds: Int) {
        for (btn in tbQuickButtons) {
            val sec = btn.tag as? Int ?: continue
            val selected = sec == seconds
            btn.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (selected) R.color.primary else R.color.toggle_btn_bg)
            )
            btn.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.on_primary else R.color.toggle_btn_text)
            )
        }
    }

    /* 显示添加自定义常用秒数对话框 */
    @SuppressLint("SetTextI18n")
    private fun showAddCustomSecondDialog() {
        val textInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.tb_custom_second_hint)
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        textInputLayout.addView(editText)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 3)
            addView(textInputLayout)
        }
        AlertDialog.Builder(this).setTitle(R.string.tb_custom_second_hint).setView(container)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                val value = editText.text.toString().toIntOrNull()
                if (value == null || value <= 0) {
                    Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (tbQuickButtons.any { it.tag == value }) {
                    Toast.makeText(this, R.string.tb_custom_second_exists, Toast.LENGTH_SHORT).show()
                    setTbBackTime(value)
                    return@setPositiveButton
                }
                addCustomTbSecond(value)
                setTbBackTime(value)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /* 显示自定义后退时间输入对话框 */
    @SuppressLint("SetTextI18n")
    private fun showCustomTbBackTimeDialog() {
        val textInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.custom_tb_back_time)
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(binding.tbBackTimeValueText.text)
            setSelection(text?.length ?: 0)
        }
        textInputLayout.addView(editText)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 3)
            addView(textInputLayout)
        }
        AlertDialog.Builder(this).setTitle(R.string.custom_tb_back_time).setView(container)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                val value = editText.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    preferences.edit { putInt(KEY_TB_BACK_TIME, value) }
                    binding.tbBackTimeSlider.valueTo = maxOf(60, value).toFloat()
                    binding.tbBackTimeSlider.value = value.toFloat()
                    binding.tbBackTimeValueText.text = value.toString()
                    updateTbQuickHighlight(value)
                    syncPauseConfigToService()
                } else {
                    Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /* 绑定速度滑块事件 */
    private fun setupSpeedControl() {
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.speedValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_SPEED, progress) }
            }
        }
        binding.speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                AutoSlideService.getInstance()?.updateSpeed(slider.value.toInt())
            }
        })
    }

    /* 绑定悬浮窗底板透明度滑块事件 */
    private fun setupTransparencyControl() {
        binding.transparencySlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.transparencyValueText.text = getString(R.string.transparency_percentage_format, progress)
            if (fromUser) {
                preferences.edit { putInt(KEY_FLOATING_TRANSPARENCY, progress) }
                SlideEventHub.sendEvent(SlideEvent.FloatingTransparencyChanged(progress))
            }
        }
    }

    /* 处理⌈无障碍服务权限⌋开关打开动作 */
    private fun onAccessibilityServicePermissionSwitchEnabled() {
        if (tryEnableAccessibilityViaSecureSettings()) {
            return
        }
        // 展示⌈无障碍服务权限⌋选项弹窗
        showAccessibilityServicePermissionOptionDialog()
    }

    /* 处理⌈无障碍服务权限⌋开关关闭动作 */
    private fun onAccessibilityServicePermissionSwitchDisabled() {
        lifecycleScope.launch(ioDispatcher) {
            val isEnabled = isAccessibilityServicePermissionEnabled()
            withContext(mainDispatcher) {
                if (!isEnabled) {
                    return@withContext
                }
                // 有⌈写入安全设置权限⌋时直接关闭⌈无障碍服务权限⌋
                if (hasWriteSecureSettingsPermission()) {
                    changeAccessibilityServicePermissionState(enable = false)
                    Toast.makeText(this@MainActivity, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT)
                        .show()
                    return@withContext
                }
                // 打开⌈无障碍⌋设置页
                openAppAccessibilitySettings()
            }
        }
    }

    /* 展示⌈无障碍服务权限⌋选项弹窗 */
    private fun showAccessibilityServicePermissionOptionDialog() = with(AlertDialog.Builder(this)) {
        // 选项数组
        val options = arrayOf(
            getString(R.string.manual_enable),
            getString(R.string.shizuku_authorization),
            getString(R.string.adb_authorization)
        )
        // 设置标题
        setTitle(R.string.choose_enable_method)
        // 设置选项监听器
        setItems(options) { _, which ->
            when (which) {
                OPTION_MANUAL -> openAppAccessibilitySettings()
                OPTION_SHIZUKU -> handleShizukuAuthorization(
                    onGranted = { grantPermissionViaShizuku() },
                    onFailed = { updateAccessibilitySwitchState(false) })
                OPTION_ADB -> {
                    showAdbCommandDialog()
                    updateAccessibilitySwitchState(false)
                }
            }
        }
        // 设置取消监听器
        setOnCancelListener { updateAccessibilitySwitchState(false) }
        // 展示对话框
        show()
    }

    /* 处理Shizuku授权(通用) */
    private fun handleShizukuAuthorization(onGranted: () -> Unit, onFailed: () -> Unit) {
        // 检查Shizuku是否运行
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
            onFailed()
            return
        }
        // 检查Shizuku权限是否已授权
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingShizukuOnGranted = onGranted
            pendingShizukuOnFailed = onFailed
            try {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Failed to request Shizuku permission", exception)
                Toast.makeText(this, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
                onFailed()
            }
        }
    }

    /* 通过Shizuku授权⌈写入安全设置⌋权限 */
    private fun grantPermissionViaShizuku() {
        runShizukuCommand(
            command = arrayOf(
                "/system/bin/pm", "grant", packageName, Manifest.permission.WRITE_SECURE_SETTINGS
            ), onSuccess = {
                binding.accessibilityServicePermissionSwitch.post {
                    if (!tryEnableAccessibilityViaSecureSettings()) {
                        Log.e(TAG, "WRITE_SECURE_SETTINGS still missing after Shizuku grant")
                        Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                        updateAccessibilitySwitchState(false)
                    }
                }
            }, onFailure = { updateAccessibilitySwitchState(false) })
    }

    /**
     * 执行Shizuku命令
     *
     * @param command 要执行的命令数组
     * @param onSuccess 命令执行成功时的回调
     * @param onFailure 命令执行失败时的回调
     */
    private fun runShizukuCommand(command: Array<String>, onSuccess: () -> Unit, onFailure: () -> Unit) {
        lifecycleScope.launch(ioDispatcher) {
            val result = runCatching { executeShizukuCommand(*command) }
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    result.onSuccess { res ->
                        if (res.exitCode != 0) {
                            Log.e(
                                TAG,
                                "Shizuku command failed: cmd=${command.joinToString(" ")}, exitCode=${res.exitCode}, stdout=${res.stdout}, stderr=${res.stderr}"
                            )
                            Toast.makeText(this@MainActivity, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                            onFailure()
                        } else {
                            onSuccess()
                        }
                    }.onFailure { exception ->
                        Log.e(
                            TAG, "Shizuku command execution failed: cmd=${command.joinToString(" ")}", exception
                        )
                        Toast.makeText(this@MainActivity, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
                        onFailure()
                    }
                }
            }
        }
    }

    /**
     * 执行Shizuku命令
     *
     * @param command 要执行的命令数组
     * @return 命令执行结果
     */
    private fun executeShizukuCommand(vararg command: String): ShizukuCommandResult {
        val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply {
            isAccessible = true
        }
        val process = newProcessMethod.invoke(null, command, null, null) as ShizukuRemoteProcess
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
        val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
        process.destroy()
        return ShizukuCommandResult(exitCode, stdout, stderr)
    }

    /* 检查是否具备⌈写入安全设置⌋权限 */
    private fun hasWriteSecureSettingsPermission() =
        checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * 尝试通过写入安全设置权限直接开启无障碍服务
     *
     * @param toastResId 成功时展示的提示文案资源ID
     * @return 是否拥有权限并已触发开启
     */
    private fun tryEnableAccessibilityViaSecureSettings(
        toastResId: Int = R.string.accessibility_service_enabled
    ): Boolean {
        if (!hasWriteSecureSettingsPermission()) {
            return false
        }
        lifecycleScope.launch {
            changeAccessibilityServicePermissionState(enable = true)
            Toast.makeText(this@MainActivity, toastResId, Toast.LENGTH_SHORT).show()
        }
        return true
    }


    /**
     * 更新⌈无障碍服务权限⌋状态
     *
     * @param enable 是否启用⌈无障碍服务权限⌋
     */
    private suspend fun changeAccessibilityServicePermissionState(enable: Boolean) = withContext(ioDispatcher) {
        // 获取当前已启用的无障碍服务列表
        val targetComponent = ComponentName(this@MainActivity, AutoSlideService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.split(":")?.filter { it.isNotBlank() }?.mapNotNull {
            ComponentName.unflattenFromString(it.trim())
        }?.toMutableSet() ?: mutableSetOf()
        // 更新无障碍服务列表
        if (enable) {
            enabledServices.add(targetComponent)
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } else {
            enabledServices.remove(targetComponent)
            if (enabledServices.isEmpty()) {
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
        }
        // 更新无障碍服务列表字符串
        val newSettingString = enabledServices.joinToString(":") { it.flattenToString() }
        Settings.Secure.putString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newSettingString
        )
        val finalAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
        // 更新无障碍服务权限开关状态并应用无动画过渡
        withContext(mainDispatcher) {
            if (!isFinishing && !isDestroyed) {
                updateAccessibilitySwitchState(finalAccessibilityEnabled)
            }
        }
    }

    /* 显示ADB授权所需的命令弹窗 */
    private fun showAdbCommandDialog() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val content = createAdbDialogContent(command)
        // 展示ADB命令弹窗
        AlertDialog.Builder(this).setTitle(R.string.authorize_via_adb).setView(content)
            .setPositiveButton(R.string.copy_command) { _, _ ->
                copyToClipboard(command)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * 从主题中获取颜色
     *
     * @param attrId 属性ID
     * @return 颜色
     */
    private fun getColorFromAttr(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    /**
     * 构造ADB指令展示视图
     *
     * @param command 需要展示的ADB命令
     * @return 包含ADB命令展示的线性布局视图
     */
    private fun createAdbDialogContent(command: String): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val codePadding = (12 * resources.displayMetrics.density).toInt()
        val textColorPrimary = getColorFromAttr(AndroidR.attr.textColorPrimary)
        val textColorSecondary = getColorFromAttr(AndroidR.attr.textColorSecondary)
        val colorSurfaceVariant = getColorFromAttr(MaterialR.attr.colorSurfaceVariant)
        // 提示用户连接电脑并在终端运行以下ADB命令
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.adb_command_instruction)
                    textSize = 16f
                    setTextColor(textColorPrimary)
                })
            // 展示ADB命令
            addView(
                TextView(this@MainActivity).apply {
                    text = command
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(colorSurfaceVariant)
                    setPadding(codePadding, codePadding, codePadding, codePadding)
                    textSize = 13f
                    setTextColor(textColorSecondary)
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                })
        }
    }

    /**
     * 把文本写入系统剪贴板
     *
     * @param text 需要复制的文本
     */
    private fun copyToClipboard(text: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("ADB Command", text)
        clipboardManager.setPrimaryClip(clipData)
    }

    /* 打开系统⌈无障碍⌋设置页并定位到当前服务 */
    private fun openAppAccessibilitySettings() {
        val serviceName = "$packageName/${AutoSlideService::class.java.canonicalName}"
        val bundle = Bundle().apply {
            putString(":settings:fragment_args_key", serviceName)
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", serviceName)
            putExtra(":settings:show_fragment_args", bundle)
        }
        startActivity(intent)
    }

    /**
     * 确保⌈无障碍服务权限⌋已启用
     *
     * @return 无障碍服务权限是否已启用
     */
    private fun ensureAccessibilityPermission(): Boolean {
        if (isAccessibilityServicePermissionEnabled()) {
            return true
        }
        if (tryEnableAccessibilityViaSecureSettings(R.string.accessibility_service_auto_enabled)) {
            return true
        }
        AlertDialog.Builder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.accessibility_service_description).setPositiveButton(R.string.go_to_open) { _, _ ->
                showAccessibilityServicePermissionOptionDialog()
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /**
     * 确保⌈悬浮窗权限⌋已启用
     *
     * @return 悬浮窗权限是否已启用
     */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) {
            return true
        }
        AlertDialog.Builder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_required).setPositiveButton(R.string.go_to_open) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /* 绑定⌈开始⌋按钮点击事件并执行运行前权限校验 */
    private fun setupStartButton() {
        // 绑定⌈开始⌋按钮点击事件
        binding.startButton.setOnClickListener {
            if (!ensureAccessibilityPermission()) return@setOnClickListener
            if (!ensureOverlayPermission()) return@setOnClickListener
            // 校验通过后启动悬浮窗服务并把应用退到后台
            val floatingIntent = Intent(this, FloatingWindowService::class.java)
            startService(floatingIntent)
            moveTaskToBack(true)
        }
        // 任务方案：点击整张卡片进入（右侧箭头入口）
        binding.macroCard.setOnClickListener {
            startActivity(Intent(this, MacroPlanActivity::class.java))
        }
    }

    /* 界面设置：布局方案分类（默认收纳） */
    private fun setupUiSettings() {
        setupCollapsible(binding.uiSettingsHeader, binding.uiSettingsParamsContainer, binding.uiSettingsChevron, expanded = false)
        loadSchemesUI()
        binding.btnNewScheme.setOnClickListener { openSchemeEditor(null) }
        binding.btnEditCurrentLayout.setOnClickListener { launchEditCurrentLayout() }
        setupPositionSelector()
        setupAppearance()
    }

    /* 悬浮框默认出现位置选择（3x3 网格） */
    private fun setupPositionSelector() {
        val items = listOf(
            binding.posTl to POS_TOP_LEFT,
            binding.posTc to POS_TOP_CENTER,
            binding.posTr to POS_TOP_RIGHT,
            binding.posMl to POS_CENTER_LEFT,
            binding.posOrig to POS_ORIGINAL,
            binding.posMr to POS_CENTER_RIGHT,
            binding.posBl to POS_BOTTOM_LEFT,
            binding.posBc to POS_BOTTOM_CENTER,
            binding.posBr to POS_BOTTOM_RIGHT
        )
        fun refresh() {
            val sel = preferences.getInt(KEY_FLOATING_POSITION, DEFAULT_FLOATING_POSITION)
            items.forEach { (btn, code) ->
                val on = code == sel
                btn.isEnabled = !on
                btn.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.primary else R.color.toggle_btn_bg)
                )
                btn.setTextColor(ContextCompat.getColor(this, if (on) R.color.on_primary else R.color.toggle_btn_text))
            }
        }
        items.forEach { (btn, code) ->
            btn.setOnClickListener {
                preferences.edit { putInt(KEY_FLOATING_POSITION, code) }
                refresh()
                if (FloatingWindowService.isRunning()) {
                    sendBroadcast(Intent(ACTION_FLOATING_POSITION_CHANGED))
                }
            }
        }
        refresh()
    }

    /* 悬浮框外观（形状 / 大小 / 圆角 / 配色） */
    private fun setupAppearance() {
        // 形状：圆形 / 方块
        val shapeItems = listOf(binding.shapeCircle to SHAPE_CIRCLE, binding.shapeSquare to SHAPE_SQUARE)
        shapeItems.forEach { (btn, code) ->
            btn.setOnClickListener {
                preferences.edit { putInt(KEY_FLOATING_SHAPE, code) }
                refreshAppearanceShape()
                previewFloating()
                notifyAppearance()
            }
        }
        // 大小滑块（36-72 dp）
        val size = preferences.getInt(KEY_FLOATING_BTN_SIZE, DEFAULT_FLOATING_BTN_SIZE).coerceIn(36, 72)
        binding.btnSizeSlider.value = size.toFloat()
        binding.btnSizeValue.text = getString(R.string.size_dp_format, size)
        binding.btnSizeSlider.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt().coerceIn(36, 72)
            binding.btnSizeValue.text = getString(R.string.size_dp_format, v)
            if (fromUser) {
                preferences.edit { putInt(KEY_FLOATING_BTN_SIZE, v) }
                val maxCorner = v / 2
                if (binding.cornerSlider.value > maxCorner.toFloat()) {
                    binding.cornerSlider.value = maxCorner.toFloat()
                    binding.cornerValue.text = getString(R.string.size_dp_format, maxCorner)
                    preferences.edit { putInt(KEY_FLOATING_CORNER, maxCorner) }
                } else {
                    binding.cornerSlider.valueTo = maxCorner.toFloat()
                }
                previewFloating()
                notifyAppearance()
            }
        }
        // 圆角滑块（仅方块有意义，上限随大小变化）
        val maxCorner = size / 2
        binding.cornerSlider.valueFrom = 0f
        binding.cornerSlider.valueTo = maxCorner.toFloat()
        val corner = preferences.getInt(KEY_FLOATING_CORNER, DEFAULT_FLOATING_CORNER).coerceIn(0, maxCorner)
        binding.cornerSlider.value = corner.toFloat()
        binding.cornerValue.text = getString(R.string.size_dp_format, corner)
        binding.cornerSlider.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt().coerceIn(0, maxCorner)
            binding.cornerValue.text = getString(R.string.size_dp_format, v)
            if (fromUser) {
                preferences.edit { putInt(KEY_FLOATING_CORNER, v) }
                previewFloating()
                notifyAppearance()
            }
        }
        // 配色方案：预设 + 自定义
        val themeItems = listOf(
            binding.themeDefault to 0, binding.themeDark to 1,
            binding.themeMint to 2, binding.themeSunset to 3
        )
        themeItems.forEach { (btn, code) ->
            btn.setOnClickListener {
                preferences.edit { putInt(KEY_FLOATING_THEME, code) }
                refreshAppearanceTheme()
                previewFloating()
                notifyAppearance()
            }
        }
        binding.btnCustomColor.setOnClickListener { previewFloating(); openColorPicker() }
        // 恢复大小与圆角默认值（图标按钮，无文本）
        binding.btnResetAppearance.setOnClickListener {
            preferences.edit {
                putInt(KEY_FLOATING_BTN_SIZE, DEFAULT_FLOATING_BTN_SIZE)
                putInt(KEY_FLOATING_CORNER, DEFAULT_FLOATING_CORNER)
            }
            binding.btnSizeSlider.value = DEFAULT_FLOATING_BTN_SIZE.toFloat()
            binding.btnSizeValue.text = getString(R.string.size_dp_format, DEFAULT_FLOATING_BTN_SIZE)
            binding.cornerSlider.valueTo = (DEFAULT_FLOATING_BTN_SIZE / 2).toFloat()
            binding.cornerSlider.value = DEFAULT_FLOATING_CORNER.toFloat()
            binding.cornerValue.text = getString(R.string.size_dp_format, DEFAULT_FLOATING_CORNER)
            previewFloating()
            notifyAppearance()
        }
        refreshAppearanceShape()
        refreshAppearanceTheme()
    }

    /* 刷新形状选择高亮，并启用/禁用圆角滑块 */
    private fun refreshAppearanceShape() {
        val sel = preferences.getInt(KEY_FLOATING_SHAPE, DEFAULT_FLOATING_SHAPE)
        val shapeItems = listOf(binding.shapeCircle to SHAPE_CIRCLE, binding.shapeSquare to SHAPE_SQUARE)
        shapeItems.forEach { (btn, code) ->
            val on = code == sel
            btn.isEnabled = !on
            btn.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.primary else R.color.toggle_btn_bg)
            )
            btn.setTextColor(ContextCompat.getColor(this, if (on) R.color.on_primary else R.color.toggle_btn_text))
        }
        val isCircle = sel == SHAPE_CIRCLE
        binding.cornerBlock.isEnabled = !isCircle
        binding.cornerBlock.alpha = if (isCircle) 0.4f else 1f
    }

    /* 刷新配色方案选择高亮 */
    private fun refreshAppearanceTheme() {
        val sel = preferences.getInt(KEY_FLOATING_THEME, DEFAULT_FLOATING_THEME)
        val themeItems = listOf(
            binding.themeDefault to 0, binding.themeDark to 1,
            binding.themeMint to 2, binding.themeSunset to 3
        )
        themeItems.forEach { (btn, code) ->
            val on = code == sel
            btn.isEnabled = !on
            btn.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.primary else R.color.toggle_btn_bg)
            )
            btn.setTextColor(ContextCompat.getColor(this, if (on) R.color.on_primary else R.color.toggle_btn_text))
        }
        val custom = sel == THEME_CUSTOM
        binding.btnCustomColor.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (custom) R.color.primary else R.color.toggle_btn_bg)
        )
        binding.btnCustomColor.setTextColor(ContextCompat.getColor(this, if (custom) R.color.on_primary else R.color.toggle_btn_text))
    }

    /* 外观变更广播（悬浮框运行时实时生效） */
    private fun notifyAppearance() {
        if (FloatingWindowService.isRunning()) {
            sendBroadcast(Intent(ACTION_FLOATING_APPEARANCE_CHANGED))
        }
    }

    /* 自定义主色取色器（色板点选 + 十六进制输入） */
    private fun openColorPicker() {
        val current = preferences.getInt(KEY_FLOATING_CUSTOM_COLOR, DEFAULT_FLOATING_CUSTOM_COLOR)
        val view = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val preview = view.findViewById<View>(R.id.colorPreview)
        val hexEdit = view.findViewById<TextInputEditText>(R.id.colorHexEdit)
        val swatches = view.findViewById<GridLayout>(R.id.colorSwatches)
        var chosen = current
        fun applyPreview(c: Int) {
            chosen = c
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            hexEdit.setText(String.format("#%06X", c and 0xFFFFFF))
        }
        val dp = resources.displayMetrics.density
        val paletteColors = listOf(
            0xFF545995, 0xFF2BB7A3, 0xFFF08A24, 0xFFE53935, 0xFF8E24AA, 0xFF1E88E5,
            0xFF43A047, 0xFF00897B, 0xFF6D4C41, 0xFF212121, 0xFFEC407A, 0xFFFBC02D,
            0xFF26C6DA, 0xFF5E35B1, 0xFFC0CA33, 0xFFEF6C00
        ).map { it.toInt() }
        paletteColors.forEach { c ->
            val dot = View(this).apply {
                layoutParams = ViewGroup.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener { applyPreview(c) }
            }
            swatches.addView(dot)
        }
        applyPreview(current)
        AlertDialog.Builder(this).setTitle(R.string.custom_color).setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val parsed = parseColorHex(hexEdit.text.toString()) ?: chosen
                preferences.edit {
                    putInt(KEY_FLOATING_CUSTOM_COLOR, parsed)
                    putInt(KEY_FLOATING_THEME, THEME_CUSTOM)
                }
                refreshAppearanceTheme()
                notifyAppearance()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /* 解析 #RRGGBB / RRGGBB 十六进制颜色 */
    private fun parseColorHex(s: String): Int? {
        val t = s.removePrefix("#").trim()
        if (t.length != 6) return null
        return runCatching { Color.parseColor("#$t") }.getOrNull()
    }

    /* 加载并展示布局方案列表 */
    private fun loadSchemesUI() {
        val schemes = SchemeStore.allSchemes(preferences)
        val selectedId = SchemeStore.loadSelectedId(preferences)
        binding.schemeList.layoutManager = LinearLayoutManager(this)
        binding.schemeList.adapter = SchemeListAdapter(schemes, selectedId,
            onSelect = { scheme ->
                SchemeStore.saveSelectedId(preferences, scheme.id)
                if (FloatingWindowService.isRunning()) {
                    sendBroadcast(Intent(ACTION_APPLY_FLOATING_SCHEME).putExtra(EXTRA_FLOATING_SCHEME_ID, scheme.id))
                }
                loadSchemesUI()
            },
            onEdit = { scheme -> openSchemeEditor(scheme) },
            onDelete = { scheme -> deleteScheme(scheme) }
        )
    }

    /* 删除自定义方案 */
    private fun deleteScheme(scheme: LayoutScheme) {
        if (scheme.builtIn) return
        AlertDialog.Builder(this).setTitle(R.string.delete_scheme)
            .setMessage(R.string.delete_scheme_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val customs = SchemeStore.loadCustomSchemes(preferences).toMutableList()
                customs.removeAll { it.id == scheme.id }
                SchemeStore.saveCustomSchemes(preferences, customs)
                if (SchemeStore.loadSelectedId(preferences) == scheme.id) {
                    SchemeStore.saveSelectedId(preferences, DEFAULT_FLOATING_SCHEME_ID)
                    
                    //删除选中方案后实时刷新悬浮框
                    if (FloatingWindowService.isRunning()) {
                        sendBroadcast(Intent(ACTION_APPLY_FLOATING_SCHEME).putExtra(EXTRA_FLOATING_SCHEME_ID, DEFAULT_FLOATING_SCHEME_ID))
                    }
                }
                loadSchemesUI()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /* 从设置页拉起悬浮框并进入编辑模式 */
    private fun launchEditCurrentLayout() {
        if (!Settings.canDrawOverlays(this)) {
            ensureOverlayPermission()
            return
        }
        startService(Intent(this, FloatingWindowService::class.java).putExtra(EXTRA_FLOATING_EDIT_MODE, true))
    }

    /* 拉起悬浮框作为实时预览（非编辑模式）；若已在运行则不做处理 */
    private fun previewFloating() {
        if (FloatingWindowService.isRunning()) return
        if (!Settings.canDrawOverlays(this)) return
        startService(Intent(this, FloatingWindowService::class.java))
    }

    /* 打开方案编辑器（base 为 null=新建；内置方案=复制为新方案） */
    private fun openSchemeEditor(base: LayoutScheme?) {
        val isBuiltIn = base?.builtIn == true
        val editingExisting = base != null && !isBuiltIn
        val ordered = base?.visibleTypes() ?: FloatingButtonType.DEFAULT_ORDER
        val hidden = FloatingButtonType.DEFAULT_ORDER.filter { it !in ordered }
        val items = (ordered.map { EditorItem(it, true) } + hidden.map { EditorItem(it, false) }).toMutableList()
        var columns = base?.columns ?: 3
        var rows = base?.let { it.placements.maxOfOrNull { it.row }?.plus(1) }
            ?: ((FloatingButtonType.DEFAULT_ORDER.size + columns - 1) / columns).coerceAtLeast(1)

        val view = layoutInflater.inflate(R.layout.scheme_editor, null)
        val nameEdit = view.findViewById<TextInputEditText>(R.id.schemeNameEdit)
        val colButtons = listOf(
            view.findViewById<MaterialButton>(R.id.col1),
            view.findViewById(R.id.col2),
            view.findViewById(R.id.col3),
            view.findViewById(R.id.col4),
            view.findViewById(R.id.col5),
            view.findViewById(R.id.col6),
            view.findViewById(R.id.col7)
        )
        val rowButtons = listOf(
            view.findViewById<MaterialButton>(R.id.row1),
            view.findViewById(R.id.row2),
            view.findViewById(R.id.row3),
            view.findViewById(R.id.row4),
            view.findViewById(R.id.row5),
            view.findViewById(R.id.row6),
            view.findViewById(R.id.row7)
        )
        val editorList = view.findViewById<RecyclerView>(R.id.editorList)
        nameEdit.setText(if (editingExisting) base?.name else "")

        fun refreshCols() {
            colButtons.forEachIndexed { i, b ->
                val on = (i + 1) == columns
                b.isEnabled = !on
                b.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.primary else R.color.toggle_btn_bg)
                )
                b.setTextColor(ContextCompat.getColor(this, if (on) R.color.on_primary else R.color.toggle_btn_text))
            }
        }
        fun refreshRows() {
            rowButtons.forEachIndexed { i, b ->
                val on = (i + 1) == rows
                b.isEnabled = !on
                b.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.primary else R.color.toggle_btn_bg)
                )
                b.setTextColor(ContextCompat.getColor(this, if (on) R.color.on_primary else R.color.toggle_btn_text))
            }
        }
        colButtons.forEachIndexed { i, b -> b.setOnClickListener { columns = i + 1; refreshCols() } }
        rowButtons.forEachIndexed { i, b -> b.setOnClickListener { rows = i + 1; refreshRows() } }
        refreshCols()
        refreshRows()

        val editorAdapter = EditorListAdapter(items)
        editorList.layoutManager = LinearLayoutManager(this)
        editorList.adapter = editorAdapter
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                items.add(to, items.removeAt(from))
                editorAdapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        })
        touchHelper.attachToRecyclerView(editorList)
        editorAdapter.onDragHandle = { vh -> touchHelper.startDrag(vh) }

        val dialog = AlertDialog.Builder(this).setTitle(
            if (editingExisting) R.string.edit else if (isBuiltIn) R.string.clone else R.string.new_scheme
        ).setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameEdit.text.toString().trim().ifEmpty { getString(R.string.scheme_default_name) }
                val visible = items.filter { it.visible }.map { it.type }
                if (visible.isEmpty()) {
                    Toast.makeText(this, R.string.scheme_no_button, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val placements = generateByColumnFill(visible, columns, rows) //保存时按 行/列 生成布局
                val customs = SchemeStore.loadCustomSchemes(preferences).toMutableList()
                val saved = if (editingExisting) {
                    base!!.copy(name = name, columns = columns, placements = placements)
                } else {
                    LayoutScheme("custom_${System.currentTimeMillis()}", name, false, columns, placements)
                }
                if (editingExisting) {
                    val idx = customs.indexOfFirst { it.id == saved.id }
                    if (idx >= 0) customs[idx] = saved else customs.add(saved)
                } else {
                    customs.add(saved)
                }
                SchemeStore.saveCustomSchemes(preferences, customs)
                SchemeStore.saveSelectedId(preferences, saved.id)
                if (FloatingWindowService.isRunning()) {
                    sendBroadcast(Intent(ACTION_APPLY_FLOATING_SCHEME).putExtra(EXTRA_FLOATING_SCHEME_ID, saved.id))
                }
                loadSchemesUI()
            }
            .setNegativeButton(R.string.cancel, null)
        if (editingExisting) {
            dialog.setNeutralButton(R.string.delete) { _, _ -> deleteScheme(base!!) }
        }
        dialog.show()
    }

    /* 布局方案列表项数据 */
    private data class EditorItem(var type: FloatingButtonType, var visible: Boolean)

    /* 布局方案列表适配器 */
    private class SchemeListAdapter(
        private val schemes: List<LayoutScheme>,
        private val selectedId: String,
        private val onSelect: (LayoutScheme) -> Unit,
        private val onEdit: (LayoutScheme) -> Unit,
        private val onDelete: (LayoutScheme) -> Unit
    ) : RecyclerView.Adapter<SchemeListAdapter.VH>() {
        class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val radio: ImageView = view.findViewById(R.id.schemeRadio)
            val name: TextView = view.findViewById(R.id.schemeName)
            val builtin: TextView = view.findViewById(R.id.schemeBuiltin)
            val edit: MaterialButton = view.findViewById(R.id.schemeEdit)
            val delete: MaterialButton = view.findViewById(R.id.schemeDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.scheme_item, parent, false))

        override fun onBindViewHolder(vh: VH, position: Int) {
            val scheme = schemes[position]
            val selected = scheme.id == selectedId
            vh.radio.setImageResource(if (selected) R.drawable.ic_radio_selected else R.drawable.ic_radio)
            vh.radio.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(vh.view.context, if (selected) R.color.primary else R.color.text_secondary)
            )
            vh.name.text = scheme.name
            vh.name.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            vh.view.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(vh.view.context, if (selected) R.color.scheme_selected_bg else android.R.color.transparent)
            )
            vh.builtin.visibility = if (scheme.builtIn) View.VISIBLE else View.GONE
            vh.edit.setText(if (scheme.builtIn) R.string.clone else R.string.edit)
            vh.delete.visibility = if (scheme.builtIn) View.GONE else View.VISIBLE
            vh.view.setOnClickListener { onSelect(scheme) }
            vh.edit.setOnClickListener { onEdit(scheme) }
            vh.delete.setOnClickListener { onDelete(scheme) }
        }

        override fun getItemCount(): Int = schemes.size
    }

    /* 编辑器内按钮排序列表适配器 */
    private class EditorListAdapter(private val items: MutableList<EditorItem>) :
        RecyclerView.Adapter<EditorListAdapter.VH>() {
        var onDragHandle: ((RecyclerView.ViewHolder) -> Unit)? = null

        class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val handle: TextView = view.findViewById(R.id.dragHandle)
            val name: TextView = view.findViewById(R.id.btnName)
            val visible: CheckBox = view.findViewById(R.id.btnVisible)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.scheme_editor_item, parent, false))

        override fun onBindViewHolder(vh: VH, position: Int) {
            val item = items[position]
            vh.name.text = vh.view.context.getString(item.type.labelRes)
            vh.visible.isChecked = item.visible
            vh.visible.setOnCheckedChangeListener { _, c -> item.visible = c }
            vh.handle.setOnTouchListener { _, _ -> onDragHandle?.invoke(vh); false }
        }

        override fun getItemCount(): Int = items.size
    }

    /* 绑定⌈检查更新⌋按钮点击事件 */
    private fun setupUpdateButton() {
        binding.checkUpdateButton.setOnClickListener {
            UpdateChecker.checkUpdate(this, showToastOnLatest = true)
        }
    }

    /**
     * 更新⌈停顿时间⌋面板的可见性
     *
     * @param pauseMode 停顿模式
     */
    @SuppressLint("SetTextI18n")
    private fun updatePauseTimeVisibility(pauseMode: Int) {
        // 停顿时间面板仅在"固定时间/随机时间"下显示；"自动后退"为独立附加功能，始终显示
        binding.pauseTimeContainer.isVisible = pauseMode != PAUSE_MODE_NONE
        binding.tbConfigContainer.isVisible = true
        if (pauseMode == PAUSE_MODE_FIXED) {
            binding.pauseTimeLabel.setText(R.string.pause_time)
            binding.pauseTimeSlider.isVisible = true
            binding.randomPauseTimeSlider.isVisible = false
            // 启用下划线及点击事件
            binding.pauseTimeValueText.paintFlags = binding.pauseTimeValueText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            binding.pauseTimeValueText.isClickable = true
            binding.pauseTimeValueText.text = binding.pauseTimeSlider.value.toInt().toString()
        } else if (pauseMode == PAUSE_MODE_RANDOM) {
            binding.pauseTimeLabel.setText(R.string.random_pause_time_range)
            binding.pauseTimeSlider.isVisible = false
            binding.randomPauseTimeSlider.isVisible = true
            // 禁用下划线及点击事件
            binding.pauseTimeValueText.paintFlags =
                binding.pauseTimeValueText.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            binding.pauseTimeValueText.isClickable = false
            val values = binding.randomPauseTimeSlider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            }
        }
    }
}
