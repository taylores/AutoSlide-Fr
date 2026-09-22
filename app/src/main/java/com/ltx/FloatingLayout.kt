package com.ltx

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 悬浮框按钮类型
 *
 * @property key 与方向常量（DIRECTION_*）一致，便于复用滑动逻辑
 * @property labelRes 按钮名称资源
 */
enum class FloatingButtonType(val key: String, val labelRes: Int) {
    UP("up", R.string.desc_slide_up),
    DOWN("down", R.string.desc_slide_down),
    LEFT("left", R.string.desc_slide_left),
    RIGHT("right", R.string.desc_slide_right),
    UPDOWN("updown", R.string.desc_slide_up_down),
    SETTING("setting", R.string.desc_settings),
    CLOSE("close", R.string.desc_close);

    companion object {
        fun fromKey(k: String): FloatingButtonType? = values().firstOrNull { it.key == k }

        /** 默认排列顺序（用于新建方案与列表编辑器初始顺序） */
        val DEFAULT_ORDER = listOf(UP, DOWN, LEFT, RIGHT, UPDOWN, SETTING, CLOSE)
    }
}

/** 单个按钮在网格中的位置（行列均从 0 开始） */
data class ButtonPlacement(val type: FloatingButtonType, val row: Int, val col: Int)

/**
 * 悬浮框布局方案
 *
 * @property id 唯一标识
 * @property name 显示名称
 * @property builtIn 是否内置（内置不可删、不可直接覆盖）
 * @property columns 网格列数
 * @property placements 按钮位置列表（未出现即隐藏）
 */
data class LayoutScheme(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val columns: Int,
    val placements: List<ButtonPlacement>
) {
    fun visibleTypes(): List<FloatingButtonType> = placements.map { it.type }
    fun placementOf(type: FloatingButtonType): ButtonPlacement? = placements.firstOrNull { it.type == type }
    fun maxRow(): Int = (placements.maxOfOrNull { it.row } ?: 0)
}

/** 三个内置方案 */
object BuiltInSchemes {
    val DEFAULT = LayoutScheme("builtin_default", "默认(3×4)", true, 3, listOf(
        ButtonPlacement(FloatingButtonType.UP, 0, 1),
        ButtonPlacement(FloatingButtonType.LEFT, 1, 0),
        ButtonPlacement(FloatingButtonType.SETTING, 1, 1),
        ButtonPlacement(FloatingButtonType.RIGHT, 1, 2),
        ButtonPlacement(FloatingButtonType.DOWN, 2, 1),
        ButtonPlacement(FloatingButtonType.CLOSE, 3, 1)
    ))
    val TWO_COL = LayoutScheme("builtin_2col", "双列(2×4)", true, 2, listOf(
        ButtonPlacement(FloatingButtonType.UP, 0, 0),
        ButtonPlacement(FloatingButtonType.LEFT, 1, 0),
        ButtonPlacement(FloatingButtonType.DOWN, 2, 0),
        ButtonPlacement(FloatingButtonType.CLOSE, 3, 0),
        ButtonPlacement(FloatingButtonType.RIGHT, 1, 1),
        ButtonPlacement(FloatingButtonType.UPDOWN, 2, 1),
        ButtonPlacement(FloatingButtonType.SETTING, 3, 1)
    ))
    val THREE_COL = LayoutScheme("builtin_3col3x3", "3列(3×3)", true, 3, listOf(
        ButtonPlacement(FloatingButtonType.CLOSE, 0, 0),
        ButtonPlacement(FloatingButtonType.LEFT, 1, 0),
        ButtonPlacement(FloatingButtonType.UP, 0, 1),
        ButtonPlacement(FloatingButtonType.UPDOWN, 1, 1),
        ButtonPlacement(FloatingButtonType.DOWN, 2, 1),
        ButtonPlacement(FloatingButtonType.SETTING, 0, 2),
        ButtonPlacement(FloatingButtonType.RIGHT, 1, 2)
    ))
    val ONE_COL = LayoutScheme("builtin_1col", "单列(1×7)", true, 1, listOf(
        ButtonPlacement(FloatingButtonType.UP, 0, 0),
        ButtonPlacement(FloatingButtonType.DOWN, 1, 0),
        ButtonPlacement(FloatingButtonType.LEFT, 2, 0),
        ButtonPlacement(FloatingButtonType.RIGHT, 3, 0),
        ButtonPlacement(FloatingButtonType.UPDOWN, 4, 0),
        ButtonPlacement(FloatingButtonType.SETTING, 5, 0),
        ButtonPlacement(FloatingButtonType.CLOSE, 6, 0)
    ))
    val SEVEN_COL = LayoutScheme("builtin_7col1x1", "7列(7×1)", true, 7, listOf(
        ButtonPlacement(FloatingButtonType.UP, 0, 0),
        ButtonPlacement(FloatingButtonType.DOWN, 0, 1),
        ButtonPlacement(FloatingButtonType.LEFT, 0, 2),
        ButtonPlacement(FloatingButtonType.RIGHT, 0, 3),
        ButtonPlacement(FloatingButtonType.UPDOWN, 0, 4),
        ButtonPlacement(FloatingButtonType.SETTING, 0, 5),
        ButtonPlacement(FloatingButtonType.CLOSE, 0, 6)
    ))
    val ONE_COL_4 = LayoutScheme("builtin_1col1x4", "1列(1×4)", true, 1, listOf(
        ButtonPlacement(FloatingButtonType.CLOSE, 0, 0),
        ButtonPlacement(FloatingButtonType.DOWN, 1, 0),
        ButtonPlacement(FloatingButtonType.UPDOWN, 2, 0),
        ButtonPlacement(FloatingButtonType.SETTING, 3, 0)
    ))

