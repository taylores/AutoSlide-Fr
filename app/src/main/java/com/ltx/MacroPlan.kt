package com.ltx

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/* 任务方案：组合动作宏的数据模型（无第三方依赖，org.json 持久化） */

/* 动作类型 */
enum class MacroActionType(val key: String) {
    SLIDE("slide"),
    BACK("back"),
    CLICK_TEXT("click_text"),
    CLICK_POSITION("click_position"),
    DELAY("delay"),
    WAIT_TEXT("wait_text"),
    BRANCH("branch"),
    BRANCH_IF("branch_if"),
    DRAG("drag");

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key == k } ?: SLIDE
    }
}

/* 单个动作步骤 */
data class MacroStep(
    val type: MacroActionType,
    // 步骤名称（业务性描述，可选；为空时界面回退显示“步骤内容”）
    val name: String = "",
    // 是否启用（false 时本步骤在方案中跳过不执行）
    val enabled: Boolean = true,
    // SLIDE
    val direction: String = DIRECTION_DOWN,
    val durationSec: Float = 0.5f,
    // SLIDE 停顿配置（借鉴偏好设置“停顿时间”三种模式；非滑动步骤忽略）
    val pauseMode: String = MACRO_PAUSE_NONE,
    val pauseTime: Int = 1,
    val minPauseTime: Int = 1,
    val maxPauseTime: Int = 3,
    // CLICK_TEXT：命中白名单任一即点；整行含黑名单任一词则跳过
    val whitelist: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    val maxClicks: Int = 1,
    // CLICK_POSITION：以屏幕百分比(0~100)记录点击坐标，运行时换算为当前分辨率像素
    val posX: Float = 50f,
    val posY: Float = 50f,
    // DELAY
    val delaySec: Float = 1f,
    // WAIT_TEXT
    val waitText: String = "",
    var waitMode: String = "appear",
    val timeoutSec: Float = 10f,
    // 等待文字的排除条件（页面出现该文字时视为不满足条件；用于避免误判，如“2000+人付款”含“20”）
    val excludeText: String = "",
    // 条件分支重复执行次数（默认 1 次，即执行一次）；back / drag 也复用它作为连发次数
    val loopCount: Int = 1,
    // 重复执行之间的间隔（秒）：用于 back / drag 连发间隔（0 表示不额外等待）
    val loopDelaySec: Float = 0f,
    // BRANCH / BRANCH_IF：条件命中(true)后顺序执行的子步骤；BRANCH_IF 额外支持未命中(false)子步骤
    val subSteps: List<MacroStep> = emptyList(),
    // BRANCH_IF：条件未命中(false)后顺序执行的子步骤（可为空）
    val falseSubSteps: List<MacroStep> = emptyList(),
    // DRAG：拖拽起止坐标（屏幕百分比 0~100），运行时换算为像素坐标
    val dragStartX: Float = 50f,
    val dragStartY: Float = 50f,
    val dragEndX: Float = 50f,
    val dragEndY: Float = 50f,
    val dragDurationMs: Int = 500
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.key)
        put("name", name)
        put("enabled", enabled)
        put("direction", direction)
        put("durationSec", durationSec)
        put("pauseMode", pauseMode)
        put("pauseTime", pauseTime)
        put("minPauseTime", minPauseTime)
        put("maxPauseTime", maxPauseTime)
        put("whitelist", JSONArray(whitelist))
        put("blacklist", JSONArray(blacklist))
        put("maxClicks", maxClicks)
        put("posX", posX)
        put("posY", posY)
        put("delaySec", delaySec)
        put("waitText", waitText)
        put("waitMode", waitMode)
        put("timeoutSec", timeoutSec)
        put("excludeText", excludeText)
        put("loopCount", loopCount)
        put("loopDelaySec", loopDelaySec)
        val subArr = JSONArray()
        for (s in subSteps) subArr.put(s.toJson())
        put("subSteps", subArr)
        val falseArr = JSONArray()
        for (s in falseSubSteps) falseArr.put(s.toJson())
        put("falseSubSteps", falseArr)
        put("dragStartX", dragStartX)
        put("dragStartY", dragStartY)
        put("dragEndX", dragEndX)
        put("dragEndY", dragEndY)
        put("dragDurationMs", dragDurationMs)
    }

    companion object {
        fun fromJson(o: JSONObject): MacroStep = MacroStep(
            type = MacroActionType.fromKey(o.optString("type")),
            name = o.optString("name", ""),
            enabled = o.optBoolean("enabled", true),
            direction = o.optString("direction", DIRECTION_DOWN),
            durationSec = o.optDouble("durationSec", o.optLong("durationMs", 500) / 1000.0).toFloat(),
            pauseMode = o.optString("pauseMode", MACRO_PAUSE_NONE),
            pauseTime = o.optInt("pauseTime", 1),
            minPauseTime = o.optInt("minPauseTime", 1),
            maxPauseTime = o.optInt("maxPauseTime", 3),
            whitelist = jsonToStringList(o.optJSONArray("whitelist")),
            blacklist = jsonToStringList(o.optJSONArray("blacklist")),
            maxClicks = o.optInt("maxClicks", 1),
            posX = o.optDouble("posX", 50.0).toFloat(),
            posY = o.optDouble("posY", 50.0).toFloat(),
            delaySec = o.optDouble("delaySec", o.optLong("delayMs", 1000) / 1000.0).toFloat(),
            waitText = o.optString("waitText", ""),
            waitMode = o.optString("waitMode", "appear"),
            timeoutSec = o.optDouble("timeoutSec", o.optLong("timeoutMs", 10000) / 1000.0).toFloat(),
            excludeText = o.optString("excludeText", ""),
            loopCount = o.optInt("loopCount", 1).coerceAtLeast(1),
            loopDelaySec = o.optDouble("loopDelaySec", 0.0).toFloat(),
            subSteps = run {
                val subs = mutableListOf<MacroStep>()
                val a = o.optJSONArray("subSteps")
                if (a != null) for (i in 0 until a.length()) subs.add(MacroStep.fromJson(a.getJSONObject(i)))
                subs
            },
            falseSubSteps = run {
                val subs = mutableListOf<MacroStep>()
                val a = o.optJSONArray("falseSubSteps")
                if (a != null) for (i in 0 until a.length()) subs.add(MacroStep.fromJson(a.getJSONObject(i)))
                subs
            },
            dragStartX = o.optDouble("dragStartX", 50.0).toFloat(),
            dragStartY = o.optDouble("dragStartY", 50.0).toFloat(),
            dragEndX = o.optDouble("dragEndX", 50.0).toFloat(),
            dragEndY = o.optDouble("dragEndY", 50.0).toFloat(),
            dragDurationMs = o.optInt("dragDurationMs", 500).coerceAtLeast(100)
        )
    }

    fun summary(): String = when (type) {
        MacroActionType.SLIDE -> buildString {
            append("滑动(${dirName(direction)}, ${fmtSec(durationSec)})")
            append(" 停顿:${pauseLabel()}")
        }
        MacroActionType.BACK -> buildString {
            append("后退")
            if (loopCount > 1) append(" ×$loopCount")
            if (loopDelaySec > 0f) append("（间隔${fmtSec(loopDelaySec)}）")
        }
        MacroActionType.CLICK_TEXT -> buildString {
            append("点击「${whitelist.joinToString("/")}」")
            if (blacklist.isNotEmpty()) append(" 排除「${blacklist.joinToString("/")}」")
            if (maxClicks > 1) append(" ×$maxClicks")
        }
        MacroActionType.CLICK_POSITION -> buildString {
            append("点击坐标(${"%.1f".format(posX)}%, ${"%.1f".format(posY)}%)")
            if (maxClicks > 1) append(" ×$maxClicks")
        }
        MacroActionType.DELAY -> "延迟 ${fmtSec(delaySec)}"
        MacroActionType.WAIT_TEXT -> buildString {
            append("等待「${waitTargets()}」(${waitModeText()}, ${fmtSec(timeoutSec)})")
            if (excludeText.isNotBlank()) append(" 排除「$excludeText」")
        }
        MacroActionType.BRANCH -> buildString {
            val subs = subSteps.filter { it.enabled }
            append("条件单支")
            if (waitMode == "always") append("（恒成立）")
            else {
                append("「${waitTargets()}」(${waitModeText()}, ${fmtSec(timeoutSec)})")
                if (excludeText.isNotBlank()) append(" 排除「$excludeText」")
            }
            if (loopCount > 1) append(" 循环${loopCount}次")
            append(" · 子步骤 ${subs.size}")
            if (subs.isNotEmpty()) append("[${subs.joinToString("/") { "「${it.summary()}」" }}]")
        }
        MacroActionType.BRANCH_IF -> buildString {
            append("条件分支")
            if (waitMode == "always") append("（恒成立）")
            else {
                append("「${waitTargets()}」(${waitModeText()}, ${fmtSec(timeoutSec)})")
                if (excludeText.isNotBlank()) append(" 排除「$excludeText」")
            }
            if (loopCount > 1) append(" 循环${loopCount}次")
            append(" · true ${subSteps.count { it.enabled }} / false ${falseSubSteps.count { it.enabled }}")
        }
        MacroActionType.DRAG -> buildString {
            append("拖拽(${"%.1f".format(dragStartX)}%, ${"%.1f".format(dragStartY)}% → ${"%.1f".format(dragEndX)}%, ${"%.1f".format(dragEndY)}%, ${dragDurationMs}ms)")
            if (loopCount > 1) append(" 循环${loopCount}次")
            if (loopDelaySec > 0f) append("（间隔${fmtSec(loopDelaySec)}）")
        }
    }

    /* 把秒数格式化为易读字符串：整数去小数（如 1s），否则保留 1 位小数（如 0.5s） */
    private fun fmtSec(sec: Float): String =
        if (sec == sec.toInt().toFloat()) "${sec.toInt()}s" else String.format("%.1f", sec) + "s"

    /* 条件类型文字：恒成立 / 等待出现 / 等待消失 */
    private fun waitModeText(): String = when (waitMode) {
        "always" -> "恒成立"
        "disappear" -> "等待消失"
        else -> "等待出现"
    }

    /* 等待文字按“,”拆分为多个目标（任一命中即满足），用于显示与匹配 */
    private fun waitTargets(): String {
        val list = waitText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return if (list.isEmpty()) waitText else list.joinToString("/")
    }

    private fun pauseLabel(): String = when (pauseMode) {
        MACRO_PAUSE_FIXED -> "固定${pauseTime}s"
        MACRO_PAUSE_RANDOM -> "随机${minPauseTime}~${maxPauseTime}s"
        else -> "无"
    }

    private fun dirName(d: String) = when (d) {
        DIRECTION_UP -> "上"; DIRECTION_DOWN -> "下"
        DIRECTION_LEFT -> "左"; DIRECTION_RIGHT -> "右"; DIRECTION_UP_DOWN -> "上下"
        else -> d
    }
}

