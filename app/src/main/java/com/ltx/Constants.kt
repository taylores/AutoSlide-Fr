package com.ltx

import android.content.SharedPreferences
import android.graphics.Color


// 配置文件名
const val PREFS_NAME = "slide_settings"

// 配置键名
const val KEY_SPEED = "speed"
const val KEY_PAUSE_MODE = "pauseMode"
const val KEY_PAUSE_TIME = "pauseTime"
const val KEY_MIN_PAUSE_TIME = "minPauseTime"
const val KEY_MAX_PAUSE_TIME = "maxPauseTime"
const val KEY_FLOATING_TRANSPARENCY = "floatingTransparency"

// 默认值
const val DEFAULT_SPEED = 50
const val DEFAULT_PAUSE_TIME = 1
const val DEFAULT_MIN_PAUSE_TIME = 1
const val DEFAULT_MAX_PAUSE_TIME = 3
const val DEFAULT_FLOATING_TRANSPARENCY = 40

// 悬浮框布局方案
const val KEY_FLOATING_SCHEMES = "floatingLayoutSchemes"
const val KEY_FLOATING_SCHEME_SELECTED = "floatingLayoutSelected"
const val DEFAULT_FLOATING_SCHEME_ID = "builtin_default"
const val EXTRA_FLOATING_EDIT_MODE = "floatingEditMode"
const val EXTRA_FLOATING_SCHEME_ID = "floatingSchemeId"
const val ACTION_APPLY_FLOATING_SCHEME = "com.ltx.action.APPLY_FLOATING_SCHEME"
const val ACTION_FLOATING_SCHEME_CHANGED = "com.ltx.action.FLOATING_SCHEME_CHANGED"

// 悬浮框默认出现位置（3x3 网格：左上/上中/右上/左中/原位置/右中/左下/下中/右下）
const val KEY_FLOATING_POSITION = "floatingPosition"
const val DEFAULT_FLOATING_POSITION = 0
const val POS_TOP_LEFT = 0
const val POS_TOP_CENTER = 1
const val POS_TOP_RIGHT = 2
const val POS_CENTER_LEFT = 3
const val POS_ORIGINAL = 4
const val POS_CENTER_RIGHT = 5
const val POS_BOTTOM_LEFT = 6
const val POS_BOTTOM_CENTER = 7
const val POS_BOTTOM_RIGHT = 8
// 悬浮框最近一次位置（用于“原位置”）
const val KEY_FLOATING_LAST_X = "floatingLastX"
const val KEY_FLOATING_LAST_Y = "floatingLastY"

// 悬浮框外观（按钮形状 / 大小 / 圆角 / 配色）
const val KEY_FLOATING_SHAPE = "floatingShape"          // 0=圆形, 1=方块
const val DEFAULT_FLOATING_SHAPE = 1                    // 默认方块
const val SHAPE_CIRCLE = 0
const val SHAPE_SQUARE = 1
const val KEY_FLOATING_BTN_SIZE = "floatingBtnSize"      // 直径 dp, 36-72
const val DEFAULT_FLOATING_BTN_SIZE = 40
const val KEY_FLOATING_CORNER = "floatingCorner"         // 方块圆角 dp, 0..size/2
const val DEFAULT_FLOATING_CORNER = 11
const val KEY_FLOATING_THEME = "floatingTheme"           // 预设索引, -1=自定义
const val DEFAULT_FLOATING_THEME = 0
const val THEME_CUSTOM = -1
const val KEY_FLOATING_CUSTOM_COLOR = "floatingCustomColor"  // 自定义主色 argb
const val DEFAULT_FLOATING_CUSTOM_COLOR = 0xFF545995.toInt()
const val ACTION_FLOATING_APPEARANCE_CHANGED = "com.ltx.action.FLOATING_APPEARANCE_CHANGED"
const val ACTION_FLOATING_POSITION_CHANGED = "com.ltx.action.FLOATING_POSITION_CHANGED"

// 任务方案（组合动作宏）
const val KEY_MACRO_PLANS = "macro_plans"
// 宏启动前的等待时间：给用户切到目标 App 的机会，避免手势打到桌面/本应用
const val MACRO_STARTUP_DELAY_MS = 4000L
const val ACTION_RUN_MACRO = "com.ltx.action.RUN_MACRO"
const val ACTION_STOP_MACRO = "com.ltx.action.STOP_MACRO"
const val EXTRA_MACRO_PLAN_JSON = "macro_plan_json"

// 悬浮框配色调色板（一套完整的按钮/面板颜色）
data class FloatingPalette(
    val btnBg: Int, val btnIcon: Int,
    val btnActive: Int, val btnActiveIcon: Int,
    val btnSettingsBg: Int, val btnSettingsIcon: Int,
    val btnCloseBg: Int, val btnCloseIcon: Int,
    val panelBg: Int
)