    val ALL = listOf(DEFAULT, THREE_COL, TWO_COL, ONE_COL, SEVEN_COL, ONE_COL_4)
}

/**
 * 按列优先（先填满一列再下一列）根据有序可见列表与列数生成布局。
 * rows 限定每列最大行数（即网格总行数），超出则自动增加列；rows<=0 表示不限制。
 */
fun generateByColumnFill(orderedVisible: List<FloatingButtonType>, columns: Int, rows: Int = Int.MAX_VALUE): List<ButtonPlacement> {
    if (columns <= 0 || orderedVisible.isEmpty()) return emptyList()
    val maxRows = if (rows <= 0) Int.MAX_VALUE else rows
    return orderedVisible.mapIndexed { index, type ->
        ButtonPlacement(type, index % maxRows, index / maxRows)
    }
}

/** 方案持久化（自定义方案存 SharedPreferences，内置方案写死） */
object SchemeStore {
    fun loadCustomSchemes(prefs: SharedPreferences): List<LayoutScheme> {
        val raw = prefs.getString(KEY_FLOATING_SCHEMES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> arr.getJSONObject(i).toScheme() }
        }.getOrDefault(emptyList())
    }

    fun saveCustomSchemes(prefs: SharedPreferences, list: List<LayoutScheme>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit { putString(KEY_FLOATING_SCHEMES, arr.toString()) }
    }

    fun loadSelectedId(prefs: SharedPreferences): String =
        prefs.getString(KEY_FLOATING_SCHEME_SELECTED, DEFAULT_FLOATING_SCHEME_ID) ?: DEFAULT_FLOATING_SCHEME_ID

    fun saveSelectedId(prefs: SharedPreferences, id: String) {
        prefs.edit { putString(KEY_FLOATING_SCHEME_SELECTED, id) }
    }

    fun allSchemes(prefs: SharedPreferences): List<LayoutScheme> = BuiltInSchemes.ALL + loadCustomSchemes(prefs)

    fun getScheme(prefs: SharedPreferences, id: String): LayoutScheme =
        allSchemes(prefs).firstOrNull { it.id == id } ?: BuiltInSchemes.DEFAULT
}

fun LayoutScheme.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("builtIn", builtIn)
    put("columns", columns)
    put("placements", JSONArray().apply {
        placements.forEach { p ->
            put(JSONObject().apply {
                put("type", p.type.key)
                put("row", p.row)
                put("col", p.col)
            })
        }
    })
}

fun JSONObject.toScheme(): LayoutScheme = LayoutScheme(
    getString("id"),
    getString("name"),
    getBoolean("builtIn"),
    getInt("columns"),
    run {
        val arr = getJSONArray("placements")
        List(arr.length()) { i ->
            arr.getJSONObject(i).let { o ->
                ButtonPlacement(
                    FloatingButtonType.fromKey(o.getString("type")) ?: FloatingButtonType.UP,
                    o.getInt("row"),
                    o.getInt("col")
                )
            }
        }
    }
)