/* 一个完整方案 */
data class MacroPlan(
    val id: String,
    val name: String,
    val repeatCount: Int,
    val steps: List<MacroStep>
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("repeatCount", repeatCount)
        put("steps", JSONArray(steps.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject) = MacroPlan(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name", "未命名方案"),
            repeatCount = o.optInt("repeatCount", 1).coerceAtLeast(1),
            steps = buildList {
                val arr = o.optJSONArray("steps")
                if (arr != null) for (i in 0 until arr.length()) add(MacroStep.fromJson(arr.getJSONObject(i)))
            }
        )
    }

    fun summary() = "步骤 ${steps.count { it.enabled }}/${steps.size} · 重复 $repeatCount 次"
}

/* 方案存储（SharedPreferences，JSON 数组） */
object MacroPlanStore {
    fun loadAll(context: android.content.Context): MutableList<MacroPlan> {
        val raw = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_MACRO_PLANS, null) ?: return mutableListOf()
        val list = mutableListOf<MacroPlan>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(MacroPlan.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    fun saveAll(context: android.content.Context, plans: List<MacroPlan>) {
        val arr = JSONArray(plans.map { it.toJson() })
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_MACRO_PLANS, arr.toString()).apply()
    }
}

private fun jsonToStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val list = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        val s = arr.optString(i)
        if (s.isNotBlank()) list.add(s)
    }
    return list
}