// 预设配色（0 默认蓝 / 1 暗夜黑 / 2 薄荷绿 / 3 落日橙）
val FLOATING_PRESETS: List<FloatingPalette> = listOf(
    FloatingPalette(
        0xFF545995.toInt(), 0xFFFFFFFF.toInt(), 0xFFA5C3FF.toInt(), 0xFF002E6A.toInt(),
        0xFFE8E9F2.toInt(), 0xFF545995.toInt(), 0xFFFDECEC.toInt(), 0xFFC62828.toInt(), 0xFFE8E9F5.toInt()
    ),
    FloatingPalette(
        0xFF2A2D33.toInt(), 0xFFFFFFFF.toInt(), 0xFF4A4D55.toInt(), 0xFFFFFFFF.toInt(),
        0xFF3A3D44.toInt(), 0xFFCFD2DA.toInt(), 0xFF4A2A2A.toInt(), 0xFFFF8A80.toInt(), 0xCC2A2D33.toInt()
    ),
    FloatingPalette(
        0xFF2BB7A3.toInt(), 0xFFFFFFFF.toInt(), 0xFF9FE3D8.toInt(), 0xFF004D40.toInt(),
        0xFFE2F5F2.toInt(), 0xFF2BB7A3.toInt(), 0xFFFDECEC.toInt(), 0xFFC62828.toInt(), 0xFFE2F5F2.toInt()
    ),
    FloatingPalette(
        0xFFF08A24.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFD29E.toInt(), 0xFF5A2D00.toInt(),
        0xFFFDEFE0.toInt(), 0xFFF08A24.toInt(), 0xFFFDECEC.toInt(), 0xFFC62828.toInt(), 0xFFFDEFE0.toInt()
    )
)

/**
 * 依据配置解析当前悬浮框配色：预设直接取，自定义主色则按主色派生整套。
 */
fun resolveFloatingPalette(prefs: SharedPreferences): FloatingPalette {
    val theme = prefs.getInt(KEY_FLOATING_THEME, DEFAULT_FLOATING_THEME)
    if (theme == THEME_CUSTOM) {
        return customFloatingPalette(prefs.getInt(KEY_FLOATING_CUSTOM_COLOR, DEFAULT_FLOATING_CUSTOM_COLOR))
    }
    return FLOATING_PRESETS.getOrElse(theme.coerceIn(0, FLOATING_PRESETS.lastIndex)) { FLOATING_PRESETS[0] }
}

// 自定义主色：普通按钮用主色，图标按亮度取对比色；激活/设置/面板由主色派生；关闭保留红色语义。
private fun customFloatingPalette(custom: Int): FloatingPalette {
    val icon = if (Color.luminance(custom) < 0.5f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val active = blend(custom, 0xFFFFFFFF.toInt(), 0.4f)
    val settingsBg = blend(custom, 0xFFFFFFFF.toInt(), 0.82f)
    val panelBg = blend(custom, 0xFFFFFFFF.toInt(), 0.88f)
    return FloatingPalette(
        custom, icon, active, 0xFF000000.toInt(),
        settingsBg, custom, 0xFFFDECEC.toInt(), 0xFFC62828.toInt(), panelBg
    )
}

// 两色按比例混合（ratio: 0=全 a, 1=全 b），结果不透明
private fun blend(a: Int, b: Int, ratio: Float): Int {
    val ar = (a shr 16) and 0xFF
    val ag = (a shr 8) and 0xFF
    val ab = a and 0xFF
    val br = (b shr 16) and 0xFF
    val bg = (b shr 8) and 0xFF
    val bb = b and 0xFF
    val r = (ar + (br - ar) * ratio).toInt()
    val g = (ag + (bg - ag) * ratio).toInt()
    val bl = (ab + (bb - ab) * ratio).toInt()
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
}

// 停顿模式（"自动后退"不再作为独立模式，而是附加功能，见 KEY_TB_*）
const val PAUSE_MODE_NONE = 0
const val PAUSE_MODE_FIXED = 1
const val PAUSE_MODE_RANDOM = 2

// tb模式（定时后退）配置键名与默认值
const val KEY_TB_AUTO_BACK = "tbAutoBack"
const val KEY_TB_BACK_TIME = "tbBackTime"
const val KEY_TB_SOUND = "tbSound"
const val KEY_TB_VIBRATE = "tbVibrate"
// 自定义常用后退秒数（逗号分隔，如 "101,151"）
const val KEY_TB_CUSTOM_SECONDS = "tbCustomSeconds"

const val DEFAULT_TB_AUTO_BACK = false
const val DEFAULT_TB_BACK_TIME = 15
const val DEFAULT_TB_SOUND = true
const val DEFAULT_TB_VIBRATE = false

// 任务方案执行时的提示配置（菜单“任务提示”控制）
const val KEY_MACRO_TEXT_HINT = "macroTextHint"        // 每步文本提示
const val KEY_MACRO_STEP_SOUND = "macroStepSound"      // 每步结束音效
const val KEY_MACRO_TASK_SOUND = "macroTaskSound"      // 任务结束音效
const val KEY_MACRO_VIBRATE = "macroVibrate"           // 任务结束震动
const val DEFAULT_MACRO_TEXT_HINT = true
const val DEFAULT_MACRO_STEP_SOUND = false
const val DEFAULT_MACRO_TASK_SOUND = true
const val DEFAULT_MACRO_VIBRATE = true
// 任务方案：启动/每步/完成时 的震动与音效开关（分阶段）
const val KEY_MACRO_VIBRATE_START = "macroVibrateStart"
const val KEY_MACRO_VIBRATE_STEP = "macroVibrateStep"
const val KEY_MACRO_VIBRATE_STEP_DONE = "macroVibrateStepDone"
const val KEY_MACRO_VIBRATE_DONE = "macroVibrateDone"
const val KEY_MACRO_STEP_SOUND_START = "macroStepSoundStart"
const val KEY_MACRO_STEP_SOUND_STEP = "macroStepSoundStep"
const val KEY_MACRO_STEP_SOUND_DONE = "macroStepSoundDone"
const val DEFAULT_MACRO_VIBRATE_START = false
const val DEFAULT_MACRO_VIBRATE_STEP = false
const val DEFAULT_MACRO_VIBRATE_STEP_DONE = false
const val DEFAULT_MACRO_VIBRATE_DONE = false
const val DEFAULT_MACRO_STEP_SOUND_START = false
const val DEFAULT_MACRO_STEP_SOUND_STEP = false
const val DEFAULT_MACRO_STEP_SOUND_DONE = false

/* 滑动配置数据类 */
data class SlideConfig(
    val speed: Int = DEFAULT_SPEED,
    val pauseMode: Int = PAUSE_MODE_NONE,
    val pauseTime: Int = DEFAULT_PAUSE_TIME,
    val minPauseTime: Int = DEFAULT_MIN_PAUSE_TIME,
    val maxPauseTime: Int = DEFAULT_MAX_PAUSE_TIME,
    val tbAutoBack: Boolean = DEFAULT_TB_AUTO_BACK,
    val tbBackTime: Int = DEFAULT_TB_BACK_TIME,
    val tbSound: Boolean = DEFAULT_TB_SOUND,
    val tbVibrate: Boolean = DEFAULT_TB_VIBRATE,
    val macroTextHint: Boolean = DEFAULT_MACRO_TEXT_HINT,
    val macroTaskSound: Boolean = DEFAULT_MACRO_TASK_SOUND,
    val macroVibrateStart: Boolean = DEFAULT_MACRO_VIBRATE_START,
    val macroVibrateStep: Boolean = DEFAULT_MACRO_VIBRATE_STEP,
    val macroVibrateStepDone: Boolean = DEFAULT_MACRO_VIBRATE_STEP_DONE,
    val macroVibrateDone: Boolean = DEFAULT_MACRO_VIBRATE_DONE,
    val macroStepSoundStart: Boolean = DEFAULT_MACRO_STEP_SOUND_START,
    val macroStepSoundStep: Boolean = DEFAULT_MACRO_STEP_SOUND_STEP,
    val macroStepSoundDone: Boolean = DEFAULT_MACRO_STEP_SOUND_DONE
)

// 滑动方向
const val DIRECTION_UP = "up"
const val DIRECTION_DOWN = "down"
const val DIRECTION_LEFT = "left"
const val DIRECTION_RIGHT = "right"
// 上下组合滑动（交替向上、向下）
const val DIRECTION_UP_DOWN = "updown"

// 任务方案“滑动”步骤的停顿模式（借鉴偏好设置的“停顿时间”三种模式）
const val MACRO_PAUSE_NONE = "none"
const val MACRO_PAUSE_FIXED = "fixed"
const val MACRO_PAUSE_RANDOM = "random"

// 自定义轨迹
const val KEY_CUSTOM_TRAJECTORY_UP = "customTrajectory_up"
const val KEY_CUSTOM_TRAJECTORY_DOWN = "customTrajectory_down"
const val KEY_CUSTOM_TRAJECTORY_LEFT = "customTrajectory_left"
const val KEY_CUSTOM_TRAJECTORY_RIGHT = "customTrajectory_right"

/**
 * 获取轨迹存储键名
 *
 * @param direction 方向字符串
 * @return 轨迹存储键名
 */
fun getTrajectoryKey(direction: String): String? = when (direction) {
    DIRECTION_UP -> KEY_CUSTOM_TRAJECTORY_UP
    DIRECTION_DOWN -> KEY_CUSTOM_TRAJECTORY_DOWN
    DIRECTION_LEFT -> KEY_CUSTOM_TRAJECTORY_LEFT
    DIRECTION_RIGHT -> KEY_CUSTOM_TRAJECTORY_RIGHT
    else -> null
}